#Requires -Version 7.2
<#
Runs development-only probes in new creative flat worlds. Source schematics are read-only fixtures;
they are not copied into the repository or saved by the probe. Requires a working Java 25 toolchain.
#>
[CmdletBinding()]
param(
    [ValidateSet('fabric-26.2', 'fabric-26.3', 'neoforge-26.2', 'neoforge-26.3',
        'litematica-fabric-26.2', 'litematica-fabric-26.3', 'forgematica-neoforge-26.2')]
    [string[]]$Targets = @('fabric-26.2', 'fabric-26.3', 'neoforge-26.2', 'neoforge-26.3'),
    [string]$SchematicsDirectory = '',
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidateRange(1, 60)][int]$TimeoutMinutes = 12
)
$ErrorActionPreference = 'Stop'
$auditRoot = Split-Path -Parent $PSScriptRoot
$auditWork = Join-Path $auditRoot ('build/gameplay-audit/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$auditSources = Join-Path $PSScriptRoot 'audit'
$auditClasses = Join-Path $auditWork 'classes'
New-Item -ItemType Directory -Path $auditClasses -Force | Out-Null
if (-not $JavaHome) { throw 'Set JAVA_HOME to Java 25 or pass -JavaHome.' }
$auditJavac = Join-Path $JavaHome 'bin/javac.exe'
$auditJar = Join-Path $JavaHome 'bin/jar.exe'
$auditVersion = & $auditJavac -version 2>&1
if ($LASTEXITCODE -ne 0 -or "$auditVersion" -notmatch 'javac 25[.]') {
    throw "The audit requires Java 25; found: $auditVersion"
}
$auditManifest = @()
if ($SchematicsDirectory) {
    $SchematicsDirectory = (Resolve-Path -LiteralPath $SchematicsDirectory).Path
    $auditManifest = @(Get-ChildItem -LiteralPath $SchematicsDirectory -File -Recurse | ForEach-Object {
        [pscustomobject]@{ path = $_.FullName; length = $_.Length; ticks = $_.LastWriteTimeUtc.Ticks;
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
    })
    $auditManifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $auditWork 'schematics-before.json') -Encoding utf8
}
function Stop-OwnedAuditClient([string]$OutputDirectory, [string]$AgentPath) {
    $auditIdentityPath = Join-Path $OutputDirectory 'client-process.properties'
    if (-not (Test-Path -LiteralPath $auditIdentityPath)) { return }
    $auditIdentity = ConvertFrom-StringData (Get-Content -LiteralPath $auditIdentityPath -Raw)
    $auditClientId = 0
    $auditClientStarted = 0L
    if (-not [int]::TryParse($auditIdentity['pid'], [ref]$auditClientId) -or $auditClientId -le 0 -or
        -not [long]::TryParse($auditIdentity['started'], [ref]$auditClientStarted)) { return }
    $auditNative = Get-Process -Id $auditClientId -ErrorAction SilentlyContinue
    if ($null -eq $auditNative) { return }
    try {
        $auditActualStart = [DateTimeOffset]::new($auditNative.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
        if ([Math]::Abs($auditActualStart - $auditClientStarted) -gt 1000) { return }
        $auditCommand = (Get-CimInstance Win32_Process -Filter "ProcessId = $auditClientId").CommandLine
        if ($auditCommand -and $auditCommand.Contains("-javaagent:$AgentPath") -and
            $auditCommand.Contains("-Dlatticium.audit.output=$OutputDirectory")) {
            $auditNative.Kill($true)
        }
    } finally { $auditNative.Dispose() }
}

try {
    Push-Location -LiteralPath $auditRoot
    try {
        & (Join-Path $auditRoot 'gradlew.bat') ':versions:shared-mc-26.2:writeAuditClasspath' '--init-script' (Join-Path $auditSources 'audit.init.gradle') "-PauditWorkDir=$auditWork" '--console=plain' *> (Join-Path $auditWork 'prepare.log')
        if ($LASTEXITCODE -ne 0) { throw "Audit preparation failed; see $auditWork/prepare.log" }
    } finally { Pop-Location }
    $auditClasspath = Get-Content -LiteralPath (Join-Path $auditWork 'classpath.txt') -Raw
    $auditJavaFiles = @(Get-ChildItem -LiteralPath $auditSources -Filter '*.java' | ForEach-Object FullName)
    & $auditJavac '-encoding' 'UTF-8' '-Xlint:all,-serial,-classfile' '-Werror' '-cp' $auditClasspath '-d' $auditClasses @auditJavaFiles
    if ($LASTEXITCODE -ne 0) { throw 'Audit probe compilation failed.' }
    $auditAgentManifest = Join-Path $auditWork 'agent-manifest.mf'
    Set-Content -LiteralPath $auditAgentManifest -Value "Premain-Class: AuditAgent`n" -Encoding ascii
    & $auditJar 'cfm' (Join-Path $auditWork 'audit-agent.jar') $auditAgentManifest '-C' $auditClasses 'AuditAgent.class'
    if ($LASTEXITCODE -ne 0) { throw 'Audit agent packaging failed.' }
    foreach ($auditTarget in $Targets) {
        $auditOutput = Join-Path $auditWork $auditTarget
        New-Item -ItemType Directory -Path $auditOutput -Force | Out-Null
        $auditProject = if ($auditTarget -match '^(fabric|neoforge)-') { ':versions:' } else { ':integrations:' }
        $auditStart = [Diagnostics.ProcessStartInfo]::new()
        $auditStart.FileName = (Get-Process -Id $PID).Path
        $auditStart.UseShellExecute = $false
        $auditStart.CreateNoWindow = $true
        $auditArguments = @('-NoProfile', '-File', (Join-Path $auditSources 'launch-gradle.ps1'),
            '-RepoRoot', $auditRoot, '-Task', "${auditProject}${auditTarget}:runClient",
            '-WorkDirectory', $auditWork, '-OutputDirectory', $auditOutput,
            '-LogPath', (Join-Path $auditOutput 'client.log'))
        if ($SchematicsDirectory) { $auditArguments += @('-SchematicsDirectory', $SchematicsDirectory) }
        if ($auditTarget -match 'fabric') {
            # Fabric's development gametest initializer currently fails during world creation.
            $auditArguments += @('-DisableMods', 'modmenu,fabric-gametest-api-v1,fabric-client-gametest-api-v1')
        }
        foreach ($auditArgument in $auditArguments) { $auditStart.ArgumentList.Add($auditArgument) }
        $auditProcess = [Diagnostics.Process]::Start($auditStart)
        Write-Host "Testing $auditTarget; output: $auditOutput"
        $auditDeadline = [DateTime]::UtcNow.AddMinutes($TimeoutMinutes)
        try {
            while (-not $auditProcess.WaitForExit(1000)) {
                if ([DateTime]::UtcNow -gt $auditDeadline) {
                    # Verify the agent's PID, start time and unique launch arguments before stopping it.
                    try { Stop-OwnedAuditClient $auditOutput (Join-Path $auditWork 'audit-agent.jar') }
                    finally {
                        # Single-use Gradle keeps this launch separate from other builds.
                        if (-not $auditProcess.HasExited) { $auditProcess.Kill($true) }
                    }
                    throw "Audit timeout: $auditTarget"
                }
            }
            if ($auditProcess.ExitCode -ne 0) { throw "Client launch failed: $auditTarget; see client.log" }
        } finally { $auditProcess.Dispose() }
        $auditResult = Get-Content -LiteralPath (Join-Path $auditOutput 'result.txt') -Raw
        if ($auditResult -notmatch 'PASS: all audit checks' -or $auditResult -match 'FAIL:') {
            throw "Gameplay checks failed: $auditTarget; see result.txt"
        }
        Write-Host $auditResult
    }
} finally {
    foreach ($auditEntry in $auditManifest) {
        $auditCurrent = Get-Item -LiteralPath $auditEntry.path
        if ($auditCurrent.Length -ne $auditEntry.length -or $auditCurrent.LastWriteTimeUtc.Ticks -ne $auditEntry.ticks -or
            (Get-FileHash -LiteralPath $auditEntry.path -Algorithm SHA256).Hash -ne $auditEntry.sha256) {
            throw "Read-only fixture changed: $($auditEntry.path)"
        }
    }
    if ($auditManifest.Count -gt 0) { Write-Host "Verified $($auditManifest.Count) source files unchanged." }
    Write-Host "Audit artifacts: $auditWork"
}
