param(
    [string]$Sdk,
    [string]$Java = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
if (-not $Sdk) {
    if ($env:ANDROID_HOME) { $Sdk = $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $Sdk = $env:ANDROID_SDK_ROOT }
    elseif ($env:LOCALAPPDATA) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    else { throw 'Set ANDROID_HOME / ANDROID_SDK_ROOT or pass -Sdk.' }
}
if (-not $Java) {
    $fallbackJdk = Join-Path $env:USERPROFILE '.jdks\jbr-17.0.9'
    $javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath (Join-Path $fallbackJdk 'bin\javac.exe')) { $Java = $fallbackJdk }
    elseif ($javacCommand) { $Java = Split-Path (Split-Path $javacCommand.Source -Parent) -Parent }
}
if (-not $Java -or -not (Test-Path -LiteralPath (Join-Path $Java 'bin\javac.exe'))) {
    throw 'Set JAVA_HOME or pass -Java with a JDK containing bin\javac.exe.'
}
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$output = Join-Path $PSScriptRoot 'out'
$classes = Join-Path $output ('classes-' + [Guid]::NewGuid().ToString('N'))
$testClasses = Join-Path $output ('tests-' + [Guid]::NewGuid().ToString('N'))
$dex = Join-Path $output ('dex-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $output, $classes, $testClasses, $dex | Out-Null
$buildTools = Join-Path $Sdk 'build-tools\36.0.0'
$androidJar = Join-Path $Sdk 'platforms\android-34\android.jar'
$unsigned = Join-Path $output 'viture-v2-probe-unsigned.apk'
$aligned = Join-Path $output 'viture-v2-probe-aligned.apk'
$apk = Join-Path $output 'viture-v2-probe-debug.apk'
$classJar = Join-Path $output 'classes.jar'
$source = Join-Path $PSScriptRoot 'src\com\droiddeck\viturev2probe'
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $Java
    & (Join-Path $Java 'bin\javac.exe') --release 8 -encoding UTF-8 -d $testClasses (Join-Path $source 'Protocol.java') (Join-Path $PSScriptRoot 'tests\com\droiddeck\viturev2probe\ProtocolTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'test compilation failed' }
    & (Join-Path $Java 'bin\java.exe') -cp $testClasses com.droiddeck.viturev2probe.ProtocolTest
    if ($LASTEXITCODE -ne 0) { throw 'protocol tests failed' }
    & (Join-Path $Java 'bin\javac.exe') --release 8 -classpath $androidJar -encoding UTF-8 -d $classes (Join-Path $source 'Protocol.java') (Join-Path $source 'MainActivity.java')
    if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
    & (Join-Path $Java 'bin\jar.exe') cf $classJar -C $classes .
    if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
    & (Join-Path $buildTools 'd8.bat') --min-api 26 --lib $androidJar --output $dex $classJar
    if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
    & (Join-Path $buildTools 'aapt2.exe') link --manifest (Join-Path $PSScriptRoot 'AndroidManifest.xml') -A (Join-Path $PSScriptRoot 'assets') -I $androidJar -o $unsigned --min-sdk-version 26 --target-sdk-version 34
    if ($LASTEXITCODE -ne 0) { throw 'aapt2 failed' }
    & (Join-Path $Java 'bin\jar.exe') uf $unsigned -C $dex classes.dex
    if ($LASTEXITCODE -ne 0) { throw 'APK dex insertion failed' }
    & (Join-Path $buildTools 'zipalign.exe') -f 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }
    & (Join-Path $buildTools 'apksigner.bat') sign --ks (Join-Path $repo 'keystore\testkey.p12') --ks-key-alias testkey --ks-pass pass:android --key-pass pass:android --out $apk $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
    & (Join-Path $buildTools 'apksigner.bat') verify --verbose $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK verification failed' }
    Get-FileHash -Algorithm SHA256 -LiteralPath $apk
    Write-Output $apk
} finally {
    $env:JAVA_HOME = $previousJavaHome
}
