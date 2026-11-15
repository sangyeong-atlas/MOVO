> 이전 모바일 0.2 인계 자료입니다. 최신 제출본 실행·지도 설정은 [루트 README](../../README.md)를 따르세요. 아래 서버·ETA 설명은 이전 독립 서버에 해당합니다.

# Hopon vehicle handoff 0.2.0

이 폴더는 모바일 개발 결과의 차량 개발자 인계 계약입니다. 별도 ZIP에는 `backend/` 실행 서버와 이 문서 전체가 포함됩니다. **DB/토큰/비밀키를 포함하지 않습니다.**

```sh
# 1. 환경변수 설정 (backend/.env 직접 생성, git에 절대 커밋 금지)
cp .env.example backend/.env
# backend/.env 파일에서 KAKAO_REST_API_KEY= 값 채우기

# 2. 서버 시작 (env var 주입 필요)
export $(grep -v '^#' backend/.env | xargs) && python3 backend/server.py

# 3. 모바일 UI에서 여행 생성 → 차량 연결 코드 발급 후
python3 vehicle-simulator/simulator.py --pair 123456 --mode drive
```

전체 모바일 저장소 내부에서는 `python3 handoff/vehicle-simulator/simulator.py ...`를 실행합니다. 매 시뮬레이터 실행에는 새 페어링 코드가 필요합니다. 다른 차량 세션이 무효화되므로 브라우저 차량과 CLI를 동시에 쓰려면 재페어링을 고려하세요. 토큰은 메모리에만 보관합니다. 기본 API `http://127.0.0.1:8787`, Android 에뮬레이터 `http://10.0.2.2:8787`; 다른 컴퓨터는 운영 HTTPS 프록시를 별도로 준비하세요.

- `openapi.yaml`: 구현된 v0.1 / v0.2 경로, 권한, 입력/출력 및 오류. JSON 문법의 YAML 1.2 파일입니다.
- `types.ts`, `mobile-api-client.ts`: 브라우저 타입·Bearer·timeout·SSE/polling·unsubscribe.
- `vehicle-contract.md`: 차량 측 필수 변경/상태/단위/ACK.
- `vehicle-simulator/`: 독립 실행 Python 클라이언트. delay/bad-gps/jump/out-of-order/arrival/seats/unknown/decision/parking 모드.
- `examples/`: 비밀 없는 shell/JSON 예시.
- `integration-checklist.md`: 인수 기준.

## 카카오 로컬 API 연동 (0.2.1)

`backend/server.py`는 카카오 로컬 REST API를 프록시합니다. `KAKAO_REST_API_KEY` 환경변수를 설정하면 모바일 UI의 모든 장소 선택(목적지·경유지·픽업 장소)에서 실제 카카오 장소 검색이 활성화됩니다.

- 발급: https://developers.kakao.com → 앱 생성 → REST API 키 → **카카오 로컬** 서비스 활성화
- 키 미설정 시 샘플 PLACES 배열로 자동 대체 (서버 fallback)
- **API 키는 절대 커밋/공유하지 마세요.** `.env.example` 형식만 공유합니다.

0.1 → 0.2: 방 토큰만 있던 인증에 테스트 계정 및 차량 전용 세션 추가, idempotent 계정 수락, 공동 코스/승인/좌석/텔레메트리/주차 출처/기록 추가, SSE 기본 + 3초 polling. 기존 방 API는 유지하며 혼자 출발을 허용합니다. 기존 초대코드 참가 API 자체는 중복 방지가 없습니다.

시연 범위: 실제 서버 연동, 실제 지도 SDK, 실제 계정별 데이터 격리. 차량 위치는 시뮬레이터이며 경로 ETA는 명시된 직선거리 기반 추정. 운영 인증·실차 SDK·실시간 교통/주차/음악·Web Push는 외부 연결 필요. PWA에는 네이티브 딥링크/백그라운드 polling이 없습니다.
