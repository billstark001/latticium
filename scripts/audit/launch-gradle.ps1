#Requires -Version 7.2
param(
    [Parameter(Mandatory)][string]$RepoRoot,
    [Parameter(Mandatory)][string]$Task,
    [Parameter(Mandatory)][string]$WorkDirectory,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][string]$LogPath,
    [string]$SchematicsDirectory = '',
    [string]$DisableMods = ''
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $RepoRoot
$auditArguments = @($Task, '--no-daemon', '--init-script', (Join-Path $PSScriptRoot 'audit.init.gradle'),
    "-PauditWorkDir=$WorkDirectory", "-PauditOutput=$OutputDirectory", '--console=plain')
if ($SchematicsDirectory) { $auditArguments += "-PauditSchematics=$SchematicsDirectory" }
if ($DisableMods) { $auditArguments += "-PauditDisableMods=$DisableMods" }
& (Join-Path $RepoRoot 'gradlew.bat') @auditArguments *> $LogPath
exit $LASTEXITCODE
