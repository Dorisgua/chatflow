@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
set "NOVA_JAVA=%~dp0..\nova9-local-camera\.tooling\java\jdk-17.0.20+8\bin"
call "%~dp0BUILD.cmd"
if errorlevel 1 exit /b 1
"%NOVA_JAVA%\java.exe" "-Dfile.encoding=UTF-8" "-Dnova9.scene.home=%~dp0." -cp "build\classes" RecordStorageSelfTest
if errorlevel 1 (
  echo Record storage self-test failed.
  pause
  exit /b 1
)
echo Record storage self-test passed.
endlocal
