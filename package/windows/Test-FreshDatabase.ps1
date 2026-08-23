$ErrorActionPreference = 'Stop'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('qihang-sqltest-' + [guid]::NewGuid().ToString('N'))
$mysqlRoot = 'D:\QihangOMS-pkg\QihangOMS\runtime\mysql'
$mysqld = Join-Path $mysqlRoot 'bin\mysqld.exe'
$client = Join-Path $mysqlRoot 'bin\mysql.exe'
$process = $null
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
try {
    $base = Join-Path $testRoot 'base.sql'
    & (Join-Path $PSScriptRoot 'New-SanitizedSchema.ps1') -Source (Join-Path $PSScriptRoot '..\..\docs\qihang-oms.sql') -Destination $base
    & $mysqld '--no-defaults' '--initialize-insecure' "--basedir=$mysqlRoot" "--datadir=$testRoot\data"
    if ($LASTEXITCODE -ne 0) { throw 'initialize failed' }
    $process = Start-Process -FilePath $mysqld -ArgumentList @('--no-defaults',"--basedir=$mysqlRoot","--datadir=$testRoot\data",'--bind-address=127.0.0.1','--port=13306','--skip-log-bin','--mysqlx=0') -WindowStyle Hidden -PassThru
    for ($i=0; $i -lt 40; $i++) {
        & $client '-h127.0.0.1' '-P13306' '-uroot' '-e' 'SELECT 1' 2>$null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 1
    }
    if ($LASTEXITCODE -ne 0) { throw 'mysql test start failed' }
    & $client '-h127.0.0.1' '-P13306' '-uroot' '-e' 'CREATE DATABASE `qihang-oms` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci'
    $baseSource = $base.Replace('\','/')
    & $client '-h127.0.0.1' '-P13306' '-uroot' '--default-character-set=utf8mb4' 'qihang-oms' '-e' "source $baseSource"
    if ($LASTEXITCODE -ne 0) { throw 'base schema failed' }
    $intelSource = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\docs\sql\market_intel.sql')).Replace('\','/')
    & $client '-h127.0.0.1' '-P13306' '-uroot' '--default-character-set=utf8mb4' 'qihang-oms' '-e' "source $intelSource"
    if ($LASTEXITCODE -ne 0) { throw 'market intel schema failed' }
    $migrationSource = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\docs\sql\market_intel_migration.sql')).Replace('\','/')
    & $client '-h127.0.0.1' '-P13306' '-uroot' '--default-character-set=utf8mb4' 'qihang-oms' '-e' "source $migrationSource"
    if ($LASTEXITCODE -ne 0) { throw 'market intel migration pass 1 failed' }
    & $client '-h127.0.0.1' '-P13306' '-uroot' 'qihang-oms' '-e' "INSERT INTO mi_competitor(merchant_id,provider,account_id,user_id,xsec_token,profile_url,enabled) VALUES(1,'xiaohongshu','legacy','legacy-user','v1:old-cipher','https://www.xiaohongshu.com/user/profile/legacy-user?xsec_token=plain',1)"
    if ($LASTEXITCODE -ne 0) { throw 'legacy token fixture failed' }
    & $client '-h127.0.0.1' '-P13306' '-uroot' '--default-character-set=utf8mb4' 'qihang-oms' '-e' "source $migrationSource"
    if ($LASTEXITCODE -ne 0) { throw 'market intel migration pass 2 failed' }
    & $client '-h127.0.0.1' '-P13306' '-uroot' 'qihang-oms' '-N' '-e' "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='qihang-oms'; SELECT COUNT(*) FROM mi_keyword; SELECT COUNT(*) FROM sys_user; SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mi_keyword' AND index_name='uk_mi_keyword_merchant_word'; SELECT generation_expression FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mi_job_run' AND column_name='active_scope'; SELECT CONCAT(enabled,':',LENGTH(xsec_token),':',profile_url) FROM mi_competitor WHERE account_id='legacy';"
    Write-Host 'fresh SQL initialization OK'
} finally {
    if ($process -and -not $process.HasExited) {
        & (Join-Path $mysqlRoot 'bin\mysqladmin.exe') '-h127.0.0.1' '-P13306' '-uroot' 'shutdown' 2>$null
        Wait-Process -Id $process.Id -Timeout 15 -ErrorAction SilentlyContinue
    }
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $temporary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if (-not $resolved.StartsWith($temporary + '\') -or -not (Split-Path $resolved -Leaf).StartsWith('qihang-sqltest-')) {
        throw 'refusing to remove unexpected SQL test directory'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
