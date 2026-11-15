# 제출본 검증 기록

검증일: 2026-11-15. 아래 결과는 이번 제출 사본을 대상으로 새로 실행한 결과입니다.

| 항목 | 결과 |
|---|---|
| 백엔드 unittest | 40개 통과; 임시 DB·임의 포트 |
| Android 단위 테스트 | 74개 통과; 실패·오류·건너뜀 0 |
| 테스트 이름 익명화 후 재확인 | VoiceCommandsTest 17개 통과 |
| Android release 빌드 | `assembleRelease` 성공 |
| Android release lint | 오류 0, 경고 135 |
| 모바일 | lockfile 기반 `npm ci`, TypeScript/Vite 빌드 성공 |
| 모바일 상태 동기화 | 격리 서버에서 픽업→주행→도착, 주차 저장/복원, 종료 후 기록 화면·홈 유지 확인 |
| APK 서명 | apksigner 검증 통과, zipalign 검증 통과 |
| APK 빌드 유형 | debuggable 플래그 없음 |
| release 실행 | 새 Pleos Connect v2.1.0 / API 34 / ARM64 에뮬레이터에 설치, MainActivity 실행 및 홈 표시 확인 |

차량 release 실행 확인은 새 기기의 설치·시작·홈 표시 범위입니다. 이번 제출 정리에서 실차, 실제 마이크 전체 시나리오, release의 실제 지도 키를 사용하는 전체 여정 통합을 다시 검증한 것은 아닙니다. API 회귀 테스트는 실제 외부 지도 API 호출 대신 격리된 응답을 사용합니다.

새 에뮬레이터 초기 부팅 중 별도 시스템 앱 `ai.umos.drivingview`의 GPU 관련 native crash가 기록되었습니다. MOVO 프로세스는 유지됐고, 부팅 완료 후 MOVO 홈 표시를 확인했습니다. 이 시스템 앱 오류를 MOVO 기능 검증 통과로 간주하지 않습니다.

SDK 연결 구현과 물리 차량 제어의 범위는 [구현 범위](IMPLEMENTATION.md)를 참고하세요. 기존 사용자 앱·DB·페어링·Journey는 이번 검증 대상으로 사용하지 않았습니다.
