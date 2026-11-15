/* Same-origin, no dependencies. DOM text only: user content is never HTML. */
const $ = id => document.getElementById(id);
let token = sessionStorage.getItem('hopon-token') || '';
let trip = sessionStorage.getItem('hopon-trip') || '';
let snapshot = null;
let inFlight = false;
const pending = new Map();
async function api(method, path, body, key) {
  const response = await fetch(path, {method, headers:{'Content-Type':'application/json', ...(token ? {Authorization:`Bearer ${token}`} : {}), ...(key ? {'Idempotency-Key':key} : {})}, body:body === undefined ? undefined : JSON.stringify(body)});
  const value = await response.json();
  if (!response.ok) throw new Error(`${response.status}: ${value.error || 'Request failed'}`);
  return value;
}
function text(id, value) { $(id).textContent = value; }
function button(parent, title, click) { const b = document.createElement('button'); b.textContent=title; b.onclick=() => run(click); parent.append(b); }
function list(id, lines) { $(id).replaceChildren(); for (const line of lines) { const p=document.createElement('p');p.textContent=line;$(id).append(p); } }
async function run(action) {
  if (inFlight) return;
  inFlight=true; text('error','');
  try { await action(); } catch(e) { text('error', e.message); }
  finally { inFlight=false; }
}
async function auth(mode) {
  const r=await api('POST', `/v2/auth/${mode}`, {name:$('name').value,password:$('password').value});
  token=r.token; sessionStorage.setItem('hopon-token',token); $('password').value=''; await home();
}
async function home() {
  const me=await api('GET','/v2/me'); $('login').hidden=true;$('home').hidden=false;text('who',me.user.name);
  $('trips').replaceChildren();
  for (const room of me.rooms) button($('trips'), `${room.title} · ${room.status}`, async()=>{trip=room.roomId;sessionStorage.setItem('hopon-trip',trip);await refresh();});
  if (trip && me.rooms.some(r=>r.roomId===trip)) await refresh();
}
async function refresh() {
  if (!token || !trip) return;
  const s=await api('GET',`/v2/trips/${trip}/snapshot`);snapshot=s;
  $('journey').hidden=false;text('title',s.room.title);text('phase',`${s.role} · ${s.room.status} · ${s.room.destination.name}`);
  text('live',`Synced ${new Date().toLocaleTimeString()} · event ${s.eventId}`);
  $('host').hidden=s.role!=='host'||s.room.status==='closed';$('passenger').hidden=s.role!=='member'||s.room.status==='closed';
  $('pair-create').disabled=s.room.status==='driving';$('invite-create').disabled=!['planning','pickup'].includes(s.room.status);
  $('preparing').disabled=$('outside').disabled=$('late').disabled=!['planning','pickup'].includes(s.room.status)||s.members.some(m=>m.memberId===s.memberId&&m.status==='boarded');
  $('stop').disabled=!['pickup','driving'].includes(s.room.status);$('climate').disabled=$('stop').disabled||s.capabilities.temperature!==true;
  list('party',s.members.map(m=>`Seat ${m.seat ?? '?'} · ${m.displayName} · ${m.status} · ${m.pickup?.memo || 'No pickup yet'}`));
  list('route',[`Destination: ${s.room.destination.name}`,`Confirmed stops: ${s.course.map(p=>p.name).join(' → ') || 'None'}`, `Vehicle: ${s.telemetry ? `${s.telemetry.source} · sequence ${s.telemetry.sequence}` : 'No current location'}`]);
  list('requests',s.requests.map(q=>`${q.kind==='stop'?q.place.name:`Seat ${q.seat} ${q.feature} ${q.value}`} · ${q.status}${q.reason ? ` · ${q.reason}`:''}`));
  list('parking',s.parking?[s.parking.memo,`${s.parking.position.lat}, ${s.parking.position.lng} · ${s.parking.source}${s.parking.simulation ? ' (simulated)' : ''}`,s.room.status==='closed'?'Journey complete · history saved':'Parking confirmed by server']:['No parking saved yet']);
}
async function idempotent(action, path, body) {
  const signature=JSON.stringify(body); let entry=pending.get(action);
  if (!entry || entry.signature!==signature) {entry={signature,key:crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`};pending.set(action,entry);}
  const result=await api('POST',path,body,entry.key);pending.delete(action);return result;
}
$('register').onclick=()=>run(()=>auth('register'));$('signin').onclick=()=>run(()=>auth('login'));
$('logout').onclick=()=>{sessionStorage.removeItem('hopon-token');sessionStorage.removeItem('hopon-trip');location.reload();};
$('create').onclick=()=>run(async()=>{const r=await api('POST','/v2/trips',{title:'MOVO Weekend Trip',destination:{name:'Seoul Forest',lat:37.5445,lng:127.0374}});trip=r.roomId;sessionStorage.setItem('hopon-trip',trip);await home();});
$('join').onclick=()=>run(async()=>{const r=await api('POST',`/v2/invites/${encodeURIComponent($('invite').value.trim())}`,{consent:true});trip=r.roomId;sessionStorage.setItem('hopon-trip',trip);await home();});
$('invite-create').onclick=()=>run(async()=>{const r=await api('POST',`/v2/trips/${trip}/invites`,{});text('invite-result',r.inviteToken);});
$('pair-create').onclick=()=>run(async()=>{const r=await api('POST',`/v2/trips/${trip}/pair`,{});text('pair-result',r.code);});
for (const status of ['preparing','outside']) $(status).onclick=()=>run(async()=>{await api('PATCH',`/v2/trips/${trip}/members/${snapshot.memberId}`,{status,pickup:{lat:37.5451,lng:127.0525,memo:$('pickup').value}});await refresh();});
$('late').onclick=()=>run(async()=>{await idempotent('late',`/v2/trips/${trip}/messages`,{type:'late_5min'});await refresh();});
$('stop').onclick=()=>run(async()=>{await idempotent('stop',`/v2/trips/${trip}/requests`,{kind:'stop',place:{name:$('stop-name').value,lat:37.5474,lng:127.0437,stayMinutes:10}});await refresh();});
$('climate').onclick=()=>run(async()=>{const me=snapshot.members.find(m=>m.memberId===snapshot.memberId);await idempotent('climate',`/v2/trips/${trip}/requests`,{kind:'climate',seat:me.seat,feature:'temperature',value:Number($('temperature').value)});await refresh();});
if(token) run(home);
setInterval(()=>{if(!inFlight&&token&&trip)run(refresh);},3000);
