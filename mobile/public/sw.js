// Do not cache authenticated API responses or map tiles.
self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));
self.addEventListener('fetch',e=>{if(e.request.mode==='navigate')e.respondWith(fetch(e.request).catch(()=>new Response('<meta charset="utf-8"><h1>연결을 확인해 주세요</h1><p>MOVO의 여행 정보는 서버 연결 후 확인할 수 있어요.</p>',{headers:{'Content-Type':'text/html'}})));});
