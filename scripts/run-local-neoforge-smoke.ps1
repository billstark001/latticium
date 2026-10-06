[CmdletBinding()]
param([int]$TimeoutSeconds = 180)

$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'The NeoForge smoke runner uses Windows process-tree APIs.' }
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$wrapper = Join-Path $projectRoot 'gradlew.bat'
$logDirectory = Join-Path $projectRoot 'build/neoforge-smoke-logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

$targets = @(
    @{ Module = 'versions:neoforge-26.2'; Project = 'versions/neoforge-26.2'; Mods = @('latticium', 'cloth_config') },
    @{ Module = 'versions:neoforge-26.3'; Project = 'versions/neoforge-26.3'; Mods = @('latticium', 'cloth_config') },
    @{ Module = 'integrations:forgematica-neoforge-26.2'; Project = 'integrations/forgematica-neoforge-26.2'; Mods = @('latticium', 'cloth_config', 'latticium_forgematica_bridge', 'forgematica') }
)

function Stop-ProcessTree {
    param([int]$ProcessId)
    foreach ($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $ProcessId" -ErrorAction SilentlyContinue)) {
        Stop-ProcessTree -ProcessId $child.ProcessId
    }
    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

foreach ($target in $targets) {
    $startedAt = Get-Date
    $name = $target.Module.Replace(':', '-')
    $runId = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $stdout = Join-Path $logDirectory "$name-$runId.out.log"
    $stderr = Join-Path $logDirectory "$name-$runId.err.log"
    $log = Join-Path $projectRoot "$($target.Project)/run/logs/latest.log"
    $arguments = @(
        ":$($target.Module):runClient", '--console=plain', '--no-daemon',
        '--project-cache-dir', (Join-Path $projectRoot "build/neoforge-smoke-cache/$name")
    )
    Write-Host "Starting $($target.Module)..."
    $process = Start-Process -FilePath $wrapper -ArgumentList $arguments `
        -WorkingDirectory $projectRoot -WindowStyle Hidden `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    $passed = $false
    $failure = "Timed out after $TimeoutSeconds seconds; inspect $log and $stdout."
    try {
        while ((Get-Date) -lt $startedAt.AddSeconds($TimeoutSeconds)) {
            $process.Refresh()
            if ($process.HasExited) {
                $failure = "Gradle exited with code $($process.ExitCode); inspect $stdout and $stderr."
                break
            }
            if (Test-Path -LiteralPath $log) {
                $logItem = Get-Item -LiteralPath $log
                if ($logItem.LastWriteTime -ge $startedAt) {
                    $content = Get-Content -LiteralPath $log -Raw
                    if ($content -match 'InjectionError|InvalidInjection|MixinApplyError|MixinTransformerError') {
                        $failure = "Mixin failure in $log."
                        break
                    }
                    $modsLoaded = @($target.Mods | Where-Object { $content -notmatch "mod/$_(?:\b|,)" }).Count -eq 0
                    if ($modsLoaded -and $content -match 'Sound engine started|SoundEngine.*started') {
                        $passed = $true
                        break
                    }
                }
            }
            Start-Sleep -Seconds 2
        }
    } finally {
        $process.Refresh()
        if (-not $process.HasExited) {
            Stop-ProcessTree -ProcessId $process.Id
            $process.WaitForExit(10000) | Out-Null
        }
    }
    if (-not $passed) { throw "$($target.Module) smoke test failed: $failure" }
    Write-Host "$($target.Module) reached client startup."
}
