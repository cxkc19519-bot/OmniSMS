$ErrorActionPreference = "Stop"

$repoRoot = Split-Path $PSScriptRoot -Parent
$releaseDirectory = Join-Path $repoRoot ".release"
$keystorePath = Join-Path $releaseDirectory "omnisms-release.jks"
$propertiesPath = Join-Path $PSScriptRoot "signing.local.properties"

if (Test-Path -LiteralPath $keystorePath) {
    throw "Release keystore already exists. Stopped to prevent overwrite."
}

$keytool = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\keytool.exe"
    if (Test-Path -LiteralPath $candidate) { $keytool = $candidate }
}
if (-not $keytool) {
    $keytool = Get-ChildItem -LiteralPath (Join-Path $repoRoot ".tooling\jdk") -Recurse -Filter "keytool.exe" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $keytool) {
    throw "keytool was not found. Configure a JDK first."
}

function Convert-SecureStringToPlainText([Security.SecureString]$value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

Write-Host "Set a release-signing password. Input is hidden. Use at least 16 characters and save it in your password manager."
$first = Read-Host "Signing password" -AsSecureString
$second = Read-Host "Enter it again" -AsSecureString
$password = Convert-SecureStringToPlainText $first
$confirmation = Convert-SecureStringToPlainText $second

try {
    if ($password.Length -lt 16) { throw "The password must contain at least 16 characters." }
    if ($password -cne $confirmation) { throw "The passwords do not match." }

    New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
    & $keytool -genkeypair -v -storetype PKCS12 -keystore $keystorePath -storepass $password -keypass $password `
        -alias omnisms -keyalg RSA -keysize 4096 -validity 9125 `
        -dname "CN=OmniSMS Personal Release, OU=Personal, O=OmniSMS, L=Private, ST=Private, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed to create the release keystore." }

    $lines = @(
        "storeFile=../.release/omnisms-release.jks",
        "storePassword=$password",
        "keyAlias=omnisms",
        "keyPassword=$password"
    )
    [IO.File]::WriteAllLines($propertiesPath, $lines, [Text.UTF8Encoding]::new($false))
    Write-Host "release_signing_setup=success"
    Write-Host "Keystore: $keystorePath"
    Write-Host "Local config: $propertiesPath"
    Write-Host "Back up the keystore and its password separately now."
}
finally {
    $password = $null
    $confirmation = $null
}
