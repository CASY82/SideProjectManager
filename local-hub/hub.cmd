@echo off
rem Local Hub 실행 (Windows). 처음이면 hub.yml 을 만들고 앱을 빌드한다.
rem   hub.cmd            실행
rem   hub.cmd --rebuild  앱을 다시 빌드한 뒤 실행
chcp 65001 >nul
cd /d "%~dp0"

if not exist hub.yml (
  copy hub.example.yml hub.yml >nul
  echo hub.yml 을 만들었습니다. services 에 감시할 저장소를 적어 주세요.
)

set JAR=app\target\local-hub.jar
if not exist %JAR% goto build
if "%~1"=="--rebuild" goto build
goto run

:build
pushd app
call mvnw.cmd -q -B package -DskipTests || (popd & exit /b 1)
popd

:run
java -Xms32m -Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -Dstdout.encoding=UTF-8 -jar %JAR%
