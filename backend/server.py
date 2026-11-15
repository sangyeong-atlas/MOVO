"""MOVO local development API. Standard library only; bind to loopback by default."""
import json, secrets, sqlite3, os, re
from datetime import datetime, timezone
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
DB = Path(os.environ.get("HOPON_DB", Path(__file__).with_name("hopon.db")))
def now(): return datetime.now(timezone.utc).isoformat()
def connect():
 c=sqlite3.connect(DB); c.execute("PRAGMA foreign_keys=ON"); return c
def init():
 with connect() as c:
  c.executescript("""CREATE TABLE IF NOT EXISTS rooms(id TEXT PRIMARY KEY, code TEXT UNIQUE NOT NULL, token TEXT NOT NULL, data TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS members(id TEXT PRIMARY KEY, room TEXT REFERENCES rooms(id), token TEXT NOT NULL, data TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS records(room TEXT REFERENCES rooms(id), kind TEXT, id TEXT, data TEXT, PRIMARY KEY(room,kind,id));""")
def obj(s): return json.loads(s)
def dump(x): return json.dumps(x,ensure_ascii=False)
def ident(prefix):return prefix+secrets.token_hex(6)
class Error(Exception):
 def __init__(self,status,message):self.status=status;self.message=message
class API(BaseHTTPRequestHandler):
 def log_message(self,*args): pass
 def reply(self,status,data):
  raw=dump(data).encode();self.send_response(status); self.send_header('Content-Type','application/json; charset=utf-8');self.send_header('Content-Length',str(len(raw))); self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(raw)
 def do_GET(self):self.handle_api()
 def do_POST(self):self.handle_api()
 def do_PATCH(self):self.handle_api()
 def do_PUT(self):self.handle_api()
 def handle_api(self):
  try:
   n=int(self.headers.get('Content-Length','0'))
   if n>16384:raise Error(413,'Request too large')
   b=obj(self.rfile.read(n)) if n else {}
   if not isinstance(b,dict):raise Error(400,'JSON object required')
   with connect() as c: result,status=self.route(c,self.command,self.path.split('?')[0],b)
   self.reply(status,result)
  except Error as e:self.reply(e.status,{'error':e.message})
  except (ValueError,KeyError,TypeError):self.reply(400,{'error':'Invalid request fields'})
  except Exception as e:
   print(type(e).__name__,str(e));self.reply(500,{'error':'Internal server error'})
 def route(self,c,method,path,b):
  if path=='/health':return {'ok':True},200
  if path=='/rooms' and method=='POST':
   title=str(b.get('title','친구들과 주말 나들이')).strip()[:80]
   rid=ident('R-');token=secrets.token_urlsafe(32)
   while True:
    code=str(secrets.randbelow(900000)+100000)
    if not c.execute('SELECT 1 FROM rooms WHERE code=?',(code,)).fetchone():break
   data={'roomId':rid,'inviteCode':code,'theme':'friends','title':title,'status':'planning','destination':b.get('destination',{'name':'서울숲','lat':37.5445,'lng':127.0374}),'waypointOrder':[],'createdAt':now(),'updatedAt':now()}
   c.execute('INSERT INTO rooms VALUES(?,?,?,?)',(rid,code,token,dump(data)))
   return dict(data,hostToken=token),201
  if path=='/join' and method=='POST':
   row=c.execute('SELECT id,data FROM rooms WHERE code=?',(str(b['inviteCode']),)).fetchone()
   if not row:raise Error(404,'Invite not found')
   room=obj(row[1])
   if room['status'] in ['arrived','closed']:raise Error(409,'Room no longer accepting members')
   if c.execute('SELECT count(*) FROM members WHERE room=?',(row[0],)).fetchone()[0]>=4:raise Error(409,'Passenger limit reached')
   mid=ident('M-');token=secrets.token_urlsafe(32); name=str(b['displayName']).strip()[:24]
   if not name:raise Error(400,'Name required')
   d={'memberId':mid,'roomId':row[0],'displayName':name,'role':'passenger','status':'preparing','pickup':None,'seat':None,'joinedAt':now(),'updatedAt':now()}
   c.execute('INSERT INTO members VALUES(?,?,?,?)',(mid,row[0],token,dump(d)))
   room['waypointOrder'].append(mid);room['updatedAt']=now();c.execute('UPDATE rooms SET data=? WHERE id=?',(dump(room),row[0]))
   return dict(d,memberToken=token),201
  parts=path.strip('/').split('/')
  if len(parts)<2 or parts[0]!='rooms':raise Error(404,'Not found')
  rid=parts[1];r=c.execute('SELECT token,data FROM rooms WHERE id=?',(rid,)).fetchone()
  if not r:raise Error(404,'Room not found')
  token=self.headers.get('Authorization','').removeprefix('Bearer ')
  host=secrets.compare_digest(token,r[0]); me=c.execute('SELECT id FROM members WHERE room=? AND token=?',(rid,token)).fetchone()
  if not host and not me:raise Error(403,'Valid room token required')
  room=obj(r[1]); tail='/'.join(parts[2:])
  if tail=='' and method=='GET':return room,200
  if tail=='snapshot' and method=='GET':
   members=[obj(x[0]) for x in c.execute('SELECT data FROM members WHERE room=? ORDER BY rowid',(rid,))]
   records={}
   for kind,raw in c.execute('SELECT kind,data FROM records WHERE room=?',(rid,)):records.setdefault(kind,[]).append(obj(raw))
   return {'room':room,'members':members,'records':records},200
  if tail=='' and method=='PATCH':
   if not host:raise Error(403,'Host only')
   transitions={'planning':['pickup'],'pickup':['driving'],'driving':['arrived'],'arrived':['closed'],'closed':[]}
   if b.get('status') not in transitions[room['status']]:raise Error(409,'Invalid phase transition')
   if b['status']=='driving':
    passengers=[obj(x[0]) for x in c.execute('SELECT data FROM members WHERE room=?',(rid,))]
    if not passengers or any(x['status']!='boarded' for x in passengers):raise Error(409,'Confirm boarding first')
   room['status']=b['status'];room['updatedAt']=now();c.execute('UPDATE rooms SET data=? WHERE id=?',(dump(room),rid));return room,200
  if tail.startswith('members/') and method=='PATCH':
   mid=parts[3]
   if room['status'] in ['driving','arrived','closed']:raise Error(409,'Member edits locked in this phase')
   if not host and me[0]!=mid:raise Error(403,'Own member only')
   m=c.execute('SELECT data FROM members WHERE room=? AND id=?',(rid,mid)).fetchone()
   if not m:raise Error(404,'Member not found')
   d=obj(m[0]);status=b.get('status',d['status'])
   if status not in ['preparing','outside','boarded']:raise Error(400,'Invalid member status')
   if not host and (status=='boarded' or d['status']=='boarded'):raise Error(403,'Boarding controlled by host')
   if 'pickup' in b:
    x=b['pickup']
    if not isinstance(x,dict) or not -90<=float(x['lat'])<=90 or not -180<=float(x['lng'])<=180:raise Error(400,'Invalid coordinates')
    d['pickup']={'lat':float(x['lat']),'lng':float(x['lng']),'memo':str(x.get('memo',''))[:120]}
   d.update(status=status,updatedAt=now());c.execute('UPDATE members SET data=? WHERE id=?',(dump(d),mid));return d,200
  if tail in ['parking','vehicle-state','trip','messages']:
   if method=='GET':return [obj(x[0]) for x in c.execute('SELECT data FROM records WHERE room=? AND kind=? ORDER BY rowid',(rid,tail))],200
   if tail!='messages' and not host:raise Error(403,'Host only')
   if method not in ['POST','PUT']:raise Error(405,'Method not allowed')
   if tail=='parking':
    if room['status']!='arrived':raise Error(409,'Parking requires arrived phase')
    x=b['position']
    if not -90<=float(x['lat'])<=90 or not -180<=float(x['lng'])<=180:raise Error(400,'Invalid coordinates')
   if tail=='messages':
    if b.get('type') not in ['late_5min','arrived','came_out']:raise Error(400,'Invalid message type')
    b['memberId']=me[0] if me else 'host'
   key=ident('E-') if tail=='messages' else 'current'
   d=dict(b,roomId=rid,updatedAt=now());c.execute('INSERT OR REPLACE INTO records VALUES(?,?,?,?)',(rid,tail,key,dump(d)));return d,200
  raise Error(404,'Not found')
if __name__=='__main__':
 init();print('MOVO API: http://127.0.0.1:8787',flush=True);ThreadingHTTPServer(('127.0.0.1',8787),API).serve_forever()
