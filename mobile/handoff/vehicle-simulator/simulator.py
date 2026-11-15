#!/usr/bin/env python3
"""Independent vehicle client: no host token or mobile dependency. Python 3.9+."""
import argparse,json,os,time,urllib.request,urllib.error
from pathlib import Path
from datetime import datetime,timezone
p=argparse.ArgumentParser();p.add_argument('--base',default='http://127.0.0.1:8787');p.add_argument('--pair',required=True);p.add_argument('--interval',type=float,default=2.5);p.add_argument('--mode',choices=['drive','delay','bad-gps','jump','out-of-order','arrival','seats','unknown','decision','parking'],default='drive');p.add_argument('--request-id');p.add_argument('--status',choices=['approved','rejected','applied','failed']);p.add_argument('--steps',type=int,default=160);a=p.parse_args()
def request(method,path,body=None,key=None):
 headers={'Content-Type':'application/json'}
 if session.get('token'):headers['Authorization']='Bearer '+session['token']
 if key:headers['Idempotency-Key']=key
 req=urllib.request.Request(a.base+path,data=json.dumps(body).encode() if body is not None else None,method=method,headers=headers)
 try:
  with urllib.request.urlopen(req,timeout=10) as r:return json.load(r)
 except urllib.error.HTTPError as e:raise RuntimeError(f'{e.code}: {e.read().decode()}') from e
session={};session=request('POST','/v2/pair/exchange',{'code':a.pair});root='/v2/trips/'+session['tripId'];snapshot=request('GET',root+'/snapshot');print('Paired simulator to',session['tripId'],'(credential not printed)')
sequence=int(time.time()*1000)
def telemetry(point,speed=8,accuracy=8,offset=0):
 global sequence
 sequence+=1
 return request('POST',root+'/telemetry',{'tripId':session['tripId'],'vehicleId':session['vehicleId'],'lat':point[0],'lng':point[1],'headingDeg':275,'speedMps':speed,'accuracyM':accuracy,'observedAt':datetime.now(timezone.utc).isoformat(),'sequence':sequence+offset,'source':'simulator'})
if a.mode in ['seats','unknown']:
 known=a.mode=='seats';print(request('PUT',root+'/vehicle-state',{'capabilities':{k:True if known else None for k in ['belt','occupancy','temperature','heat','ventilation']},'seats':{str(m['seat']):{'occupied':True if known else None,'belt':False if known else None} for m in snapshot['members']}}))
elif a.mode=='decision':
 if not a.request_id or not a.status:p.error('--request-id and --status are required')
 print(request('PATCH',root+'/requests/'+a.request_id,{'status':a.status,'reason':'Simulator result'}))
elif a.mode=='parking':
 t=snapshot['telemetry']
 if not t:raise RuntimeError('Upload final valid position before power-off')
 body={'position':{'lat':t['lat'],'lng':t['lng']},'source':'vehicle','observedAt':t['observedAt'],'accuracyM':t['accuracyM'],'memo':'전원 종료 전 동기화'}
 print(request('PUT',root+'/parking',body,'power-off-'+session['tripId']))
else:
 if snapshot['room']['status']=='planning':request('PATCH',root+'/phase',{'status':'pickup'})
 route=json.loads(Path(__file__).with_name('road-route.json').read_text())['points']
 for step in range(a.steps):
  n=min(step*.12,len(route)-1);i=int(n);f=n-i;x=route[i];y=route[min(i+1,len(route)-1)];point=[x[0]+(y[0]-x[0])*f,x[1]+(y[1]-x[1])*f]
  if a.mode=='arrival':point=[snapshot['room']['destination']['lat'],snapshot['room']['destination']['lng']]
  if a.mode=='jump' and step==5:point=[37.58,127.08]
  print(telemetry(point,speed=0 if a.mode=='arrival' or n==len(route)-1 else 8,accuracy=300 if a.mode=='bad-gps' else 8,offset=-1000 if a.mode=='out-of-order' and step==5 else 0))
  time.sleep(35 if a.mode=='delay' and step==5 else max(2,a.interval))
