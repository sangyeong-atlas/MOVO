# MOVO 모바일 웹

산경 아틀라스의 Hyundai MOVO 동승자·운전자용 웹/PWA입니다.

1. 저장소 루트에서 `python3 backend/server_v2.py`를 실행합니다.
2. 이 디렉터리에서 `npm ci`, `npm run dev`를 실행합니다.
3. `http://localhost:5173`에서 실제 서비스 홈을 사용합니다. `/?preview=1`은 디자인 미리보기입니다.

지도 키, 차량 연결, 시연·검증은 [루트 README](../README.md)를 따르세요. `mobile/backend/`는 이전 독립 서버이며 최신 B안 시연의 실행 서버가 아닙니다.

`npm run build`로 정적 배포 파일을 생성합니다. Vite의 `/api` 프록시는 개발 서버용이며, 운영 배포에는 별도의 HTTPS API 프록시 설정이 필요합니다. 웹/PWA는 차량용 APK와 별도입니다.
