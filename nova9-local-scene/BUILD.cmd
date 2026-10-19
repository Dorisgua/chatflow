@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
set "NOVA_JAVA=%~dp0..\nova9-local-camera\.tooling\java\jdk-17.0.20+8\bin"
if not exist "%NOVA_JAVA%\javac.exe" (
  echo Missing local Java compiler: %NOVA_JAVA%\javac.exe
  pause
  exit /b 1
)
if not exist "build\classes" mkdir "build\classes"
"%NOVA_JAVA%\javac.exe" -encoding UTF-8 -d "build\classes" "src\ActivityRecord.java" "src\EncryptedRecordStore.java" "src\RecordStorageSelfTest.java" "src\Nova9LocalScene.java"
if errorlevel 1 (
  echo Build failed.
  pause
  exit /b 1
)
echo Build succeeded.
endlocal
