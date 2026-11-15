@echo off
chcp 65001 >nul
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
"%ADB%" logcat -d -s GilkokTraffic:* GilkokRoute:* AndroidRuntime:E > "%~dp0build\app-log.txt"
type "%~dp0build\app-log.txt"
echo.
echo 저장: build\app-log.txt
pause
