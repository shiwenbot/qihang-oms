param(
    # MySQL runtime directory taken from the release template (Build-Package.ps1 passes it in).
    [string]$MysqlRoot = 'D:\QihangOMS-pkg\QihangOMS\runtime\mysql'
)

$ErrorActionPreference = 'Stop'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('qihang-sqltest-' + [guid]::NewGuid().ToString('N'))
$mysqld = Join-Path $MysqlRoot 'bin\mysqld.exe'
$client = Join-Path $MysqlRoot 'bin\mysql.exe'
$process = $null
[IO.Directory]::CreateDirectory($testRoot) | Out-Null

function Assert-Scalar([string]$query, [string]$expected, [string]$label) {
    $actual = (& $client '-h127.0.0.1' '-P13306' '-uroot' '-N' '-B' '-e' $query | Select-Object -First 1)
    if ("$actual" -ne "$expected") {
        throw ("assert failed [$label]: expected '$expected', got '$actual'")
    }
    Write-Host ("  ok: {0} = {1}" -f $label, $actual)
}

try {
    # 1) Build the exact SQL set the release package ships: generated base schema + all docs\sql\*.sql
    $base = Join-Path $testRoot 'base.sql'
    & (Join-Path $PSScriptRoot 'New-SanitizedSchema.ps1') -Source (Join-Path $PSScriptRoot '..\..\docs\qihang-oms.sql') -Destination $base
    $sqlDir = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\docs\sql'))
    $sqlFiles = @(Get-ChildItem -LiteralPath $sqlDir -Filter '*.sql' | Sort-Object Name)
    if ($sqlFiles.Count -lt 3) { throw "unexpected sql file set in docs\sql (found $($sqlFiles.Count))" }
    $migrationFile = $sqlFiles | Where-Object { $_.Name -eq 'market_intel_migration.sql' }
    if (-not $migrationFile) { throw 'market_intel_migration.sql missing from docs\sql' }

    # 2) Fresh mysqld on a scratch port, exactly like a first install on a new machine
    & $mysqld '--no-defaults' '--initialize-insecure' "--basedir=$MysqlRoot" "--datadir=$testRoot\data"
    if ($LASTEXITCODE -ne 0) { throw 'initialize failed' }
    $process = Start-Process -FilePath $mysqld -ArgumentList @('--no-defaults',"--basedir=$MysqlRoot","--datadir=$testRoot\data",'--bind-address=127.0.0.1','--port=13306','--skip-log-bin','--mysqlx=0') -WindowStyle Hidden -PassThru
    for ($i=0; $i -lt 40; $i++) {
        & $client '-h127.0.0.1' '-P13306' '-uroot' '-e' 'SELECT 1' 2>$null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 1
    }
    if ($LASTEXITCODE -ne 0) { throw 'mysql test start failed' }
    & $client '-h127.0.0.1' '-P13306' '-uroot' '-e' 'CREATE DATABASE `qihang-oms` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci'

    # 3) Fresh install: base schema only when the database is empty (Start-QihangOms.ps1 logic)
    $baseSource = $base.Replace('\','/')
    & $client '-h127.0.0.1' '-P13306' '-uroot' '--default-character-set=utf8mb4' 'qihang-oms' '-e' "source $baseSource"
    if ($LASTEXITCODE -ne 0) { throw 'base schema failed' }

    # 4) Pass 1: source every docs\sql file, sorted by name (what every start does now)
    foreach ($f in $sqlFiles) {
        $p = $f.FullName.Replace('\','/')
        & $client '-h127.0.0.1' '-P13306' '-uroot' '--default-character-set=utf8mb4' 'qihang-oms' '-e' "source $p"
        if ($LASTEXITCODE -ne 0) { throw ("docs/sql sync pass 1 failed: " + $f.Name) }
    }

    # 5) Legacy-token migration coverage: plaintext token must be wiped by a re-run of the migration
    & $client '-h127.0.0.1' '-P13306' '-uroot' 'qihang-oms' '-e' "INSERT INTO mi_competitor(merchant_id,provider,account_id,user_id,xsec_token,profile_url,enabled) VALUES(1,'xiaohongshu','legacy','legacy-user','v1:old-cipher','https://www.xiaohongshu.com/user/profile/legacy-user?xsec_token=plain',1)"
    if ($LASTEXITCODE -ne 0) { throw 'legacy token fixture failed' }
    & $client '-h127.0.0.1' '-P13306' '-uroot' '--default-character-set=utf8mb4' 'qihang-oms' '-e' ("source " + $migrationFile.FullName.Replace('\','/'))
    if ($LASTEXITCODE -ne 0) { throw 'market intel migration re-run failed' }

    # 6) Pass 2: full sync again — proves every docs\sql script is idempotent (next start on an old install)
    foreach ($f in $sqlFiles) {
        $p = $f.FullName.Replace('\','/')
        & $client '-h127.0.0.1' '-P13306' '-uroot' '--default-character-set=utf8mb4' 'qihang-oms' '-e' "source $p"
        if ($LASTEXITCODE -ne 0) { throw ("docs/sql sync pass 2 (idempotency) failed: " + $f.Name) }
    }

    # 7) Machine assertions — this is the release gate
    Assert-Scalar "SELECT COUNT(*) FROM sys_user WHERE user_id=1 AND user_name='admin'" '1' 'admin user seeded exactly once'
    Assert-Scalar "SELECT password FROM sys_user WHERE user_id=1" '$2a$10$ib0ZSsf7EhY1jU/FeiQpleNbLVRZmGTL9w8RJNk6IZRVjuGUle6Cm' 'admin password hash seeded for password: admin'
    Assert-Scalar "SELECT config_value FROM sys_config WHERE config_key='sys.account.captchaEnabled'" 'false' 'captcha disabled'
    Assert-Scalar "SELECT COUNT(*) FROM sys_menu WHERE menu_id IN (8000,8001)" '2' 'AI image menus present'
    Assert-Scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ai_image_task'" '1' 'ai_image_task table present'
    Assert-Scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'mi\\_%'" '9' 'market intel tables present'
    Assert-Scalar "SELECT CONCAT(enabled,':',LENGTH(xsec_token),':',profile_url) FROM mi_competitor WHERE account_id='legacy'" '0:0:https://www.xiaohongshu.com/user/profile/legacy-user' 'legacy token wiped by migration'
    $tableCount = (& $client '-h127.0.0.1' '-P13306' '-uroot' '-N' '-B' '-e' "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='qihang-oms'" | Select-Object -First 1)
    Write-Host ("  info: total tables = {0}" -f $tableCount)

    Write-Host 'fresh SQL initialization OK'
} finally {
    if ($process -and -not $process.HasExited) {
        & (Join-Path $MysqlRoot 'bin\mysqladmin.exe') '-h127.0.0.1' '-P13306' '-uroot' 'shutdown' 2>$null
        Wait-Process -Id $process.Id -Timeout 15 -ErrorAction SilentlyContinue
    }
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $temporary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if (-not $resolved.StartsWith($temporary + '\') -or -not (Split-Path $resolved -Leaf).StartsWith('qihang-sqltest-')) {
        throw 'refusing to remove unexpected SQL test directory'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
