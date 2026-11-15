import {chromium} from '@playwright/test';
import {execFileSync,spawn} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {homedir} from 'node:os';
import assert from 'node:assert/strict';
const sdk=process.env.ANDROID_HOME||process.env.ANDROID_SDK_ROOT||path.join(homedir(),'Library','Android','sdk');
const adb=process.env.ADB||path.join(sdk,'platform-tools','adb');
const base='http://127.0.0.1:8793';
const out=process.env.OUT_DIR||'/private/tmp/movo-mobile-stop-validation';fs.mkdirSync(out,{recursive:true,mode:0o700});
const device=(...args)=>execFileSync(adb,['-s','emulator-5554',...args],{encoding:'utf8'});
const put=(name,value)=>execFileSync(adb,['-s','emulator-5554','shell','run-as','com.hopon.car.voiceprobe','--user','10','tee',`files/${name}`],{input:value,encoding:'utf8'});
async function api(method,route,body,token){const r=await fetch(base+route,{method,headers:{'Content-Type':'application/json',Authorization:`Bearer ${token||''}`},body:body===undefined?undefined:JSON.stringify(body)});const data=await r.json();assert.ok(r.ok,JSON.stringify(data));return data}
const stamp=Date.now();
const host=(await api('POST','/v2/auth/register',{name:`mplus-h-${stamp}`,password:'test-only-password'})).token;
const member=(await api('POST','/v2/auth/register',{name:`mplus-m-${stamp}`,password:'test-only-password'})).token;
const trip=await api('POST','/v2/trips',{title:'모바일 경유지 연동 검증'},host);const root=`/v2/trips/${trip.roomId}`;
const invite=(await api('POST',`${root}/invites`,{},host)).inviteToken;
await api('POST',`/v2/invites/${invite}`,{consent:true},member);
await api('PATCH',`${root}/phase`,{status:'pickup'},host);
const code=(await api('POST',`${root}/pair`,{},host)).code;
put('mobile-stop-fixture.json',JSON.stringify({base,tripId:trip.roomId,code}));
device('reverse','tcp:8793','tcp:8793');
const instrument=spawn(adb,['-s','emulator-5554','shell','am','instrument','--user','10','-w','-e','class','com.hopon.car.voice.MobileStopUiIntegrationTest','-e','movoNativeRouteApproval','isolated-native-route-and-restore','com.hopon.car.voiceprobe.test/android.test.InstrumentationTestRunner']);
let deviceOutput='';instrument.stdout.on('data',b=>deviceOutput+=b);instrument.stderr.on('data',b=>deviceOutput+=b);
const completed=new Promise(resolve=>instrument.on('close',code=>resolve(code)));
const browser=await chromium.launch({channel:'chrome',headless:true});
const context=await browser.newContext({viewport:{width:390,height:844}});const errors=[];
await context.addInitScript(({token,trip})=>{localStorage.setItem('hopon-token',token);localStorage.setItem('hopon-trip',trip)},{token:member,trip:trip.roomId});
const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
try{
 await page.goto('http://127.0.0.1:5173');
 const plus=page.getByRole('button',{name:'성수 온실 카페 추가',exact:true});await plus.waitFor();
 await page.locator('.nearby-picker').screenshot({path:path.join(out,'mobile-before-add.png')});
 await plus.click();
 await page.getByRole('button',{name:'성수 온실 카페 승인 대기',exact:true}).waitFor();
 let snapshot=await api('GET',`${root}/snapshot`,undefined,host);
 assert.equal(snapshot.requests.length,1);assert.equal(snapshot.course.length,0);assert.equal(snapshot.requests[0].source,'mobile');
 await page.locator('.nearby-picker').screenshot({path:path.join(out,'mobile-pending.png')});
 put(`mobile-stop-approve-${trip.roomId}`,'ready');
 await page.getByRole('button',{name:'성수 온실 카페 코스 반영 완료',exact:true}).waitFor({timeout:30000});
 snapshot=await api('GET',`${root}/snapshot`,undefined,host);assert.equal(snapshot.course.length,1);assert.match(snapshot.requests[0].reason,/Pleos navigation confirmed/);
 // Native navigation may foreground itself; return to the existing vehicle test Activity.
 device('shell','am','start','--user','10','--activity-reorder-to-front','-n','com.hopon.car.voiceprobe/com.hopon.car.MainActivity');
 await page.reload();await page.getByRole('button',{name:'성수 온실 카페 코스 반영 완료',exact:true}).waitFor();
 assert.equal(await page.getByRole('button',{name:'성수 온실 카페 코스 반영 완료',exact:true}).isDisabled(),true);
 await page.locator('.nearby-picker').screenshot({path:path.join(out,'mobile-applied.png')});
 await page.getByRole('button',{name:'식당',exact:true}).click();
 await page.getByRole('button',{name:'성수 골목 식당 추가',exact:true}).click();
 await page.getByRole('button',{name:'성수 골목 식당 승인 대기',exact:true}).waitFor();
 put(`mobile-stop-reject-${trip.roomId}`,'ready');
 await page.getByText('거절됨 · 다시 요청 가능',{exact:true}).waitFor({timeout:30000});
 snapshot=await api('GET',`${root}/snapshot`,undefined,host);assert.equal(snapshot.course.length,1);assert.equal(snapshot.requests.length,2);assert.equal(snapshot.requests[1].status,'rejected');
 await page.getByRole('button',{name:'주차',exact:true}).click();await page.getByText('검색 결과가 없어요. 다른 종류나 장소 이름으로 찾아보세요.',{exact:true}).waitFor();
 for(const width of [360,390,430]){await page.setViewportSize({width,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false)}
 assert.equal(errors.length,0,errors.join('\n'));
 await completed;assert.match(deviceOutput,/OK \(1 test\)/);
 fs.writeFileSync(path.join(out,'android-result.txt'),deviceOutput);
 console.log(JSON.stringify({ok:true,mobilePlus:true,vehicleUiApproval:true,nativeRouteConfirmed:true,reload:true,rejection:true,course:1,requests:2,pageErrors:errors.length}));
}catch(error){
 await Promise.race([completed,new Promise(resolve=>setTimeout(resolve,105000))]);
 fs.writeFileSync(path.join(out,'android-result.txt'),deviceOutput);throw error;
}finally{
 await browser.close();instrument.kill();device('shell','am','force-stop','--user','10','com.hopon.car.voiceprobe');device('shell','am','start','--user','10','-n','com.hopon.car/.MainActivity');
}
