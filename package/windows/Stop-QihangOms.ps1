$ErrorActionPreference = 'Stop'
$appHome = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$stateFile = Join-Path $appHome 'config\processes.json'
if (-not [IO.File]::Exists($stateFile)) { Write-Host 'No process state exists for this installation.'; exit 0 }

$owned = Get-Content -Raw $stateFile | ConvertFrom-Json
foreach ($entry in $owned) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)" -ErrorAction SilentlyContinue
    if (-not $process) { continue }
    $actual = if ($process.ExecutablePath) { [IO.Path]::GetFullPath($process.ExecutablePath) } else { '' }
    $expected = [IO.Path]::GetFullPath([string]($entry.executable))
    if (-not $actual.Equals($expected, [StringComparison]::OrdinalIgnoreCase) -or
        ([bool]($entry.requireMarker) -and $process.CommandLine -notlike "*$appHome*")) {
        Write-Warning "Skipped PID $($entry.pid): process is not owned by this installation"
        continue
    }
    if ($expected.EndsWith('mysqld.exe',[StringComparison]::OrdinalIgnoreCase)) {
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'SilentlyContinue'
        & (Join-Path $appHome 'runtime\mysql\bin\mysqladmin.exe') '-uroot' '-pAndy_123' 'shutdown' 2>$null
        $ErrorActionPreference = $previousPreference
        Wait-Process -Id $entry.pid -Timeout 15 -ErrorAction SilentlyContinue
    } elseif ($expected.EndsWith('redis-server.exe',[StringComparison]::OrdinalIgnoreCase)) {
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'SilentlyContinue'
        & (Join-Path $appHome 'runtime\redis\redis-cli.exe') '-h' '127.0.0.1' '-p' '6379' 'shutdown' 'nosave' 2>$null
        $ErrorActionPreference = $previousPreference
        Wait-Process -Id $entry.pid -Timeout 10 -ErrorAction SilentlyContinue
    } else {
        Stop-Process -Id $entry.pid -Force
    }
}
[IO.File]::Delete($stateFile)
Write-Host 'Services owned by this installation were stopped.'
