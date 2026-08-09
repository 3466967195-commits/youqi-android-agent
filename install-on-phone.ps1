$ErrorActionPreference = "Stop"

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$apk = Join-Path $PSScriptRoot "release\YouQi-1.0.0.apk"

if (-not (Test-Path -LiteralPath $adb)) {
    throw "ADB not found: $adb"
}

if (-not (Test-Path -LiteralPath $apk)) {
    throw "APK not found: $apk. Run .\gradlew.bat assembleRelease first."
}

$devices = & $adb devices
$connected = $devices | Select-String -Pattern "\sdevice$"
if (-not $connected) {
    throw "No authorized Android device. Enable USB debugging and accept the phone authorization dialog."
}

& $adb install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw "APK installation failed."
}

& $adb shell am start -n com.wanggao.youqi/.MainActivity
