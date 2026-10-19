@echo off
setlocal
cd /d "%~dp0"

set "NOVA_JAVA=%~dp0..\nova9-local-camera\.tooling\java\jdk-17.0.20+8\bin"
if not exist "%NOVA_JAVA%\javac.exe" (
  echo Cannot find javac at: %NOVA_JAVA%\javac.exe
  pause
  exit /b 1
)
if not exist "lib\onnxruntime-1.26.0.jar" (
  echo Missing local ONNX Runtime library.
  pause
  exit /b 1
)
if not exist "models\yolo26n.onnx" (
  echo Missing local YOLO model.
  pause
  exit /b 1
)

if not exist "build\classes" mkdir "build\classes"
"%NOVA_JAVA%\javac.exe" -encoding UTF-8 -cp "lib\onnxruntime-1.26.0.jar" -d "build\classes" "src\Nova9LocalAnalyzer.java"
if errorlevel 1 (
  echo.
  echo Build failed. No program was started.
  pause
  exit /b 1
)
echo Build succeeded.
endlocal
