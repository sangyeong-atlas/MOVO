"""Additive mobile endpoints. Called within the shared API lock/SQLite transaction."""
import secrets
import time
import urllib.parse
from datetime import datetime, timezone
from server import dump, obj, ident, now


def init(c):
    c.executescript('''CREATE TABLE IF NOT EXISTS party_invitations(
        token TEXT PRIMARY KEY, room TEXT NOT NULL, sender TEXT NOT NULL,
        recipient TEXT, role TEXT NOT NULL, status TEXT NOT NULL,
        expires REAL NOT NULL, data TEXT NOT NULL);
        CREATE INDEX IF NOT EXISTS party_invites_recipient ON party_invitations(recipient);''')


def route(api, c, method, path, body, v):
    require, room, extra, save, members = v.require, v.room, v.extra, v.save, v.members
    _, uid = api.auth(c)
    def user(aid):
        row = c.execute('SELECT data FROM accounts WHERE id=?', (aid,)).fetchone()
        require(row, '계정을 찾을 수 없어요', 404)
        return obj(row[0])
    def summary(row):
        token, rid, sender, recipient, role, status, expires, raw = row
        r = room(c, rid)
        return {**obj(raw), 'token':token, 'roomId':rid, 'title':r['title'],
                'destination':r['destination'], 'origin':extra(c,rid)['origin'],
                'scheduledAt':r.get('scheduledAt'), 'senderName':user(sender)['name'],
                'recipientName':user(recipient)['name'] if recipient else None,
                'role':role, 'status':'expired' if status=='pending' and expires<=time.time() else status,
                'expiresAt':datetime.fromtimestamp(expires,timezone.utc).isoformat(),
                'incoming':recipient==uid}
    def add_member(rid, aid):
        ms=members(c,rid)
        require(len(ms)<4, '동승자 좌석이 모두 찼어요', 409)
        u=user(aid)
        r=room(c,rid)
        d,_=super(type(api),api).route(c,'POST','/join',{'inviteCode':r['inviteCode'],'displayName':u['name']})
        d.pop('memberToken',None)
        d.update(avatar=u['avatar'],seat=next(s for s in range(1,5) if s not in [m.get('seat') for m in ms]))
        c.execute('UPDATE members SET data=? WHERE id=?',(dump(d),d['memberId']))
        c.execute('INSERT OR REPLACE INTO access VALUES(?,?,?,?)',(rid,aid,'member',d['memberId']))
        return d['memberId']

    if path=='/v2/config' and method=='GET':
        return {'kakaoJavascriptKey':v.os.getenv('KAKAO_JAVASCRIPT_KEY') or v.os.getenv('KAKAO_JAVASCRIPT_API_KEY',''), 'placeSearchConfigured':bool(v.KAKAO_KEY)},200
    if path=='/v2/invitations' and method=='GET':
        require(uid)
        rows=c.execute('SELECT * FROM party_invitations WHERE sender=? OR recipient=? ORDER BY rowid DESC LIMIT 100',(uid,uid)).fetchall()
        return {'invitations':[summary(row) for row in rows]},200
    if path=='/v2/invitations' and method=='POST':
        require(uid)
        rid=str(body.get('roomId',''));actor,role,_=api.permission(c,rid)
        require(role in ['host','member'])
        r=room(c,rid);require(r['status'] in ['planning','pickup'],'초대가 마감되었어요',409)
        invited_role=body.get('role','member');require(invited_role in ['member','driver'],'초대 역할을 확인해 주세요',400)
        if invited_role=='driver':require(role=='host' and r['status']=='planning','운전자 변경은 준비 중인 운전자만 요청할 수 있어요',409)
        name=str(body.get('recipientName','')).strip();recipient=None
        if name:
            found=c.execute('SELECT id FROM accounts WHERE name=?',(name,)).fetchone();require(found,'등록된 이름을 정확히 입력해 주세요',404);recipient=found[0]
            require(recipient!=uid,'나 자신에게 초대할 수 없어요',400)
            access=c.execute('SELECT role FROM access WHERE room=? AND account=?',(rid,recipient)).fetchone()
            require(not access or (invited_role=='driver' and access[0]=='member'),'이미 파티에 참여 중이에요',409)
            old=c.execute("SELECT * FROM party_invitations WHERE room=? AND sender=? AND recipient=? AND role=? AND status='pending' AND expires>?",(rid,uid,recipient,invited_role,time.time())).fetchone()
            if old:return summary(old),200
        token=secrets.token_urlsafe(24)
        c.execute('INSERT INTO party_invitations VALUES(?,?,?,?,?,?,?,?)',(token,rid,uid,recipient,invited_role,'pending',time.time()+86400,dump({'createdAt':now()})))
        return summary(c.execute('SELECT * FROM party_invitations WHERE token=?',(token,)).fetchone()),201
    if path.startswith('/v2/invitations/'):
        parts=path.split('/');token=parts[3]
        row=c.execute('SELECT * FROM party_invitations WHERE token=?',(token,)).fetchone();require(row,'초대장을 찾을 수 없어요',404)
        _,rid,sender,recipient,invited_role,status,expires,raw=row
        if method=='GET' and len(parts)==4:return summary(row),200
        require(uid)
        action=parts[4] if len(parts)==5 else ''
        require(method=='POST' and action in ['accept','decline','revoke'],'지원하지 않는 요청이에요',405)
        if action=='revoke':
            require(uid==sender);require(status=='pending','이미 처리된 초대예요',409)
            c.execute("UPDATE party_invitations SET status='revoked' WHERE token=?",(token,));return {'status':'revoked'},200
        require(uid!=sender and (recipient is None or recipient==uid),'이 초대장을 받은 계정으로 로그인해 주세요')
        if status=='accepted' and action=='accept':
            require(obj(raw).get('acceptedBy')==uid)
            access=c.execute('SELECT member,role FROM access WHERE room=? AND account=?',(rid,uid)).fetchone();require(access,'이미 파티에서 나왔어요. 새 초대를 받아 주세요',409)
            return {'roomId':rid,'memberId':access[0],'role':access[1]},200
        require(status=='pending' and expires>time.time(),'만료되거나 이미 처리된 초대예요',409)
        if action=='decline':
            require(recipient==uid,'링크 초대는 화면을 닫아 거절할 수 있어요',400)
            c.execute("UPDATE party_invitations SET status='declined' WHERE token=?",(token,));return {'status':'declined'},200
        require(body.get('consent') is True,'파티 참가와 픽업 지점 공유에 동의해 주세요',400)
        r=room(c,rid);require(r['status'] in ['planning','pickup'],'참가가 마감되었어요',409)
        existing=c.execute('SELECT role,member FROM access WHERE room=? AND account=?',(rid,uid)).fetchone()
        if invited_role=='driver':
            require(r['status']=='planning','운전자 변경은 출발 준비 중에만 가능해요',409)
            current=c.execute("SELECT account FROM access WHERE room=? AND role='host'",(rid,)).fetchone()
            require(current and current[0]==sender,'운전자가 변경되어 이 초대는 사용할 수 없어요',409)
            require(not c.execute('SELECT 1 FROM vehicles WHERE room=?',(rid,)).fetchone(),'연결 차량이 있어 운전자를 변경할 수 없어요',409)
            require(not existing or existing[0]=='member','이미 운전자예요',409)
            if existing:
                c.execute('DELETE FROM members WHERE id=?',(existing[1],))
                r['waypointOrder']=[m for m in r['waypointOrder'] if m!=existing[1]]
                v.putroom(c,r)
            add_member(rid,sender)
            c.execute('INSERT OR REPLACE INTO access VALUES(?,?,?,?)',(rid,uid,'host',None))
            r=room(c,rid);u=user(uid);r.update(hostName=u['name'],vehicle=u['vehicle']);v.putroom(c,r)
            x=extra(c,rid);x['pickupOrder']=[m for m in x['pickupOrder'] if not existing or m!=existing[1]]
            c.execute('DELETE FROM pairs WHERE room=?',(rid,));mid=None
            c.execute("UPDATE party_invitations SET status='revoked' WHERE room=? AND role='driver' AND status='pending' AND token!=?",(rid,token))
        else:
            mid=existing[1] if existing else add_member(rid,uid)
            x=extra(c,rid)
        x['notifications'].append({'id':ident('N-'),'text':user(uid)['name']+'님이 '+('운전자로 ' if invited_role=='driver' else '')+'파티에 합류했어요','createdAt':now()});save(c,rid,x)
        data={**obj(raw),'acceptedBy':uid,'acceptedAt':now()}
        c.execute("UPDATE party_invitations SET status='accepted',recipient=?,data=? WHERE token=?",(uid,dump(data),token))
        return {'roomId':rid,'memberId':mid,'role':'host' if invited_role=='driver' else (existing[0] if existing else 'member')},200
    if path=='/v2/me/pins' and method=='PUT':
        require(uid);rid=body.get('roomId');api.permission(c,rid);u=user(uid)
        require(type(body.get('pinned')) is bool,'고정 여부를 확인해 주세요',400)
        pins=[p for p in u.get('pinnedRooms',[]) if p!=rid]
        if body['pinned']:pins.append(rid)
        u['pinnedRooms']=pins;c.execute('UPDATE accounts SET data=? WHERE id=?',(dump(u),uid));return {'user':u},200
    if path.startswith('/v2/trips/'):
        parts=path.split('/');rid=parts[3];tail='/'.join(parts[4:])
        if tail not in ['settings','messages']:return None
        actor,role,mid=api.permission(c,rid);require(role in ['host','member'])
        r=room(c,rid);x=extra(c,rid)
        if tail=='settings' and method=='PUT':
            require(role=='host' and r['status']=='planning','여정 준비 중 운전자만 수정할 수 있어요',409)
            title=str(body.get('title',r['title'])).strip();require(0<len(title)<=80,'이름을 입력해 주세요',400)
            purpose=body.get('purpose',r.get('purpose','모임'));require(purpose in ['출퇴근','모임','데이트','가족','출장'],'여정 목적을 선택해 주세요',400)
            days=body.get('weekdays',r.get('weekdays',[]));require(isinstance(days,list) and all(type(d)is int and 0<=d<7 for d in days),'요일을 확인해 주세요',400)
            r.update(title=title,purpose=purpose,weekdays=sorted(set(days)));v.putroom(c,r);save(c,rid,x);return r,200
        if tail=='messages' and method=='POST' and 'type' in body:return None # Preserve authenticated passenger message/ACK contract.
        if tail=='messages' and method=='POST':
            require(r['status'] in ['planning','pickup','driving'],'진행 중인 파티에서 보내 주세요',409)
            require(not(role=='host' and r['status']=='driving'),'주행 중에는 차량 화면을 이용해 주세요',409)
            text=str(body.get('text','')).strip();require(0<len(text)<=200,'메시지를 200자 이내로 입력해 주세요',400)
            kind='pickup-delay' if text=='5분 늦어요' else 'message'
            if kind=='pickup-delay':
                previous=next((n for n in x['notifications'] if n.get('kind')==kind and n.get('senderId')==uid),None)
                if previous:return previous,200
            notice={'id':ident('N-'),'text':user(uid)['name']+': '+text,'createdAt':now(),'senderId':uid,'kind':kind}
            x['notifications'].append(notice);x['notifications']=x['notifications'][-200:];save(c,rid,x);return notice,201
    return None
