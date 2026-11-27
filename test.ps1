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
if (-not $jdk) { throw 'JDK 17 not found.' }
$env:JAVA_HOME = $jdk.FullName
Write-Host "JAVA_HOME = $env:JAVA_HOME"

$logDir = Join-Path $root 'build'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$testLog = Join-Path $logDir 'test.log'

Push-Location $root
& cmd.exe /c "call gradlew.bat -p . testDebugUnitTest --no-daemon --console=plain > build\test.log 2>&1"
$code = $LASTEXITCODE
Pop-Location

Write-Host '--- test log (tail) ---'
Get-Content $testLog -Tail 50 -ErrorAction SilentlyContinue
exit $code