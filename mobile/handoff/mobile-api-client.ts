import type {Snapshot} from './types';
import {selectSnapshot} from './snapshot-order';
export class ApiError extends Error {constructor(public status:number,message:string){super(message)}}
export class HoponClient {
 constructor(public token='',public base='/api'){}
 async request<T=unknown>(method:string,path:string,body?:unknown,key?:string):Promise<T>{
  const response=await fetch(this.base+path,{method,headers:{'Content-Type':'application/json',...(this.token?{Authorization:`Bearer ${this.token}`} : {}),...(key?{'Idempotency-Key':key}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(10000)});
  const data=await response.json();if(!response.ok)throw new ApiError(response.status,data.error||`HTTP ${response.status}`);return data as T;
 }
 snapshot(id:string){return this.request<Snapshot>('GET',`/v2/trips/${encodeURIComponent(id)}/snapshot`)}
 subscribe(id:string,onSnapshot:(s:Snapshot)=>void,onConnection:(mode:string)=>void){
  const controller=new AbortController();let lastId='',stopped=false;let previous:Snapshot|null=null;
  const receive=(incoming:Snapshot)=>{if(stopped)return;const next=selectSnapshot(previous,incoming,id);if(next!==incoming)return;previous=next;lastId=String(next.eventId);onSnapshot(next);if(next.room.status==='closed'){stopped=true;controller.abort()}};
  const run=async()=>{while(!stopped){try{
   const r=await fetch(`${this.base}/v2/trips/${encodeURIComponent(id)}/events`,{headers:{Authorization:`Bearer ${this.token}`,'Last-Event-ID':lastId},signal:controller.signal});
   if(!r.ok||!r.body)throw new ApiError(r.status,'실시간 연결을 확인해 주세요');onConnection('live');const reader=r.body.getReader();const decoder=new TextDecoder();let buffer='';
   while(!stopped){const {value,done}=await reader.read();if(done)break;buffer+=decoder.decode(value,{stream:true});let i;while((i=buffer.indexOf('\n\n'))>=0){const block=buffer.slice(0,i);buffer=buffer.slice(i+2);const data=block.split('\n').find(l=>l.startsWith('data: '));if(data){const s=JSON.parse(data.slice(6)) as Snapshot;receive(s);if(stopped)break}}}
  }catch(e){if(stopped)break;if(e instanceof ApiError&&e.status===403){onConnection('forbidden');break}onConnection('polling');try{receive(await this.snapshot(id))}catch(err){onConnection(err instanceof ApiError&&err.status===403?'forbidden':'offline');if(err instanceof ApiError&&err.status===403)break}}
  if(!stopped)await new Promise<void>(resolve=>{const timer=setTimeout(resolve,3000);controller.signal.addEventListener('abort',()=>{clearTimeout(timer);resolve()},{once:true})});}};
  void run();return()=>{stopped=true;controller.abort()};
 }
}
// const api = new HoponClient(accountToken);
// const unsubscribe = api.subscribe(tripId, renderSnapshot, renderConnection);
// unsubscribe(); // screen unmount, logout, leave or trip closed
