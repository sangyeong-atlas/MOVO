import unittest,tempfile,threading,json,urllib.request,urllib.error,sys,time
from pathlib import Path
from datetime import datetime,timezone,timedelta
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'backend'))
import server,legacy
class V2Test(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.tmp=tempfile.TemporaryDirectory();legacy.DB=Path(cls.tmp.name)/'v2.db';server.init();cls.http=server.ThreadingHTTPServer(('127.0.0.1',0),server.API);threading.Thread(target=cls.http.serve_forever,daemon=True).start();cls.base='http://127.0.0.1:'+str(cls.http.server_port)
 @classmethod
 def tearDownClass(cls):cls.http.shutdown();cls.http.server_close();cls.tmp.cleanup()
 def req(self,method,path,body=None,token='',key=None):
  headers={'Content-Type':'application/json','Authorization':'Bearer '+token}
  if key:headers['Idempotency-Key']=key
  req=urllib.request.Request(self.base+path,data=json.dumps(body).encode() if body is not None else None,method=method,headers=headers)
  try:
   with urllib.request.urlopen(req,timeout=5) as r:return r.status,json.load(r)
  except urllib.error.HTTPError as e:return e.code,json.load(e)
 def account(self):
  name=server.ident('test');code,a=self.req('POST','/v2/auth/register',{'name':name,'password':'test1234'});self.assertEqual(code,200);return a['token']
 def setUp(self):
  self.host=self.account();self.member=self.account();_,r=self.req('POST','/v2/trips',{'title':'시험 여행','destination':server.PLACES[0],'scheduledAt':datetime.now(timezone.utc).isoformat()},self.host);self.rid=r['roomId'];self.path='/v2/trips/'+self.rid
  _,i=self.req('POST',self.path+'/invites',{},self.host);self.invite='/v2/invites/'+i['inviteToken'];_,m=self.req('POST',self.invite+'/accept',{'consent':True},self.member);self.mid=m['memberId'];_,p=self.req('POST',self.path+'/pair',{},self.host);_,self.v=self.req('POST','/v2/pair/exchange',{'code':p['code']});self.vehicle=self.v['token']
 def snap(self,t=None):return self.req('GET',self.path+'/snapshot',token=t or self.host)[1]
 def telemetry(self,sequence=1,**kw):
  b={'tripId':self.rid,'vehicleId':self.v['vehicleId'],'lat':37.5445,'lng':127.0374,'headingDeg':None,'speedMps':0,'accuracyM':8,'sequence':sequence,'source':'simulator','observedAt':datetime.now(timezone.utc).isoformat()};b.update(kw);return b
 def pickup(self):self.assertEqual(self.req('PATCH',self.path+'/phase',{'status':'pickup'},self.host)[0],200)
 def test_two_clients_invite_restore_conflict(self):
  self.assertEqual(self.req('POST',self.invite+'/accept',{'consent':True},self.member)[0],200);self.assertEqual(len(self.snap()['members']),1)
  body={'revision':0,'course':[server.PLACES[1]]};self.assertEqual(self.req('PUT',self.path+'/course',body,self.member)[0],200);self.assertEqual(self.req('PUT',self.path+'/course',body,self.host)[0],409)
  self.assertEqual(self.snap()['course'],self.snap(self.member)['course']);self.assertEqual(self.snap()['revision'],1)
  self.assertEqual(self.req('GET',self.path+'/snapshot',token=self.account())[0],403)
  self.assertEqual(self.req('PATCH',self.path+'/members/'+self.mid,{'status':'boarded'},self.member)[0],403)
 def test_pair_once_and_expired_invite(self):
  _,p=self.req('POST',self.path+'/pair',{},self.host)
  self.assertEqual(self.req('POST','/v2/pair/exchange',{'code':p['code']})[0],200);self.assertEqual(self.req('POST','/v2/pair/exchange',{'code':p['code']})[0],403)
  self.req('DELETE',self.path+'/invites',{},self.host);self.assertEqual(self.req('GET',self.invite)[0],404)
 def test_telemetry_order_freshness_proximity(self):
  self.pickup();self.req('PATCH',self.path+'/members/'+self.mid,{'pickup':{'lat':37.5445,'lng':127.0374,'memo':'입구'}},self.member)
  self.assertEqual(self.req('POST',self.path+'/telemetry',self.telemetry(),self.member)[0],403)
  self.assertEqual(self.req('POST',self.path+'/telemetry',self.telemetry(lat=91),self.vehicle)[0],400)
  for i in range(1,5):self.assertEqual(self.req('POST',self.path+'/telemetry',self.telemetry(i),self.vehicle)[0],200)
  self.assertTrue(self.req('POST',self.path+'/telemetry',self.telemetry(1),self.vehicle)[1]['ignored']);s=self.snap();self.assertEqual(s['telemetry']['sequence'],4);self.assertEqual(s['route']['perMemberETA'][self.mid]['state'],'arrived');self.assertEqual(len(s['notifications']),2)
  with server.LOCK,legacy.connect() as c:
   x=server.extra(c,self.rid);x['telemetry']['receivedAt']=(datetime.now(timezone.utc)-timedelta(seconds=31)).isoformat();server.save(c,self.rid,x)
  s=self.snap();self.assertEqual(s['freshness'],'lost');self.assertIsNone(s['route']['perMemberETA'][self.mid]['seconds'])
 def test_wait_time_and_bad_gps(self):
  self.pickup();self.req('PATCH',self.path+'/members/'+self.mid,{'pickup':{'lat':37.5445,'lng':127.0374}},self.member)
  other=self.account();_,m=self.req('POST',self.invite+'/accept',{'consent':True},other);self.req('PATCH',self.path+'/members/'+m['memberId'],{'pickup':{'lat':37.5445,'lng':127.0374}},other)
  for i in range(1,5):self.req('POST',self.path+'/telemetry',self.telemetry(i,accuracyM=300),self.vehicle)
  s=self.snap();etas=s['route']['perMemberETA'];self.assertEqual(etas[m['memberId']]['seconds']-etas[self.mid]['seconds'],120);self.assertEqual(etas[self.mid]['state'],'waiting');self.assertFalse(s['notifications'])
 def test_request_ack_and_idempotency(self):
  self.pickup();body={'kind':'stop','place':server.PLACES[1]};code,q=self.req('POST',self.path+'/requests',body,self.member,'same');self.assertEqual(code,200);self.req('POST',self.path+'/requests',body,self.member,'same');self.assertEqual(len(self.snap()['requests']),1)
  path=self.path+'/requests/'+q['requestId'];self.assertEqual(self.req('PATCH',path,{'status':'approved'},self.host)[0],403);self.assertEqual(self.req('PATCH',path,{'status':'applied'},self.vehicle)[0],409)
  self.req('PATCH',path,{'status':'approved'},self.vehicle);self.req('PATCH',path,{'status':'failed'},self.vehicle);self.assertEqual(len(self.snap()['course']),0)
  _,q=self.req('POST',self.path+'/requests',body,self.member,'next');path=self.path+'/requests/'+q['requestId'];self.req('PATCH',path,{'status':'approved'},self.vehicle);self.req('PATCH',path,{'status':'applied'},self.vehicle);self.req('PATCH',path,{'status':'applied'},self.vehicle);self.assertEqual(len(self.snap()['course']),1)
 def test_capability_and_parking_retry_history(self):
  self.pickup();m=self.snap()['members'][0];b={'kind':'climate','feature':'temperature','value':22,'seat':m['seat']};self.assertEqual(self.req('POST',self.path+'/requests',b,self.member,'climate')[0],409)
  self.req('PUT',self.path+'/vehicle-state',{'capabilities':{'temperature':True},'seats':{}},self.vehicle);self.assertEqual(self.req('POST',self.path+'/requests',b,self.member,'climate')[0],200)
  self.req('PATCH',self.path+'/members/'+self.mid,{'status':'boarded'},self.vehicle);self.req('PATCH',self.path+'/phase',{'status':'driving'},self.host)
  self.assertEqual(self.req('PUT',self.path+'/course',{'revision':0,'course':[]},self.member)[0],409);self.assertEqual(self.req('PATCH',self.path+'/phase',{'status':'arrived'},self.host)[0],409)
  self.req('PATCH',self.path+'/phase',{'status':'arrived'},self.vehicle);body={'position':{'lat':37.5445,'lng':127.0374},'source':'phone','observedAt':datetime.now(timezone.utc).isoformat(),'memo':'B2 C-14','accuracyM':5}
  self.assertEqual(self.req('PUT',self.path+'/parking',body,self.host,'park')[0],400);body['confirmed']=True
  self.assertEqual(self.req('PUT',self.path+'/parking',body,self.host,'park')[0],200);self.assertEqual(self.req('PUT',self.path+'/parking',body,self.host,'park')[0],200);self.assertEqual(self.snap(self.member)['parking']['memo'],'B2 C-14')
  self.req('POST',self.path+'/library',{'kind':'crew'},self.host);self.req('PATCH',self.path+'/phase',{'status':'closed'},self.host);self.assertIsNone(self.snap()['telemetry']);self.assertEqual(self.snap(self.member)['room']['status'],'closed')
 def test_solo_and_leave(self):
  self.assertEqual(self.req('POST',self.path+'/leave',{},self.member)[0],200);self.assertEqual(self.req('GET',self.path+'/snapshot',token=self.member)[0],403);self.pickup();self.assertEqual(self.req('PATCH',self.path+'/phase',{'status':'driving'},self.host)[0],200)
 def test_preferences_and_shared_destination(self):
  self.assertEqual(self.req('PATCH','/v2/me',{'preferences':['카페']},self.host)[0],200)
  self.assertEqual(self.req('GET','/v2/recommendations',token=self.host)[1]['places'][0]['category'],'카페')
  self.assertEqual(self.req('PUT',self.path+'/course',{'revision':0,'course':[],'origin':{'name':'출발지','lat':37.55,'lng':127.05},'destination':server.PLACES[2]},self.member)[0],200)
  self.assertEqual(self.snap()['room']['destination']['name'],'성수 골목 식당');self.assertEqual(self.snap()['origin']['name'],'출발지')
 def test_sse_auth_and_snapshot(self):
  self.assertEqual(self.req('GET',self.path+'/events')[0],403)
  req=urllib.request.Request(self.base+self.path+'/events',headers={'Authorization':'Bearer '+self.member,'Last-Event-ID':'999'})
  with urllib.request.urlopen(req,timeout=3) as r:
   self.assertIn('text/event-stream',r.headers['Content-Type']);self.assertTrue(r.readline().startswith(b'id:'));self.assertIn(b'snapshot',r.readline());data=json.loads(r.readline().decode()[6:]);self.assertEqual(data['room']['roomId'],self.rid)
if __name__=='__main__':unittest.main()
