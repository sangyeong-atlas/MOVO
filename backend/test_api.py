import unittest, tempfile, threading, json, urllib.request, urllib.error
from pathlib import Path
import server
class ContractTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.tmp=tempfile.TemporaryDirectory(); server.DB=Path(cls.tmp.name)/'test.db';server.init()
  cls.http=server.ThreadingHTTPServer(('127.0.0.1',0),server.API)
  threading.Thread(target=cls.http.serve_forever,daemon=True).start();cls.base='http://127.0.0.1:'+str(cls.http.server_port)
 @classmethod
 def tearDownClass(cls):cls.http.shutdown();cls.http.server_close();cls.tmp.cleanup()
 def req(self,method,path,data=None,token=''):
  r=urllib.request.Request(self.base+path,data=json.dumps(data).encode() if data is not None else None,method=method,headers={'Content-Type':'application/json','Authorization':'Bearer '+token})
  try:
   with urllib.request.urlopen(r) as x:return x.status,json.load(x)
  except urllib.error.HTTPError as e:return e.code,json.load(e)
 def new(self):return self.req('POST','/rooms',{'title':'MOVO test'})[1]
 def test_full_journey_and_persistence(self):
  r=self.new();url='/rooms/'+r['roomId'];t=r['hostToken']
  code,m=self.req('POST','/join',{'inviteCode':r['inviteCode'],'displayName':'민지'});self.assertEqual(code,201)
  self.assertEqual(self.req('GET',url+'/snapshot')[0],403)
  self.assertEqual(self.req('PATCH',url,{'status':'driving'},t)[0],409)
  self.assertEqual(self.req('PATCH',url,{'status':'pickup'},t)[0],200)
  self.assertEqual(self.req('PATCH',url,{'status':'driving'},t)[0],409)
  member=url+'/members/'+m['memberId']
  self.assertEqual(self.req('PATCH',member,{'status':'boarded'},m['memberToken'])[0],403)
  self.assertEqual(self.req('PATCH',member,{'status':'outside','pickup':{'lat':37.5,'lng':127}},m['memberToken'])[0],200)
  self.assertEqual(self.req('PATCH',member,{'status':'boarded'},t)[0],200)
  self.assertEqual(self.req('PATCH',url,{'status':'driving'},t)[0],200)
  self.assertEqual(self.req('PATCH',member,{'status':'outside'},t)[0],409)
  self.assertEqual(self.req('PATCH',url,{'status':'arrived'},t)[0],200)
  self.assertEqual(self.req('PUT',url+'/parking',{'position':{'lat':37.5,'lng':127},'memo':'B2'},m['memberToken'])[0],403)
  self.assertEqual(self.req('PUT',url+'/parking',{'position':{'lat':37.5,'lng':127},'memo':'B2'},t)[0],200)
  snap=self.req('GET',url+'/snapshot',token=m['memberToken'])[1]
  self.assertEqual(snap['records']['parking'][0]['memo'],'B2')
  with server.connect() as c:self.assertEqual(c.execute('SELECT count(*) FROM records WHERE room=?',(r['roomId'],)).fetchone()[0],1)
 def test_cross_room_access_and_validation(self):
  a=self.new();b=self.new()
  self.assertEqual(self.req('GET','/rooms/'+b['roomId']+'/snapshot',token=a['hostToken'])[0],403)
  m=self.req('POST','/join',{'inviteCode':a['inviteCode'],'displayName':'x'})[1]
  url='/rooms/'+a['roomId']+'/members/'+m['memberId']
  self.assertEqual(self.req('PATCH',url,{'pickup':{'lat':100,'lng':127}},m['memberToken'])[0],400)
  self.assertEqual(self.req('PATCH',url,{'status':'unknown'},m['memberToken'])[0],400)
if __name__=='__main__':unittest.main()
