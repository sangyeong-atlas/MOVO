import {useState} from 'react';
import {ArrowLeft, RefreshCw, Link} from 'lucide-react';

const PHONES = [
  {seat:'host', label:'🚗 오너 (운전자)', bg:'#c8f0e4'},
  {seat:'p1',   label:'👤 동승자 1',      bg:'#fde0cc'},
  {seat:'p2',   label:'👤 동승자 2',      bg:'#e2d8f7'},
  {seat:'p3',   label:'👤 동승자 3',      bg:'#fdf4c8'},
];

export function MultiDemo() {
  const [inviteUrl, setInviteUrl] = useState('');

  function applyInvite() {
    const raw = inviteUrl.trim();
    if (!raw) return;
    let token: string | null = null;
    try { token = new URL(raw).searchParams.get('invite'); } catch { token = raw; }
    if (!token) { alert('초대 링크를 확인해 주세요'); return; }
    ['p1','p2','p3'].forEach(seat => {
      const iframe = document.getElementById(`phone-${seat}`) as HTMLIFrameElement | null;
      if (iframe) iframe.src = `/?invite=${encodeURIComponent(token!)}&seat=${seat}`;
    });
    setInviteUrl('');
  }

  function reload(seat: string) {
    const iframe = document.getElementById(`phone-${seat}`) as HTMLIFrameElement | null;
    if (iframe) { const s = iframe.src; iframe.src = ''; iframe.src = s; }
  }

  return (
    <div className="multi-demo">
      <div className="multi-header">
        <a className="brand" href="/">hopon<span>↗</span></a>
        <div className="multi-header-info">
          <h2>동승자 멀티 뷰</h2>
          <p>① 오너 화면에서 로그인 → 여행 만들기 → "탑승권 공유" → 초대 링크 복사 &nbsp;②&nbsp;아래 입력칸에 붙여넣기 → 동승자 화면 자동 이동</p>
        </div>
        <div className="multi-invite">
          <input
            value={inviteUrl}
            onChange={e => setInviteUrl(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && applyInvite()}
            placeholder="초대 링크 붙여넣기…"
          />
          <button onClick={applyInvite} disabled={!inviteUrl.trim()}>
            <Link size={13}/> 동승자에게 적용
          </button>
        </div>
        <a href="/"><ArrowLeft size={13}/>단일 화면</a>
      </div>

      <div className="multi-grid">
        {PHONES.map(({seat, label, bg}) => (
          <div key={seat} className="phone-slot">
            <div className="phone-slot-label" style={{background: bg}}>{label}</div>
            <div className="phone-chrome">
              <iframe
                id={`phone-${seat}`}
                src={`/?seat=${seat}`}
                title={label}
                className="phone-iframe"
                allow="geolocation; microphone"
              />
            </div>
            <button
              onClick={() => reload(seat)}
              style={{display:'flex', alignItems:'center', gap:6, color:'rgba(255,255,255,.35)', fontSize:11, minHeight:34, padding:'0 6px'}}
            >
              <RefreshCw size={11}/> 새로고침
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
