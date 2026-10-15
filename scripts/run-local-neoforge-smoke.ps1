#Requires -Version 7.2
<# Uses isolated MineDriver clients to prove title-screen readiness and preserve fresh reports. #>
[CmdletBinding()]
param(
    [ValidateRange(30, 3600)][int]$TimeoutSeconds = 180,
    [string]$MineDriverRoot = (Join-Path (Split-Path -Parent $PSScriptRoot) '../minedriver'),
    [switch]$AcceptClothLogoWarning,
    [switch]$AcceptKnownLogoWarnings
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'run-minedriver-audit.ps1') `
    -Targets neoforge-26.2,neoforge-26.3,forgematica-neoforge-26.2 `
    -Scenario StartupScenario -TimeoutSeconds $TimeoutSeconds `
    -MineDriverRoot $MineDriverRoot -AcceptClothLogoWarning:$AcceptClothLogoWarning `
    -AcceptKnownLogoWarnings:$AcceptKnownLogoWarnings
