#Requires -Version 7.2
<# Owns a disposable loopback vanilla server and runs real remote-client acceptance. #>
[CmdletBinding()]
param(
    [ValidateSet('26.2','26.3')][string]$Version = '26.3',
    [string[]]$Targets = @('neoforge-26.3'),
    [string]$ServerJar = '',
    [switch]$AcceptKnownLogoWarnings
)
$ErrorActionPreference = 'Stop'
$auditRoot = Split-Path -Parent $PSScriptRoot
if (-not $ServerJar) { $ServerJar = Join-Path $auditRoot ".tmp/minecraft-official/$Version/server.jar" }
$ServerJar = (Resolve-Path -LiteralPath $ServerJar).Path
if ($Targets | Where-Object { -not $_.EndsWith($Version) }) { throw 'Every target must match the server version' }
$serverOutput = Join-Path $auditRoot ('build/dedicated-audit/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $serverOutput -Force | Out-Null
function Get-LoopbackPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}
$serverPort = Get-LoopbackPort
$rconPort = Get-LoopbackPort
$rconPassword = [Guid]::NewGuid().ToString('N')
@"
eula=true
"@ | Set-Content -LiteralPath (Join-Path $serverOutput 'eula.txt') -Encoding ascii
@"
server-ip=127.0.0.1
server-port=$serverPort
online-mode=false
white-list=false
enforce-whitelist=false
enable-rcon=true
rcon.port=$rconPort
rcon.password=$rconPassword
view-distance=3
simulation-distance=3
spawn-protection=0
level-name=audit-world
level-seed=20261016
max-players=2
difficulty=peaceful
motd=Latticium isolated acceptance
"@ | Set-Content -LiteralPath (Join-Path $serverOutput 'server.properties') -Encoding ascii
$clientConfiguration = Join-Path $serverOutput 'client.properties'
@"
address=127.0.0.1:$serverPort
port=$rconPort
password=$rconPassword
"@ | Set-Content -LiteralPath $clientConfiguration -Encoding ascii
$javaExecutable = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
$serverProcess = Start-Process -FilePath $javaExecutable -ArgumentList @('-Xms512M','-Xmx2G','-jar',('"' + $ServerJar + '"'),'--nogui') -WorkingDirectory $serverOutput -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $serverOutput 'server.log') -RedirectStandardError (Join-Path $serverOutput 'server-error.log')
try {
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    do {
        if ($serverProcess.HasExited) { throw "Dedicated server exited; see $serverOutput/server.log" }
        $ready = (Test-Path -LiteralPath (Join-Path $serverOutput 'server.log')) -and
            ((Get-Content -LiteralPath (Join-Path $serverOutput 'server.log') -Raw) -match 'RCON running on')
        if (-not $ready) { Start-Sleep -Milliseconds 500 }
    } until ($ready -or [DateTime]::UtcNow -gt $deadline)
    if (-not $ready) { throw "Dedicated server startup timed out; see $serverOutput/server.log" }
    & (Join-Path $PSScriptRoot 'run-minedriver-audit.ps1') -Targets $Targets -Scenario DedicatedScenario -ServerConfiguration $clientConfiguration -AcceptKnownLogoWarnings:$AcceptKnownLogoWarnings
    [pscustomobject]@{ version=$Version; targets=$Targets; status='PASS'; serverJar=$ServerJar; sha256=(Get-FileHash -LiteralPath $ServerJar).Hash } |
        ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $serverOutput 'summary.json') -Encoding utf8
} finally {
    # Only the process launched above is owned by this invocation; never stop other Java processes.
    if (-not $serverProcess.HasExited) { Stop-Process -InputObject $serverProcess }
    # Keep reports and the disposable world, but remove the short-lived RCON credential.
    Remove-Item -LiteralPath $clientConfiguration -ErrorAction SilentlyContinue
    $propertiesPath = Join-Path $serverOutput 'server.properties'
    if (Test-Path -LiteralPath $propertiesPath) {
        (Get-Content -LiteralPath $propertiesPath) -replace '^rcon.password=.*$', 'rcon.password=expired' | Set-Content -LiteralPath $propertiesPath -Encoding ascii
    }
}
Write-Host "Dedicated acceptance evidence: $serverOutput"
