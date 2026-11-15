// Own ephemeral backend/SQLite and Vite; never connects to the user's backend or browser profile.
import {chromium, expect} from '@playwright/test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import net from 'node:net';
import {spawn} from 'node:child_process';
import {fileURLToPath, pathToFileURL} from 'node:url';
const mobile=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const root=path.dirname(mobile), output=fs.mkdtempSync(path.join(os.tmpdir(),'movo-p1-regression-'));
fs.chmodSync(output,0o700);
const children=[],checks=[],errors=[];let browser;
function launch(command,args,options){const child=spawn(command,args,{...options,stdio:['ignore','pipe','pipe']});children.push(child);return child;}
async function firstLine(child){return new Promise((resolve,reject)=>{let data='';const timer=setTimeout(()=>reject(Error('Backend startup timeout')),15000);child.stdout.on('data',chunk=>{data+=chunk;if(data.includes('\n')){clearTimeout(timer);resolve(data.split('\n')[0]);}});child.once('exit',code=>{clearTimeout(timer);reject(Error('Backend exited '+code));});child.once('error',reject);});}
async function freePort(){const server=net.createServer();await new Promise(r=>server.listen(0,'127.0.0.1',r));const port=server.address().port;await new Promise(r=>server.close(r));return port;}
async function stop(child){if(child.exitCode!==null)return;await new Promise(resolve=>{const timer=setTimeout(()=>{child.kill('SIGKILL');resolve();},5000);child.once('exit',()=>{clearTimeout(timer);resolve();});child.kill('SIGTERM');});}
try{
 const backend=launch('python3',['-B','-u','-c',"import server_v2 as v; v.init(); http=v.ThreadingHTTPServer(('127.0.0.1',0),v.API); print(http.server_port,flush=True); http.serve_forever()"],{cwd:path.join(root,'backend'),env:{...process.env,HOPON_DB:path.join(output,'test.db'),KAKAO_REST_API_KEY:''}});
 const backendPort=Number(await firstLine(backend));assert.ok(backendPort>0);const port=await freePort(),origin='http://127.0.0.1:'+port;
 const config=path.join(output,'vite.config.mjs');fs.writeFileSync(config,`import original from ${JSON.stringify(pathToFileURL(path.join(mobile,'vite.config.ts')).href)};export default {...original,server:{host:'127.0.0.1',port:${port},strictPort:true,proxy:{'/api':{target:'http://127.0.0.1:${backendPort}',rewrite:p=>p.replace(/^\\/api/,'')}}}};`);
 const vite=launch(process.execPath,[path.join(mobile,'node_modules/vite/bin/vite.js'),'--config',config],{cwd:mobile});let viteLog='';vite.stdout.on('data',b=>viteLog+=b);vite.stderr.on('data',b=>viteLog+=b);
 await expect.poll(async()=>{try{return (await fetch(origin)).status}catch{return 0}},{timeout:20000}).toBe(200);
 async function api(method,p,token='',body,key){const r=await fetch(origin+'/api'+p,{method,headers:{'Content-Type':'application/json',Authorization:'Bearer '+token,...(key?{'Idempotency-Key':key}:{})},body:body===undefined?undefined:JSON.stringify(body)});return {status:r.status,data:await r.json()};}
 const host=(await api('POST','/v2/auth/register','',{name:'P1-owner',password:'isolated-test-only'})).data.token;
 async function trip(title){return (await api('POST','/v2/trips',host,{title})).data.roomId;}
 const route=id=>'/v2/trips/'+id;
 const snapshot=async id=>(await api('GET',route(id)+'/snapshot',host)).data;
 async function phase(id,status,token=host){const r=await api('PATCH',route(id)+'/phase',token,{status});assert.equal(r.status,200);return r.data;}
 async function pair(id){const code=(await api('POST',route(id)+'/pair',host,{})).data.code;return (await api('POST','/v2/pair/exchange','',{code})).data.token;}
 browser=await chromium.launch({channel:'chrome',headless:true});
 async function page(id){const context=await browser.newContext({viewport:{width:390,height:844},serviceWorkers:'block'});await context.route('**/*',r=>new URL(r.request().url()).origin===origin?r.continue():r.abort());await context.addInitScript(({host,id})=>{localStorage.setItem('hopon-token',host);localStorage.setItem('hopon-trip',id)},{host,id});const p=await context.newPage();p.setDefaultTimeout(12000);p.on('pageerror',e=>errors.push(e.message));return p;}
 async function open(p,title,history=false){await p.goto(origin+'/?functional=1');await p.getByRole('button',{name:history?'기록':'파티방',exact:true}).click();await p.getByRole('button',{name:new RegExp(title)}).click();await expect(p.getByRole('heading',{name:title,level:1,exact:true})).toBeVisible();}
 const id=await trip('P1 ordering'),old=await snapshot(id);await phase(id,'pickup');const latest=await snapshot(id);const p=await page(id);
 const settle=async(p,response)=>{await (await response).finished();await p.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));};
 const frame=x=>`id: ${x.eventId}\nevent: snapshot\ndata: ${JSON.stringify(x)}\n\n`;
 await p.route('**/events',r=>r.fulfill({status:200,contentType:'text/event-stream',body:frame(latest)+frame(old)}));await open(p,'P1 ordering');await expect(p.getByText('픽업 중 · 오너',{exact:true})).toBeVisible();await p.screenshot({path:path.join(output,'ordered-sse.png'),fullPage:true});checks.push('SSE newest then older snapshot retains pickup');
 const unit=await p.evaluate(async({old,latest})=>{const {selectSnapshot:f}=await import('/handoff/snapshot-order.ts');const a={...latest,serverTime:'2026-11-14T12:00:00.123900Z'};const older={...a,serverTime:'2026-11-14T12:00:00.123100Z'};const fresh={...a,serverTime:'2026-11-14T12:00:00.124000Z'};const closed={...a,room:{...a.room,status:'closed'}};return [f(latest,old,latest.room.roomId)===latest,f(a,older,a.room.roomId)===a,f(a,fresh,a.room.roomId)===fresh,f(a,{...a,room:{...a.room,roomId:'other'}},a.room.roomId)===a,f(closed,{...a,eventId:a.eventId+1},a.room.roomId)===closed,f(a,{...a,serverTime:'invalid'},a.room.roomId)===a];},{old,latest});assert.deepEqual(unit,[true,true,true,true,true,true]);checks.push('Ordering: lower event, microsecond timestamp, freshness, wrong trip, closed, invalid time');await p.context().close();
 // A mutation's delayed GET must not overwrite a newer SSE snapshot.
 const mid=await trip('P1 delayed mutation'),mp=await page(mid);let served=await snapshot(mid),release,captured;const capturedPromise=new Promise(r=>captured=r);
 await mp.route('**/events',r=>r.fulfill({status:200,contentType:'text/event-stream',body:frame(served)}));
 await mp.route('**/snapshot',async r=>{const response=await r.fetch();const body=await response.json();captured(body);await new Promise(done=>release=done);await r.fulfill({response,json:body});});
 await open(mp,'P1 delayed mutation');await mp.getByRole('button',{name:'픽업 시작하기',exact:true}).click();const pending=await capturedPromise;assert.equal(pending.room.status,'pickup');await phase(mid,'driving');served=await snapshot(mid);await expect(mp.getByText('여행 중 · 오너',{exact:true})).toBeVisible();const mutationResponse=mp.waitForResponse(r=>r.url().endsWith('/snapshot'));release();await settle(mp,mutationResponse);await expect(mp.getByText('여행 중 · 오너',{exact:true})).toBeVisible();checks.push('Delayed mutation GET cannot overwrite newer SSE');await mp.context().close();
 // Polling must obey the same order when SSE fails.
 const pp=await page(id);let polls=0;
 await pp.route('**/events',r=>r.abort('connectionfailed'));
 await pp.route('**/snapshot',r=>{polls++;return r.fulfill({status:200,contentType:'application/json',body:JSON.stringify(polls===1?latest:old)});});
 await open(pp,'P1 ordering');await expect(pp.getByText('픽업 중 · 오너',{exact:true})).toBeVisible();await expect.poll(()=>polls,{timeout:12000}).toBeGreaterThan(1);await expect(pp.getByText('픽업 중 · 오너',{exact:true})).toBeVisible();checks.push('Polling rejects older snapshot after SSE failure');await pp.context().close();
 // Conflict recovery GET also cannot overwrite newer SSE.
 const fid=await trip('P1 conflict'),fp=await page(fid);let conflictStream=await snapshot(fid),releaseConflict,conflictFetched;
 const gotConflict=new Promise(r=>conflictFetched=r);
 await fp.route('**/events',r=>r.fulfill({status:200,contentType:'text/event-stream',body:frame(conflictStream)}));
 await fp.route('**/phase',r=>r.fulfill({status:409,contentType:'application/json',body:JSON.stringify({error:'Injected conflict'})}));
 await fp.route('**/snapshot',async r=>{const response=await r.fetch();const body=await response.json();conflictFetched();await new Promise(done=>releaseConflict=done);await r.fulfill({response,json:body});});
 await open(fp,'P1 conflict');await fp.getByRole('button',{name:'픽업 시작하기',exact:true}).click();await gotConflict;await phase(fid,'pickup');conflictStream=await snapshot(fid);await expect(fp.getByText('픽업 중 · 오너',{exact:true})).toBeVisible();const conflictResponse=fp.waitForResponse(r=>r.url().endsWith('/snapshot'));releaseConflict();await settle(fp,conflictResponse);await expect(fp.getByText('픽업 중 · 오너',{exact:true})).toBeVisible();checks.push('409 recovery GET cannot overwrite newer SSE');await fp.context().close();
 // A response started in an abandoned trip must not replace the selected trip.
 const aid=await trip('P1 old selection'),bid=await trip('P1 new selection'),sp=await page(aid);let releaseSelection,selectionFetched;
 const gotSelection=new Promise(r=>selectionFetched=r);
 await sp.route('**/snapshot',async r=>{const response=await r.fetch();selectionFetched();await new Promise(done=>releaseSelection=done);await r.fulfill({response});});
 await open(sp,'P1 old selection');await sp.getByRole('button',{name:'픽업 시작하기',exact:true}).click();await gotSelection;await sp.getByRole('button',{name:'내 여행',exact:true}).click();await sp.getByRole('button',{name:/P1 new selection/}).click();await expect(sp.getByRole('heading',{name:'P1 new selection',level:1,exact:true})).toBeVisible();const selectionResponse=sp.waitForResponse(r=>r.url().endsWith('/snapshot'));releaseSelection();await settle(sp,selectionResponse);await expect(sp.getByRole('heading',{name:'P1 new selection',level:1,exact:true})).toBeVisible();checks.push('Late old-trip mutation response cannot replace current selection');await sp.context().close();
 // Parking must be acknowledged by the server before the host UI enables completion.
 const cid=await trip('P1 parking gate'),vehicle=await pair(cid);await phase(cid,'pickup');await phase(cid,'driving');await phase(cid,'arrived',vehicle);const cp=await page(cid);await open(cp,'P1 parking gate',true);const finish=cp.getByRole('button',{name:'여행 마치고 추억으로 남기기',exact:true});await expect(finish).toBeDisabled();assert.equal((await api('PATCH',route(cid)+'/phase',host,{status:'closed'})).status,409);assert.equal((await api('GET',route(cid)+'/snapshot',vehicle)).status,200);
 const parking={position:{lat:37.5,lng:127},source:'manual',memo:'P1 server acknowledged',observedAt:new Date().toISOString()};assert.equal((await api('PUT',route(cid)+'/parking',host,parking,'p1-parking')).status,200);await expect(finish).toBeEnabled();await finish.click();await expect(cp.getByText('여행 완료 · 오너',{exact:true})).toBeVisible();assert.equal((await api('GET',route(cid)+'/snapshot',vehicle)).status,403);assert.equal((await snapshot(cid)).parking.memo,parking.memo);await cp.screenshot({path:path.join(output,'closed-with-parking.png'),fullPage:true});checks.push('No parking: UI disabled/API 409/token retained; persisted parking: UI close succeeds/token revoked');await cp.context().close();
 assert.deepEqual(errors,[]);fs.writeFileSync(path.join(output,'result.json'),JSON.stringify({ok:true,checks,pageErrors:errors},null,2));console.log(JSON.stringify({ok:true,checks,output},null,2));
}catch(e){fs.writeFileSync(path.join(output,'failure.txt'),String(e.stack||e));console.error('FAIL',e);console.error('Evidence:',output);process.exitCode=1;}
finally{if(browser)await browser.close();for(const child of children.reverse())await stop(child);}
