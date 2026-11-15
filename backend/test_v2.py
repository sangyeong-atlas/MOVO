"""Contract and regression tests against real HTTP and isolated SQLite storage."""
import json
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
from datetime import datetime, timezone, timedelta
from pathlib import Path
import server
import server_v2 as v2
from routing import RoadRoutes, NaverDirections

class V2ContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.no_roads=RoadRoutes(NaverDirections('',''))
        cls.road_patch=patch.object(v2,'ROADS',cls.no_roads);cls.road_patch.start()
        cls.key_patch=patch.object(v2,'KAKAO_KEY','');cls.key_patch.start()
        cls.tmp = tempfile.TemporaryDirectory()
        cls.old_db = server.DB
        server.DB = Path(cls.tmp.name) / 'contract.db'
        v2.init()
        cls.http = v2.ThreadingHTTPServer(('127.0.0.1', 0), v2.API)
        cls.http.daemon_threads = True
        threading.Thread(target=cls.http.serve_forever, daemon=True).start()
        cls.base = 'http://127.0.0.1:' + str(cls.http.server_port)
    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown(); cls.http.server_close(); server.DB = cls.old_db; cls.tmp.cleanup(); cls.road_patch.stop(); cls.key_patch.stop(); cls.no_roads.close()
    def request(self, method, path, body=None, token='', key=None):
        headers = {'Content-Type':'application/json', 'Authorization':'Bearer ' + token}
        if key: headers['Idempotency-Key'] = key
        r = urllib.request.Request(self.base + path, method=method, headers=headers,
            data=json.dumps(body).encode() if body is not None else None)
        try:
            with urllib.request.urlopen(r, timeout=5) as response: return response.status, json.load(response)
        except urllib.error.HTTPError as e: return e.code, json.load(e)
    def ok(self, method, path, body=None, token='', key=None):
        code, result = self.request(method, path, body, token, key)
        self.assertIn(code, [200,201], result)
        return result
    def setup_trip(self):
        suffix = str(time.time_ns())
        host = self.ok('POST','/v2/auth/register',{'name':'h'+suffix,'password':'test-pass'})['token']
        member = self.ok('POST','/v2/auth/register',{'name':'m'+suffix,'password':'test-pass'})['token']
        room = self.ok('POST','/v2/trips',{'title':'MOVO Weekend Trip'},host)
        root = '/v2/trips/' + room['roomId']
        invite = self.ok('POST',root+'/invites',{},host)['inviteToken']
        mid = self.ok('POST','/v2/invites/'+invite,{'consent':True},member)['memberId']
        code = self.ok('POST',root+'/pair',{},host)['code']
        vehicle = self.ok('POST','/v2/pair/exchange',{'code':code})
        return root, host, member, mid, vehicle, code
    def phase(self, root, vehicle, phase):
        return self.ok('PATCH',root+'/phase',{'status':phase},vehicle['token'])
    def test_road_provider_does_not_block_member_ack_and_all_roles_share_route(self):
        root,h,m,mid,v,_=self.setup_trip()
        gate=threading.Event();entered=threading.Event()
        class Provider:
            configured=True
            def fetch(self,points):
                entered.set();gate.wait(3)
                return {'source':'naver','status':'ready','polyline':[[37.54,127.04],[37.55,127.04],[37.55,127.05]],'drivingSeconds':120,'remainingMeters':800,'routeId':'shared-road','waypointSeconds':[]}
        roads=RoadRoutes(Provider())
        try:
            with patch.object(v2,'ROADS',roads):
                pending=self.ok('GET',root+'/snapshot',token=h)
                self.assertEqual(pending['route']['status'],'pending');self.assertTrue(entered.wait(1))
                started=time.monotonic()
                ack=self.ok('POST',root+'/messages',{'type':'late_5min'},m,'road-network-test')
                self.assertLess(time.monotonic()-started,1);self.assertEqual(ack['type'],'late_5min')
                gate.set()
                for _ in range(100):
                    mobile=self.ok('GET',root+'/snapshot',token=m)
                    if mobile['route']['status']=='ready':break
                    time.sleep(.01)
                vehicle=self.ok('GET',root+'/snapshot',token=v['token'])
                self.assertEqual(mobile['route']['routeId'],'shared-road')
                self.assertEqual(mobile['route']['polyline'],vehicle['route']['polyline'])
                self.assertEqual(mobile['revision'],pending['revision'])
                self.assertEqual(mobile['course'],pending['course'])
        finally:
            gate.set();roads.close()

    def test_nearby_detour_cannot_read_another_trips_route(self):
        root,h,*_=self.setup_trip()
        other,_,*_=self.setup_trip()
        path='/v2/places/nearby?lat=37.54&lng=127.04&tripId='+other.split('/')[-1]
        with patch.object(v2,'kakao_places') as search:
            self.assertEqual(self.request('GET',path,token=h)[0],403)
            search.assert_not_called()

    def test_nearby_search_failure_is_explicit_in_latest_mobile(self):
        _, host, _, _, _, _ = self.setup_trip()
        with patch.object(v2, 'kakao_places', return_value=None):
            code, result = self.request('GET','/v2/places/nearby?lat=37.54&lng=127.04&category=CE7',token=host)
        self.assertEqual(code,503);self.assertNotIn('places',result)

    def test_vehicle_voice_stop_requires_separate_driver_approval(self):
        root,h,m,mid,v,_=self.setup_trip();t=v['token'];self.phase(root,v,'pickup')
        query=root+'/stop-candidates?query='+urllib.parse.quote('카페')
        self.assertEqual(self.request('GET',query,token=m)[0],403)
        self.assertEqual(self.request('GET',query,token=t)[0],409)
        telemetry={'tripId':root.split('/')[-1],'vehicleId':v['vehicleId'],'lat':37.545,'lng':127.05,'speedMps':0,'accuracyM':8,'observedAt':datetime.now(timezone.utc).isoformat(),'sequence':1,'source':'simulator'}
        self.ok('POST',root+'/telemetry',telemetry,t)
        before=self.ok('GET',root+'/snapshot',token=t)
        with patch.object(v2,'kakao_places',return_value=None):
            candidates=self.ok('GET',query,token=t)
            empty=self.ok('GET',root+'/stop-candidates?query='+urllib.parse.quote('없는상호'),token=t)
        self.assertEqual(candidates['source'],'sample-fallback');self.assertIsNone(candidates['detourSeconds'])
        self.assertEqual(empty['places'],[]);self.assertTrue(all(p['category']=='카페' for p in candidates['places']))
        self.assertEqual(before['requests'],self.ok('GET',root+'/snapshot',token=t)['requests'])
        body={'kind':'stop','source':'voice','searchSource':candidates['source'],'expectedRevision':candidates['revision'],'place':candidates['places'][0],'status':'applied'}
        self.assertEqual(self.request('POST',root+'/requests',{**body,'source':'other'},t,'denied')[0],403)
        self.assertEqual(self.request('POST',root+'/requests',{**body,'kind':'climate'},t,'climate')[0],403)
        self.assertEqual(self.request('POST',root+'/requests',{**body,'expectedRevision':-1},t,'stale')[0],409)
        first=self.ok('POST',root+'/requests',body,t,'voice-one')
        self.assertEqual(first['status'],'pending')
        self.assertEqual(first['requestId'],self.ok('POST',root+'/requests',body,t,'voice-one')['requestId'])
        path=root+'/requests/'+first['requestId']
        self.assertEqual(self.request('PATCH',path,{'status':'applied'},t)[0],409)
        self.assertEqual(self.request('PATCH',path,{'status':'approved'},m)[0],403)
        self.assertEqual(before['course'],self.ok('GET',root+'/snapshot',token=t)['course'])
        self.ok('PATCH',path,{'status':'approved'},t)
        self.assertEqual(before['course'],self.ok('GET',root+'/snapshot',token=t)['course'])
        self.ok('PATCH',path,{'status':'applied'},t);self.ok('PATCH',path,{'status':'applied'},t)
        after=self.ok('GET',root+'/snapshot',token=t)
        self.assertEqual(len(after['course']),len(before['course'])+1);self.assertEqual(after['revision'],before['revision']+1)
        body['expectedRevision']=after['revision'];rejected=self.ok('POST',root+'/requests',body,t,'voice-two')
        self.ok('PATCH',root+'/requests/'+rejected['requestId'],{'status':'rejected'},t)
        failed=self.ok('POST',root+'/requests',body,t,'voice-three');path=root+'/requests/'+failed['requestId']
        self.ok('PATCH',path,{'status':'approved'},t);self.ok('PATCH',path,{'status':'failed'},t)
        self.assertEqual(after['course'],self.ok('GET',root+'/snapshot',token=t)['course'])
        late=self.ok('POST',root+'/requests',body,t,'before-arrival')
        self.ok('PATCH',root+'/members/'+mid,{'status':'boarded'},t);self.phase(root,v,'driving');self.phase(root,v,'arrived')
        self.assertEqual(self.request('PATCH',root+'/requests/'+late['requestId'],{'status':'approved'},t)[0],409)
        self.ok('PATCH',root+'/requests/'+late['requestId'],{'status':'rejected'},t)
        self.assertEqual(self.request('GET',query,token=t)[0],409)
        self.assertEqual(self.request('POST',root+'/requests',body,t,'arrived')[0],409)

    def test_passenger_messages_are_authenticated_deduplicated_and_in_snapshot(self):
        root,h,m,mid,v,_=self.setup_trip();t=v['token']
        body={'type':'late_5min','memberId':'spoof','displayName':'spoof','text':'spoof'}
        for token in [h,t]:
            self.assertEqual(self.request('POST',root+'/messages',body,token,'denied')[0],403)
        self.assertEqual(self.request('POST',root+'/messages',body,m)[0],400)
        first=self.ok('POST',root+'/messages',body,m,'late-one')
        self.assertEqual(first['memberId'],mid);self.assertNotEqual(first['displayName'],'spoof')
        self.assertEqual(first['text'],'5분 늦어요')
        self.assertEqual(first,self.ok('POST',root+'/messages',body,m,'late-one'))
        self.assertEqual(self.request('POST',root+'/messages',{'type':'came_out'},m,'late-one')[0],409)
        self.assertEqual(self.request('POST',root+'/messages',{'type':'unknown'},m,'bad')[0],400)
        self.assertEqual(self.request('POST',root+'/messages',{'type':[]},m,'bad-list')[0],400)
        self.phase(root,v,'pickup')
        self.ok('PATCH',root+'/members/'+mid,{'status':'outside'},m)
        self.ok('PATCH',root+'/members/'+mid,{'status':'outside'},m)
        snap=self.ok('GET',root+'/snapshot',token=t)
        self.assertEqual([x['type'] for x in snap['messages']],['late_5min','came_out'])
        self.assertEqual(snap['members'][0]['status'],'outside')
        self.assertEqual(len({x['id'] for x in snap['messages']}),2)
        self.ok('PATCH',root+'/members/'+mid,{'status':'preparing'},m)
        self.ok('POST',root+'/messages',{'type':'came_out'},m,'out-one')
        self.assertEqual(self.ok('GET',root+'/snapshot',token=t)['members'][0]['status'],'outside')
        self.ok('PATCH',root+'/members/'+mid,{'status':'boarded'},t)
        self.assertEqual(self.request('POST',root+'/messages',body,m,'boarded')[0],409)
        self.phase(root,v,'driving')
        self.assertEqual(self.request('POST',root+'/messages',body,m,'driving')[0],409)
        self.assertEqual(len(self.ok('GET',root+'/snapshot',token=t)['messages']),3)

    def test_pairing_permissions_rotation_and_seat(self):
        root,h,m,mid,v,code = self.setup_trip()
        self.assertEqual(self.request('POST','/v2/pair/exchange',{'code':code})[0],403)
        self.assertEqual(self.ok('GET',root+'/snapshot',token=v['token'])['members'][0]['seat'],1)
        for token in [h,m]:
            self.assertEqual(self.request('PUT',root+'/vehicle-state',{},token)[0],403)
        other,_,_,_,_,_=self.setup_trip()
        self.assertEqual(self.request('GET',other+'/snapshot',token=v['token'])[0],403)
        newcode=self.ok('POST',root+'/pair',{},h)['code']
        self.ok('POST','/v2/pair/exchange',{'code':newcode})
        self.assertEqual(self.request('GET',root+'/snapshot',token=v['token'])[0],403)
    def test_weekend_trip_full_flow_and_history(self):
        root,h,m,mid,v,_=self.setup_trip();t=v['token']
        self.phase(root,v,'pickup')
        self.assertEqual(self.request('PATCH',root+'/phase',{'status':'driving'},t)[0],409)
        self.ok('PATCH',root+'/members/'+mid,{'status':'outside','pickup':{'lat':37.545,'lng':127.05,'memo':'Exit 2'}},m)
        self.assertEqual(self.ok('GET',root+'/snapshot',token=t)['members'][0]['status'],'outside')
        self.assertEqual(self.request('PATCH',root+'/members/'+mid,{'status':'boarded'},m)[0],403)
        self.ok('PATCH',root+'/members/'+mid,{'status':'boarded'},t);self.phase(root,v,'driving')
        self.assertEqual(self.request('PATCH',root+'/phase',{'status':'arrived'},h)[0],409)
        body={'kind':'stop','place':{'name':'Cafe','lat':37.5474,'lng':127.0437}}
        q=self.ok('POST',root+'/requests',body,m,'stop-one')
        self.assertEqual(self.ok('POST',root+'/requests',body,m,'stop-one')['requestId'],q['requestId'])
        path=root+'/requests/'+q['requestId']
        self.assertEqual(self.request('PATCH',path,{'status':'applied'},t)[0],409)
        self.ok('PATCH',path,{'status':'approved'},t)
        self.assertEqual(len(self.ok('GET',root+'/snapshot',token=m)['course']),0)
        for _ in range(2): self.ok('PATCH',path,{'status':'applied','reason':'Simulator'},t)
        snap=self.ok('GET',root+'/snapshot',token=m)
        self.assertEqual(len(snap['course']),1); self.assertEqual(snap['revision'],1)
        self.phase(root,v,'arrived')
        parking={'position':{'lat':37.5445,'lng':127.0374},'memo':'B2 C-14','source':'manual','simulation':True,'observedAt':datetime.now(timezone.utc).isoformat(),'accuracyM':8}
        first=self.ok('PUT',root+'/parking',parking,t,'park-one')
        self.assertEqual(first,self.ok('PUT',root+'/parking',parking,t,'park-one'))
        final=self.phase(root,v,'closed')
        self.assertEqual(final['finalSnapshot']['room']['status'],'closed')
        self.assertIsNone(final['finalSnapshot']['telemetry'])
        self.assertEqual(self.request('GET',root+'/snapshot',token=t)[0],403)
        self.assertEqual(self.ok('GET',root+'/snapshot',token=m)['parking']['memo'],'B2 C-14')
        self.assertEqual(self.ok('GET','/v2/me',token=h)['rooms'][0]['status'],'closed')
    def test_climate_capabilities_rejection_and_failed_result(self):
        root,h,m,mid,v,_=self.setup_trip();t=v['token'];self.phase(root,v,'pickup')
        body={'kind':'climate','seat':1,'feature':'temperature','value':22}
        self.assertEqual(self.request('POST',root+'/requests',body,m,'climate')[0],409)
        self.ok('PUT',root+'/vehicle-state',{'capabilities':{'temperature':True,'belt':None,'occupancy':False},'seats':{}},t)
        self.assertEqual(self.request('POST',root+'/requests',{**body,'seat':2},m,'wrong-seat')[0],403)
        self.assertEqual(self.request('POST',root+'/requests',{**body,'value':40},m,'bad-temp')[0],400)
        q=self.ok('POST',root+'/requests',body,m,'climate')
        self.ok('PATCH',root+'/requests/'+q['requestId'],{'status':'rejected','reason':'Driver declined'},t)
        q=self.ok('POST',root+'/requests',body,m,'climate-two')
        self.ok('PATCH',root+'/requests/'+q['requestId'],{'status':'approved'},t)
        self.ok('PATCH',root+'/requests/'+q['requestId'],{'status':'failed','reason':'Adapter unavailable'},t)
        self.assertEqual(self.ok('GET',root+'/snapshot',token=m)['requests'][-1]['status'],'failed')
    def test_telemetry_duplicate_freshness_and_bad_observation(self):
        root,h,m,mid,v,_=self.setup_trip();t=v['token'];self.phase(root,v,'pickup')
        body={'tripId':root.split('/')[-1],'vehicleId':v['vehicleId'],'lat':37.545,'lng':127.05,'headingDeg':None,'speedMps':0,'accuracyM':8,'observedAt':datetime.now(timezone.utc).isoformat(),'sequence':10,'source':'simulator'}
        self.assertEqual(self.request('POST',root+'/telemetry',body,h)[0],403)
        self.ok('POST',root+'/telemetry',body,t)
        self.assertTrue(self.ok('POST',root+'/telemetry',body,t)['ignored'])
        self.assertTrue(self.ok('POST',root+'/telemetry',{**body,'sequence':9},t)['ignored'])
        old={**body,'sequence':11,'observedAt':(datetime.now(timezone.utc)-timedelta(seconds=80)).isoformat()}
        self.assertEqual(self.request('POST',root+'/telemetry',old,t)[0],400)
        with v2.LOCK,server.connect() as c:
            x=v2.extra(c,body['tripId']);x['telemetry']['receivedAt']=(datetime.now(timezone.utc)-timedelta(seconds=35)).isoformat();v2.save(c,body['tripId'],x)
        s=self.ok('GET',root+'/snapshot',token=m)
        self.assertEqual(s['freshness'],'lost');self.assertIsNone(s['route']['destinationSeconds']);self.assertEqual(s['telemetry']['sequence'],10)
    def test_sse_reconnect_full_snapshot_and_expired_auth(self):
        root,h,m,mid,v,_=self.setup_trip()
        for _ in range(2):
            request=urllib.request.Request(self.base+root+'/events',headers={'Authorization':'Bearer '+v['token'],'Last-Event-ID':'999999'})
            with urllib.request.urlopen(request,timeout=5) as response:
                self.assertIn('text/event-stream',response.headers['Content-Type'])
                lines=[response.readline().decode() for _ in range(4)]
                payload=json.loads(next(x[6:] for x in lines if x.startswith('data: ')))
                self.assertEqual(payload['room']['roomId'],root.split('/')[-1])
        self.assertEqual(self.request('GET',root+'/events',token='expired')[0],403)
    def test_parking_retry_after_server_restart_preserves_observation(self):
        root,h,m,mid,v,_=self.setup_trip();t=v['token'];self.phase(root,v,'pickup')
        self.ok('PATCH',root+'/members/'+mid,{'status':'boarded'},t);self.phase(root,v,'driving');self.phase(root,v,'arrived')
        body={'position':{'lat':37.5,'lng':127},'source':'manual','memo':'B2','observedAt':datetime.now(timezone.utc).isoformat(),'accuracyM':None}
        first=self.ok('PUT',root+'/parking',body,t,'durable-key')
        v2.init()  # same persisted DB, independently reopened connections
        self.assertEqual(self.ok('PUT',root+'/parking',body,t,'durable-key'),first)
        self.assertEqual(self.request('PUT',root+'/parking',{**body,'memo':'different'},t,'durable-key')[0],409)
    def test_close_rejects_unresolved_application_and_invalidates_pair(self):
        root,h,m,mid,v,_=self.setup_trip();t=v['token'];self.phase(root,v,'pickup')
        q=self.ok('POST',root+'/requests',{'kind':'stop','place':{'name':'x','lat':37.5,'lng':127}},m,'stop')
        self.ok('PATCH',root+'/requests/'+q['requestId'],{'status':'approved'},t)
        self.ok('PATCH',root+'/members/'+mid,{'status':'boarded'},t);self.phase(root,v,'driving');self.phase(root,v,'arrived')
        self.ok('PUT',root+'/parking',{'position':{'lat':37.5,'lng':127},'source':'manual','observedAt':datetime.now(timezone.utc).isoformat()},t,'close-parking')
        pair=self.ok('POST',root+'/pair',{},h)['code']
        self.assertEqual(self.request('PATCH',root+'/phase',{'status':'closed'},t)[0],409)
        self.ok('PATCH',root+'/requests/'+q['requestId'],{'status':'failed'},t);self.phase(root,v,'closed')
        self.assertEqual(self.request('POST','/v2/pair/exchange',{'code':pair})[0],403)
    def test_close_requires_persisted_parking_for_host_and_vehicle(self):
        for closing_role in ['host', 'vehicle']:
            with self.subTest(closing_role=closing_role):
                root,h,m,mid,v,_=self.setup_trip();t=v['token']
                self.phase(root,v,'pickup')
                self.ok('PATCH',root+'/members/'+mid,{'status':'boarded'},t)
                self.phase(root,v,'driving');self.phase(root,v,'arrived')
                before=self.ok('GET',root+'/snapshot',token=t)
                for token in [h,t]:
                    self.assertEqual(self.request('PATCH',root+'/phase',{'status':'closed'},token)[0],409)
                    after=self.ok('GET',root+'/snapshot',token=t)
                    self.assertEqual(after['room'],before['room'])
                    self.assertEqual(after['eventId'],before['eventId'])
                    self.assertIsNone(after['parking'])
                body={'position':{'lat':37.5,'lng':127},'source':'manual','observedAt':datetime.now(timezone.utc).isoformat()}
                self.assertEqual(self.request('PUT',root+'/parking',{**body,'source':'phone'},h,'invalid-parking')[0],400)
                self.assertEqual(self.request('PATCH',root+'/phase',{'status':'closed'},h)[0],409)
                saved=self.ok('PUT',root+'/parking',body,h,'confirmed-parking')
                final=self.ok('PATCH',root+'/phase',{'status':'closed'},h if closing_role=='host' else t)
                self.assertEqual(final['finalSnapshot']['parking'],saved)
                self.assertEqual(final['finalSnapshot']['room']['status'],'closed')
                self.assertEqual(self.request('GET',root+'/snapshot',token=t)[0],403)
                self.assertEqual(self.ok('GET',root+'/snapshot',token=m)['parking'],saved)
                self.assertEqual(self.request('PATCH',root+'/phase',{'status':'pickup'},h)[0],409)

    def add_passenger(self, root, host):
        token=self.ok('POST','/v2/auth/register',{'name':'s'+str(time.time_ns()),'password':'test-pass'})['token']
        invite=self.ok('POST',root+'/invites',{},host)['inviteToken']
        joined=self.ok('POST','/v2/invites/'+invite,{'consent':True},token)
        self.assertEqual(self.ok('POST','/v2/invites/'+invite,{'consent':True},token),joined)
        return token,joined['memberId']

    def test_seat_assignment_persists_and_streams_to_all_roles(self):
        root,h,m,mid,v,_=self.setup_trip();other,oid=self.add_passenger(root,h)
        before=self.ok('GET',root+'/snapshot',token=h)
        self.assertEqual(self.ok('PATCH',root+'/members/'+mid,{'seat':3},m)['seat'],3)
        self.assertEqual(self.ok('PATCH',root+'/members/'+oid,{'seat':1},other)['seat'],1)
        v2.init()  # Reopen persisted SQLite data without migration.
        for token in [h,m,other,v['token']]:
            snap=self.ok('GET',root+'/snapshot',token=token)
            self.assertEqual({x['memberId']:x['seat'] for x in snap['members']},{mid:3,oid:1})
            self.assertEqual(snap['eventId'],before['eventId']+2)
            request=urllib.request.Request(self.base+root+'/events',headers={'Authorization':'Bearer '+token})
            with urllib.request.urlopen(request,timeout=5) as response:
                lines=[response.readline().decode() for _ in range(4)]
                payload=json.loads(next(x[6:] for x in lines if x.startswith('data: ')))
                self.assertEqual(payload['members'],snap['members'])
        _,third=self.add_passenger(root,h)
        snap=self.ok('GET',root+'/snapshot',token=h)
        self.assertEqual(next(x['seat'] for x in snap['members'] if x['memberId']==third),2)
        self.assertEqual(len(snap['members']),3)

    def test_seat_rejections_are_atomic_and_authorized(self):
        root,h,m,mid,v,_=self.setup_trip();other,oid=self.add_passenger(root,h)
        before=self.ok('GET',root+'/snapshot',token=h)
        path=root+'/members/'+mid
        for value in [0,5,-1,True,False,None,'3',2.5,{},[]]:
            with self.subTest(value=value):self.assertEqual(self.request('PATCH',path,{'seat':value,'status':'outside'},m)[0],400)
        self.assertEqual(self.request('PATCH',path,{'seat':2,'status':'outside'},m)[0],409)
        for token in [other,'invalid']:
            self.assertEqual(self.request('PATCH',path,{'seat':3},token)[0],403)
        after=self.ok('GET',root+'/snapshot',token=h)
        self.assertEqual(after['members'],before['members']);self.assertEqual(after['eventId'],before['eventId'])
        self.assertEqual(self.ok('PATCH',path,{'seat':3},h)['seat'],3)
        self.assertEqual(self.ok('PATCH',path,{'seat':4},v['token'])['seat'],4)
        self.ok('PATCH',path,{'status':'boarded'},v['token'])
        self.assertEqual(self.request('PATCH',path,{'seat':3,'status':'outside'},h)[0],409)
        self.assertEqual(self.request('PATCH',path,{'seat':3},m)[0],403)
        self.phase(root,v,'pickup');self.ok('PATCH',root+'/members/'+oid,{'status':'boarded'},v['token']);self.phase(root,v,'driving')
        for token in [h,m,v['token']]:self.assertEqual(self.request('PATCH',path,{'seat':3},token)[0],409)
        self.phase(root,v,'arrived')
        self.assertEqual(self.request('PATCH',path,{'seat':3},v['token'])[0],409)

    def test_concurrent_seat_claim_has_exactly_one_winner(self):
        from concurrent.futures import ThreadPoolExecutor
        root,h,m,mid,v,_=self.setup_trip();other,oid=self.add_passenger(root,h)
        barrier=threading.Barrier(2)
        def claim(pair):
            token,target=pair;barrier.wait()
            return self.request('PATCH',root+'/members/'+target,{'seat':3},token)[0]
        before=self.ok('GET',root+'/snapshot',token=h)
        with ThreadPoolExecutor(max_workers=2) as pool:codes=list(pool.map(claim,[(m,mid),(other,oid)]))
        self.assertEqual(sorted(codes),[200,409])
        after=self.ok('GET',root+'/snapshot',token=v['token'])
        self.assertEqual(len({x['seat'] for x in after['members']}),2)
        self.assertEqual(sum(x['seat']==3 for x in after['members']),1)
        self.assertEqual(after['eventId'],before['eventId']+1)

    def test_seat_change_waits_for_climate_request_resolution(self):
        root,h,m,mid,v,_=self.setup_trip();self.phase(root,v,'pickup');t=v['token'];path=root+'/members/'+mid
        self.ok('PUT',root+'/vehicle-state',{'capabilities':{'temperature':True},'seats':{}},t)
        body={'kind':'climate','seat':1,'feature':'temperature','value':22}
        q=self.ok('POST',root+'/requests',body,m,'seat-climate')
        self.assertEqual(self.request('PATCH',path,{'seat':3},m)[0],409)
        self.ok('PATCH',root+'/requests/'+q['requestId'],{'status':'approved'},t)
        self.assertEqual(self.request('PATCH',path,{'seat':3},h)[0],409)
        self.ok('PATCH',root+'/requests/'+q['requestId'],{'status':'applied'},t)
        self.ok('PATCH',path,{'seat':3},m)
        self.assertEqual(self.request('POST',root+'/requests',body,m,'old-seat')[0],403)
        q=self.ok('POST',root+'/requests',{**body,'seat':3},m,'new-seat')
        self.assertEqual(q['seat'],3)

    def test_legacy_routes_retained(self):
        room=self.ok('POST','/rooms',{'title':'Original client'})
        self.ok('POST','/join',{'inviteCode':room['inviteCode'],'displayName':'Legacy passenger'})
        s=self.ok('GET','/rooms/'+room['roomId']+'/snapshot',token=room['hostToken'])
        self.assertEqual(s['members'][0]['displayName'],'Legacy passenger')
    def test_demo_served_same_origin(self):
        with urllib.request.urlopen(self.base+'/demo') as response:self.assertIn(b'Mobile integration demo',response.read())

if __name__=='__main__':unittest.main()
