#!/usr/bin/env bash
# Local Hub 실행. 처음이면 hub.yml 을 만들고 앱을 빌드한다.
#   ./hub.sh            실행
#   ./hub.sh --rebuild  앱을 다시 빌드한 뒤 실행 (이 저장소를 pull 받은 뒤)
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f hub.yml ]]; then
  cp hub.example.yml hub.yml
  echo "hub.yml 을 만들었습니다. 필요하면 공통 서비스(infra-profiles)를 고친 뒤 대시보드에서 프로젝트를 추가하세요."
fi

JAR=app/target/local-hub.jar
if [[ ! -f "$JAR" || "${1:-}" == "--rebuild" ]]; then
  (cd app && ./mvnw -q -B package -DskipTests)
fi

# 메모리를 적게 쓰도록 힙을 제한하고 가벼운 GC 를 쓴다
exec java -Xms32m -Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k \
  -Dstdout.encoding=UTF-8 -jar "$JAR"
