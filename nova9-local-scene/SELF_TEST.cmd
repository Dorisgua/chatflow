@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
call "%~dp0BUILD.cmd"
if errorlevel 1 exit /b 1
set "SCENE_HOME=%~dp0."
set "NOVA_JAVA=%~dp0..\nova9-local-camera\.tooling\java\jdk-17.0.20+8\bin"
"%NOVA_JAVA%\java.exe" "-Dfile.encoding=UTF-8" "-Dnova9.scene.home=%SCENE_HOME%" "-Dnova9.scene.port=17863" -cp "build\classes" Nova9LocalScene --self-test
if errorlevel 1 (
  echo Self-test failed.
  pause
  exit /b 1
)
echo Self-test passed.
pause
endlocal
