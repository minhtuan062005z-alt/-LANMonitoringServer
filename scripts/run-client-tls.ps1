[CmdletBinding()]
param(
    [string]$TlsDirectory = (Join-Path $env:LOCALAPPDATA 'LANMonitoringServer\tls'),
    [ValidateNotNullOrEmpty()]
    [string]$ServerIdentity = 'lanmonitoring-server.local',
    [Security.SecureString]$Password,
    [string[]]$JavaOption = @()
)

$ErrorActionPreference = 'Stop'
$applicationHome = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $applicationHome 'LANMonitoringServer.jar'
$trustStore = Join-Path $TlsDirectory 'client-truststore.p12'
foreach ($path in @($jarPath, $trustStore)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file not found: $path" }
}
if ($null -eq $Password) { $Password = Read-Host 'Client truststore password' -AsSecureString }
$secretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
try {
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPointer)
    Remove-Item Env:LANMONITORING_TLS_KEY_STORE -ErrorAction SilentlyContinue
    Remove-Item Env:LANMONITORING_TLS_KEY_STORE_PASSWORD -ErrorAction SilentlyContinue
    $env:LANMONITORING_TLS_TRUST_STORE = (Resolve-Path -LiteralPath $trustStore).Path
    $env:LANMONITORING_TLS_TRUST_STORE_PASSWORD = $plainPassword
    $env:LANMONITORING_TLS_SERVER_IDENTITY = $ServerIdentity
    Write-Host "TLS will connect to the IPv4 entered in the GUI and verify certificate identity: $ServerIdentity"
    $classPath = "$jarPath$([IO.Path]::PathSeparator)$(Join-Path $applicationHome 'lib\*')"
    & java @JavaOption -cp $classPath lanmonitoringserver.LANMonitoringServer --client
    if ($LASTEXITCODE -ne 0) { throw "Client exited with code $LASTEXITCODE" }
} finally {
    Remove-Item Env:LANMONITORING_TLS_TRUST_STORE -ErrorAction SilentlyContinue
    Remove-Item Env:LANMONITORING_TLS_TRUST_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:LANMONITORING_TLS_SERVER_IDENTITY -ErrorAction SilentlyContinue
    if ($null -ne $plainPassword) { $plainPassword = $null }
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPointer)
}
