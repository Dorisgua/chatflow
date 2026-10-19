@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
set "SCENE_HOME=%~dp0."
set "NOVA_JAVA=%~dp0..\nova9-local-camera\.tooling\java\jdk-17.0.20+8\bin"
if not exist "%NOVA_JAVA%\java.exe" (
  echo Missing local Java runtime.
  pause
  exit /b 1
)
if not exist "runtime\llama-server.exe" (
  echo Missing local llama.cpp runtime. Setup is not complete.
  pause
  exit /b 1
)
if not exist "build\classes\Nova9LocalScene.class" call "%~dp0BUILD.cmd"
if errorlevel 1 exit /b 1
echo Starting the fully local scene-understanding window...
echo Keep this command window open. Camera frames and descriptions are not logged here.
"%NOVA_JAVA%\java.exe" "-Dfile.encoding=UTF-8" "-Dnova9.scene.home=%SCENE_HOME%" -cp "build\classes" Nova9LocalScene
if errorlevel 1 (
  echo The local scene program stopped with an error shown above.
  pause
)
endlocal
