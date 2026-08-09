$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root "backend\.env"
$privateDir = Join-Path $root "private\backend"
$credentials = Join-Path $privateDir "ADMIN_CREDENTIALS.txt"
if (Test-Path -LiteralPath $envFile) { throw "Backend .env already exists: $envFile" }

New-Item -ItemType Directory -Path $privateDir -Force | Out-Null
function New-Secret([int]$bytes) {
    $buffer = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    return [Convert]::ToBase64String($buffer).Replace("+", "A").Replace("/", "B").TrimEnd("=")
}

$jwtSecret = New-Secret 48
$adminPassword = New-Secret 24
$adminUser = "youqi_admin"

@"
PORT=8787
YOUQI_JWT_SECRET=$jwtSecret
YOUQI_ADMIN_USER=$adminUser
YOUQI_ADMIN_PASSWORD=$adminPassword
"@ | Set-Content -LiteralPath $envFile -Encoding ASCII

@"
YouQi control server administrator
Owner: YouQi Studio (油漆工作室)
Admin URL: /admin
Username: $adminUser
Password: $adminPassword

Keep backend/.env and this file private. Back them up offline.
Changing YOUQI_JWT_SECRET logs out every client.
"@ | Set-Content -LiteralPath $credentials -Encoding UTF8

Write-Host "Created: $envFile"
Write-Host "Credentials: $credentials"
