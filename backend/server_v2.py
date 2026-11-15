"""MOVO 0.2 development API. SQLite persistence and authenticated SSE."""
import os,secrets,hashlib,hmac,threading,time,math,itertools,urllib.request,urllib.parse,json as _json
from datetime import datetime,timezone
from http.server import ThreadingHTTPServer
import server as legacy
from server import Error,now,dump,obj,ident
from routing import ROADS
import sys
import mobile_features
KAKAO_KEY=os.getenv('KAKAO_REST_API_KEY','')
RATE={};LOCK=threading.RLock();STALE=int(os.getenv('LOCATION_STALE_SECONDS','10'));LOST=int(os.getenv('LOCATION_LOST_SECONDS','30'))
PLACES=[{'id':'forest','name':'서울숲','lat':37.5445,'lng':127.0374,'category':'명소','stayMinutes':40},{'id':'cafe','name':'성수 온실 카페','lat':37.5474,'lng':127.0437,'category':'카페','stayMinutes':30},{'id':'meal','name':'성수 골목 식당','lat':37.5458,'lng':127.0493,'category':'음식점','stayMinutes':50},{'id':'rest','name':'한강 쉼터','lat':37.5385,'lng':127.0417,'category':'휴게소','stayMinutes':15}]
PLACE_CACHE={};PLACE_LOCK=threading.Lock()
CATEGORY_NAMES={'FD6':'음식점','CE7':'카페','AT4':'명소','CS2':'편의점','PK6':'주차장'}
def kakao_places(lat,lng,category='FD6',query=None,radius=1500,size=10):
 if not KAKAO_KEY:return None
 cache_key=(lat,lng,category,query,radius,size)
 with PLACE_LOCK:
  cached=PLACE_CACHE.get(cache_key)
  if cached and time.monotonic()-cached[0]<60:return [dict(p) for p in cached[1]]
 try:
  if query:
   params={'query':query,'size':str(size)}
   if lat and lng:params.update({'x':str(lng),'y':str(lat),'radius':str(radius),'sort':'distance'})
   url='https://dapi.kakao.com/v2/local/search/keyword.json?'+urllib.parse.urlencode(params)
  else:
   params={'category_group_code':category,'x':str(lng),'y':str(lat),'radius':str(radius),'sort':'distance','size':str(size)}
   url='https://dapi.kakao.com/v2/local/search/category.json?'+urllib.parse.urlencode(params)
  req=urllib.request.Request(url,headers={'Authorization':f'KakaoAK {KAKAO_KEY}'})
  with urllib.request.urlopen(req,timeout=5) as res:data=_json.loads(res.read().decode())
  places=[{'id':d['id'],'name':d['place_name'],'lat':float(d['y']),'lng':float(d['x']),'category':d.get('category_group_name') or d['category_name'].split(' > ')[-1],'stayMinutes':30,'phone':d.get('phone') or None,'distance':int(d['distance']) if d.get('distance') else None,'address':d.get('road_address_name') or d.get('address_name',''),'placeUrl':d.get('place_url','')} for d in data.get('documents',[])]
  with PLACE_LOCK:
   PLACE_CACHE[cache_key]=(time.monotonic(),places)
   if len(PLACE_CACHE)>128:PLACE_CACHE.pop(next(iter(PLACE_CACHE)))
  return [dict(p) for p in places]
 except Exception:return None
def init():
 legacy.init()
 with legacy.connect() as c:mobile_features.init(c)
 with legacy.connect() as c:c.executescript('''CREATE TABLE IF NOT EXISTS accounts(id TEXT PRIMARY KEY,name TEXT UNIQUE,salt TEXT,hash TEXT,data TEXT);
CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY,account TEXT,expires REAL);
CREATE TABLE IF NOT EXISTS access(room TEXT,account TEXT,role TEXT,member TEXT,PRIMARY KEY(room,account));
CREATE TABLE IF NOT EXISTS extra(room TEXT PRIMARY KEY,data TEXT);
CREATE TABLE IF NOT EXISTS invites(token TEXT PRIMARY KEY,room TEXT,expires REAL);
CREATE TABLE IF NOT EXISTS pairs(code TEXT PRIMARY KEY,room TEXT,expires REAL);
CREATE TABLE IF NOT EXISTS vehicles(token TEXT PRIMARY KEY,room TEXT,id TEXT);
CREATE TABLE IF NOT EXISTS library(id TEXT PRIMARY KEY,account TEXT,kind TEXT,data TEXT);
CREATE TABLE IF NOT EXISTS dedup(actor TEXT,path TEXT,key TEXT,data TEXT,PRIMARY KEY(actor,path,key));''')
def require(ok,msg='이 작업의 권한이 없어요',status=403):
 if not ok:raise Error(status,msg)
def coord(p):
 require(isinstance(p,dict),'좌표가 필요해요',400)
 for k,lim in [('lat',90),('lng',180)]:
  v=p.get(k);require(not isinstance(v,bool) and isinstance(v,(int,float)) and math.isfinite(v) and -lim<=v<=lim,'좌표 범위를 확인해 주세요',400)
 return p
def stamp(s):
 d=datetime.fromisoformat(s.replace('Z','+00:00'));require(d.tzinfo is not None,'UTC offset required',400);return d.timestamp()
def dist(a,b):return 6371000*2*math.asin(min(1,math.sqrt(math.sin(math.radians(b['lat']-a['lat'])/2)**2+math.cos(math.radians(a['lat']))*math.cos(math.radians(b['lat']))*math.sin(math.radians(b['lng']-a['lng'])/2)**2)))
def fresh(t):
 if not t:return 'unknown'
 age=max(time.time()-stamp(t['receivedAt']),time.time()-stamp(t['observedAt']));return 'lost' if age>=LOST else 'stale' if age>=STALE else 'fresh'
def base_extra():return {'revision':0,'eventId':0,'course':[],'pickupOrder':[],'fixedOrder':False,'requests':[],'playlist':[],'notifications':[],'messages':[],'capabilities':{},'seats':{},'telemetry':None,'parking':None,'visits':[],'origin':{'name':'성수역','lat':37.5447,'lng':127.0558},'bufferMinutes':10,'consent':True}
MESSAGE_TEXT={'late_5min':'5분 늦어요','came_out':'나왔어요','arrived':'도착했어요'}
def passenger_message(x,m,kind):
 message={'id':ident('MSG-'),'memberId':m['memberId'],'displayName':m['displayName'],'type':kind,'text':MESSAGE_TEXT[kind],'createdAt':now()}
 x['messages']=(x.get('messages',[])+[message])[-100:]
 return message
def extra(c,rid):
 a=c.execute('SELECT data FROM extra WHERE room=?',(rid,)).fetchone();return obj(a[0]) if a else base_extra()
def save(c,rid,x):x.update(eventId=x['eventId']+1,updatedAt=now());c.execute('INSERT OR REPLACE INTO extra VALUES(?,?)',(rid,dump(x)))
def room(c,rid):
 r=c.execute('SELECT data FROM rooms WHERE id=?',(rid,)).fetchone();require(r,'여행을 찾을 수 없어요',404);return obj(r[0])
def members(c,rid):return [obj(r[0]) for r in c.execute('SELECT data FROM members WHERE room=? ORDER BY rowid',(rid,))]
def putroom(c,r):r['updatedAt']=now();c.execute('UPDATE rooms SET data=? WHERE id=?',(dump(r),r['roomId']))
def route_stops(r,x,ms):
 t=x.get('telemetry');pos=t if t else x['origin']
 order=[m for mid in x['pickupOrder'] for m in ms if m['memberId']==mid and m.get('pickup') and m['status']!='boarded']
 order += [m for m in ms if m.get('pickup') and m['status']!='boarded' and m not in order]
 return [pos]+[m['pickup'] for m in order]+x['course']+[m['dropoff'] for m in ms if m.get('dropoff')]+[r['destination']],order

def road_route(r,x,ms):
 stops,order=route_stops(r,x,ms)
 result=ROADS.unavailable('trip_complete') if r['status'] in ['arrived','closed'] else ROADS.request(stops,scope=(r['roomId'],x['revision'],r['status']))
 prefix=[]
 for p in stops[:1+len(order)+len(x['course'])]:
  value=(p['lat'],p['lng'])
  if not prefix or value!=prefix[-1]:prefix.append(value)
 result['_insertionIndex']=len(prefix)
 return result,stops,order

def routeinfo(r,x,ms):
 result,stops,order=road_route(r,x,ms)
 ready=result['status']=='ready';valid=x.get('telemetry') and fresh(x['telemetry'])=='fresh'
 # ETA uses road travel plus explicit pickup waits and course dwell, never an air-distance multiplier.
 dwell=sum(p.get('stayMinutes',0)*60 for p in x['course'])+120*len(order)
 waypoints=result.get('waypointSeconds',[])
 # Deduplicated adjacent stops do not have a one-to-one guide mapping.
 mapped=len(result.get('_points',[]))==len(stops)
 etas={m['memberId']:{'seconds':waypoints[i]+120*i if valid and mapped and i<len(waypoints) else None,'ahead':i,'source':'naver' if ready else 'unavailable','state':x.get('proximity',{}).get(m['memberId'],{}).get('state','waiting')} for i,m in enumerate(order)}
 departure=None
 if r.get('scheduledAt') and r['status'] not in ['arrived','closed']:
  planned=ROADS.request([x['origin']]+stops[1:],scope=(r['roomId'],x['revision'],'departure'))
  if planned['status']=='ready':departure=datetime.fromtimestamp(stamp(r['scheduledAt'])-planned['drivingSeconds']-dwell-x['bufferMinutes']*60,timezone.utc).isoformat()
 labels={'ready':'네이버 도로 경로 · 조회 시점 교통 반영','pending':'도로 경로 조회 중','unavailable':'도로 경로를 불러올 수 없어요'}
 if result.get('reason')=='not_configured':labels['unavailable']='도로 경로 연결 설정 필요'
 elif result.get('reason')=='too_many_waypoints':labels['unavailable']='경유지 15개까지 도로 경로를 조회할 수 있어요'
 return {**{k:v for k,v in result.items() if not k.startswith('_') and k!='waypointSeconds'},'label':labels[result['status']],'stops':[[p['lat'],p['lng']] for p in stops],'perMemberETA':etas,'distanceSource':'road' if ready else 'unavailable','destinationSeconds':result['drivingSeconds']+dwell if valid and ready else None,'recommendedDepartureAt':departure,'nextMemberId':order[0]['memberId'] if order else None,'pickupWaitSeconds':120}

class API(legacy.API):
 def do_DELETE(self):self.handle_api()
 def handle_api(self):
  if self.command=='GET' and self.path.split('?')[0] in ['/demo','/demo/','/demo/app.js']:
   from pathlib import Path
   name='app.js' if self.path.split('?')[0].endswith('.js') else 'index.html'
   raw=(Path(__file__).parent/'demo'/name).read_bytes()
   self.send_response(200);self.send_header('Content-Type','text/javascript; charset=utf-8' if name.endswith('.js') else 'text/html; charset=utf-8');self.send_header('Content-Length',str(len(raw)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(raw);return
  if self.path.split('?')[0].endswith('/events') and self.command=='GET':return self.events()
  # These authenticated read-only searches may wait for external providers.
  # They must not hold the global Journey lock and delay ACKs or telemetry.
  search_path=self.path.split('?')[0]
  if self.command=='GET' and (search_path=='/v2/places/nearby' or search_path.endswith('/stop-candidates')):return super().handle_api()
  with LOCK:super().handle_api()
 def auth(self,c):
  token=self.headers.get('Authorization','').removeprefix('Bearer ');a=c.execute('SELECT account FROM sessions WHERE token=? AND expires>?',(token,time.time())).fetchone();return token,a[0] if a else None
 def permission(self,c,rid):
  token,uid=self.auth(c)
  if uid:
   a=c.execute('SELECT role,member FROM access WHERE room=? AND account=?',(rid,uid)).fetchone()
   if a:return uid,a[0],a[1]
  v=c.execute('SELECT id FROM vehicles WHERE token=? AND room=?',(token,rid)).fetchone()
  if v:return v[0],'vehicle',None
  r=c.execute('SELECT token FROM rooms WHERE id=?',(rid,)).fetchone()
  if r and hmac.compare_digest(token,r[0]):return 'legacy-host','host',None
  m=c.execute('SELECT id FROM members WHERE room=? AND token=?',(rid,token)).fetchone()
  if m:return m[0],'member',m[0]
  raise Error(403,'세션 또는 여행 참가 권한을 확인해 주세요')
 def snapshot(self,c,rid):
  actor,role,mid=self.permission(c,rid);r=room(c,rid);x=extra(c,rid);ms=members(c,rid)
  if r['status']=='closed' or (x.get('telemetry') and time.time()-stamp(x['telemetry']['receivedAt'])>86400):
   if x.get('telemetry'):x['telemetry']=None;save(c,rid,x)
  return {'room':r,'members':ms,**x,'role':role,'memberId':mid,'route':routeinfo(r,x,ms),'freshness':fresh(x.get('telemetry')),'serverTime':now(),'thresholds':{'stale':STALE,'lost':LOST}}
 def route(self,c,method,path,b):
  if not path.startswith('/v2'):return super().route(c,method,path,b)
  # Provider-derived estimates are display-only, not part of saved Journey places.
  if method!='GET' and isinstance(b,dict):
   def selected_place(p):return {k:v for k,v in p.items() if not k.startswith('detour') and k!='extraMinutes'} if isinstance(p,dict) else p
   b=selected_place(b)
   for field in ['place','destination','origin']:
    if field in b:b[field]=selected_place(b[field])
   if isinstance(b.get('course'),list):b['course']=[selected_place(p) for p in b['course']]
  mobile_result=mobile_features.route(self,c,method,path,b,sys.modules[__name__])
  if mobile_result is not None:return mobile_result
  if path.startswith('/v2/auth/') or path=='/v2/pair/exchange':
   bucket=(self.client_address[0],path);recent=[t for t in RATE.get(bucket,[]) if time.time()-t<60];require(len(recent)<60,'요청이 많아요. 60초 후 다시 시도해 주세요',429);RATE[bucket]=recent+[time.time()]
  token,uid=self.auth(c)
  if path in ['/v2/auth/register','/v2/auth/login'] and method=='POST':
   name=str(b.get('name','')).strip()[:24];password=str(b.get('password',''));require(name and len(password)>=6,'이름과 6자 이상 테스트 비밀번호를 입력해 주세요',400);a=c.execute('SELECT id,salt,hash,data FROM accounts WHERE name=?',(name,)).fetchone()
   if path.endswith('register'):
    require(not a,'이미 등록된 이름이에요. 로그인해 주세요',409);salt=secrets.token_hex(16);aid=ident('U-');data={'id':aid,'name':name,'avatar':{'hair':0,'face':0,'color':'#b9e8db'},'vehicle':{'name':'나의 아반떼','plate':'1234'}};digest=hashlib.pbkdf2_hmac('sha256',password.encode(),salt.encode(),150000).hex();c.execute('INSERT INTO accounts VALUES(?,?,?,?,?)',(aid,name,salt,digest,dump(data)));a=(aid,salt,digest,dump(data))
   require(a and hmac.compare_digest(a[2],hashlib.pbkdf2_hmac('sha256',password.encode(),a[1].encode(),150000).hex()),'이름 또는 비밀번호를 확인해 주세요');tok=secrets.token_urlsafe(32);c.execute('INSERT INTO sessions VALUES(?,?,?)',(tok,a[0],time.time()+86400*7));return {'token':tok,'user':obj(a[3]),'authMode':'test-password'},200
  if path=='/v2/pair/exchange' and method=='POST':
   p=c.execute('SELECT room,expires FROM pairs WHERE code=?',(str(b.get('code')),)).fetchone();require(p and p[1]>time.time(),'페어링 코드가 만료되었어요');require(room(c,p[0])['status']!='closed','Journey is closed',409);tok=secrets.token_urlsafe(32);vid=ident('V-');c.execute('DELETE FROM pairs WHERE code=?',(str(b['code']),));c.execute('DELETE FROM vehicles WHERE room=?',(p[0],));c.execute('INSERT INTO vehicles VALUES(?,?,?)',(tok,p[0],vid));return {'token':tok,'tripId':p[0],'vehicleId':vid},200
  if path.startswith('/v2/invites/'):
   it=path.split('/')[3];i=c.execute('SELECT room,expires FROM invites WHERE token=?',(it,)).fetchone();require(i and i[1]>time.time(),'만료되거나 폐기된 탑승권이에요',404);r=room(c,i[0])
   if method=='GET':return {'title':r['title'],'destination':r['destination'],'scheduledAt':r.get('scheduledAt'),'expiresAt':datetime.fromtimestamp(i[1],timezone.utc).isoformat()},200
   require(uid,'먼저 테스트 계정으로 로그인해 주세요');require(b.get('consent') is True,'픽업 지점 공유 동의가 필요해요',400);existing=c.execute('SELECT member FROM access WHERE room=? AND account=?',(i[0],uid)).fetchone()
   if existing:return {'roomId':i[0],'memberId':existing[0]},200
   require(r['status'] in ['planning','pickup'],'참가가 마감되었어요',409);user=obj(c.execute('SELECT data FROM accounts WHERE id=?',(uid,)).fetchone()[0]);d,status=super().route(c,'POST','/join',{'inviteCode':r['inviteCode'],'displayName':user['name']});m={k:v for k,v in d.items() if k!='memberToken'};m['avatar']=user['avatar'];m['seat']=next(seat for seat in range(1,5) if seat not in [v.get('seat') for v in members(c,i[0]) if v['memberId']!=d['memberId']]);c.execute('UPDATE members SET data=? WHERE id=?',(dump(m),d['memberId']));c.execute('INSERT INTO access VALUES(?,?,?,?)',(i[0],uid,'member',d['memberId']));x=extra(c,i[0]);save(c,i[0],x);return {'roomId':i[0],'memberId':d['memberId']},201
  if path=='/v2/me':
   require(uid);user=obj(c.execute('SELECT data FROM accounts WHERE id=?',(uid,)).fetchone()[0])
   if method=='PATCH':
    for k in ['avatar','vehicle','preferences']:
     if k in b:
      if k=='preferences':
       require(isinstance(b[k],list) and all(v in ['명소','카페','음식점','휴게소'] for v in b[k]),'Invalid preferences',400);user[k]=b[k];continue
      require(isinstance(b[k],dict),'프로필 형식을 확인해 주세요',400)
      if k=='avatar':require(b[k].get('hair') in [0,1,2] and b[k].get('face') in [0,1,2] and b[k].get('color') in ['#b9e8db','#f4ccb8','#d5c8ee','#b9d8ed'],'Invalid avatar',400)
      if k=='vehicle':require(isinstance(b[k].get('name'),str) and 0<len(b[k]['name'])<=40 and isinstance(b[k].get('plate'),str) and len(b[k]['plate'])<=4,'Invalid vehicle profile',400)
      user[k]=b[k]
    c.execute('UPDATE accounts SET data=? WHERE id=?',(dump(user),uid))
    for rid,mid in c.execute('SELECT room,member FROM access WHERE account=?',(uid,)):
     if mid:
      m=next((m for m in members(c,rid) if m['memberId']==mid),None)
      if m:m['avatar']=user['avatar'];c.execute('UPDATE members SET data=? WHERE id=?',(dump(m),mid))
   return {'user':user,'rooms':[dict(room(c,r[0]),role=r[1]) for r in c.execute('SELECT room,role FROM access WHERE account=?',(uid,))],'library':[dict(obj(r[1]),id=r[0],kind=r[2]) for r in c.execute('SELECT id,data,kind FROM library WHERE account=?',(uid,))]},200
  if path=='/v2/recommendations':
   require(uid);u=obj(c.execute('SELECT data FROM accounts WHERE id=?',(uid,)).fetchone()[0]);prefs=u.get('preferences',[]);visits=[]
   for (rid,) in c.execute('SELECT room FROM access WHERE account=?',(uid,)):visits+=extra(c,rid)['visits']
   scored=sorted(PLACES,key=lambda p:-(3*(p['category'] in prefs)+sum(v.get('category')==p['category'] for v in visits)))
   reason='좋아하는 '+', '.join(prefs)+' 취향을 반영했어요' if prefs else '방문 확인한 장소의 종류를 참고했어요' if visits else '아직 취향과 기록이 없어 가벼운 산책 코스를 골랐어요'
   return {'source':'sample','reason':reason,'places':scored,'queriedAt':now()},200
  if path=='/v2/places':
   require(uid);return {'source':'sample','queriedAt':now(),'places':[dict(p,extraMinutes=5,openingHours='샘플 · 영업정보 확인 필요',availability=None) for p in PLACES]},200
  if path=='/v2/places/nearby':
   require(method=='GET','GET 요청만 지원해요',405)
   qs=urllib.parse.parse_qs(self.path.split('?',1)[1] if '?' in self.path else '')
   try:lat=float(qs.get('lat',['0'])[0]);lng=float(qs.get('lng',['0'])[0])
   except ValueError:raise Error(400,'lat/lng 숫자를 입력해 주세요')
   require(-90<=lat<=90 and -180<=lng<=180,'좌표 범위를 확인해 주세요',400)
   cat=qs.get('category',['FD6'])[0];query=qs.get('query',[''])[0].strip() or None
   require(cat in ['FD6','CE7','AT4','CS2','PK6'],'장소 분류를 확인해 주세요',400)
   require(query is None or len(query)<=100,'검색어는 100자 이내로 입력해 주세요',400)
   require(lat!=0 or lng!=0 or query,'위치 정보 또는 검색어가 필요해요',400)
   trip=qs.get('tripId',[''])[0];baseline=None;revision=None
   if trip:
    self.permission(c,trip);r=room(c,trip);x=extra(c,trip);revision=x['revision'];baseline,_,_=road_route(r,x,members(c,trip))
   if baseline and baseline['status']=='ready' and qs.get('alongRoute',['0'])[0]=='1':lat,lng=baseline['polyline'][0]
   result=kakao_places(lat,lng,cat,query,size=5) if trip else kakao_places(lat,lng,cat,query)
   if result is None:
    raise Error(503,'장소 검색에 연결하지 못했어요. 잠시 후 다시 검색해 주세요.')
   if baseline and qs.get('alongRoute',['0'])[0]=='1' and baseline['status']=='ready':
    # Three distributed road samples bound external calls. Results remain ephemeral.
    path_points=baseline['polyline'];found={str(p['id']):p for p in result}
    for index in sorted({len(path_points)//2,len(path_points)-1}):
     point=path_points[index]
     for p in kakao_places(point[0],point[1],cat,query,size=5) or []:found.setdefault(str(p['id']),p)
    result=sorted(found.values(),key=lambda p:dist({'lat':lat,'lng':lng},p))[:5]
    result=[dict(p,distance=round(dist({'lat':lat,'lng':lng},p))) for p in result]
   if baseline:result=ROADS.detours(baseline,result)
   return {'source':'kakao','places':result,**({'revision':revision,'detourPending':any(p.get('detourStatus')=='pending' for p in result)} if trip else {})},200
  if path=='/v2/trips' and method=='POST':
   require(bool(str(b.get('title','새 파티방')).strip()),'파티방 이름을 입력해 주세요',400)
   if 'origin' in b:coord(b['origin'])
   require(b.get('purpose','모임') in ['출퇴근','모임','데이트','가족','출장'],'여정 목적을 확인해 주세요',400)
   days=b.get('weekdays',[]);require(isinstance(days,list) and all(type(d)is int and 0<=d<7 for d in days),'요일을 확인해 주세요',400)
   require(uid);coord(b.get('destination',PLACES[0]));stamp(b['scheduledAt']) if b.get('scheduledAt') else None;r,_=super().route(c,'POST','/rooms',b);rid=r['roomId'];r.pop('hostToken');r.update(scheduledAt=b.get('scheduledAt'),hostName=obj(c.execute('SELECT data FROM accounts WHERE id=?',(uid,)).fetchone()[0])['name']);r['vehicle']=obj(c.execute('SELECT data FROM accounts WHERE id=?',(uid,)).fetchone()[0])['vehicle'];putroom(c,r);c.execute('INSERT INTO access VALUES(?,?,?,?)',(rid,uid,'host',None));x=base_extra();x['course']=b.get('course',[]);x['origin']=b.get('origin',x['origin'])
   for p in x['course']:coord(p)
   r.update(purpose=b.get('purpose','모임'),weekdays=sorted(set(days)),solo=b.get('solo') is True);putroom(c,r)
   if b.get('pinned') is True:
    u=obj(c.execute('SELECT data FROM accounts WHERE id=?',(uid,)).fetchone()[0]);u['pinnedRooms']=u.get('pinnedRooms',[])+[rid];c.execute('UPDATE accounts SET data=? WHERE id=?',(dump(u),uid))
   save(c,rid,x);return r,201
  parts=path.split('/');require(len(parts)>=4 and parts[2]=='trips','Not found',404);rid=parts[3];actor,role,mid=self.permission(c,rid);r=room(c,rid);x=extra(c,rid);tail='/'.join(parts[4:]);ms=members(c,rid)
  if method=='GET' and tail=='snapshot':return self.snapshot(c,rid),200
  if method=='GET' and tail=='stop-candidates':
   require(role=='vehicle');require(r['status'] in ['pickup','driving'],'픽업 또는 주행 중 요청해 주세요',409)
   qs=urllib.parse.parse_qs(self.path.split('?',1)[1] if '?' in self.path else '')
   query=qs.get('query',[''])[0].strip();require(1<=len(query)<=40,'검색할 장소를 40자 이내로 말해 주세요',400)
   t=x.get('telemetry');require(t and fresh(t)=='fresh','최신 차량 위치를 확인한 뒤 다시 검색해 주세요',409)
   category={'카페':'CE7','커피':'CE7','커피숍':'CE7','식당':'FD6','음식점':'FD6','편의점':'CS2','명소':'AT4'}.get(query)
   found=kakao_places(t['lat'],t['lng'],category or 'CE7',None if category else query,size=5)
   source='kakao'
   if found is None:
    source='sample-fallback';labels={'CE7':'카페','FD6':'음식점','CS2':'편의점','AT4':'명소'}
    found=[dict(p,address='시연용 샘플 장소') for p in PLACES if (category and p.get('category')==labels[category]) or (not category and query in p['name'])]
   if source=='kakao':
    baseline,_,_=road_route(r,x,ms);found=ROADS.detours(baseline,found)
   return {'places':found[:5],'source':source,'basisSource':t.get('source','unknown'),'searchedAt':now(),'revision':x['revision'],'phase':r['status'],'detourSeconds':None},200
  require(r['status']!='closed' or tail in ['library','history'],'종료된 여행이에요',409);host=role=='host';vehicle=role=='vehicle';owner=host or vehicle
  if host and r['status']=='driving' and method!='GET':require(tail in ['consent','leave'],'주행 중에는 차량 화면을 이용해 주세요',409)
  key=self.headers.get('Idempotency-Key')
  if method!='GET' and tail in ['requests','parking','playlist','messages']:
   require(key and len(key)<=128,'Idempotency-Key required',400);old=c.execute('SELECT data FROM dedup WHERE actor=? AND path=? AND key=?',(actor,path,key)).fetchone()
   if old:
    old=obj(old[0]);require(old['body']==b,'같은 키에 다른 요청을 보낼 수 없어요',409);return old['result'],200
  result=None
  if tail=='invites' and method=='POST':
   require(host);it=secrets.token_urlsafe(24);c.execute('INSERT INTO invites VALUES(?,?,?)',(it,rid,time.time()+86400));result={'inviteToken':it,'expiresIn':86400}
  elif tail=='invites' and method=='DELETE':require(host);c.execute('DELETE FROM invites WHERE room=?',(rid,));result={'revoked':True}
  elif tail=='pair' and method=='POST':
   require(host);code=str(secrets.randbelow(900000)+100000);c.execute('DELETE FROM pairs WHERE room=?',(rid,));c.execute('INSERT INTO pairs VALUES(?,?,?)',(code,rid,time.time()+300));result={'code':code,'expiresIn':300}
  elif tail=='phase' and method=='PATCH':
   require(owner);trans={'planning':'pickup','pickup':'driving','driving':'arrived','arrived':'closed'};require(trans.get(r['status'])==b.get('status'),'순서에 맞는 여정 전환이 필요해요',409)
   if b['status']=='driving':require(all(m['status']=='boarded' for m in ms),'탑승을 먼저 확인해 주세요',409)
   r['status']=b['status']
   if r['status']=='driving':r['startedAt']=now()
   if r['status']=='arrived':r['arrivedAt']=now()
   if r['status']=='closed':
    require(not any(q['status']=='approved' for q in x['requests']),'Finish approved requests before closing',409)
    require(x.get('parking') is not None,'주차 위치를 저장하고 서버 확인 후 여행을 마쳐 주세요',409)
    x['telemetry']=None;c.execute('DELETE FROM invites WHERE room=?',(rid,));c.execute('DELETE FROM pairs WHERE room=?',(rid,))
   putroom(c,r);result=r
  elif tail=='messages' and method=='POST':
   require(role=='member');require(r['status'] in ['planning','pickup'],'픽업 메시지는 픽업 중에 보내 주세요',409)
   kind=b.get('type');require(isinstance(kind,str) and kind in MESSAGE_TEXT,'Invalid message type',400)
   m=next((m for m in ms if m['memberId']==mid),None);require(m and m['status']!='boarded','탑승 전 동승자만 보낼 수 있어요',409)
   if kind=='came_out':
    m.update(status='outside',updatedAt=now());c.execute('UPDATE members SET data=? WHERE id=?',(dump(m),mid))
   result=passenger_message(x,m,kind)
  elif tail.startswith('members/') and method=='PATCH':
   target=tail.split('/')[1];require(owner or mid==target);require(r['status'] in ['planning','pickup'],'픽업 변경이 마감되었어요',409);m=next((m for m in ms if m['memberId']==target),None);require(m,'멤버가 없어요',404);status=b.get('status',m['status']);require(status in ['preparing','outside','boarded'],'Invalid status',400);require(owner or (status!='boarded' and m['status']!='boarded'))
   if 'seat' in b:
    seat=b['seat'];require(type(seat) is int and 1<=seat<=4,'좌석은 1~4 사이 정수로 선택해 주세요',400)
    if seat!=m.get('seat'):
     require(m['status']!='boarded','탑승 완료 후에는 좌석을 변경할 수 없어요',409)
     require(not any(other['memberId']!=target and other.get('seat')==seat for other in ms),'다른 크루가 선택한 좌석이에요. 다시 선택해 주세요',409)
     require(not any(q['kind']=='climate' and q.get('memberId')==target and q['status'] in ['pending','approved'] for q in x['requests']),'공조 요청 처리가 끝난 뒤 좌석을 변경해 주세요',409)
    m['seat']=seat
   if 'pickup' in b:m['pickup']=coord(b['pickup']);x['proximity']={}
   if role=='member' and status=='outside' and m['status']!='outside':passenger_message(x,m,'came_out')
   if 'dropoff' in b:m['dropoff']=coord(b['dropoff'])
   if 'seatPreferences' in b:
    prefs=b['seatPreferences'];require(isinstance(prefs,dict),'시트 선호 형식을 확인해 주세요',400)
    require(all(type(prefs.get(k)) is int and lo<=prefs[k]<=hi for k,lo,hi in [('temperature',16,30),('heat',0,3),('ventilation',0,3)]),'온도 16~30도, 열선·통풍 0~3단계를 선택해 주세요',400)
    require(not(prefs['heat'] and prefs['ventilation']),'열선과 통풍은 동시에 선택할 수 없어요',400)
    require(m['status']!='boarded','탑승 후에는 차량 선호 요청을 이용해 주세요',409);m['seatPreferences']=prefs
   if status=='outside' and m['status']!='outside':
    x['notifications'].append({'id':ident('N-'),'text':m['displayName']+'님이 나왔어요. 픽업 장소에서 기다리고 있어요.','createdAt':now(),'senderId':uid,'memberId':target,'kind':'pickup-outside'});x['notifications']=x['notifications'][-200:]
   m.update(status=status,updatedAt=now());c.execute('UPDATE members SET data=? WHERE id=?',(dump(m),target));result=m
  elif tail=='course' and method=='PUT':
   require(role!='vehicle');require(r['status']=='planning','확정 경로 변경은 경유지 요청을 이용해 주세요',409);require(b.get('revision')==x['revision'],'다른 사람이 코스를 수정했어요. 새로 확인해 주세요',409);course=b.get('course',[]);require(isinstance(course,list) and len(course)<=20,'코스는 20곳까지예요',400)
   for p in course:coord(p);require(0<=p.get('stayMinutes',0)<=1440,'체류 시간을 확인해 주세요',400)
   x.update(course=course,revision=x['revision']+1,changedBy=actor)
   if 'destination' in b:r['destination']=coord(b['destination']);putroom(c,r)
   if 'origin' in b:x['origin']=coord(b['origin'])
   if 'scheduledAt' in b:stamp(b['scheduledAt']);r['scheduledAt']=b['scheduledAt'];putroom(c,r)
   if 'bufferMinutes' in b:require(0<=b['bufferMinutes']<=120,'여유 시간 범위 0~120분',400);x['bufferMinutes']=b['bufferMinutes']
   result=x
  elif tail=='pickup-order' and method=='PUT':
   require(host and r['status'] in ['planning','pickup']);order=b['order'];require(len(order)==len(set(order)) and set(order)=={m['memberId'] for m in ms},'모든 멤버를 한 번씩 지정해 주세요',400);x.update(pickupOrder=order,fixedOrder=b.get('fixed',True));result={'order':order}
  elif tail=='optimize' and method=='POST':
   require(host);picked=[m for m in ms if m.get('pickup')];best=None;cost=float('inf')
   for perm in itertools.permutations(picked):
    points=[x['origin']]+[m['pickup'] for m in perm]+[r['destination']];v=sum(dist(a,z)*1.35/8 for a,z in zip(points,points[1:]))
    if v<cost:best=[m['memberId'] for m in perm];cost=v
   result={'order':x['pickupOrder'] if x['fixedOrder'] else (best or []),'source':'demo-distance','seconds':round(cost) if math.isfinite(cost) else 0,'fixed':x['fixedOrder']}
  elif tail=='telemetry' and method=='POST':
   require(vehicle);require(x['consent'],'위치 공유가 철회되었어요');require(r['status'] in ['pickup','driving','arrived'],'픽업 시작 후 전송해 주세요',409);coord(b);require(b.get('tripId')==rid and b.get('vehicleId')==actor);require(b.get('source') in ['real','simulator'],'Invalid source',400);require(isinstance(b.get('sequence'),int) and b['sequence']>=0,'Invalid sequence',400);ts=stamp(b['observedAt']);require(abs(ts-time.time())<60,'기기 시각 또는 오래된 관측을 확인해 주세요',400)
   for field,limit in [('headingDeg',360),('speedMps',100),('accuracyM',10000)]:
    v=b.get(field);require(v is None or (isinstance(v,(int,float)) and math.isfinite(v) and 0<=v<=limit),'Invalid '+field,400)
   old=x.get('telemetry')
   if old and (b['sequence']<=old['sequence'] or ts<=stamp(old['observedAt'])):return {'ignored':True,'reason':'out-of-order'},200
   t={k:b.get(k) for k in ['tripId','vehicleId','lat','lng','headingDeg','speedMps','accuracyM','observedAt','sequence','source']};t['receivedAt']=now();t['jump']=bool(old and dist(old,t)>max(150,(ts-stamp(old['observedAt']))*60));x['telemetry']=t;prox=x.setdefault('proximity',{})
   for m in ms:
    if not m.get('pickup') or m['status']=='boarded':continue
    p=prox.setdefault(m['memberId'],{'count':0,'state':'waiting'});d=dist(t,m['pickup']);good=t['accuracyM'] is not None and t['accuracyM']<=30 and not t['jump'];p['count']=p['count']+1 if good and d<300 else 0;stage='arrived' if p['count']>=3 and d<50 and t['speedMps'] is not None and t['speedMps']<1 else 'approaching' if p['count']>=2 else 'waiting';p['state']=stage;nid=m['memberId']+':'+stage
    if stage!='waiting' and not any(n['id']==nid for n in x['notifications']):x['notifications'].append({'id':nid,'memberId':m['memberId'],'text':'픽업 장소에 도착했어요' if stage=='arrived' else '곧 도착해요','createdAt':now()})
   if r['status']=='driving' and dist(t,r['destination'])<=1000 and not any(n['id']=='parking' for n in x['notifications']):x['notifications'].append({'id':'parking','text':'목적지 근처예요. 주차장을 확인해 보세요','createdAt':now()})
   result={'accepted':True,'sequence':t['sequence']}
  elif tail=='vehicle-state' and method=='PUT':
   require(vehicle);caps=b.get('capabilities',{});require(all(v in [True,False,None] for v in caps.values()),'Capability must be boolean or null',400)
   for s in b.get('seats',{}).values():require(s.get('occupied') in [True,False,None] and s.get('belt') in [True,False,None],'Seat values must be boolean or null',400)
   x.update(capabilities=caps,seats=b.get('seats',{}),seatUpdatedAt=now(),vehicleStateSource=b.get('source','unknown'));result={'saved':True}
  elif tail=='proximity' and method=='PUT':
   require(owner);require(b.get('state') in ['waiting','approaching','arrived'],'Invalid state',400);x.setdefault('proximity',{})[b['memberId']]={'state':b['state'],'count':0};result={'saved':True}
  elif tail=='requests' and method=='POST':
   kind=b.get('kind');voice=vehicle and kind=='stop' and b.get('source')=='voice';require(role=='member' or (host and r['status']!='driving') or voice);require(r['status'] in ['pickup','driving'],'픽업 또는 주행 중 요청해 주세요',409);require(kind in ['stop','climate'],'Invalid kind',400)
   if voice:
    require(type(b.get('expectedRevision')) is int and b['expectedRevision']==x['revision'],'경로가 바뀌었어요. 장소를 다시 검색해 주세요',409)
    place=b.get('place');coord(place);require(isinstance(place.get('name'),str) and 0<len(place['name'].strip())<=100,'장소 이름을 확인해 주세요',400)
    require(len(x['course'])<20,'경유지는 20곳까지예요',409)
    require(type(place.get('stayMinutes',0)) is int and 0<=place.get('stayMinutes',0)<=1440,'체류 시간을 확인해 주세요',400)
    require(b.get('searchSource') in ['kakao','sample-fallback'],'장소 출처를 확인해 주세요',400)
   if kind=='stop':coord(b['place'])
   if kind=='climate':
    require(role=='member');m=next(m for m in ms if m['memberId']==mid);require(str(b.get('seat'))==str(m['seat']));require(x['capabilities'].get(b.get('feature')) is True,'이 기능은 차량 지원 확인이 필요해요',409);require(b.get('feature') in ['temperature','heat','ventilation'],'Invalid feature',400);value=b.get('value');require(isinstance(value,(float,int)) and ((16<=value<=30) if b['feature']=='temperature' else (0<=value<=3)),'Invalid preference',400)
   result={**b,'requestId':ident('Q-'),'idempotencyKey':key,'requestedBy':actor,'memberId':mid,'status':'pending','createdAt':now()};x['requests'].append(result)
  elif tail.startswith('requests/') and method=='PATCH':
   require(vehicle);q=next((q for q in x['requests'] if q['requestId']==tail.split('/')[1]),None);require(q,'요청이 없어요',404);target=b.get('status');trans={'pending':['approved','rejected'],'approved':['applied','failed']}
   if q['status']==target:return q,200
   if q.get('source')=='voice' and target=='approved':require(r['status'] in ['pickup','driving'],'여정 단계가 바뀌었어요. 경유지 제안을 거절해 주세요',409)
   require(target in trans.get(q['status'],[]),'승인 후 적용 결과를 보내 주세요',409)
   if q['kind']=='climate' and target in ['approved','applied']:require(x['capabilities'].get(q['feature']) is True,'지원되지 않는 기능이에요',409)
   q.update(status=target,updatedAt=now(),reason=str(b.get('reason',''))[:200])
   if target=='applied' and q['kind']=='stop':x['course'].append(q['place']);x['revision']+=1
   result=q
  elif tail=='playlist' and method=='POST':
   require(role!='vehicle');require(str(b.get('title','')).strip(),'곡 제목을 입력해 주세요',400);existing=next((q for q in x['playlist'] if q['title'].strip().casefold()==b['title'].strip().casefold()),None);result=existing or {'id':ident('S-'),'title':b['title'][:120],'url':b.get('url',''),'requestedBy':actor,'requestedByName':obj(c.execute('SELECT data FROM accounts WHERE id=?',(uid,)).fetchone()[0])['name'] if uid else actor,'playback':'not-connected'}
   if not existing:x['playlist'].append(result)
  elif tail=='playlist' and method=='PUT':
   require(role!='vehicle');ids=b['order'];require(len(ids)==len(set(ids)) and set(ids)=={s['id'] for s in x['playlist']},'Invalid order',400);x['playlist']=sorted(x['playlist'],key=lambda s:ids.index(s['id']));result=x['playlist']
  elif tail=='parking' and method=='PUT':
   require(owner);require(r['status']=='arrived','도착 후 주차를 저장해 주세요',409);coord(b['position']);require(b.get('source') in ['vehicle','phone','manual'],'Invalid source',400);stamp(b['observedAt'])
   if b['source']=='phone':require(b.get('confirmed') is True,'휴대폰 위치 확인이 필요해요',400)
   if b['source']=='vehicle':require(vehicle,'차량 출처는 차량만 기록해요')
   result={**b,'updatedAt':now()};x['parking']=result;c.execute('INSERT OR REPLACE INTO records VALUES(?,?,?,?)',(rid,'parking','current',dump(result)))
  elif tail=='visits' and method=='POST':
   require(owner);require(r['status']=='arrived','도착 후 방문을 확인해 주세요',409);coord(b);result={**b,'confirmedBy':actor,'confirmedAt':now(),'evidence':'manual-confirmation'};x['visits'].append(result)
  elif tail=='library' and method=='POST':
   require(uid);kind=b.get('kind');require(kind in ['crew','recurring'],'Invalid kind',400)
   if kind=='crew':require(host)
   result={'name':b.get('name',r['title']),'destination':r['destination'],'course':x['course'],'members':[m['displayName'] for m in ms] if kind=='crew' else [],'invitationRequired':True};c.execute('INSERT INTO library VALUES(?,?,?,?)',(ident('L-'),uid,kind,dump(result)))
  elif tail=='consent' and method=='PUT':
   require(host);require(isinstance(b.get('consent'),bool),'Boolean required',400);x['consent']=b['consent']
   if not x['consent']:x['telemetry']=None
   result={'consent':x['consent']}
  elif tail=='leave' and method=='POST':
   require(role=='member');x['pickupOrder']=[v for v in x['pickupOrder'] if v!=mid];r['waypointOrder']=[v for v in r['waypointOrder'] if v!=mid];putroom(c,r);c.execute('DELETE FROM members WHERE id=?',(mid,));c.execute('DELETE FROM access WHERE room=? AND account=?',(rid,uid));result={'left':True}
  else:raise Error(404,'Not found')
  save(c,rid,x)
  if tail=='phase' and r['status']=='closed':
   result={**result,'finalSnapshot':self.snapshot(c,rid)}
   c.execute('DELETE FROM vehicles WHERE room=?',(rid,))
  if key and tail in ['requests','parking','playlist','messages']:c.execute('INSERT INTO dedup VALUES(?,?,?,?)',(actor,path,key,dump({'body':b,'result':result})))
  return result,200
 def events(self):
  started=False
  try:
   rid=self.path.split('/')[3]
   with LOCK,legacy.connect() as c:self.permission(c,rid)
   self.send_response(200);self.send_header('Content-Type','text/event-stream');self.send_header('Cache-Control','no-store');self.send_header('X-Accel-Buffering','no');self.end_headers();started=True
   for _ in range(25):
    with LOCK,legacy.connect() as c:s=self.snapshot(c,rid)
    self.wfile.write(f"id: {s['eventId']}\nevent: snapshot\ndata: {dump(s)}\n\n".encode());self.wfile.flush()
    if s['room']['status']=='closed':break
    time.sleep(2)
  except Error as e:
   if not started:self.reply(e.status,{'error':e.message})
  except (BrokenPipeError,ConnectionResetError):pass
def cleanup_loop():
 while True:
  time.sleep(60)
  with LOCK,legacy.connect() as c:
   for rid,raw in c.execute('SELECT room,data FROM extra').fetchall():
    x=obj(raw);t=x.get('telemetry')
    if t and time.time()-stamp(t['receivedAt'])>86400:x['telemetry']=None;save(c,rid,x)
   c.execute('DELETE FROM sessions WHERE expires<?',(time.time(),));c.execute('DELETE FROM invites WHERE expires<?',(time.time(),));c.execute('DELETE FROM pairs WHERE expires<?',(time.time(),))
if __name__=='__main__':
 init();threading.Thread(target=cleanup_loop,daemon=True).start();port=int(os.getenv('PORT','8787'));print(f'MOVO API http://127.0.0.1:{port}',flush=True);ThreadingHTTPServer((os.getenv('HOPON_HOST','127.0.0.1'),port),API).serve_forever()
