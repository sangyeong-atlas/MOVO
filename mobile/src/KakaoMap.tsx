import {useEffect,useRef,useState} from 'react';
import {Navigation} from 'lucide-react';
import {HoponClient,type Place,type Point,type Snapshot} from './api';
type SDK=any;
declare global {interface Window {kakao?:{maps:SDK}}}
let sdk:Promise<SDK>|null=null;
function loadSdk(key:string):Promise<SDK>{
 if(window.kakao?.maps?.LatLng)return Promise.resolve(window.kakao.maps);
 if(sdk)return sdk;
 sdk=new Promise((resolve,reject)=>{const script=document.createElement('script');script.src=`https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(key)}&autoload=false`;const timer=setTimeout(()=>reject(Error('카카오지도 응답이 늦어요')),12000);script.onload=()=>{if(!window.kakao?.maps){clearTimeout(timer);reject(Error('카카오지도 설정을 확인해 주세요'));return}window.kakao.maps.load(()=>{clearTimeout(timer);resolve(window.kakao!.maps)})};script.onerror=()=>{clearTimeout(timer);reject(Error('카카오지도에 연결하지 못했어요'))};document.head.appendChild(script)}).catch(e=>{sdk=null;throw e});return sdk;
}
export interface MapProps {s?:Snapshot;selected?:Place|null;onSelect?:(p:Place)=>void;onPin?:(p:Point)=>void;pin?:Point|null;compact?:boolean}
export function KakaoMap({s,selected,onSelect,onPin,pin,compact=false}:MapProps){
 const root=useRef<HTMLDivElement>(null),map=useRef<SDK>(null),kakao=useRef<SDK>(null),overlays=useRef<SDK[]>([]),onPinRef=useRef(onPin),onSelectRef=useRef(onSelect);onPinRef.current=onPin;onSelectRef.current=onSelect;
 const [ready,setReady]=useState(false),[error,setError]=useState('');
 const latest=useRef({s,pin});latest.current={s,pin};
 function fit(){const k=kakao.current,m=map.current;if(!k||!m)return;const {s,pin}=latest.current;const points:Point[]=[...(s?[s.origin,...s.members.flatMap(m=>[...(m.pickup?[m.pickup]:[]),...(m.dropoff?[m.dropoff]:[])]),...s.course,s.room.destination,...(s.telemetry?[s.telemetry]:[])]:[]),...(pin?[pin]:[])];if(!points.length)return;const bounds=new k.LatLngBounds();points.forEach(p=>bounds.extend(new k.LatLng(p.lat,p.lng)));m.setBounds(bounds,50,40,80,40)}
 useEffect(()=>{let alive=true;let resize:ResizeObserver|undefined;let click:((e:SDK)=>void)|undefined;let instance:SDK;void new HoponClient().request<{kakaoJavascriptKey:string}>('GET','/v2/config').then(async c=>{if(!c.kakaoJavascriptKey)throw Error('카카오지도 JavaScript 키 설정을 기다리고 있어요. 장소 검색과 여정 저장은 이용할 수 있어요.');const k=await loadSdk(c.kakaoJavascriptKey);if(!alive||!root.current)return;kakao.current=k;const p=latest.current.pin||latest.current.s?.origin||{lat:37.5445,lng:127.0374};instance=new k.Map(root.current,{center:new k.LatLng(p.lat,p.lng),level:5});map.current=instance;click=e=>onPinRef.current?.({lat:e.latLng.getLat(),lng:e.latLng.getLng()});k.event.addListener(instance,'click',click);resize=new ResizeObserver(()=>instance.relayout());resize.observe(root.current);setReady(true)}).catch(e=>{if(alive)setError(e.message)});return()=>{alive=false;resize?.disconnect();if(instance&&click)kakao.current.event.removeListener(instance,'click',click);overlays.current.forEach(o=>o.setMap(null));map.current=null}},[]);
 useEffect(()=>{const k=kakao.current,m=map.current;if(!ready||!k||!m)return;overlays.current.forEach(o=>o.setMap(null));overlays.current=[];
 const mark=(p:Point,label:string,kind:string)=>{const el=document.createElement('button');el.className='kakao-marker '+kind;el.textContent=label;el.type='button';el.title=p.name||label;el.onclick=()=>onSelectRef.current?.({...p,name:p.name||label});const overlay=new k.CustomOverlay({position:new k.LatLng(p.lat,p.lng),content:el,yAnchor:1});overlay.setMap(m);overlays.current.push(overlay)};
 if(s){mark(s.origin,'출발','origin');s.members.filter(m=>m.pickup).forEach((m,i)=>mark({...m.pickup!,name:m.displayName},`${i+1} ${m.displayName}`,'member'));s.members.filter(m=>m.dropoff).forEach(m=>mark({...m.dropoff!,name:m.displayName+' 하차'},m.displayName+' 하차','stop'));s.course.forEach((p,i)=>mark(p,String(i+1),'stop'));mark(s.room.destination,'도착','destination');if(s.telemetry)mark(s.telemetry,'차량','vehicle');if(s.route.source==='naver'&&s.route.status==='ready'){const route=new k.Polyline({path:s.route.polyline.map(p=>new k.LatLng(p[0],p[1])),strokeWeight:5,strokeColor:'#002c5f',strokeOpacity:.7,strokeStyle:'solid'});route.setMap(m);overlays.current.push(route)}}if(pin)mark(pin,'선택 위치','selected');
 },[ready,s,pin]);
 useEffect(()=>{if(ready)fit()},[ready,s?.room.roomId]);
 useEffect(()=>{const p=selected||pin;if(ready&&p)map.current?.panTo(new kakao.current.LatLng(p.lat,p.lng))},[ready,selected,pin?.lat,pin?.lng]);
 return <div className={'map-wrap '+(compact?'compact':'')}><div className="map" ref={root} aria-label="카카오 여행 지도"/>{error&&<div className="map-unavailable" role="status"><Navigation size={38}/><p>{error}</p>{(pin||s?.room.destination)&&<a target="_blank" rel="noreferrer" href={`https://map.kakao.com/link/map/${encodeURIComponent((pin||s?.room.destination)?.name||'선택 위치')},${(pin||s?.room.destination)?.lat},${(pin||s?.room.destination)?.lng}`}>카카오맵에서 위치 열기 ↗</a>}</div>}{ready&&<button className="map-center" onClick={fit}><Navigation size={16}/>전체 경로</button>}<span className="map-source">{s?s.route.label:onPin?'지도를 눌러 만날 곳을 지정해 주세요':'카카오 지도'}</span></div>
}
