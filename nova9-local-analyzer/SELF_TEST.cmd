@echo off
setlocal
cd /d "%~dp0"
call "%~dp0BUILD.cmd"
if errorlevel 1 exit /b 1
set "NOVA_HOME=%~dp0."
set "NOVA_JAVA=%~dp0..\nova9-local-camera\.tooling\java\jdk-17.0.20+8\bin"
"%NOVA_JAVA%\java.exe" "-Dnova9.app.home=%NOVA_HOME%" -cp "build\classes;lib\onnxruntime-1.26.0.jar" Nova9LocalAnalyzer --self-test
if errorlevel 1 (
  echo.
  echo Self-test failed.
  pause
  exit /b 1
)
echo.
echo Self-test passed.
pause
endlocal
