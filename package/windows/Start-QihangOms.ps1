$ErrorActionPreference = 'Stop'
$appHome = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$runtime = Join-Path $appHome 'runtime'
$config = Join-Path $appHome 'config'
$logs = Join-Path $appHome 'logs'
$stateFile = Join-Path $config 'processes.json'
$tokenFile = Join-Path $config 'market-intel-token.txt'
$dataKeyFile = Join-Path $config 'market-intel-data-key.bin'
$started = [Collections.Generic.List[object]]::new()

Add-Type -AssemblyName System.Security

function New-RandomBytes([int]$length) {
    $bytes = New-Object byte[] $length
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return $bytes
}
function ConvertTo-Hex([byte[]]$bytes) {
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

function Require-File([string]$path) {
    if (-not [IO.File]::Exists($path)) { throw "incomplete package: $path" }
}
function Assert-PortFree([int]$port) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) { throw "port $port is already used by process $($listener.OwningProcess)" }
}
function Save-ProcessState {
    [IO.File]::WriteAllText($stateFile, ($script:started | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
}
function Wait-ForPort([int]$port, [int]$seconds, [int]$expectedPid, [string]$expectedExecutable) {
    for ($i=0; $i -lt $seconds; $i++) {
        $listeners = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
        foreach ($listener in $listeners) {
            $candidate = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction SilentlyContinue
            if ($candidate -and $candidate.ExecutablePath -and
                [IO.Path]::GetFullPath($candidate.ExecutablePath).Equals([IO.Path]::GetFullPath($expectedExecutable), [StringComparison]::OrdinalIgnoreCase) -and
                ($candidate.ProcessId -eq $expectedPid -or $candidate.ParentProcessId -eq $expectedPid)) {
                return [int]($listener.OwningProcess)
            }
        }
        Start-Sleep -Seconds 1
    }
    throw "service port $port startup timed out"
}
function Start-OwnedProcess([string]$name, [string]$file, [string[]]$arguments) {
    $process = Start-Process -FilePath $file -ArgumentList $arguments -WorkingDirectory $appHome -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logs "$name.out.log") -RedirectStandardError (Join-Path $logs "$name.err.log")
    $entry = @{ pid=$process.Id; executable=[IO.Path]::GetFullPath($file); requireMarker=($name -in @('sidecar','oms')) }
    $script:started.Add($entry)
    Save-ProcessState
    return $entry
}



[IO.Directory]::CreateDirectory($config) | Out-Null
[IO.Directory]::CreateDirectory($logs) | Out-Null
$mysql = Join-Path $runtime 'mysql\bin\mysqld.exe'
$mysqlClient = Join-Path $runtime 'mysql\bin\mysql.exe'
$redis = Join-Path $runtime 'redis\redis-server.exe'
$python = Join-Path $runtime 'python\python.exe'
$java = Join-Path $runtime 'jre\bin\java.exe'
@($mysql,$mysqlClient,$redis,$python,$java,(Join-Path $appHome 'app\oms.jar'),(Join-Path $appHome 'intel-sidecar\app.py'),(Join-Path $appHome 'sql\base-schema.sql')) | ForEach-Object { Require-File $_ }
if ($appHome -match '[^\x00-\x7F]') {
    throw "Please unpack to an ASCII path such as D:\QihangOMS. MySQL cannot start from: $appHome"
}



if ([IO.File]::Exists($stateFile)) {
    $entries = Get-Content -Raw $stateFile | ConvertFrom-Json
    $allOwned = $entries.Count -gt 0
    foreach ($entry in $entries) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)" -ErrorAction SilentlyContinue
        $expected = [IO.Path]::GetFullPath([string]($entry.executable))
        if (-not $process -or -not $process.ExecutablePath -or
            -not [IO.Path]::GetFullPath($process.ExecutablePath).Equals($expected, [StringComparison]::OrdinalIgnoreCase) -or
            ([bool]($entry.requireMarker) -and $process.CommandLine -notlike "*$appHome*")) { $allOwned = $false; break }
    }
    if ($allOwned) { Start-Process 'http://127.0.0.1:8086'; Write-Host 'Qihang OMS is already running.'; exit 0 }
    [IO.File]::Delete($stateFile)
}
3306,6379,18080,8086 | ForEach-Object { Assert-PortFree $_ }

if (-not [IO.File]::Exists($tokenFile)) {
    $bytes = New-RandomBytes 32
    [IO.File]::WriteAllText($tokenFile, (ConvertTo-Hex $bytes), [Text.Encoding]::ASCII)
}
$token = [IO.File]::ReadAllText($tokenFile, [Text.Encoding]::ASCII).Trim()
if ($token.Length -lt 32) { throw 'local communication token is invalid' }
$env:MARKET_INTEL_TOKEN = $token
$env:TOKEN = $token
if (-not [IO.File]::Exists($dataKeyFile)) {
    $dataKeyBytes = New-RandomBytes 32
    $protectedKey = [Security.Cryptography.ProtectedData]::Protect($dataKeyBytes, [Text.Encoding]::UTF8.GetBytes('QihangOMS.MarketIntel.DataKey.v1'), [Security.Cryptography.DataProtectionScope]::CurrentUser)
    [IO.File]::WriteAllBytes($dataKeyFile, $protectedKey)
}
$protectedDataKey = [IO.File]::ReadAllBytes($dataKeyFile)
$dataKeyBytes = [Security.Cryptography.ProtectedData]::Unprotect($protectedDataKey, [Text.Encoding]::UTF8.GetBytes('QihangOMS.MarketIntel.DataKey.v1'), [Security.Cryptography.DataProtectionScope]::CurrentUser)
$env:MARKET_INTEL_DATA_KEY = ConvertTo-Hex $dataKeyBytes
$env:PYTHONUTF8 = '1'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:Path = (Join-Path $runtime 'node') + ';' + $env:Path

$data = Join-Path $runtime 'mysql\data'
$freshData = -not [IO.File]::Exists((Join-Path $data 'mysql.ibd'))
if ($freshData) {
    [IO.Directory]::CreateDirectory($data) | Out-Null
    $initialize = Start-Process -FilePath $mysql -ArgumentList @('--no-defaults','--initialize-insecure',"--basedir=$runtime\mysql","--datadir=$data") -WindowStyle Hidden -Wait -PassThru
    if ($initialize.ExitCode -ne 0) { throw 'MySQL initialization failed' }
}

try {
$mysqlEntry = Start-OwnedProcess 'mysql' $mysql @('--no-defaults',"--basedir=$runtime\mysql","--datadir=$data",'--bind-address=127.0.0.1','--port=3306','--skip-log-bin','--mysqlx=0','--character-set-server=utf8mb4')
$mysqlEntry.pid = Wait-ForPort 3306 45 $mysqlEntry.pid $mysqlEntry.executable
Save-ProcessState
$rootArgs = @('-uroot')
try {
    $ErrorActionPreference = 'Continue'
    & $mysqlClient @rootArgs '-e' 'SELECT 1' 2>$null
    if ($LASTEXITCODE -ne 0) {
        $rootArgs = @('-uroot','-pAndy_123')
        & $mysqlClient @rootArgs '-e' 'SELECT 1' 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'database authentication failed' }
    }
} finally {
    $ErrorActionPreference = 'Stop'
}
$existingTableCount = & $mysqlClient @rootArgs '-N' '-s' '-e' "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='qihang-oms'"
if ($LASTEXITCODE -ne 0) { throw 'database state check failed' }
$needsBaseSchema = [int]($existingTableCount | Select-Object -Last 1) -eq 0
& $mysqlClient @rootArgs '-e' "CREATE DATABASE IF NOT EXISTS ``qihang-oms`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
if ($LASTEXITCODE -ne 0) { throw 'database creation failed' }
if ($needsBaseSchema) {
    foreach ($schema in @('base-schema.sql')) {
        $schemaPath = (Join-Path $appHome "sql\$schema").Replace('\','/')
        & $mysqlClient @rootArgs '--default-character-set=utf8mb4' 'qihang-oms' '-e' "source $schemaPath"
        if ($LASTEXITCODE -ne 0) { throw "schema import failed: $schema" }
    }
    if ($rootArgs.Count -eq 1) {
        & $mysqlClient @rootArgs '-e' "ALTER USER 'root'@'localhost' IDENTIFIED BY 'Andy_123'; FLUSH PRIVILEGES;"
        if ($LASTEXITCODE -ne 0) { throw 'database local password setup failed' }
    }
}
# Full sync: every *.sql in the sql folder except base-schema.sql (fresh-install
# only, not idempotent) is sourced on every start, sorted by file name, and must
# be idempotent. New features only need to drop an idempotent SQL file into the
# package sql folder (Build-Package.ps1 copies docs\sql\*.sql automatically).
$sqlFiles = Get-ChildItem -LiteralPath (Join-Path $appHome 'sql') -Filter '*.sql' | Sort-Object Name
foreach ($sqlFile in $sqlFiles) {
    if ($sqlFile.Name -eq 'base-schema.sql') { continue }
    $sourcePath = $sqlFile.FullName.Replace('\','/')
    & $mysqlClient @rootArgs '--default-character-set=utf8mb4' 'qihang-oms' '-e' "source $sourcePath"
    if ($LASTEXITCODE -ne 0) { throw ("sql sync failed: " + $sqlFile.Name) }
}



$redisEntry = Start-OwnedProcess 'redis' $redis @('--bind','127.0.0.1','--protected-mode','yes','--port','6379','--save','""','--appendonly','no')
$redisEntry.pid = Wait-ForPort 6379 20 $redisEntry.pid $redisEntry.executable
Save-ProcessState
$sidecarEntry = Start-OwnedProcess 'sidecar' $python @('-m','uvicorn','app:app','--app-dir',(Join-Path $appHome 'intel-sidecar'),'--host','127.0.0.1','--port','18080')
$sidecarEntry.pid = Wait-ForPort 18080 30 $sidecarEntry.pid $sidecarEntry.executable
Save-ProcessState
$omsEntry = Start-OwnedProcess 'oms' $java @('-Dfile.encoding=utf-8','-jar',(Join-Path $appHome 'app\oms.jar'),'--server.address=127.0.0.1')
$omsEntry.pid = Wait-ForPort 8086 90 $omsEntry.pid $omsEntry.executable
Save-ProcessState
Start-Process 'http://127.0.0.1:8086'
Write-Host 'Started: http://127.0.0.1:8086  username: admin  password: admin'
} catch {
    Write-Error $_
    & (Join-Path $appHome 'package\windows\Stop-QihangOms.ps1')
    exit 1
}
