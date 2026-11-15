"""Multi-account invitations use the real API and an isolated database."""
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
import server
import server_v2
import test_v2

class MobileFeaturesTest(unittest.TestCase):
    setUpClass=classmethod(test_v2.V2ContractTest.setUpClass.__func__)
    tearDownClass=classmethod(test_v2.V2ContractTest.tearDownClass.__func__)
    request=test_v2.V2ContractTest.request
    ok=test_v2.V2ContractTest.ok
    def test_public_place_search_uses_kakao_and_keeps_writes_private(self):
        from unittest.mock import patch
        place={'id':'real-place','name':'Actual restaurant','lat':37.5,'lng':127.0,'category':'음식점'}
        with patch.object(server_v2,'kakao_places',return_value=[place]) as search:
            code,data=self.request('GET','/v2/places/nearby?lat=37.5&lng=127&category=FD6')
            self.assertEqual(code,200);self.assertEqual(data,{'source':'kakao','places':[place]})
            search.assert_called_with(37.5,127.0,'FD6',None)
            self.assertEqual(self.request('GET','/v2/places/nearby?query=Seoul')[0],200)
            search.assert_called_with(0.0,0.0,'FD6','Seoul')
            self.assertEqual(self.request('POST','/v2/places/nearby?query=Seoul',{})[0],405)
            self.assertEqual(self.request('GET','/v2/places/nearby?lat=nan&lng=127')[0],400)
            self.assertEqual(self.request('GET','/v2/places/nearby?query=Seoul&category=bad')[0],400)
            self.assertEqual(self.request('GET','/v2/me')[0],403)
        with patch.object(server_v2,'kakao_places',return_value=None):
            code,data=self.request('GET','/v2/places/nearby?query=Seoul')
            self.assertEqual(code,503);self.assertNotIn('places',data)

    def test_pickup_notices_persist_without_duplicate_sends(self):
        h,m,name,rid=self.setup();joined=self.accept(m,self.invite(h,rid,name));member=joined['memberId']
        path='/v2/trips/'+rid
        first=self.ok('POST',path+'/messages',{'text':'5분 늦어요'},m)
        again=self.ok('POST',path+'/messages',{'text':'5분 늦어요'},m)
        self.assertEqual(first['id'],again['id'])
        for _ in range(2):self.ok('PATCH',path+'/members/'+member,{'status':'outside'},m)
        snapshot=self.snapshot(h,rid)
        self.assertEqual(snapshot['members'][0]['status'],'outside')
        notices=[n for n in snapshot['notifications'] if n.get('kind') in ['pickup-delay','pickup-outside']]
        self.assertEqual(len(notices),2)
        self.assertTrue(all(n.get('senderId') for n in notices))
        self.assertIn(name,notices[1]['text'])

    def setUp(self):server_v2.RATE.clear()
    def tearDown(self):server_v2.RATE.clear()
    def account(self,label):
        name=label+str(time.time_ns())[-15:]
        result=self.ok('POST','/v2/auth/register',{'name':name,'password':'test-only'})
        return result['token'],name,result['user']['id']
    def setup(self):
        h,hn,hid=self.account('host');m,mn,mid=self.account('member')
        r=self.ok('POST','/v2/trips',{'title':'금요일 모임','origin':{'name':'성수역','lat':37.54,'lng':127.05},'purpose':'모임','pinned':True,'weekdays':[1,3]},h)
        return h,m,mn,r['roomId']
    def invite(self,h,rid,name=None,role='member'):
        return self.ok('POST','/v2/invitations',{'roomId':rid,'recipientName':name or '', 'role':role},h)
    def accept(self,m,i):
        return self.ok('POST','/v2/invitations/'+i['token']+'/accept',{'consent':True},m)
    def snapshot(self,h,rid):return self.ok('GET','/v2/trips/'+rid+'/snapshot',token=h)
    def test_direct_invite_persistence_consent_duplicate_and_identity(self):
        h,m,name,rid=self.setup();i=self.invite(h,rid,name)
        self.assertEqual(self.snapshot(h,rid)['members'],[])
        incoming=self.ok('GET','/v2/invitations',token=m)['invitations'];self.assertEqual(incoming[0]['token'],i['token'])
        self.assertEqual(self.invite(h,rid,name)['token'],i['token'])
        stranger,_,_=self.account('stranger')
        path='/v2/invitations/'+i['token']+'/accept'
        self.assertEqual(self.request('POST',path,{'consent':True},stranger)[0],403)
        self.assertEqual(self.request('POST',path,{},m)[0],400)
        joined=self.accept(m,i);self.assertEqual(self.accept(m,i),joined)
        self.assertEqual(len(self.snapshot(h,rid)['members']),1)
        self.assertEqual(self.ok('GET','/v2/me',token=m)['rooms'][0]['roomId'],rid)
        # Re-initializing schema preserves invitation and party state.
        server_v2.init();self.assertEqual(self.ok('GET','/v2/invitations',token=m)['invitations'][0]['status'],'accepted')
    def test_decline_revoke_expiry_and_closed_phase(self):
        h,m,name,rid=self.setup()
        for action in ['decline','revoke','expire']:
            i=self.invite(h,rid,name);path='/v2/invitations/'+i['token']
            if action=='expire':
                with server.connect() as c:c.execute('UPDATE party_invitations SET expires=0 WHERE token=?',(i['token'],))
            else:self.ok('POST',path+'/'+action,{},m if action=='decline' else h)
            self.assertEqual(self.request('POST',path+'/accept',{'consent':True},m)[0],409)
        self.assertEqual(self.snapshot(h,rid)['members'],[])
        i=self.invite(h,rid,name)
        self.ok('PATCH','/v2/trips/'+rid+'/phase',{'status':'pickup'},h)
        self.ok('PATCH','/v2/trips/'+rid+'/phase',{'status':'driving'},h)
        self.assertEqual(self.request('POST','/v2/invitations/'+i['token']+'/accept',{'consent':True},m)[0],409)
    def test_driver_accept_transfers_host_and_retains_prior_driver(self):
        h,m,name,rid=self.setup();i=self.invite(h,rid,name,'driver');self.accept(m,i)
        s=self.snapshot(m,rid);self.assertEqual(s['role'],'host');self.assertEqual(s['room']['hostName'],name)
        old=self.snapshot(h,rid);self.assertEqual(old['role'],'member');self.assertEqual(len(old['members']),1)
        self.assertEqual(self.request('POST','/v2/trips/'+rid+'/pair',{},h)[0],403)
        self.assertEqual(self.accept(m,i)['role'],'host')
    def test_driver_promotion_at_capacity_and_vehicle_guard(self):
        h,m,name,rid=self.setup();self.accept(m,self.invite(h,rid,name))
        for n in range(3):
            t,name2,_=self.account('p'+str(n));self.accept(t,self.invite(h,rid,name2))
        i=self.invite(h,rid,name,'driver');self.accept(m,i)
        self.assertEqual(len(self.snapshot(m,rid)['members']),4)
        self.assertEqual(len({p['seat'] for p in self.snapshot(m,rid)['members']}),4)
        other,on,_=self.account('next');i=self.invite(m,rid,on,'driver')
        code=self.ok('POST','/v2/trips/'+rid+'/pair',{},m)['code'];self.ok('POST','/v2/pair/exchange',{'code':code})
        self.assertEqual(self.request('POST','/v2/invitations/'+i['token']+'/accept',{'consent':True},other)[0],409)
        self.assertEqual(self.snapshot(m,rid)['role'],'host')
    def test_final_seat_race_and_link_single_use(self):
        h,m,name,rid=self.setup();self.accept(m,self.invite(h,rid,name))
        for n in range(2):
            t,nn,_=self.account('p'+str(n));self.accept(t,self.invite(h,rid,nn))
        a,an,_=self.account('a');b,bn,_=self.account('b');i=self.invite(h,rid)
        def accept(t):return self.request('POST','/v2/invitations/'+i['token']+'/accept',{'consent':True},t)[0]
        with ThreadPoolExecutor(2) as pool:codes=list(pool.map(accept,[a,b]))
        self.assertEqual(sorted(codes),[200,403]);self.assertEqual(len(self.snapshot(h,rid)['members']),4)
        full=self.invite(h,rid,bn if codes[0]==200 else an)
        self.assertEqual(self.request('POST','/v2/invitations/'+full['token']+'/accept',{'consent':True},b if codes[0]==200 else a)[0],409)
    def test_members_can_invite_and_send_message_but_not_transfer_driver(self):
        h,m,name,rid=self.setup();self.accept(m,self.invite(h,rid,name));b,bn,_=self.account('friend')
        self.accept(b,self.invite(m,rid,bn));self.assertEqual(len(self.snapshot(h,rid)['members']),2)
        self.assertEqual(self.request('POST','/v2/invitations',{'roomId':rid,'role':'driver'},m)[0],409)
        self.ok('POST','/v2/trips/'+rid+'/messages',{'text':'5분 늦어요'},m)
        self.assertIn('5분 늦어요',self.snapshot(h,rid)['notifications'][-1]['text'])
    def test_metadata_pins_and_secret_boundary(self):
        h,m,name,rid=self.setup();me=self.ok('GET','/v2/me',token=h)
        self.assertIn(rid,me['user']['pinnedRooms']);self.assertEqual(me['rooms'][0]['weekdays'],[1,3])
        self.assertEqual(self.snapshot(h,rid)['origin']['name'],'성수역')
        self.ok('PUT','/v2/me/pins',{'roomId':rid,'pinned':False},h)
        self.assertNotIn(rid,self.ok('GET','/v2/me',token=h)['user']['pinnedRooms'])
        self.assertEqual(self.request('PUT','/v2/me/pins',{'roomId':rid,'pinned':True},m)[0],403)
        config=self.ok('GET','/v2/config');self.assertEqual(set(config),{'kakaoJavascriptKey','placeSearchConfigured'})
        self.assertNotIn(server_v2.KAKAO_KEY,str(config)) if server_v2.KAKAO_KEY else None

    def test_pickup_dropoff_seat_preferences_persist_and_validate(self):
        h,m,name,rid=self.setup();joined=self.accept(m,self.invite(h,rid,name))
        path='/v2/trips/'+rid+'/members/'+joined['memberId']
        point={'name':'하차 장소','lat':37.53,'lng':127.04}
        self.ok('PATCH',path,{'dropoff':point,'seatPreferences':{'temperature':23,'heat':1,'ventilation':0}},m)
        snap=self.snapshot(h,rid);self.assertEqual(snap['members'][0]['dropoff'],point)
        self.assertIn([point['lat'],point['lng']],snap['route']['stops'])
        self.assertEqual(snap['members'][0]['seatPreferences']['temperature'],23)
        self.assertEqual(self.request('PATCH',path,{'seatPreferences':{'temperature':23,'heat':1,'ventilation':1}},m)[0],400)
        self.assertEqual(self.snapshot(h,rid)['members'][0]['seatPreferences']['ventilation'],0)
