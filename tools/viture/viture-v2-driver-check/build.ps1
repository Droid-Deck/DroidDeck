param(
    [string]$Sdk,
    [string]$Java = $env:JAVA_HOME,
    [switch]$SkipCompile
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
$buildId = [Guid]::NewGuid().ToString('N')
$classes = Join-Path $output ('classes-' + $buildId)
$production = Join-Path $output ('production-' + $buildId)
$productionPackage = Join-Path $production 'com\droiddeck\launcher\glasses'
$assets = Join-Path $output ('assets-' + $buildId)
$dex = Join-Path $output ('dex-' + $buildId)
$buildTools = Join-Path $Sdk 'build-tools\36.0.0'
$androidJar = Join-Path $Sdk 'platforms\android-34\android.jar'
$compiled = Join-Path $repo 'app\build\tmp\kotlin-classes\debug\com\droiddeck\launcher\glasses'
$stdlibVersion = '2.1.0'
$gradleCacheRoot = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$stdlibDirectory = Join-Path $gradleCacheRoot ('caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib\' + $stdlibVersion)
$stdlibFiles = @(Get-ChildItem -LiteralPath $stdlibDirectory -Recurse -File | Where-Object Name -EQ ('kotlin-stdlib-' + $stdlibVersion + '.jar'))
if ($stdlibFiles.Count -ne 1) { throw "Expected one cached Kotlin stdlib $stdlibVersion jar." }
$stdlib = $stdlibFiles[0].FullName
$unsigned = Join-Path $output 'viture-v2-driver-check-unsigned.apk'
$aligned = Join-Path $output 'viture-v2-driver-check-aligned.apk'
$apk = Join-Path $output 'viture-v2-driver-check-debug.apk'
$classJar = Join-Path $output 'harness.jar'
$productionJar = Join-Path $output 'production-driver.jar'
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $Java
    if (-not $SkipCompile) {
        Push-Location -LiteralPath $repo
        try {
            & (Join-Path $repo 'gradlew.bat') ':app:compileDebugKotlin' '-PvitureSdkDir='
            if ($LASTEXITCODE -ne 0) { throw 'Production Kotlin compilation failed.' }
        } finally { Pop-Location }
    }
    $manifestSources = @()
    foreach ($pair in @(
        @('VitureV2Link.class', 'VitureV2Link.kt'),
        @('VitureV2Protocol.class', 'VitureV2Protocol.kt'),
        @('GlassesLink.class', 'GlassesHardware.kt')
    )) {
        $binary = Get-Item -LiteralPath (Join-Path $compiled $pair[0])
        $source = Get-Item -LiteralPath (Join-Path $repo ('app\src\main\java\com\droiddeck\launcher\glasses\' + $pair[1]))
        $manifestSources += [ordered]@{ name = $source.Name; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $source.FullName).Hash }
    }
    New-Item -ItemType Directory -Force -Path $output, $classes, $productionPackage, $assets, $dex | Out-Null
    $classFiles = @(Get-ChildItem -LiteralPath $compiled -File | Where-Object {
        $_.Name -like 'VitureV2Link*.class' -or $_.Name -like 'VitureV2Protocol*.class' -or $_.Name -like 'GlassesLink*.class'
    })
    if ($classFiles.Count -lt 3) { throw 'Production class selection is incomplete.' }
    $manifestClasses = @()
    foreach ($classFile in $classFiles) {
        Copy-Item -LiteralPath $classFile.FullName -Destination $productionPackage
        $manifestClasses += [ordered]@{ name = $classFile.Name; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $classFile.FullName).Hash }
    }
    & (Join-Path $Java 'bin\jar.exe') cf $productionJar -C $production .
    if ($LASTEXITCODE -ne 0) { throw 'Production jar creation failed.' }
    $dependencies = & (Join-Path $Java 'bin\jdeps.exe') -verbose:class -filter:none $productionJar
    if ($LASTEXITCODE -ne 0) { throw 'Production dependency inspection failed.' }
    foreach ($line in $dependencies) {
        if ($line -match '->\s+(com\.droiddeck\.launcher\.glasses\.\S+)') {
            if ($Matches[1] -notmatch '^com\.droiddeck\.launcher\.glasses\.(VitureV2Link|VitureV2Protocol|GlassesLink)(\$|$)') {
                throw ('Unexpected production dependency: ' + $Matches[1] + '. Recompile the current isolated driver source.')
            }
        }
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'assets\LICENSE.viture-v2') -Destination $assets
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'assets\NOTICE.txt') -Destination $assets
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'assets\LICENSE.kotlin-Apache-2.0') -Destination $assets
    $runtimeManifest = [ordered]@{
        source = 'Actual app/build/tmp/kotlin-classes/debug production bytecode; not reimplemented'
        upstreamCommit = 'ece8c30485f7d357fefa362ae74f3eabac9b3899'
        builtUtc = [DateTime]::UtcNow.ToString('o')
        kotlinStdlibVersion = $stdlibVersion
        kotlinStdlibSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $stdlib).Hash
        productionJarSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $productionJar).Hash
        sources = $manifestSources
        classes = $manifestClasses
    }
    $runtimeManifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $assets 'runtime-manifest.json') -Encoding utf8
    & (Join-Path $Java 'bin\javac.exe') --release 8 -classpath ($androidJar + ';' + $productionJar + ';' + $stdlib) -encoding UTF-8 -d $classes (Join-Path $PSScriptRoot 'src\com\droiddeck\viturev2check\MainActivity.java')
    if ($LASTEXITCODE -ne 0) { throw 'Harness Java compilation failed.' }
    & (Join-Path $Java 'bin\jar.exe') cf $classJar -C $classes .
    if ($LASTEXITCODE -ne 0) { throw 'Harness jar creation failed.' }
    & (Join-Path $buildTools 'd8.bat') --min-api 26 --lib $androidJar --output $dex $classJar $productionJar $stdlib
    if ($LASTEXITCODE -ne 0) { throw 'D8 failed.' }
    & (Join-Path $buildTools 'aapt2.exe') link --manifest (Join-Path $PSScriptRoot 'AndroidManifest.xml') -A $assets -I $androidJar -o $unsigned --min-sdk-version 26 --target-sdk-version 34
    if ($LASTEXITCODE -ne 0) { throw 'Manifest linking failed.' }
    & (Join-Path $Java 'bin\jar.exe') uf $unsigned -C $dex classes.dex
    if ($LASTEXITCODE -ne 0) { throw 'Dex insertion failed.' }
    & (Join-Path $buildTools 'zipalign.exe') -f 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
    & (Join-Path $buildTools 'apksigner.bat') sign --ks (Join-Path $repo 'keystore\testkey.p12') --ks-key-alias testkey --ks-pass pass:android --key-pass pass:android --out $apk $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & (Join-Path $buildTools 'apksigner.bat') verify --verbose $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK verification failed.' }
    Get-FileHash -Algorithm SHA256 -LiteralPath $apk
    Write-Output $apk
} finally { $env:JAVA_HOME = $previousJavaHome }
