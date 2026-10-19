@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
set "CALENDAR_JAVA=%~dp0..\nova9-local-camera\.tooling\java\jdk-17.0.20+8\bin"
if not exist "%CALENDAR_JAVA%\javac.exe" (
  echo Missing bundled Java 17 compiler: %CALENDAR_JAVA%\javac.exe
  pause
  exit /b 1
)
if not exist "build\classes" mkdir "build\classes"
"%CALENDAR_JAVA%\javac.exe" -encoding UTF-8 -d "build\classes" ^
  "src\ActivityPeriod.java" ^
  "src\SavedSummaryReader.java" ^
  "src\ActivityCalendarModel.java" ^
  "src\ActivityWeekGrid.java" ^
  "src\ActivityWeekHeader.java" ^
  "src\ActivityCalendarPanel.java" ^
  "src\Nova9LocalCalendar.java" ^
  "src\CalendarSelfTest.java"
if errorlevel 1 (
  echo Calendar build failed.
  pause
  exit /b 1
)
echo Calendar build succeeded.
endlocal
