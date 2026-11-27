$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdkCandidates = @(
    (Join-Path $root '.tools\jdk17'),
    (Join-Path (Split-Path $root -Parent) '.tools\jdk17')
)

$jdk = $null
foreach ($candidate in $jdkCandidates) {
    if (-not (Test-Path $candidate)) { continue }
    $jdk = Get-ChildItem $candidate -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
        Select-Object -First 1
    if ($jdk) { break }
}

if (-not $jdk) {
    throw 'JDK 17 not found. Put it under .tools\jdk17 or edit build.ps1.'
}

$env:JAVA_HOME = $jdk.FullName
Write-Host "JAVA_HOME = $env:JAVA_HOME"

$gradlew = Join-Path $root 'gradlew.bat'
if (-not (Test-Path $gradlew)) {
    throw "gradlew.bat not found: $gradlew"
}

$logDir = Join-Path $root 'build'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$buildLog = Join-Path $logDir 'build.log'

Push-Location $root
& cmd.exe /c "call gradlew.bat -p . assembleDebug --no-daemon --console=plain > build\build.log 2>&1"
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) {
    Write-Host '--- build log (tail) ---'
    Get-Content $buildLog -Tail 80 -ErrorAction SilentlyContinue
    exit $code
}

Get-Content $buildLog -Tail 5 -ErrorAction SilentlyContinue

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) {
    throw "Build finished but APK not found: $apk"
}

$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$target = Join-Path $dist 'Velora-preview-debug.apk'
Copy-Item $apk $target -Force

Write-Host ''
Write-Host "APK: $target"
Write-Host "Size: $([math]::Round((Get-Item $target).Length / 1MB, 2)) MB"