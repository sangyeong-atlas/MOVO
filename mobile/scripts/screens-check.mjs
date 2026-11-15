import {chromium,expect} from '@playwright/test';
import fs from 'node:fs';import os from 'node:os';import path from 'node:path';import assert from 'node:assert/strict';
const out=fs.mkdtempSync(path.join(os.tmpdir(),'movo-twelve-screens-'));
const browser=await chromium.launch({channel:'chrome',headless:true});const errors=[];
try{
 const context=await browser.newContext({viewport:{width:1440,height:1000},deviceScaleFactor:1,serviceWorkers:'block'});
 const source=await context.newPage();await source.goto('http://localhost:5173/MOVO-mobile-B-best-uiux.html');await source.evaluate(()=>document.fonts.ready);
 const app=await context.newPage();app.setDefaultTimeout(10000);app.on('pageerror',e=>errors.push(e.message));await app.goto('http://localhost:5173/?preview=1');const viewer=app.frameLocator('iframe');await expect(viewer.locator('.shot')).toHaveCount(12);await viewer.locator('.movo-viewer').waitFor();const frame=app.frames().find(f=>f.parentFrame());await frame.evaluate(()=>document.fonts.ready);
 const results=[];
 for(let i=0;i<12;i++){const expected=await source.locator('.phone').nth(i).screenshot({animations:'disabled'});const actual=await viewer.locator('.phone').nth(i).screenshot({animations:'disabled'});fs.writeFileSync(path.join(out,`${String(i+1).padStart(2,'0')}-original.png`),expected);fs.writeFileSync(path.join(out,`${String(i+1).padStart(2,'0')}-app.png`),actual);results.push({screen:i+1,pngIdentical:expected.equals(actual)});}
 assert.ok(results.every(r=>r.pngIdentical),'Some rendered screens differ from the approved HTML');
 await viewer.getByLabel('화면 선택',{exact:true}).selectOption('1');await expect(viewer.locator('.shot:visible')).toHaveCount(1);await viewer.getByRole('button',{name:'새 파티방 만들기',exact:true}).click();await expect(viewer.locator('.shot').nth(1)).toBeVisible();await viewer.getByRole('button',{name:'초대 링크 만들기',exact:true}).click();await expect(viewer.locator('.shot').nth(2)).toBeVisible();await viewer.getByRole('button',{name:'수락하고 자리 고르기',exact:true}).click();await expect(viewer.locator('.shot').nth(3)).toBeVisible();await viewer.getByRole('button',{name:'뒷좌석 왼쪽',exact:true}).click();await viewer.getByRole('button',{name:'이 자리로 저장',exact:true}).click();await expect(viewer.locator('.shot').nth(4)).toBeVisible();
 for(let i=1;i<=12;i++){await viewer.getByLabel('화면 선택',{exact:true}).selectOption(String(i));await expect(viewer.locator('.shot:visible')).toHaveCount(1);await expect(viewer.locator('.shot').nth(i-1)).toBeVisible()}
 await viewer.getByRole('button',{name:'12개 전체',exact:true}).click();await expect(viewer.locator('.shot:visible')).toHaveCount(12);await app.screenshot({path:path.join(out,'twelve-screens.png'),fullPage:true});
 await app.setViewportSize({width:390,height:844});await viewer.getByLabel('화면 선택',{exact:true}).selectOption('7');await app.screenshot({path:path.join(out,'mobile-screen-07.png')});assert.equal(await frame.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,'mobile screen overflows horizontally');
 assert.deepEqual(errors,[]);console.log(JSON.stringify({ok:true,results,checks:['12 source-exact rendered screens','screen 1 → 2 → 3 → 4 → 5 navigation','all 12 individually accessible','390px mobile view'],output:out},null,2));
}catch(e){console.error(e);console.error('Evidence:',out);process.exitCode=1}finally{await browser.close()}
