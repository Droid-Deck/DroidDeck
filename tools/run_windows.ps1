param([string]$Serial)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    $javaConfig = if (Test-Path -LiteralPath '.gradle/config.properties' -PathType Leaf) {
        Get-Content -LiteralPath '.gradle/config.properties' | Where-Object { $_ -match '^java.home=' } | Select-Object -First 1
    }
    if ($javaConfig) { $env:JAVA_HOME = $javaConfig.Substring('java.home='.Length).Replace('\\', '\').Replace('\:', ':') }
    & .\gradlew.bat :app:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
    $adbArgs = @()
    if ($Serial) { $adbArgs = @('-s', $Serial) }
    & adb @adbArgs install -r 'app/build/outputs/apk/debug/app-debug.apk'
    if ($LASTEXITCODE -ne 0) { throw 'APK installation failed; existing app data was not removed.' }
    $suffixLine = if (Test-Path -LiteralPath 'local.properties' -PathType Leaf) {
        Get-Content -LiteralPath 'local.properties' | Where-Object { $_ -match '^droiddeck.debugApplicationIdSuffix=' } | Select-Object -First 1
    }
    $suffix = if ($suffixLine) { $suffixLine.Substring('droiddeck.debugApplicationIdSuffix='.Length) } else { '' }
    & adb @adbArgs shell am start -W -n "com.droiddeck.launcher$suffix/com.droiddeck.launcher.MainActivity"
    if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
} finally {
    Pop-Location
}
