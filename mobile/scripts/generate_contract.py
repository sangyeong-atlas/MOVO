"""Generate a JSON-compatible YAML 1.2 OpenAPI contract for the implemented API."""
import json
from pathlib import Path
S={'type':'string'};N={'type':'number'};I={'type':'integer'};B={'type':'boolean'};ANY={'type':'object','additionalProperties':True}
def ref(n):return {'$ref':'#/components/schemas/'+n}
def arr(x):return {'type':'array','items':x}
def enum(*xs):return {'type':'string','enum':list(xs)}
def obj(props,required=()):return {'type':'object','properties':props,'required':list(required)}
def nullable(x):return {**x,'nullable':True}
def example(schema,value):return {**schema,'example':value}
point=obj({'lat':{'type':'number','minimum':-90,'maximum':90},'lng':{'type':'number','minimum':-180,'maximum':180},'name':S,'memo':S},['lat','lng'])
schemas={
 'Error':obj({'error':S},['error']), 'Point':point,
 'Place':obj({**point['properties'],'id':S,'name':S,'category':S,'stayMinutes':{'type':'number','minimum':0,'maximum':1440}},['lat','lng','name']),
 'Phase':enum('planning','pickup','driving','arrived','closed'),
 'Avatar':obj({'hair':{'type':'integer','minimum':0,'maximum':2},'face':{'type':'integer','minimum':0,'maximum':2},'color':S},['hair','face','color']),
 'User':obj({'id':S,'name':S,'avatar':ref('Avatar'),'preferences':arr(S),'vehicle':obj({'name':S,'plate':S},['name','plate'])},['id','name','avatar','vehicle']),
 'AuthInput':obj({'name':{'type':'string','maxLength':24},'password':{'type':'string','minLength':6}},['name','password']),
 'AuthSession':obj({'token':S,'user':ref('User'),'authMode':{'type':'string','enum':['test-password']}},['token','user','authMode']),
 'Trip':obj({'roomId':S,'inviteCode':S,'title':S,'theme':{'type':'string','enum':['friends']},'status':ref('Phase'),'destination':ref('Place'),'waypointOrder':arr(S),'scheduledAt':nullable({'type':'string','format':'date-time'}),'createdAt':S,'updatedAt':S,'startedAt':S,'arrivedAt':S,'hostName':S},['roomId','status','destination']),
 'NewTrip':obj({'title':S,'destination':ref('Place'),'scheduledAt':{'type':'string','format':'date-time'},'course':arr(ref('Place'))}),
 'Member':obj({'memberId':S,'roomId':S,'displayName':S,'status':enum('preparing','outside','boarded'),'pickup':nullable(ref('Point')),'seat':nullable(I),'avatar':ref('Avatar'),'updatedAt':S},['memberId','displayName','status','pickup']),
 'MemberPatch':obj({'status':enum('preparing','outside','boarded'),'pickup':ref('Point')}),
 'CourseUpdate':obj({'revision':I,'course':arr(ref('Place')),'destination':ref('Place'),'origin':ref('Place'),'scheduledAt':{'type':'string','format':'date-time'},'bufferMinutes':{'type':'number','minimum':0,'maximum':120}},['revision','course']),
 'TelemetryInput':obj({'tripId':S,'vehicleId':S,**point['properties'],'headingDeg':nullable({'type':'number','minimum':0,'maximum':360}),'speedMps':nullable({'type':'number','minimum':0,'maximum':100}),'accuracyM':nullable({'type':'number','minimum':0,'maximum':10000}),'observedAt':{'type':'string','format':'date-time'},'sequence':{'type':'integer','minimum':0},'source':enum('real','simulator')},['tripId','vehicleId','lat','lng','observedAt','sequence','source']),
 'Telemetry':{'allOf':[ref('TelemetryInput'),obj({'receivedAt':S,'jump':B},['receivedAt','jump'])]},
 'Capabilities':obj({k:nullable(B) for k in ['occupancy','belt','temperature','heat','ventilation']}),
 'SeatState':obj({'occupied':nullable(B),'belt':nullable(B)}),
 'VehicleState':obj({'capabilities':ref('Capabilities'),'seats':{'type':'object','additionalProperties':ref('SeatState')}}),
 'StopInput':obj({'kind':enum('stop'),'place':ref('Place')},['kind','place']),
 'ClimateInput':obj({'kind':enum('climate'),'seat':I,'feature':enum('temperature','heat','ventilation'),'value':N},['kind','seat','feature','value']),
 'RequestInput':{'oneOf':[ref('StopInput'),ref('ClimateInput')]},
 'Request':obj({'requestId':S,'idempotencyKey':S,'kind':enum('stop','climate'),'place':ref('Place'),'seat':I,'feature':S,'value':N,'requestedBy':S,'memberId':nullable(S),'status':enum('pending','approved','rejected','applied','failed'),'createdAt':S,'updatedAt':S,'reason':S},['requestId','status','kind','requestedBy']),
 'RequestResult':obj({'status':enum('approved','rejected','applied','failed'),'reason':S},['status']),
 'SongInput':obj({'title':S,'url':S},['title']),
 'Song':obj({'id':S,'title':S,'url':S,'requestedBy':S,'playback':enum('not-connected')},['id','title','requestedBy','playback']),
 'ParkingInput':obj({'position':ref('Point'),'source':enum('vehicle','phone','manual'),'observedAt':{'type':'string','format':'date-time'},'accuracyM':nullable(N),'memo':S,'confirmed':B},['position','source','observedAt']),
 'Parking':{'allOf':[ref('ParkingInput'),obj({'updatedAt':S},['updatedAt'])]},
 'Route':obj({'source':enum('demo-distance'),'label':S,'polyline':arr(arr(N)),'remainingMeters':N,'distanceSource':enum('straight-line'),'destinationSeconds':nullable(N),'recommendedDepartureAt':nullable(S),'nextMemberId':nullable(S),'pickupWaitSeconds':N,'perMemberETA':{'type':'object','additionalProperties':obj({'seconds':nullable(N),'ahead':I,'state':enum('waiting','approaching','arrived'),'source':S})}}),
 'Snapshot':obj({'room':ref('Trip'),'members':arr(ref('Member')),'role':enum('host','member','vehicle'),'memberId':nullable(S),'revision':I,'eventId':I,'course':arr(ref('Place')),'pickupOrder':arr(S),'fixedOrder':B,'origin':ref('Place'),'bufferMinutes':N,'changedBy':S,'updatedAt':S,'telemetry':nullable(ref('Telemetry')),'freshness':enum('fresh','stale','lost','unknown'),'route':ref('Route'),'requests':arr(ref('Request')),'playlist':arr(ref('Song')),'capabilities':ref('Capabilities'),'seats':{'type':'object','additionalProperties':ref('SeatState')},'seatUpdatedAt':S,'parking':nullable(ref('Parking')),'visits':arr(ANY),'notifications':arr(obj({'id':S,'memberId':S,'text':S,'createdAt':S})),'consent':B,'serverTime':S,'thresholds':obj({'stale':I,'lost':I})},['room','members','role','revision','eventId','course','route','telemetry','freshness']),
 'LibraryItem':obj({'id':S,'kind':enum('crew','recurring'),'name':S,'destination':ref('Place'),'course':arr(ref('Place')),'members':arr(S),'invitationRequired':B}),
 'Me':obj({'user':ref('User'),'rooms':arr(ref('Trip')),'library':arr(ref('LibraryItem'))},['user','rooms','library']),
 'Pair':obj({'token':S,'tripId':S,'vehicleId':S},['token','tripId','vehicleId'])
}
paths={}
def add(path,method,summary,roles,body=None,response=None,status=200,idempotent=False,public=False,desc=''):
 params=[]
 for name in ['tripId','memberId','requestId','token','roomId','kind']:
  if '{'+name+'}' in path:params.append({'in':'path','name':name,'required':True,'schema':S})
 if idempotent:params.append({'in':'header','name':'Idempotency-Key','required':True,'schema':{'type':'string','maxLength':128},'description':'Retry unchanged body with same key. Changed payload with same key returns 409.'})
 op={'summary':summary,'tags':['v0.2 implemented' if path.startswith('/v2') else 'v0.1 compatible'],'description':'Allowed roles: '+roles+'. '+desc,'security':[] if public else [{'bearerAuth':[]}],'parameters':params,'responses':{str(status):{'description':'Success','content':{'application/json':{'schema':response or ANY}}}}}
 for code,message in [(400,'Invalid fields'),(403,'Invalid session / permission'),(404,'Not found / expired invite'),(409,'Phase, revision, capability or idempotency conflict'),(429,'Rate limited; wait 60 seconds'),(500,'Internal error')]:op['responses'][str(code)]={'description':message,'content':{'application/json':{'schema':ref('Error')}}}
 if body:op['requestBody']={'required':True,'content':{'application/json':{'schema':body}}}
 paths.setdefault(path,{})[method.lower()]=op
add('/health','get','Health','public',response=obj({'ok':B}),public=True)
for action in ['register','login']:add('/v2/auth/'+action,'post','Test password '+action,'public',ref('AuthInput'),ref('AuthSession'),public=True,desc='7-day opaque account session. Explicit test authentication, not OIDC.')
add('/v2/me','get','Account, trips and library','account',response=ref('Me'))
add('/v2/me','patch','Update avatar and vehicle profile','account',obj({'avatar':ref('Avatar'),'preferences':arr(S),'vehicle':obj({'name':S,'plate':S})}),ref('Me'))
add('/v2/recommendations','get','Preference and visit based sample recommendations','account',response=obj({'source':enum('sample'),'reason':S,'places':arr(ref('Place')),'queriedAt':S}))
add('/v2/places','get','Explicit sample places','account',response=obj({'source':enum('sample'),'queriedAt':S,'places':arr(ref('Place'))}))
add('/v2/trips','post','Create trip','account',ref('NewTrip'),ref('Trip'),201)
add('/v2/pair/exchange','post','Consume one-time pair code','public',obj({'code':S},['code']),ref('Pair'),public=True)
add('/v2/invites/{token}','get','Resolve safe invitation landing','public',response=obj({'title':S,'destination':ref('Place'),'scheduledAt':nullable(S),'expiresAt':S}),public=True)
add('/v2/invites/{token}/accept','post','Accept invitation once per account/trip','account',obj({'consent':B},['consent']),obj({'roomId':S,'memberId':nullable(S)}),201,desc='Repeated acceptance returns 200 and the existing membership. Consent must be true.')
paths['/v2/invites/{token}/accept']['post']['responses']['200']=paths['/v2/invites/{token}/accept']['post']['responses']['201']
root='/v2/trips/{tripId}'
add(root+'/snapshot','get','Authoritative full snapshot','host/member/vehicle',response=ref('Snapshot'))
add(root+'/events','get','Bearer-authenticated SSE snapshots','host/member/vehicle',response=ref('Snapshot'),desc='Fetch streaming, never URL token. Last-Event-ID accepted; full snapshot sent on every connection. Reconnect after stream close, polling fallback at 3 seconds.')
paths[root+'/events']['get']['parameters'].append({'in':'header','name':'Last-Event-ID','schema':S})
paths[root+'/events']['get']['responses']['200']={'description':'SSE event: snapshot; id: eventId; data: full Snapshot JSON','content':{'text/event-stream':{'schema':S,'example':'id: 3\nevent: snapshot\ndata: {"eventId":3,"room":{...}}\n\n'}}}
add(root+'/invites','post','Issue 24-hour invite','host',response=obj({'inviteToken':S,'expiresIn':I}))
add(root+'/invites','delete','Revoke all trip invites','host',response=obj({'revoked':B}))
add(root+'/pair','post','Issue 5-minute pairing code','host',response=obj({'code':S,'expiresIn':I}))
add(root+'/phase','patch','Advance trip phase','host before driving / vehicle',obj({'status':ref('Phase')},['status']),ref('Trip'))
add(root+'/members/{memberId}','patch','Member state or pickup','own member / host / vehicle',ref('MemberPatch'),ref('Member'),desc='planning/pickup only; boarded is host/vehicle only.')
add(root+'/course','put','Conditional shared course update','host/member in planning',ref('CourseUpdate'),ANY,desc='revision mismatch returns 409. Response is complete stored trip extension, not full Snapshot.')
add(root+'/pickup-order','put','Confirm fixed pickup order','host in planning/pickup',obj({'order':arr(S),'fixed':B},['order']),obj({'order':arr(S)}))
add(root+'/optimize','post','Compare up to four pickup permutations','host',response=obj({'order':arr(S),'source':enum('demo-distance'),'seconds':N,'fixed':B}),desc='Straight-line demo cost; respects fixed order, does not mutate confirmed order.')
add(root+'/telemetry','post','Upload authenticated vehicle observation','vehicle',ref('TelemetryInput'),obj({'accepted':B,'sequence':I,'ignored':B,'reason':S}),desc='pickup/driving/arrived; consent required. Duplicate/out-of-order ignored. source always explicit. receivedAt server controlled.')
add(root+'/vehicle-state','put','Publish capabilities and seat states','vehicle',ref('VehicleState'),obj({'saved':B}))
add(root+'/proximity','put','Driver correction of proximity','host/vehicle',obj({'memberId':S,'state':enum('waiting','approaching','arrived')},['memberId','state']),obj({'saved':B}))
add(root+'/requests','post','Request stop or supported seat preference','member / host before driving',ref('RequestInput'),ref('Request'),idempotent=True)
add(root+'/requests/{requestId}','patch','Driver decision / navigation or climate ACK','vehicle',ref('RequestResult'),ref('Request'),desc='pending→approved/rejected; approved→applied/failed. Identical current status retries return current result.')
add(root+'/playlist','post','Deduplicated song request','host before driving / member',ref('SongInput'),ref('Song'),idempotent=True)
add(root+'/playlist','put','Reorder stored queue','host before driving / member',obj({'order':arr(S)},['order']),arr(ref('Song')),idempotent=True)
add(root+'/parking','put','Persist parking after arrival','host/vehicle',ref('ParkingInput'),ref('Parking'),idempotent=True,desc='source=vehicle requires vehicle; source=phone requires confirmed=true. State arrived only.')
add(root+'/visits','post','Manually confirm actual visit','host/vehicle after arrival',ref('Place'),ANY)
add(root+'/library','post','Save recurring course or crew','account; crew host only',obj({'kind':enum('crew','recurring'),'name':S},['kind']),ref('LibraryItem'),desc='Save result omits library id/kind; GET /me includes them. New trips copy content; crew requires new invitation acceptance.')
add(root+'/consent','put','Vehicle location consent / withdrawal','host',obj({'consent':B},['consent']),obj({'consent':B}))
add(root+'/leave','post','Delete member and revoke subscription access','member',response=obj({'left':B}))
add('/rooms','post','Legacy room creation','public',ref('NewTrip'),{'allOf':[ref('Trip'),obj({'hostToken':S})]},201,public=True)
add('/join','post','Legacy inviteCode join','public',obj({'inviteCode':S,'displayName':S},['inviteCode','displayName']),{'allOf':[ref('Member'),obj({'memberToken':S})]},201,public=True,desc='Legacy behavior creates new member each call; account dedup applies only to v0.2 acceptance.')
add('/rooms/{roomId}','get','Legacy room','room participant',response=ref('Trip'))
add('/rooms/{roomId}','patch','Legacy phase transition','host',obj({'status':ref('Phase')},['status']),ref('Trip'),desc='0 passengers allowed in driving; existing passengers must be boarded.')
add('/rooms/{roomId}/snapshot','get','Legacy snapshot','room participant',response=obj({'room':ref('Trip'),'members':arr(ref('Member')),'records':{'type':'object','additionalProperties':arr(ANY)}}))
add('/rooms/{roomId}/members/{memberId}','patch','Legacy member update','own member/host',ref('MemberPatch'),ref('Member'))
for kind in ['messages','vehicle-state','parking','trip']:
 add('/rooms/{roomId}/'+kind,'get','Legacy '+kind,'room participant',response=arr(ANY))
 for method in ['post','put']:add('/rooms/{roomId}/'+kind,method,'Legacy '+kind,'participant' if kind=='messages' else 'host',obj({'type':enum('late_5min','arrived','came_out')},['type']) if kind=='messages' else obj({'position':ref('Point'),'memo':S,'source':S},['position']) if kind=='parking' else ANY,ANY)
contract={'openapi':'3.0.3','info':{'title':'Hopon development API','version':'0.2.0','description':'Implemented v0.2 PoC endpoints and retained v0.1 contracts. No speculative provider/SDK endpoints are presented as implemented. UTC ISO dates, WGS84, meters, seconds. Opaque tokens must not appear in URLs. 0.1 uses room tokens; 0.2 uses account and paired vehicle tokens.'},'servers':[{'url':'http://127.0.0.1:8787'},{'url':'/api'}],'tags':[{'name':'v0.1 compatible'},{'name':'v0.2 implemented'}],'paths':paths,'components':{'securitySchemes':{'bearerAuth':{'type':'http','scheme':'bearer','description':'Opaque account, vehicle or legacy room token; not JWT'}},'schemas':schemas}}
p=Path(__file__).resolve().parents[1]/'handoff/openapi.yaml';p.write_text(json.dumps(contract,ensure_ascii=False,indent=2)+'\n');print(len(paths),'paths documented')
