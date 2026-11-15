@echo off
chcp 65001 >nul
cd /d "%~dp0"
rem Android Studio에 들어 있는 Java를 자동으로 찾습니다 (설치 위치가 달라도 동작)
if not defined JAVA_HOME if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not defined JAVA_HOME if exist "D:\android\android studio\jbr\bin\java.exe" set "JAVA_HOME=D:\android\android studio\jbr"
if not defined JAVA_HOME if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
if not defined JAVA_HOME (
  echo Android Studio의 Java를 찾지 못했어요. Android Studio에서 Run 버튼으로 실행해 주세요.
  pause
  exit /b 1
)
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
rem 에뮬레이터가 켜져 있는지 먼저 확인
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo 에뮬레이터가 꺼져 있어요. Android Studio Device Manager에서 Pleos Connect를 켠 뒤 다시 실행해 주세요.
  pause
  exit /b 1
)
echo [1/2] 빌드하고 에뮬레이터에 설치하는 중...
if not exist build mkdir build
call gradlew.bat installDebug > build\last-build.log 2>&1
set "BUILD_RESULT=%errorlevel%"
type build\last-build.log
if not "%BUILD_RESULT%"=="0" (
  echo.
  echo 전체 기록: build\last-build.log
  echo.
  echo 빌드 실패: 위의 에러 메시지를 팀 대화방에 공유해 주세요.
  pause
  exit /b 1
)
echo [2/2] 앱 다시 실행...
"%ADB%" shell am force-stop com.example.gilkok
"%ADB%" shell am start --user current -n com.example.gilkok/.MainActivity
echo 완료! 이 창은 3초 뒤 닫힙니다.
timeout /t 3 >nul
