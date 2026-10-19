@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
set "CALENDAR_HOME=%~dp0."
set "CALENDAR_JAVA=%~dp0..\nova9-local-camera\.tooling\java\jdk-17.0.20+8\bin"
if not exist "%CALENDAR_JAVA%\java.exe" (
  echo Missing bundled Java 17 runtime.
  pause
  exit /b 1
)
if not exist "build\classes\Nova9LocalCalendar.class" call "%~dp0BUILD.cmd"
if errorlevel 1 exit /b 1
echo Starting the read-only local activity calendar...
echo Only daily-summary.md is read. Images, videos and encrypted details are not opened.
"%CALENDAR_JAVA%\java.exe" "-Dfile.encoding=UTF-8" "-Dnova9.calendar.home=%CALENDAR_HOME%" -cp "build\classes" Nova9LocalCalendar
if errorlevel 1 (
  echo The local calendar stopped with an error shown above.
  pause
)
endlocal
