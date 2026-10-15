#Requires -Version 7.2
<# Runs the typed gameplay/UI matrix in MineDriver-owned directories; optional schematics stay read-only. #>
[CmdletBinding()]
param(
    [ValidateSet('fabric-26.2', 'fabric-26.3', 'neoforge-26.2', 'neoforge-26.3',
        'litematica-fabric-26.2', 'litematica-fabric-26.3', 'forgematica-neoforge-26.2')]
    [string[]]$Targets = @('fabric-26.2', 'fabric-26.3', 'neoforge-26.2', 'neoforge-26.3',
        'litematica-fabric-26.2', 'litematica-fabric-26.3', 'forgematica-neoforge-26.2'),
    [string]$MineDriverRoot = (Join-Path (Split-Path -Parent $PSScriptRoot) '../minedriver'),
    [string]$SchematicsDirectory = '',
    [ValidateSet('LatticiumScenario', 'UiScenario', 'StartupScenario', 'ActivationScenario', 'ResidencyScenario', 'BridgeConcurrencyScenario')]
    [string]$Scenario = 'LatticiumScenario',
    [ValidateRange(30, 3600)][int]$TimeoutSeconds = 600,
    [switch]$AcceptClothLogoWarning,
    [switch]$AcceptKnownLogoWarnings
)
$ErrorActionPreference = 'Stop'
$auditRoot = Split-Path -Parent $PSScriptRoot
$MineDriverRoot = (Resolve-Path -LiteralPath $MineDriverRoot).Path
$auditOutput = Join-Path $auditRoot ('build/minedriver-audit/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $auditOutput -Force | Out-Null
$auditResults = @()

function Get-FixtureManifest {
    if (-not $SchematicsDirectory) { return @() }
    return @(Get-ChildItem -LiteralPath $SchematicsDirectory -File -Recurse -Force | Sort-Object FullName | ForEach-Object {
        [pscustomobject]@{ path = $_.FullName; length = $_.Length; ticks = $_.LastWriteTimeUtc.Ticks;
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
    })
}

if ($SchematicsDirectory) { $SchematicsDirectory = (Resolve-Path -LiteralPath $SchematicsDirectory).Path }
$auditBefore = @(Get-FixtureManifest)
ConvertTo-Json -InputObject $auditBefore -Depth 4 | Set-Content -LiteralPath (Join-Path $auditOutput 'schematics-before.json') -Encoding utf8
try {
    Push-Location -LiteralPath $MineDriverRoot
    try {
        & (Join-Path $MineDriverRoot 'gradlew.bat') ':gradle-plugin:jar' '--console=plain' *> (Join-Path $auditOutput 'prepare.log')
        if ($LASTEXITCODE -ne 0) { throw "MineDriver build failed; see $auditOutput/prepare.log" }
    } finally { Pop-Location }
    Push-Location -LiteralPath $auditRoot
    try {
        foreach ($auditTarget in $Targets) {
            $auditKind = if ($auditTarget -match '^(fabric|neoforge)-') { 'versions' } else { 'integrations' }
            $auditPointerPath = Join-Path $auditRoot "$auditKind/$auditTarget/build/reports/minedriver/mineDriverCheck/latest.json"
            $auditPreviousId = if (Test-Path -LiteralPath $auditPointerPath) {
                (Get-Content -LiteralPath $auditPointerPath -Raw | ConvertFrom-Json).runId
            } else { $null }
            $auditArguments = @('-I', (Join-Path $PSScriptRoot 'minedriver.init.gradle'),
                "-Dminedriver.root=$MineDriverRoot", "-Dminedriver.target=$auditTarget",
                "-Dminedriver.scenario=$Scenario", "-Dminedriver.timeoutMillis=$($TimeoutSeconds * 1000)",
                ":${auditKind}:${auditTarget}:mineDriverCheck", '--console=plain')
            if ($auditTarget -match 'fabric') { $auditArguments += '-Dminedriver.disableGametest=true' }
            if (($AcceptClothLogoWarning -or $AcceptKnownLogoWarnings) -and $auditTarget -match 'neoforge-26.2$') {
                $auditArguments += '-Dminedriver.acceptClothLogoWarning=true'
            }
            if ($AcceptKnownLogoWarnings -and $auditTarget -eq 'forgematica-neoforge-26.2') {
                $auditArguments += '-Dminedriver.acceptBridgeLogoWarnings=true'
            }
            if ($SchematicsDirectory -and $auditKind -eq 'integrations' -and $Scenario -eq 'LatticiumScenario') {
                $auditArguments += "-Dminedriver.schematics=$SchematicsDirectory"
            }
            Write-Host "Testing $auditTarget; log: $auditOutput/$auditTarget.log"
            & (Join-Path $auditRoot 'gradlew.bat') @auditArguments *> (Join-Path $auditOutput "$auditTarget.log")
            $auditExitCode = $LASTEXITCODE
            $auditPointer = if (Test-Path -LiteralPath $auditPointerPath) {
                Get-Content -LiteralPath $auditPointerPath -Raw | ConvertFrom-Json
            } else { $null }
            $auditResultPath = if ($auditPointer) { Join-Path $auditPointer.directory 'result.json' } else { $null }
            $auditResult = if ($auditResultPath -and (Test-Path -LiteralPath $auditResultPath)) {
                Get-Content -LiteralPath $auditResultPath -Raw | ConvertFrom-Json
            } else { $null }
            $auditFresh = $auditPointer -and $auditResult -and $auditPointer.runId -ne $auditPreviousId -and
                $auditResult.runId -eq $auditPointer.runId -and $auditResult.complete -eq $true
            $auditFailure = $null
            if ($auditExitCode -ne 0) {
                $auditFailure = if ($auditFresh -and $auditResult.failure) { $auditResult.failure.message }
                    else { 'Gradle failed without a fresh complete agent report' }
            } elseif (-not $auditFresh -or $auditResult.status -ne 'PASS') {
                $auditFailure = 'Missing fresh complete PASS report'
            } elseif ($SchematicsDirectory -and $auditKind -eq 'integrations' -and $Scenario -eq 'LatticiumScenario') {
                $auditChecksPath = Join-Path $auditPointer.directory 'result.txt'
                if (-not (Test-Path -LiteralPath $auditChecksPath) -or
                    (Get-Content -LiteralPath $auditChecksPath -Raw) -notmatch 'PASS: read-only real schematics:') {
                    $auditFailure = 'Requested real schematic checks did not execute'
                }
            }
            $auditResults += [pscustomobject]@{ target = $auditTarget; scenario = $Scenario;
                runId = $auditPointer.runId; result = $auditResultPath;
                status = if ($auditFailure) { 'FAIL' } else { 'PASS' }; agentStatus = $auditResult.status;
                fresh = [bool]$auditFresh; exitCode = $auditExitCode; failure = $auditFailure }
            ConvertTo-Json -InputObject $auditResults -Depth 4 | Set-Content -LiteralPath (Join-Path $auditOutput 'summary.json') -Encoding utf8
            if ($auditFailure) { throw "MineDriver check failed: $auditTarget ($auditFailure); see $auditOutput/$auditTarget.log" }
            Write-Host "PASS: $auditTarget ($($auditPointer.runId))"
        }
    } finally { Pop-Location }
} finally {
    $auditAfter = @(Get-FixtureManifest)
    ConvertTo-Json -InputObject $auditAfter -Depth 4 | Set-Content -LiteralPath (Join-Path $auditOutput 'schematics-after.json') -Encoding utf8
    if ((ConvertTo-Json -InputObject $auditBefore -Depth 4 -Compress) -cne (ConvertTo-Json -InputObject $auditAfter -Depth 4 -Compress)) {
        throw "Read-only schematic fixtures changed; see $auditOutput/schematics-*.json"
    }
}
Write-Host "Validated $($auditResults.Count) targets; summary: $auditOutput/summary.json"
