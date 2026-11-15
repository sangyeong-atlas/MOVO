# 차량 통합 인수 기준

- [ ] 모바일 생성 여행의 5분/1회 페어링 코드로 차량 연결, 재사용 403.
- [ ] 오너/멤버 토큰으로 telemetry 업로드 403, 다른 여행 접근 403.
- [ ] 실제 SDK 위치의 lat/lng/observedAt/sequence/source=real, 없는 heading/speed는 null.
- [ ] 약 2.5초 telemetry → 두 폰 snapshot에서 같은 sequence/좌표.
- [ ] sequence 역전/중복 200 ignored, 10초 지연/30초 연결 확인과 ETA null.
- [ ] GPS jump/accuracy>30m에서 자동 도착 억제. 근접은 boarded가 아님.
- [ ] preparing/outside 상태가 차량에 반영, 차량 탑승 확인 후 폰에 boarded.
- [ ] capability occupancy/belt 각각 확인. unknown/stale/unsupported를 미착용으로 표시하지 않음.
- [ ] 경유지 요청 pending → 승인 → 내비 SDK → applied/failed; applied만 코스에 추가.
- [ ] 동일 requestId 재전송 시 SDK 중복 실행 금지, 동일 ACK 재전송 성공.
- [ ] 공조는 해당 좌석/지원 feature만, 실제 SDK 결과를 applied/failed로 전송.
- [ ] 차량에서 driving→arrived 전환. 주행 중 오너 폰 변경 409.
- [ ] 종료 전 유효 위치 주차 업로드, ACK 미수신 재시도/로컬 영속 큐.
- [ ] 주차/도착 기록 양쪽 폰 일치, 여행 종료 시 위치·차량 token 폐기.
- [ ] 네트워크 단절/429/timeout에서 무한 중복 제어가 발생하지 않음.
- [ ] 기존 v0.1 APK에 신규 pairing/telemetry/requests/ACK UI 구현. 서버 교체만으로 자동 지원되지 않음.

자동화 검증은 저장소의 `tests/test_v2.py`, 3개 독립 브라우저 검증은 `scripts/browser-check.mjs`를 참조합니다. 위 체크박스는 실차 담당자의 검증용이며 실차에서 완료되었다고 표시하지 않습니다.
