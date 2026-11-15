#!/usr/bin/env python3
"""Small mobile API driver for repeatable synthetic demos; no vehicle token access."""
import argparse,json,os,secrets,time,urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('action',choices=['create','outside','stop','climate','status']);p.add_argument('--base',default='http://127.0.0.1:8787');p.add_argument('--state',default='/private/tmp/movo-demo-session.json');a=p.parse_args()
state_path=Path(a.state)
# Read an existing CLI session without renaming it; new sessions use the MOVO path.
if a.action != 'create' and a.state == '/private/tmp/movo-demo-session.json' and not state_path.exists():
 legacy_state=Path('/private/tmp/hopon-demo-session.json')
 if legacy_state.is_file():state_path=legacy_state
def call(method,path,body=None,token='',key=None):
 headers={'Content-Type':'application/json','Authorization':'Bearer '+token}
 if key:headers['Idempotency-Key']=key
 request=urllib.request.Request(a.base+path,method=method,headers=headers,data=json.dumps(body).encode() if body is not None else None)
 with urllib.request.urlopen(request,timeout=8) as r:return json.load(r)
if a.action=='create':
 suffix=str(int(time.time()))[-6:];names=['Driver'+suffix,'Mina'+suffix];password=secrets.token_urlsafe(8)
 tokens=[call('POST','/v2/auth/register',{'name':name,'password':password})['token'] for name in names]
 room=call('POST','/v2/trips',{'title':'MOVO Weekend Trip','destination':{'name':'Seoul Forest','lat':37.5445,'lng':127.0374}},tokens[0]);root='/v2/trips/'+room['roomId']
 invite=call('POST',root+'/invites',{},tokens[0])['inviteToken'];mid=call('POST','/v2/invites/'+invite,{'consent':True},tokens[1])['memberId']
 call('PATCH',root+'/members/'+mid,{'pickup':{'lat':37.5451,'lng':127.0525,'memo':'Station exit 2'}},tokens[1])
 pair=call('POST',root+'/pair',{},tokens[0])['code'];state={'root':root,'host':tokens[0],'member':tokens[1],'memberId':mid,'base':a.base}
 fd=os.open(state_path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
 with os.fdopen(fd,'w') as f:json.dump(state,f)
 state_path.chmod(0o600)
 print('Vehicle pairing code:',pair,'(valid 5 minutes)');print('Mobile page:',a.base+'/demo');print('Synthetic driver / passenger:',*names);print('Demo password for both:',password);print('Local session:',state_path)
else:
 state=json.loads(state_path.read_text());a.base=state['base'];root=state['root'];token=state['member']
 if a.action=='outside':call('PATCH',root+'/members/'+state['memberId'],{'status':'outside'},token)
 if a.action=='stop':call('POST',root+'/requests',{'kind':'stop','place':{'name':'Riverside cafe','lat':37.5474,'lng':127.0437,'stayMinutes':10}},token,secrets.token_hex(16))
 if a.action=='climate':call('POST',root+'/requests',{'kind':'climate','seat':1,'feature':'temperature','value':22},token,secrets.token_hex(16))
 snapshot=call('GET',root+'/snapshot',token=token)
 print(json.dumps({k:snapshot[k] for k in ['room','members','requests','parking']},ensure_ascii=False,indent=2))
