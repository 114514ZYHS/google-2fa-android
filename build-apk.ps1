$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$tools = Join-Path $root ".build-tools"
$downloads = Join-Path $tools "downloads"
$sdk = Join-Path $tools "android-sdk"
$gradleHome = Join-Path $tools "gradle-8.10.2"
New-Item -ItemType Directory -Force -Path $downloads, $sdk | Out-Null

function Download-File([string]$url, [string]$path) {
    $isComplete = (Test-Path $path) -and ((Get-Item $path).Length -gt 100000000)
    if (!$isComplete) {
        Remove-Item -Force $path -ErrorAction SilentlyContinue
        Invoke-WebRequest -Uri $url -OutFile $path
    }
}

$jdk = Get-ChildItem (Join-Path $tools "jdk*") -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $jdk) {
    $jdkZip = Join-Path $downloads "jdk17.zip"
    Download-File "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk" $jdkZip
    Expand-Archive -Force $jdkZip $tools
    $jdk = Get-ChildItem (Join-Path $tools "jdk*") -Directory | Select-Object -First 1
}

$sdkManager = Join-Path $sdk "cmdline-tools\latest\bin\sdkmanager.bat"
if (!(Test-Path $sdkManager)) {
    $sdkZip = Join-Path $downloads "commandlinetools.zip"
    $sdkTemp = Join-Path $tools "sdk-temp"
    Download-File "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" $sdkZip
    Remove-Item -Recurse -Force $sdkTemp -ErrorAction SilentlyContinue
    Expand-Archive -Force $sdkZip $sdkTemp
    New-Item -ItemType Directory -Force -Path (Join-Path $sdk "cmdline-tools") | Out-Null
    Move-Item (Join-Path $sdkTemp "cmdline-tools") (Join-Path $sdk "cmdline-tools\latest")
}

$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
1..100 | ForEach-Object { "y" } | & $sdkManager "--sdk_root=$sdk" --licenses | Out-Null
& $sdkManager "--sdk_root=$sdk" "platforms;android-35" "build-tools;35.0.0" | Out-Host

if (!(Test-Path $gradleHome)) {
    $gradleZip = Join-Path $downloads "gradle.zip"
    Download-File "https://services.gradle.org/distributions/gradle-8.10.2-bin.zip" $gradleZip
    Expand-Archive -Force $gradleZip $tools
}

& (Join-Path $gradleHome "bin\gradle.bat") -p $root assembleDebug
Write-Host "APK: $(Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk')"
