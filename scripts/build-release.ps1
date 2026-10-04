[CmdletBinding()]
param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$versionLine = @(Get-Content -LiteralPath (Join-Path $projectRoot 'gradle.properties') |
    Where-Object { $_ -match '^mod_version=' })
if ($versionLine.Count -ne 1) { throw 'Expected exactly one mod_version in gradle.properties.' }
$version = $versionLine[0].Substring('mod_version='.Length).Trim()
if ($version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$') {
    throw "Invalid mod_version: $version"
}

$targets = @(
    @{ Project = 'versions/fabric-26.2'; Name = 'latticium-fabric-26.2' },
    @{ Project = 'versions/fabric-26.3'; Name = 'latticium-fabric-26.3' },
    @{ Project = 'versions/neoforge-26.2'; Name = 'latticium-neoforge-26.2' },
    @{ Project = 'versions/neoforge-26.3'; Name = 'latticium-neoforge-26.3' },
    @{ Project = 'integrations/litematica-fabric-26.2'; Name = 'latticium-litematica-fabric-26.2' },
    @{ Project = 'integrations/litematica-fabric-26.3'; Name = 'latticium-litematica-fabric-26.3' },
    @{ Project = 'integrations/forgematica-neoforge-26.2'; Name = 'latticium-forgematica-neoforge-26.2' }
)

if (-not $SkipBuild) {
    $wrapper = Join-Path $projectRoot $(if ($IsWindows) { 'gradlew.bat' } else { 'gradlew' })
    & $wrapper buildAll --no-parallel --stacktrace
    if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE." }
}

# Verify all inputs before touching an existing release directory.
$artifacts = foreach ($target in $targets) {
    $path = Join-Path $projectRoot "$($target.Project)/build/libs/$($target.Name)-$version.jar"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing distributable JAR: $path" }
    Get-Item -LiteralPath $path
}
if (@($artifacts).Count -ne 7 -or @($artifacts | Select-Object -ExpandProperty Name -Unique).Count -ne 7) {
    throw 'Expected seven uniquely named distributable JARs.'
}

$distributionDirectory = Join-Path $projectRoot 'build/release'
$buildDirectory = Join-Path $projectRoot 'build'
if (-not [IO.Path]::GetFullPath($distributionDirectory).StartsWith(
    [IO.Path]::GetFullPath($buildDirectory) + [IO.Path]::DirectorySeparatorChar,
    [StringComparison]::OrdinalIgnoreCase)) {
    throw "Release directory escapes the build directory: $distributionDirectory"
}
if (Test-Path -LiteralPath $distributionDirectory) {
    Remove-Item -LiteralPath $distributionDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path $distributionDirectory -Force | Out-Null
foreach ($artifact in $artifacts) {
    Copy-Item -LiteralPath $artifact.FullName -Destination $distributionDirectory
}

$hashLines = foreach ($artifact in $artifacts) {
    $hash = (Get-FileHash -LiteralPath (Join-Path $distributionDirectory $artifact.Name) -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $($artifact.Name)"
}
[IO.File]::WriteAllLines((Join-Path $distributionDirectory 'SHA256SUMS.txt'), [string[]]$hashLines)
Write-Host "Collected seven version $version distributable JARs in $distributionDirectory"
$artifacts | ForEach-Object { Write-Host " - $($_.Name)" }
