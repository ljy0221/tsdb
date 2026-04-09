#!/usr/bin/env bash
# check-java-version.sh
# FFM API는 Java 22+ 필수. 미만이면 차단.

JAVA_VERSION=$(java -version 2>&1 | head -1 | grep -oP '(?<=version ")[^"]+' | cut -d'.' -f1)

if [[ -z "$JAVA_VERSION" ]]; then
  echo '{"decision":"block","reason":"Java가 설치되어 있지 않습니다. Java 22 이상을 설치하세요."}' 
  exit 1
fi

if [[ "$JAVA_VERSION" -lt 22 ]]; then
  echo "{\"decision\":\"block\",\"reason\":\"⚠️  Java $JAVA_VERSION 감지. Kronos는 FFM API(JEP 454) 사용으로 Java 22 이상이 필수입니다.\\n\\n현재: Java $JAVA_VERSION\\n필요: Java 22+\\n\\nSDKMAN: sdk install java 22-open\"}"
  exit 1
fi

exit 0