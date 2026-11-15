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
const root=path.dirname(mobile), output=fs.mkdtempSync(path.join(os.tmpdir(),'movo-seat-regression-'));
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
 const register=async name=>(await api('POST','/v2/auth/register','',{name,password:'isolated-test-only'})).data.token;
 const host=await register('Seat owner'),b=await register('Seat B'),c=await register('Seat C');
 const id=(await api('POST','/v2/trips',host,{title:'Seat integration'})).data.roomId,rootPath='/v2/trips/'+id;
 const invite=(await api('POST',rootPath+'/invites',host,{})).data.inviteToken;
 const join=async token=>(await api('POST','/v2/invites/'+invite,token,{consent:true})).data.memberId;
 const bid=await join(b),cid=await join(c);assert.equal(await join(b),bid);
 const snapshot=async token=>(await api('GET',rootPath+'/snapshot',token)).data;
 browser=await chromium.launch({channel:'chrome',headless:true});
 async function page(token){
  const context=await browser.newContext({viewport:{width:390,height:844},serviceWorkers:'block'});
  await context.route('**/*',r=>new URL(r.request().url()).origin===origin?r.continue():r.abort());
  await context.addInitScript(({token,id})=>{localStorage.setItem('hopon-token',token);localStorage.setItem('hopon-trip',id)},{token,id});
  const p=await context.newPage();p.setDefaultTimeout(12000);p.on('pageerror',e=>errors.push(e.message));return p;
 }
 async function open(p){await p.goto(origin+'/?functional=1');await p.getByRole('button',{name:'파티방',exact:true}).click();await p.getByRole('button',{name:/Seat integration/}).click();await expect(p.getByRole('heading',{name:'Seat integration',level:1,exact:true})).toBeVisible();}
 const hp=await page(host),bp=await page(b),cp=await page(c);await Promise.all([open(hp),open(bp),open(cp)]);
 const select=(p,name)=>p.getByRole('combobox',{name:name+' 좌석',exact:true});
 await expect(select(bp,'Seat B')).toHaveValue('1');await expect(select(cp,'Seat C')).toHaveValue('2');
 await expect(select(bp,'Seat C')).toBeDisabled();await expect(select(cp,'Seat B')).toBeDisabled();
 assert.equal((await api('PATCH',rootPath+'/members/'+cid,b,{seat:4})).status,403);
 await expect(select(bp,'Seat B').locator('option[value="2"]')).toBeDisabled();
 checks.push('Independent passenger contexts: own seat only, occupied seat unavailable, duplicate invite idempotent');
 // Let C take a seat after B has selected it but before B's PATCH reaches the server.
 let conflictSeen=false;
 await bp.route('**/members/'+bid,async r=>{
  if(!conflictSeen&&r.request().method()==='PATCH'){
   conflictSeen=true;assert.equal((await api('PATCH',rootPath+'/members/'+cid,c,{seat:3})).status,200);
  }
  await r.continue();
 });
 const conflict=bp.waitForResponse(r=>r.url().endsWith('/members/'+bid)&&r.request().method()==='PATCH');
 await select(bp,'Seat B').selectOption('3');assert.equal((await conflict).status(),409);
 await expect(bp.getByText('다른 크루가 선택한 좌석이에요. 다시 선택해 주세요',{exact:true})).toBeVisible();
 await expect(select(bp,'Seat B')).toHaveValue('1');await expect(select(bp,'Seat C')).toHaveValue('3');
 await expect(select(bp,'Seat B').locator('option[value="3"]')).toBeDisabled();checks.push('Racing claim returns 409 and refreshes assignment without optimistic seat overwrite');
 await expect(select(cp,'Seat C')).toHaveValue('3');await select(cp,'Seat C').selectOption('4');
 await expect(select(bp,'Seat B').locator('option[value="3"]')).toBeEnabled();await select(bp,'Seat B').selectOption('3');
 await expect(select(cp,'Seat C').locator('option[value="1"]')).toBeEnabled();await select(cp,'Seat C').selectOption('1');
 for(const p of [hp,bp,cp]){await expect(select(p,'Seat B')).toHaveValue('3');await expect(select(p,'Seat C')).toHaveValue('1');}
 await open(bp);await expect(select(bp,'Seat B')).toHaveValue('3');checks.push('Passenger selection propagates to host and other passenger over SSE and survives reload');
 await select(hp,'Seat B').selectOption('2');await expect(select(bp,'Seat B')).toHaveValue('2');
 await select(hp,'Seat B').selectOption('3');await expect(select(bp,'Seat B')).toHaveValue('3');
 await bp.screenshot({path:path.join(output,'seat-selection.png'),fullPage:true});
 const code=(await api('POST',rootPath+'/pair',host,{})).data.code;
 const vehicle=(await api('POST','/v2/pair/exchange','',{code})).data.token;
 const seats=(await snapshot(vehicle)).members.map(m=>m.seat);assert.deepEqual(seats,[3,1]);checks.push('Host assignment and paired vehicle snapshot agree on selected seats');
 assert.equal((await api('PATCH',rootPath+'/phase',vehicle,{status:'pickup'})).status,200);
 assert.equal((await api('PUT',rootPath+'/vehicle-state',vehicle,{capabilities:{temperature:true},seats:{}})).status,200);
 const q=(await api('POST',rootPath+'/requests',b,{kind:'climate',seat:3,feature:'temperature',value:22},'seat-pending')).data;
 await expect(select(bp,'Seat B')).toBeDisabled();await expect(bp.getByText('공조 요청 처리 후 변경할 수 있어요.',{exact:true})).toBeVisible();
 assert.equal((await api('PATCH',rootPath+'/requests/'+q.requestId,vehicle,{status:'rejected'})).status,200);
 await expect(select(bp,'Seat B')).toBeEnabled();checks.push('Pending climate request disables seat editing until resolved');
 for(const mid of [bid,cid])assert.equal((await api('PATCH',rootPath+'/members/'+mid,vehicle,{status:'boarded'})).status,200);
 for(const p of [hp,bp,cp]){await expect(select(p,'Seat B')).toBeDisabled();await expect(select(p,'Seat C')).toBeDisabled();}
 assert.equal((await api('PATCH',rootPath+'/phase',vehicle,{status:'driving'})).status,200);
 await expect(bp.getByText('여행 중 · 동승자',{exact:true})).toBeVisible();
 await expect(select(bp,'Seat B')).toHaveValue('3');await expect(select(bp,'Seat B')).toBeDisabled();
 await bp.screenshot({path:path.join(output,'seat-driving-locked.png'),fullPage:true});checks.push('Boarded and driving assignments remain visible and locked');
 assert.deepEqual(errors,[]);fs.writeFileSync(path.join(output,'result.json'),JSON.stringify({ok:true,checks,pageErrors:errors},null,2));console.log(JSON.stringify({ok:true,checks,output},null,2));
}catch(e){fs.writeFileSync(path.join(output,'failure.txt'),String(e.stack||e));console.error('FAIL',e);console.error('Evidence:',output);process.exitCode=1;}
finally{if(browser)await browser.close();for(const child of children.reverse())await stop(child);}
