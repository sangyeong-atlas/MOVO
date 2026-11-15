"""Provider protocol, shared geometry, failures and detour regression tests. No live APIs/DBs."""
import io
import json
import threading
import time
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse
from routing import NaverDirections, RoadRoutes, RouteError
import server_v2 as v2

POINTS = [{'lat':37.54,'lng':127.04}, {'lat':37.55,'lng':127.05}, {'lat':37.56,'lng':127.06}]


def payload(duration=180000, distance=2100):
    return {'code':0,'route':{'traoptimal':[{'summary':{'distance':distance,'duration':duration},
        'path':[[127.04,37.54],[127.041,37.54],[127.041,37.55],[127.06,37.56]],
        'guide':[{'type':3,'duration':30000},{'type':87,'duration':30000},{'type':88,'duration':120000}]}]}}


class FakeProvider:
    configured = True
    def __init__(self): self.calls=[]
    def fetch(self, points):
        self.calls.append(points)
        return {'source':'naver','status':'ready','polyline':[[37.54,127.04],[37.54,127.041],[37.56,127.06]],
                'remainingMeters':len(points)*1000,'drivingSeconds':len(points)*60,'waypointSeconds':[60],
                'routeId':str(points),'queriedAt':time.time()}


class RoutingTest(unittest.TestCase):
    def service(self, provider=None, **kwargs):
        service=RoadRoutes(provider or FakeProvider(), **kwargs)
        self.addCleanup(service.close)
        return service

    def ready(self, service, points=POINTS, scope=None):
        for _ in range(200):
            result=service.request(points,scope)
            if result['status']!='pending':return result
            time.sleep(.005)
        self.fail('route never completed')

    def test_naver_units_coordinate_order_waypoint_order_and_headers(self):
        requests=[]
        def opener(request, timeout):
            requests.append(request);self.assertEqual(timeout,5)
            return io.BytesIO(json.dumps(payload()).encode())
        provider=NaverDirections('test-id','test-secret',opener)
        result=provider.fetch(tuple((p['lat'],p['lng']) for p in POINTS))
        request=requests[0];query=parse_qs(urlparse(request.full_url).query)
        self.assertEqual(query['start'],['127.04,37.54']);self.assertEqual(query['waypoints'],['127.05,37.55'])
        self.assertEqual(query['goal'],['127.06,37.56'])
        self.assertEqual(request.get_header('X-ncp-apigw-api-key'),'test-secret')
        self.assertTrue(request.full_url.startswith('https://maps.apigw.ntruss.com/map-direction/v1/driving?'))
        self.assertEqual(result['polyline'][1],[37.54,127.041])
        self.assertEqual(result['drivingSeconds'],180);self.assertEqual(result['waypointSeconds'],[60])
        self.assertEqual(result['remainingMeters'],2100)

    def test_directions15_and_no_silent_waypoint_truncation(self):
        urls=[]
        def opener(request, timeout):
            urls.append(request.full_url);return io.BytesIO(json.dumps(payload()).encode())
        service=self.service(NaverDirections('id','secret',opener))
        points=[dict(lat=37.5+i*.001,lng=127.04) for i in range(17)]
        self.assertEqual(self.ready(service,points)['status'],'ready')
        self.assertIn('/map-direction-15/',urls[0])
        self.assertEqual(len(parse_qs(urlparse(urls[0]).query)['waypoints'][0].split('|')),15)
        self.assertEqual(service.request(points+[dict(lat=37.6,lng=127.1)])['reason'],'too_many_waypoints')
        self.assertEqual(len(urls),1)

    def test_missing_key_and_provider_failure_never_draw_straight_line(self):
        service=self.service(NaverDirections('',''))
        result=service.request(POINTS)
        self.assertEqual(result['reason'],'not_configured');self.assertEqual(result['polyline'],[])
        self.assertIsNone(result['remainingMeters'])
        provider=FakeProvider();provider.fetch=lambda _: (_ for _ in ()).throw(RouteError('quota'))
        failed=self.ready(self.service(provider))
        self.assertEqual(failed['reason'],'quota');self.assertEqual(failed['polyline'],[])

    def test_malformed_response_does_not_leak_provider_payload(self):
        provider=NaverDirections('id','secret',lambda *a,**kw:io.BytesIO(b'{"code":0,"secret":"hidden"}'))
        with self.assertRaises(RouteError) as caught:provider.fetch(((37.,127.),(38.,128.)))
        self.assertEqual(str(caught.exception),'provider_unavailable')

    def test_shared_cache_small_movement_and_changed_course(self):
        provider=FakeProvider();service=self.service(provider)
        a=self.ready(service,scope=('trip',1,'planning'))
        moved=[dict(POINTS[0],lat=37.5401)]+POINTS[1:]
        b=service.request(moved,scope=('trip',1,'planning'))
        self.assertEqual(a['routeId'],b['routeId']);self.assertEqual(len(provider.calls),1)
        b['polyline'].clear();self.assertNotEqual(service.request(moved,('trip',1,'planning'))['polyline'],[])
        c=self.ready(service,[POINTS[0],POINTS[2],POINTS[1]],('trip',2,'planning'))
        self.assertNotEqual(a['routeId'],c['routeId']);self.assertEqual(len(provider.calls),2)

    def test_pending_provider_does_not_block_snapshot_or_duplicate_requests(self):
        gate=threading.Event();entered=threading.Event();provider=FakeProvider();original=provider.fetch
        def slow(points):entered.set();gate.wait(2);return original(points)
        provider.fetch=slow;service=self.service(provider)
        started=time.monotonic();first=service.request(POINTS,'trip')
        self.assertLess(time.monotonic()-started,.2);self.assertEqual(first['status'],'pending')
        self.assertTrue(entered.wait(1))
        for _ in range(20):self.assertEqual(service.request(POINTS,'trip')['status'],'pending')
        gate.set();self.ready(service,scope='trip');self.assertEqual(len(provider.calls),1)

    def test_detour_matches_append_contract_and_excludes_dwell_time(self):
        provider=FakeProvider();service=self.service(provider);base=self.ready(service)
        candidate={'name':'카페','lat':37.57,'lng':127.07,'stayMinutes':90}
        for _ in range(200):
            places=service.detours(base,[candidate])
            if places[0]['detourStatus']=='ready':break
            time.sleep(.005)
        self.assertEqual(places[0]['detourSeconds'],60);self.assertEqual(places[0]['detourMeters'],1000)
        self.assertEqual(provider.calls[-1],((37.54,127.04),(37.55,127.05),(37.57,127.07),(37.56,127.06)))
        self.assertEqual(candidate['stayMinutes'],90);self.assertNotIn('detourSeconds',candidate)

    def test_mobile_vehicle_snapshots_share_geometry_without_mutating_journey(self):
        service=self.service();room={'roomId':'test','status':'planning','destination':POINTS[-1]}
        extra=v2.base_extra();extra['origin']=POINTS[0];extra['course']=[POINTS[1]]
        original=json.dumps([room,extra],sort_keys=True)
        with patch.object(v2,'ROADS',service):
            self.ready(service,scope=('test',0,'planning'))
            mobile=v2.routeinfo(room,extra,[]);car=v2.routeinfo(room,extra,[])
        self.assertEqual(mobile['polyline'],car['polyline']);self.assertEqual(mobile['routeId'],car['routeId'])
        self.assertEqual(mobile['stops'],[[37.54,127.04],[37.55,127.05],[37.56,127.06]])
        self.assertIsNone(mobile['destinationSeconds']) # No fresh vehicle fix.
        self.assertNotIn('_points',mobile);self.assertEqual(json.dumps([room,extra],sort_keys=True),original)

    def test_expired_geometry_retries_without_serving_old_road(self):
        clock=[0];service=self.service(clock=lambda:clock[0],ttl=60)
        self.ready(service);clock[0]=61
        self.assertEqual(service.request(POINTS)['status'],'pending')


if __name__=='__main__':unittest.main()
