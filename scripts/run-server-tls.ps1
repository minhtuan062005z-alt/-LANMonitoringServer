[CmdletBinding()]
param(
    [string]$TlsDirectory = (Join-Path $env:LOCALAPPDATA 'LANMonitoringServer\tls'),
    [Security.SecureString]$Password,
    [string[]]$JavaOption = @()
)

$ErrorActionPreference = 'Stop'
$applicationHome = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $applicationHome 'LANMonitoringServer.jar'
$keyStore = Join-Path $TlsDirectory 'server-keystore.p12'
$trustStore = Join-Path $TlsDirectory 'client-truststore.p12'
foreach ($path in @($jarPath, $keyStore, $trustStore)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file not found: $path" }
}
if ($null -eq $Password) { $Password = Read-Host 'Server PKCS12 password' -AsSecureString }
$secretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
try {
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPointer)
    $env:LANMONITORING_TLS_KEY_STORE = (Resolve-Path -LiteralPath $keyStore).Path
    $env:LANMONITORING_TLS_KEY_STORE_PASSWORD = $plainPassword
    $env:LANMONITORING_TLS_TRUST_STORE = (Resolve-Path -LiteralPath $trustStore).Path
    $env:LANMONITORING_TLS_TRUST_STORE_PASSWORD = $plainPassword
    $classPath = "$jarPath$([IO.Path]::PathSeparator)$(Join-Path $applicationHome 'lib\*')"
    & java @JavaOption -cp $classPath lanmonitoringserver.LANMonitoringServer --server
    if ($LASTEXITCODE -ne 0) { throw "Server exited with code $LASTEXITCODE" }
} finally {
    Remove-Item Env:LANMONITORING_TLS_KEY_STORE -ErrorAction SilentlyContinue
    Remove-Item Env:LANMONITORING_TLS_KEY_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:LANMONITORING_TLS_TRUST_STORE -ErrorAction SilentlyContinue
    Remove-Item Env:LANMONITORING_TLS_TRUST_STORE_PASSWORD -ErrorAction SilentlyContinue
    if ($null -ne $plainPassword) { $plainPassword = $null }
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPointer)
}
