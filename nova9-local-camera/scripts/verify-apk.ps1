$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = Join-Path $projectRoot '.tooling\android-sdk'
$buildTools = Join-Path $sdkRoot 'build-tools\35.0.0'
$apk = Join-Path $projectRoot 'dist\Nova9LocalCamera-v0.1.0.apk'
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$javaRoot = Join-Path $projectRoot '.tooling\java'
$java = (Get-ChildItem -LiteralPath $javaRoot -Filter java.exe -Recurse |
    Where-Object { $_.FullName -match '\\bin\\java.exe$' } |
    Select-Object -First 1).FullName
$jdkBin = Split-Path -Parent $java
$env:JAVA_HOME = Split-Path -Parent $jdkBin
$env:PATH = "$jdkBin;$env:PATH"

if (-not (Test-Path -LiteralPath $apk)) {
    throw "APK not found: $apk"
}

Write-Host 'APK SHA-256'
(Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLowerInvariant()
Write-Host ''
Write-Host 'APK permissions'
& $aapt2 dump permissions $apk
Write-Host ''
Write-Host 'APK signature'
& $apksigner verify --verbose --print-certs $apk
