[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$ServerIdentity = 'lanmonitoring-server.local',

    [ValidateNotNullOrEmpty()]
    [string]$ServerDnsName,

    [string]$ServerIpAddress,

    [string]$OutputDirectory = (Join-Path $env:LOCALAPPDATA 'LANMonitoringServer\tls'),

    [Security.SecureString]$Password
)

$ErrorActionPreference = 'Stop'

$dnsPattern = '^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$'
if ($ServerIdentity -notmatch $dnsPattern -or $ServerIdentity -match '^[0-9.]+$') {
    throw "ServerIdentity must be a stable DNS name, not an IP address."
}
if ($ServerDnsName -and $ServerDnsName -notmatch $dnsPattern) {
    throw "ServerDnsName is not a valid DNS name."
}
if ($ServerIpAddress) {
    $parsedAddress = $null
    if (-not [Net.IPAddress]::TryParse($ServerIpAddress, [ref]$parsedAddress)) {
        throw "ServerIpAddress must be a literal IPv4 or IPv6 address."
    }
}

$keytool = Get-Command keytool -ErrorAction Stop
if (-not (Test-Path -LiteralPath $OutputDirectory)) {
    New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
}
$tlsDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
$serverStore = Join-Path $tlsDirectory 'server-keystore.p12'
$publicCertificate = Join-Path $tlsDirectory 'server.cer'
$clientTrustStore = Join-Path $tlsDirectory 'client-truststore.p12'

foreach ($path in @($serverStore, $publicCertificate, $clientTrustStore)) {
    if (Test-Path -LiteralPath $path) {
        throw "Refusing to overwrite existing TLS material: $path"
    }
}

if ($null -eq $Password) {
    $Password = Read-Host 'New PKCS12 password (minimum 12 characters)' -AsSecureString
}
$secretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
try {
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPointer)
    if ($plainPassword.Length -lt 12) {
        throw 'PKCS12 password must contain at least 12 characters.'
    }
    $env:LANMONITORING_TLS_SETUP_PASSWORD = $plainPassword

    $sanEntries = [Collections.Generic.List[string]]::new()
    $sanEntries.Add("dns:$ServerIdentity")
    if ($ServerDnsName -and $ServerDnsName -ne $ServerIdentity) { $sanEntries.Add("dns:$ServerDnsName") }
    if ($ServerIpAddress) { $sanEntries.Add("ip:$ServerIpAddress") }
    $subjectAlternativeNames = $sanEntries -join ','

    & $keytool.Source -genkeypair -alias lan-server -storetype PKCS12 `
        -keystore $serverStore -storepass:env LANMONITORING_TLS_SETUP_PASSWORD `
        -keypass:env LANMONITORING_TLS_SETUP_PASSWORD -keyalg EC -groupname secp256r1 `
        -sigalg SHA256withECDSA -validity 825 -dname "CN=$ServerIdentity" `
        -ext "SAN=$subjectAlternativeNames" -ext 'EKU=serverAuth'
    if ($LASTEXITCODE -ne 0) { throw "keytool certificate generation failed with exit code $LASTEXITCODE" }

    & $keytool.Source -exportcert -alias lan-server -storetype PKCS12 `
        -keystore $serverStore -storepass:env LANMONITORING_TLS_SETUP_PASSWORD `
        -file $publicCertificate
    if ($LASTEXITCODE -ne 0) { throw "keytool certificate export failed with exit code $LASTEXITCODE" }

    & $keytool.Source -importcert -noprompt -alias lan-server -storetype PKCS12 `
        -keystore $clientTrustStore -storepass:env LANMONITORING_TLS_SETUP_PASSWORD `
        -file $publicCertificate
    if ($LASTEXITCODE -ne 0) { throw "keytool truststore creation failed with exit code $LASTEXITCODE" }

    $windowsIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $serverStore '/grant:r' "${windowsIdentity}:(F)" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to grant the current Windows identity access to the Server keystore." }
    & icacls.exe $serverStore '/inheritance:r' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to remove inherited ACLs from the Server keystore." }

    Write-Host "TLS material created in: $tlsDirectory"
    Write-Host "Stable TLS Server identity: $ServerIdentity"
    Write-Host "Certificate SAN: $subjectAlternativeNames"
    Write-Host 'Changing the TCP IPv4 does not require reissuing this certificate.'
    Write-Host 'Keep server-keystore.p12 only on the Server.'
    Write-Host 'Copy only client-truststore.p12 to the same protected TLS directory on each Client.'
} finally {
    Remove-Item Env:LANMONITORING_TLS_SETUP_PASSWORD -ErrorAction SilentlyContinue
    if ($null -ne $plainPassword) { $plainPassword = $null }
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPointer)
}
