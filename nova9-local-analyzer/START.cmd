@echo off
setlocal
cd /d "%~dp0"

set "NOVA_HOME=%~dp0."
set "NOVA_JAVA=%~dp0..\nova9-local-camera\.tooling\java\jdk-17.0.20+8\bin"
if not exist "%NOVA_JAVA%\java.exe" (
  echo Cannot find the local Java runtime.
  echo Expected: %NOVA_JAVA%\java.exe
  pause
  exit /b 1
)

if not exist "build\classes\Nova9LocalAnalyzer.class" call "%~dp0BUILD.cmd"
if errorlevel 1 exit /b 1

echo Starting the Nova 9 local analyzer window...
echo Keep this small command window open while the analyzer is running.
echo If the analyzer does not appear, read the last line shown here.
"%NOVA_JAVA%\java.exe" "-Dnova9.app.home=%NOVA_HOME%" -cp "build\classes;lib\onnxruntime-1.26.0.jar" Nova9LocalAnalyzer
if errorlevel 1 (
  echo.
  echo The analyzer could not start. The error is shown above.
  pause
  exit /b 1
)
endlocal
