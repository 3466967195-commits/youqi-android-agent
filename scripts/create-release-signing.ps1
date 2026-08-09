$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$privateDir = Join-Path $root "private\signing"
$keystore = Join-Path $privateDir "youqi-release.jks"
$properties = Join-Path $root "keystore.properties"
$credentials = Join-Path $privateDir "RELEASE_CREDENTIALS.txt"

if (Test-Path -LiteralPath $keystore) {
    throw "Release keystore already exists: $keystore"
}

New-Item -ItemType Directory -Path $privateDir -Force | Out-Null
$passwordBytes = New-Object byte[] 30
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($passwordBytes)
$password = [Convert]::ToBase64String($passwordBytes).Replace("+", "A").Replace("/", "B").TrimEnd("=")

& keytool -genkeypair -v -keystore $keystore -storetype PKCS12 -alias youqi-release `
    -keyalg RSA -keysize 4096 -validity 10000 -storepass $password -keypass $password `
    -dname "CN=Wang Jiaze, OU=YouQi, O=Wang Jiaze, C=CN"
if ($LASTEXITCODE -ne 0) { throw "keytool failed" }

@"
storeFile=private/signing/youqi-release.jks
storePassword=$password
keyAlias=youqi-release
keyPassword=$password
"@ | Set-Content -LiteralPath $properties -Encoding ASCII

@"
YouQi Android release signing credentials
Owner: Wang Jiaze (王嘉泽)
Package: com.wanggao.youqi
Alias: youqi-release
Password: $password

Back up this file and youqi-release.jks in at least two secure offline locations.
Losing either file prevents future in-place application updates.
Never commit private/ or keystore.properties to GitHub.
"@ | Set-Content -LiteralPath $credentials -Encoding UTF8

Write-Host "Created: $keystore"
Write-Host "Credentials: $credentials"
