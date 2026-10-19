$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.tooling'
$sdkRoot = Join-Path $toolRoot 'android-sdk'
$javaRoot = Join-Path $toolRoot 'java'
$buildTools = Join-Path $sdkRoot 'build-tools\35.0.0'
$androidJar = Join-Path $sdkRoot 'platforms\android-35\android.jar'

$java = (Get-ChildItem -LiteralPath $javaRoot -Filter java.exe -Recurse |
    Where-Object { $_.FullName -match '\\bin\\java.exe$' } |
    Select-Object -First 1).FullName
$jdkBin = Split-Path -Parent $java
$env:JAVA_HOME = Split-Path -Parent $jdkBin
$env:PATH = "$jdkBin;$env:PATH"
$javac = Join-Path $jdkBin 'javac.exe'
$jar = Join-Path $jdkBin 'jar.exe'
$keytool = Join-Path $jdkBin 'keytool.exe'
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$d8 = Join-Path $buildTools 'd8.bat'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'

$required = @($javac, $jar, $keytool, $aapt2, $d8, $zipalign, $apksigner, $androidJar)
foreach ($path in $required) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing build tool: $path"
    }
}

function Assert-ExitCode([string]$step) {
    if ($LASTEXITCODE -ne 0) {
        throw "$step failed with exit code $LASTEXITCODE"
    }
}

$buildId = [Guid]::NewGuid().ToString('N')
$buildRoot = Join-Path $projectRoot "build-work\$buildId"
$classesDir = Join-Path $buildRoot 'classes'
$dexDir = Join-Path $buildRoot 'dex'
$distDir = Join-Path $projectRoot 'dist'
$signingDir = Join-Path $projectRoot 'signing-local'
New-Item -ItemType Directory -Force -Path $buildRoot, $distDir, $signingDir | Out-Null
New-Item -ItemType Directory -Force -Path $classesDir, $dexDir | Out-Null

$manifest = Join-Path $projectRoot 'app\src\main\AndroidManifest.xml'
$sourceRoot = Join-Path $projectRoot 'app\src\main\java'
$sources = @(Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -Recurse |
    Sort-Object FullName |
    ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) {
    throw 'No Java source files found.'
}

Write-Host '1/7 Compile Java sources'
& $javac -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d $classesDir @sources
Assert-ExitCode 'Java compilation'

Write-Host '2/7 Create intermediate JAR'
$classesJar = Join-Path $buildRoot 'classes.jar'
& $jar --create --file $classesJar -C $classesDir .
Assert-ExitCode 'JAR packaging'

Write-Host '3/7 Convert bytecode to Android DEX'
& $d8 --min-api 23 --lib $androidJar --output $dexDir $classesJar
Assert-ExitCode 'DEX conversion'

Write-Host '4/7 Create base APK'
$baseApk = Join-Path $buildRoot 'base.apk'
& $aapt2 link -o $baseApk -I $androidJar --manifest $manifest `
    --min-sdk-version 23 --target-sdk-version 35 `
    --version-code 1 --version-name '0.1.0'
Assert-ExitCode 'APK resource packaging'

Write-Host '5/7 Add DEX and align APK'
& $jar --update --file $baseApk -C $dexDir classes.dex
Assert-ExitCode 'Adding DEX to APK'
$alignedApk = Join-Path $buildRoot 'aligned.apk'
& $zipalign -f -p 4 $baseApk $alignedApk
Assert-ExitCode 'APK alignment'

Write-Host '6/7 Sign with project-local test certificate'
$keyStore = Join-Path $signingDir 'local-test.jks'
$keyPassword = 'nova9-local-test'
if (-not (Test-Path -LiteralPath $keyStore)) {
    & $keytool -genkeypair -noprompt -keystore $keyStore `
        -storepass $keyPassword -keypass $keyPassword `
        -alias local-test -keyalg RSA -keysize 3072 -validity 3650 `
        -dname 'CN=Nova9 Local Test, OU=Local Build, O=Source Available, C=CN'
    Assert-ExitCode 'Test certificate generation'
}
$finalApk = Join-Path $distDir 'Nova9LocalCamera-v0.1.0.apk'
& $apksigner sign --ks $keyStore --ks-key-alias local-test `
    --ks-pass "pass:$keyPassword" --key-pass "pass:$keyPassword" `
    --out $finalApk $alignedApk
Assert-ExitCode 'APK signing'

Write-Host '7/7 Verify APK and generate transparency report'
$signatureReport = (& $apksigner verify --verbose --print-certs $finalApk 2>&1 | Out-String).Trim()
Assert-ExitCode 'APK signature verification'
$permissionsReport = (& $aapt2 dump permissions $finalApk 2>&1 | Out-String).Trim()
Assert-ExitCode 'APK permission inspection'
$packageReport = (& $aapt2 dump packagename $finalApk 2>&1 | Out-String).Trim()
Assert-ExitCode 'APK package inspection'

$sourceRows = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\src\main') -File -Recurse |
    Sort-Object FullName |
    ForEach-Object {
        $relative = $_.FullName.Substring($projectRoot.Length + 1).Replace('\', '/')
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
        "$hash  $relative"
    }
$sourceHashFile = Join-Path $distDir 'SOURCE-SHA256.txt'
$sourceRows | Set-Content -LiteralPath $sourceHashFile -Encoding UTF8

$apkHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $finalApk).Hash.ToLowerInvariant()
$sourceManifestHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourceHashFile).Hash.ToLowerInvariant()
$apkEntries = (& $jar --list --file $finalApk 2>&1 | Out-String).Trim()

$report = @"
Nova 9 Local Camera - Build Transparency Report
Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss K')

APK: $finalApk
APK SHA-256: $apkHash
Source hash manifest SHA-256: $sourceManifestHash

Third-party runtime dependencies: none
Cloud services: none
Advertising or analytics SDKs: none

Package:
$packageReport

Permissions:
$permissionsReport

Signature verification:
$signatureReport

APK entries:
$apkEntries
"@
$reportFile = Join-Path $distDir 'BUILD-REPORT.txt'
$report | Set-Content -LiteralPath $reportFile -Encoding UTF8

Write-Host ''
Write-Host "Build complete: $finalApk"
Write-Host "APK SHA-256: $apkHash"
Write-Host "Report: $reportFile"
