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
echo [1/2] 제출용 release APK 만드는 중...
if not exist build mkdir build
call gradlew.bat assembleRelease > build\release-build.log 2>&1
if errorlevel 1 (
  type build\release-build.log
  echo.
  echo 빌드 실패: build\release-build.log 를 확인해 주세요.
  pause
  exit /b 1
)
set "OUT=app\build\outputs\apk\release\app-release.apk"
if not exist "%OUT%" (
  echo 서명된 APK가 없습니다. local.properties 의 RELEASE_STORE_FILE 설정을 확인해 주세요.
  pause
  exit /b 1
)
if not exist apk mkdir apk
copy /y "%OUT%" "apk\샛길연구소_길콕_release.apk" >nul
echo [2/2] 완료: apk\샛길연구소_길콕_release.apk
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
"%ADB%" get-state >nul 2>&1
if not errorlevel 1 (
  echo 에뮬레이터에 release 버전 설치 확인 중... (기존 debug 앱은 지웁니다)
  "%ADB%" uninstall com.example.gilkok >nul 2>&1
  "%ADB%" install -r "apk\샛길연구소_길콕_release.apk"
  "%ADB%" shell am start --user current -n com.example.gilkok/.MainActivity
)
pause
