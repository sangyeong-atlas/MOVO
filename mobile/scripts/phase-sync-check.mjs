// Run with the mobile dev server open. All API writes use a temporary database.
import {chromium,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import fs from 'node:fs';import os from 'node:os';import path from 'node:path';import {spawn} from 'node:child_process';import {fileURLToPath} from 'node:url';import assert from 'node:assert/strict';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const out=fs.mkdtempSync(path.join(os.tmpdir(),'movo-phase-sync-')),origin=process.env.MOVO_TEST_ORIGIN||'http://localhost:5173';
const env={...process.env,HOPON_DB:path.join(out,'test.db'),KAKAO_REST_API_KEY:'',KAKAO_JAVASCRIPT_KEY:'',KAKAO_JAVASCRIPT_API_KEY:'',NAVER_MAPS_CLIENT_ID:'',NAVER_MAPS_CLIENT_SECRET:''};
const server=spawn('python3',['-B','-u','-c',"import server_v2 as v;v.init();s=v.ThreadingHTTPServer(('127.0.0.1',0),v.API);print(s.server_port,flush=True);s.serve_forever()"],{cwd:path.join(root,'backend'),env,stdio:['ignore','pipe','ignore']});
let browser;
try{
 const port=await new Promise((resolve,reject)=>{let data='';server.stdout.on('data',b=>{data+=b;if(data.includes('\n'))resolve(Number(data.split('\n')[0]))});server.once('error',reject);server.once('exit',()=>reject(Error('Temporary backend stopped')))}),base='http://127.0.0.1:'+port;
 async function api(method,url,body,token=''){const res=await fetch(base+url,{method,headers:{'Content-Type':'application/json',Authorization:'Bearer '+token,'Idempotency-Key':randomUUID()},body:body===undefined?undefined:JSON.stringify(body)});const data=await res.json();assert.ok(res.ok,JSON.stringify(data));return data}
 const host=await api('POST','/v2/auth/register',{name:'단계 동기화 테스트',password:'isolated-test-password'}),trip=await api('POST','/v2/trips',{title:'단계 자동 전환',destination:{name:'테스트 목적지',lat:37.5445,lng:127.0374}},host.token),url='/v2/trips/'+trip.roomId;
 const pair=await api('POST',url+'/pair',{},host.token),vehicle=await api('POST','/v2/pair/exchange',{code:pair.code});
 browser=await chromium.launch({channel:'chrome',headless:true});const ctx=await browser.newContext({viewport:{width:390,height:844},serviceWorkers:'block'});
 await ctx.addInitScript(({token,trip})=>{localStorage.setItem('hopon-token',token);localStorage.setItem('hopon-trip',trip)},{token:host.token,trip:trip.roomId});
 await ctx.route(origin+'/api/**',async route=>{const u=new URL(route.request().url());await route.fulfill({response:await route.fetch({url:base+u.pathname.replace(/^\/api/,'')+u.search})})});
 const page=await ctx.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));const f=page.frameLocator('iframe'),shot=n=>f.locator('.shot').nth(n-1);
 await page.goto(origin+'/?screen=5');await expect(shot(5)).toBeVisible();
 for(const [phase,screen] of [['pickup',7],['driving',8],['arrived',9]]){await api('PATCH',url+'/phase',{status:phase},vehicle.token);await expect(shot(screen)).toBeVisible({timeout:15000})}
 await expect(shot(9)).toContainText('주차 위치 저장 전');
 await api('PUT',url+'/parking',{position:{lat:37.5445,lng:127.0374},source:'manual',memo:'B3 기둥 12',observedAt:new Date().toISOString()},vehicle.token);
 await expect(shot(9)).toContainText('주차 위치 저장됨',{timeout:15000});await expect(shot(9)).toContainText('B3 기둥 12');await f.getByRole('button',{name:'내 차 찾기',exact:true}).click();await expect(shot(10)).toBeVisible();await page.reload();await expect(shot(10)).toContainText('B3 기둥 12');
 await api('PATCH',url+'/phase',{status:'closed'},vehicle.token);await expect.poll(async()=>{const response=await api('GET',url+'/snapshot',undefined,host.token);return response.room.status}).toBe('closed');await page.waitForTimeout(2500);await expect(shot(10)).toBeVisible();
 await f.getByRole('button',{name:'이전 화면',exact:true}).filter({visible:true}).click();await expect(shot(9).locator('h3')).toContainText('마쳤어요');await page.goto(origin+'/?screen=8');await expect(shot(9)).toBeVisible({timeout:15000});
 await page.goto(origin);await expect(shot(1)).toBeVisible();await page.waitForTimeout(2500);await expect(shot(1)).toBeVisible();
 await page.goto(origin+'/?preview=1&screen=8');await expect(shot(8)).toBeVisible();await page.waitForTimeout(2500);await expect(shot(8)).toBeVisible();assert.deepEqual(errors,[]);
 console.log(JSON.stringify({ok:true,checks:['Vehicle phase changes advance the open mobile journey without navigation','Unsaved/saved parking labels update with server state','Parking view survives reload and trip completion','Stale driving URL restores completed view','Home and design preview remain unchanged'],output:out},null,2));
}finally{await browser?.close();server.kill('SIGTERM')}
