#!/bin/zsh
set -e
cd "${0:A:h:h}"
if [[ -z "${JAVA_HOME:-}" ]]; then
  hopon_jdk="$HOME/Library/Java/JavaVirtualMachines/hopon-temurin-17.jdk/Contents/Home"
  if [[ -d "$hopon_jdk" ]]; then
    export JAVA_HOME="$hopon_jdk"
  else
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  fi
fi
./gradlew :app:assembleDebug
