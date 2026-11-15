# release APK 빌드 및 서명

제출 APK는 `app/src/main`의 최신 소스로 release 빌드하고 별도 제출용 인증서로 서명합니다. 앱 코드·패키지·API 계약은 유지합니다.

```sh
./gradlew :app:assembleRelease
```

출력은 `app/build/outputs/apk/release/app-release-unsigned.apk`입니다. 설치하려면 Android SDK Build Tools의 `zipalign`과 `apksigner`로 정렬·서명해야 합니다.

```sh
zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/movo-aligned.apk
apksigner sign --ks /secure/path/submission.jks --ks-key-alias movo-submission \
  --out "apk/산경아틀라스_MOVO_release.apk" /tmp/movo-aligned.apk
apksigner verify --verbose --print-certs "apk/산경아틀라스_MOVO_release.apk"
```

위 경로는 예시입니다. 서명 키·비밀번호는 저장소 밖에서 보관하고 명령 기록이나 README에 실제 값을 넣지 않습니다. 인증서 주체에도 개인 실명·개인 이메일을 사용하지 않습니다. 같은 앱의 후속 업데이트는 같은 서명 키를 사용해야 합니다.

기존 debug 설치본은 서명이 달라 업데이트 설치가 실패할 수 있으므로 **별도 심사용 에뮬레이터**를 사용합니다. 기존 앱 삭제·데이터 초기화는 제출 검증 절차에 포함하지 않습니다.
