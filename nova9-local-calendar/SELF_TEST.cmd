@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
set "CALENDAR_JAVA=%~dp0..\nova9-local-camera\.tooling\java\jdk-17.0.20+8\bin"
call "%~dp0BUILD.cmd"
if errorlevel 1 exit /b 1
"%CALENDAR_JAVA%\java.exe" "-Dfile.encoding=UTF-8" -cp "build\classes" CalendarSelfTest
if errorlevel 1 (
  echo Calendar self-test failed.
  pause
  exit /b 1
)
echo Calendar self-test passed.
endlocal
