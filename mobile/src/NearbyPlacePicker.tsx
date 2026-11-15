import {useEffect,useRef,useState} from 'react';
import {Coffee,MapPin,Navigation,Plus,Check} from 'lucide-react';
import {HoponClient,ApiError,type Place,type Snapshot,type Point} from './api';
import {PickupMap} from './components';

export function samePlace(a:Place,b:Place){
 return Boolean(a.id&&b.id&&a.id===b.id)||(a.name===b.name&&Math.abs(a.lat-b.lat)<0.00001&&Math.abs(a.lng-b.lng)<0.00001);
}
export function placeStatus(s:Snapshot|null,p:Place){
 if(s?.course.some(v=>samePlace(v,p)))return 'applied';
 const requests=s?.requests.filter(q=>q.kind==='stop'&&q.place&&samePlace(q.place,p))||[];
 return requests.find(q=>q.status==='approved')?.status||requests.find(q=>q.status==='pending')?.status||requests.at(-1)?.status;
}
const categories=[['CE7','카페'],['FD6','식당'],['AT4','명소'],['rest','휴게소'],['PK6','주차']] as const;
const labels={pending:'승인 대기',approved:'차량 적용 중',applied:'코스 반영 완료',rejected:'거절됨 · 다시 요청 가능',failed:'적용 실패 · 다시 요청 가능'};
export function NearbyPlacePicker({api,snapshot,busy,onAdd}:{api:HoponClient;snapshot:Snapshot|null;busy:boolean;onAdd:(p:Place)=>void}){
 const [category,setCategory]=useState('CE7'),[query,setQuery]=useState(''),[center,setCenter]=useState<Point|null>(null),[basis,setBasis]=useState('출발지'),[places,setPlaces]=useState<Place[]>([]),[source,setSource]=useState(''),[error,setError]=useState(''),[loading,setLoading]=useState(false),[selected,setSelected]=useState<Place|null>(null),[retry,setRetry]=useState(0);
 const scope=useRef(0);
 useEffect(()=>{scope.current++;setCenter(snapshot?.origin||null);setBasis('출발지');setSelected(null);setPlaces([]);return()=>{scope.current++}},[snapshot?.room.roomId,api]);
 useEffect(()=>{
  let active=true;let poll:ReturnType<typeof setTimeout>|undefined;let attempts=0;setPlaces([]);setError('');setSource('');
  if(!center){setLoading(false);return()=>{active=false}}
  setLoading(true);
  const search=async()=>{
   try{
    const q=new URLSearchParams({lat:String(center.lat),lng:String(center.lng)});
    if(snapshot){q.set('tripId',snapshot.room.roomId);if(basis==='출발지')q.set('alongRoute','1')}
    if(query.trim())q.set('query',query.trim());else if(category==='rest')q.set('query','휴게소');else q.set('category',category);
    const response=await api.request<{places:Place[];source:string;detourPending?:boolean}>('GET',`/v2/places/nearby?${q}`);
    if(active){setPlaces([...response.places].sort((a,b)=>(a.distance??Infinity)-(b.distance??Infinity)));setSource(response.source);if(response.detourPending&&attempts++<10)poll=setTimeout(search,1500)}
   }catch(e){if(active)setError(e instanceof ApiError?e.message:'장소를 불러오지 못했어요. 다시 검색해 주세요.')}
   finally{if(active)setLoading(false)}
  };
  const timer=setTimeout(search,query?350:0);
  return()=>{active=false;clearTimeout(timer);clearTimeout(poll)};
 },[api,center?.lat,center?.lng,query,category,retry,basis,snapshot?.room.roomId,snapshot?.revision,snapshot?.room.status]);
 const locate=()=>{
  const token=scope.current;
  if(!navigator.geolocation){setError('위치를 사용할 수 없어요. 출발지를 기준으로 검색해 주세요.');return}
  navigator.geolocation.getCurrentPosition(p=>{if(token===scope.current){setCenter({lat:p.coords.latitude,lng:p.coords.longitude});setBasis('현재 위치')}},()=>{if(token===scope.current)setError('위치 권한을 확인해 주세요. 출발지 주변 검색은 계속 사용할 수 있어요.')},{timeout:8000,maximumAge:30000});
 };
 const phase=snapshot?.room.status;
 const canAdd=!!snapshot&&['planning','pickup','driving'].includes(phase!)&&!(snapshot.role==='host'&&phase==='driving');
 return <section className="nearby-picker" aria-label="주변 장소 선택">
  <label className="nearby-search"><MapPin size={20}/><input aria-label="주변 장소 검색" placeholder="장소 이름 검색" maxLength={80} value={query} onChange={e=>setQuery(e.target.value)}/></label>
  {center&&<PickupMap s={snapshot||undefined} pin={selected||center} selected={selected} compact/>}
  <div className="place-categories">{categories.map(([code,name])=><button type="button" key={code} aria-pressed={category===code} onClick={()=>{setCategory(code);setQuery('')}}>{code==='CE7'&&<Coffee size={17}/>} {name}</button>)}</div>
  <div className="nearby-location"><span>{snapshot&&basis==='출발지'?'경로 주변':`${basis} 주변`} · 장소 거리는 직선거리</span><button type="button" onClick={locate}><Navigation size={16}/>현재 위치</button></div>
  <p className="fineprint">{snapshot?`${snapshot.room.title} · ${phase==='planning'?'출발 전 코스에 추가돼요.':'차량에서 운전자가 승인한 뒤 코스에 반영돼요.'}`:'먼저 추가할 여행을 선택해 주세요.'}</p>
  {snapshot?.role==='host'&&phase==='driving'&&<p className="note">주행 중인 운전자는 차량에서 경유지를 제안해 주세요.</p>}
  {source==='sample-fallback'&&<p className="note">시연용 샘플 장소예요. 실제 주변 검색 결과와 다를 수 있어요.</p>}
  {error&&<p role="alert">{error} <button className="text-btn" onClick={()=>setRetry(v=>v+1)}>다시 검색</button></p>}
  {loading&&<p role="status">장소를 찾고 있어요…</p>}
  {!loading&&center&&!places.length&&!error&&<p className="muted">검색 결과가 없어요. 다른 종류나 장소 이름으로 찾아보세요.</p>}
  <div className="nearby-results">{places.map((p,i)=>{
   const state=placeStatus(snapshot,p);const locked=state==='pending'||state==='approved'||state==='applied';
   return <article className="nearby-result" key={`${p.id||p.name}-${i}`}>
    <span className="nearby-place-icon">{category==='CE7'?<Coffee size={23}/>:<MapPin size={23}/>}</span>
    <div className="nearby-result-info"><button className="place-name" onClick={()=>setSelected(p)}>{p.name}</button><small>{p.address||p.category||'주소 정보 없음'}</small>{p.detourSeconds!=null?<small>네이버 · 추가 주행 {Math.ceil(p.detourSeconds/60)}분 · {((p.detourMeters||0)/1000).toFixed(1)}km (체류 제외)</small>:snapshot&&source==='kakao'?<small>{p.detourStatus==='pending'?'추가 주행시간 계산 중…':'추가 주행시간 확인 불가'}</small>:null}{state&&<small role="status" className="place-request-state">{labels[state]}</small>}</div>
    {p.distance!=null&&<strong className="nearby-distance">{p.distance<1000?`${p.distance}m`:`${(p.distance/1000).toFixed(1)}km`}</strong>}
    <button className="nearby-add" aria-label={`${p.name} ${locked?labels[state]:'추가'}`} disabled={busy||!canAdd||locked} onClick={()=>onAdd(p)}>{locked?<Check size={22}/>:<Plus size={24}/>}</button>
   </article>
  })}</div>
 </section>;
}
