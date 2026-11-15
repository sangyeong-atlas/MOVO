# Hopon vehicle protocol 0.2.0

## 연결과 권한

1. 오너 account Bearer로 `POST /v2/trips/{tripId}/pair` → 6자리 code, 300초.
2. 차량 `POST /v2/pair/exchange {code}` → tripId, vehicleId, vehicle 전용 token. 1회만 소비됩니다. 재페어링은 기존 차량 token을 폐기합니다.
3. 이후 `Authorization: Bearer <vehicleToken>`. 토큰은 URL·QR·로그에 넣지 않습니다. 초대에는 별도의 24시간 초대 토큰만 씁니다.
4. location·좌석 상태·요청 결정/적용은 vehicle만 보냅니다. host와 member 토큰으로 위장한 전송은 403입니다. 여행 간 권한은 공유하지 않습니다.

## 좌표와 시간

WGS84 lat/lng, 거리 m, 시간 s, 속도 m/s, heading deg. 시간은 timezone 포함 UTC ISO 8601입니다. heading/speed/accuracy가 없으면 null입니다. 0으로 추측하지 마세요. receivedAt은 서버가 기록합니다.

```json
{"tripId":"R-…","vehicleId":"V-…","lat":37.5445,"lng":127.0374,"headingDeg":275,"speedMps":8,"accuracyM":8,"observedAt":"2026-11-09T12:00:00Z","sequence":12,"source":"real"}
```

`POST /v2/trips/{tripId}/telemetry`. 기본 2.5초(권장 2~3초), 실제 관측마다 증가하는 sequence. 60초 이상 시계 오차/오래된 관측은 400. sequence 또는 observedAt 역전/중복은 200 ignored, 서버 최신값을 덮지 않습니다. GPS 점프는 jump=true이며 보간/자동 도착에 쓰지 않습니다. 기본 10초 지연, 30초 단절이면 ETA를 null로 보냅니다. source=simulator는 화면에 항상 표시합니다.

`GET .../snapshot`이 단일 진실 원천입니다. `GET .../events`는 Bearer fetch stream SSE입니다. event snapshot, id=eventId. 매 연결 시 전체 snapshot을 전송하므로 Last-Event-ID와 무관하게 복원할 수 있습니다. 이벤트 delta log replay가 아닌 snapshot 일관성 모델입니다. 세션 종료 전 최대 50초 stream, 클라이언트 재연결. 실패 시 3초 polling, 403에는 중단. 폐쇄/탈퇴 시 중단합니다.

## 상태와 승차

planning → pickup → driving → arrived → closed. 차량은 모든 정방향 전이를 처리할 수 있습니다. driving 이전에는 모든 동승자가 boarded여야 하며 동승자가 0명이면 허용합니다. 운전자 모바일은 driving부터 수정 차단하고 차량 화면에서 도착 처리합니다.

본인의 preparing/outside는 member, boarded는 host/vehicle. 차량이 `PATCH .../members/{memberId} {status:"boarded"}`로 확인합니다. 서버 근접 상태는 탑승 완료가 아닙니다. 차량은 `PUT .../proximity {memberId,state}`로 waiting/approaching/arrived를 보정할 수 있습니다.

## 안전벨트와 공조

`PUT .../vehicle-state`:
```json
{"capabilities":{"occupancy":true,"belt":true,"temperature":true,"heat":null,"ventilation":false},"seats":{"1":{"occupied":true,"belt":false}}}
```

좌석은 참가 순서로 1=조수석, 2=후열 좌, 3=후열 중앙, 4=후열 우. snapshot 멤버 seat 필드를 사용합니다. 미지원 false, 미확인 null/필드 없음. occupied/belt도 boolean|null. 안전벨트 높이 조절 capability와 belt buckled/occupancy capability는 다릅니다. 차종/권한별로 각각 확인해 주세요. 화면은 두 capability가 true, 상태 30초 이내, occupied=true, belt=false일 때만 착용 안내합니다.

공조는 자신의 seat에만 요청 가능하며 feature temperature(16~30°C)/heat(0~3)/ventilation(0~3). 기능 capability=true일 때만 서버 접수합니다. 안전장치 제어는 없습니다. 서버 capability는 차량 SDK 지원 검사 결과를 반영해야 합니다.

공식 Seat reference: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/entry

## 요청 승인과 내비 적용

동승자 `POST .../requests`에 Idempotency-Key와 kind=stop 또는 climate. 서버가 requestId 발급. 차량 snapshot.requests에서 pending을 확인합니다.

- `PATCH .../requests/{id} {status:"approved"}`: 운전자 승인. 아직 코스에 반영하지 않습니다.
- `... {status:"rejected",reason:"…"}`: 거절.
- approved 후 SDK/내비 실행 → `... {status:"applied"}` 또는 `... {status:"failed",reason:"…"}`.
- applied stop만 확정 코스에 추가하고 revision 증가. 동일 결과 ACK 재전송은 200, 중복 코스 추가 없음.
- 실패를 성공처럼 표시하거나 모바일이 낙관적으로 적용 상태를 만들지 않습니다.
- 실제 SDK 요청 중복 방지는 차량에서도 requestId를 영속 저장하고 처리한 요청은 재실행하지 않아야 합니다. 서버 idempotency만으로 차량 부작용까지 exactly-once가 되지는 않습니다.

## 종료 전 주차 동기화

차량이 P/도착을 확인하면 phase arrived 후 마지막 유효 좌표를 `PUT .../parking`에 source=vehicle, observedAt, accuracyM, position, memo로 전송합니다. 전원 종료 전에 ACK(200)를 확인하고 동일 Idempotency-Key로 재시도하세요. 미전송 시 로컬 영속 큐에 저장하고 다음 연결 시 같은 관측시각으로 보냅니다. 모바일은 시동 꺼진 차량에서 위치를 다시 얻을 수 없습니다. 폰 보정은 source=phone+confirmed 또는 manual이며 vehicle로 위장하지 않습니다.

## v0.1 호환

기존 `/rooms`, `/join`, `/rooms/{id}/snapshot`, members, messages, vehicle-state, parking, trip을 유지합니다. solo driving 조건만 완화했습니다. v0.1 hostToken/memberToken도 v0.2 snapshot에 인증할 수 있으나 v0.2 차량 전용 쓰기는 페어링해야 합니다. 기존 APK는 API URL이 10.0.2.2:8787입니다. 기존 APK는 account pairing/telemetry/events/requests 기능을 모릅니다. 새 계약에 따라 수정이 필요합니다. v0.1 records 차량 상태/주차와 v0.2의 확장 모델은 동일하다고 가정하지 마세요.
