<#
Build-Package.ps1 - one-click deterministic release build.

Everything except third-party runtime binaries is rebuilt from the git working
tree, so any machine produces the same package content from the same source:

  1. record git commit + dirty state
  2. check toolchain and the runtime template
  3. build frontend (npm ci if needed + npm run build:prod)
  4. embed frontend dist into the jar (api resources static) + mvn clean package
  5. stage the package: template binaries + repo scripts + generated sql set
     + QihangOMS.exe desktop host (csc, inbox .NET Framework)
  6. write BUILD-INFO.txt (commit, tool versions, sha256 of key files)
  7. gate: Test-FreshDatabase.ps1 simulates a fresh install from the same sql set
  8. zip + self-extracting QihangOMS-Setup-*.exe

Usage (from repo root, on the build machine):
  powershell -ExecutionPolicy Bypass -File package\windows\Build-Package.ps1
  powershell -ExecutionPolicy Bypass -File package\windows\Build-Package.ps1 -TemplateDir D:\DianShang\runtime-template\QihangOMS

The template folder only contributes third-party binaries under runtime\
(mysql, redis, jre, python, node). Scripts, jar, sql and sidecar sources are
always taken from the repo, never from the template.
#>
param(
    [string]$TemplateDir = 'D:\DianShang\runtime-template\QihangOMS',
    [string]$OutDir,
    [switch]$SkipTest
)

$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if (-not $OutDir) { $OutDir = Join-Path $repo 'package\out' }
$staging = Join-Path $OutDir 'QihangOMS'
$buildTime = Get-Date

function Step([string]$message) { Write-Host ('[' + $buildTime.ToString('HH:mm:ss') + '] ' + $message) -ForegroundColor Cyan }
function Require-Tool([string]$name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) { throw "missing tool: $name (install it and make sure it is on PATH)" }
}
function Clear-Directory([string]$dir) {
    if (-not (Test-Path -LiteralPath $dir)) { return }
    Get-ChildItem -LiteralPath $dir -Force | Remove-Item -Recurse -Force
}
function New-SfxInstaller([string]$StubPath, [string]$ZipPath, [string]$OutPath) {
    $tmp = $OutPath + '.tmp'
    if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force }
    $out = [IO.File]::Create($tmp)
    try {
        $stub = [IO.File]::OpenRead($StubPath)
        try { $stub.CopyTo($out) } finally { $stub.Dispose() }
        $zip = [IO.File]::OpenRead($ZipPath)
        $zipLen = $zip.Length
        try { $zip.CopyTo($out) } finally { $zip.Dispose() }
        $bw = New-Object IO.BinaryWriter $out
        $bw.Write([int64]$zipLen)
        $bw.Write([Text.Encoding]::ASCII.GetBytes('QHOMSFX1'))
        $bw.Flush()
    } finally { $out.Dispose() }
    if (Test-Path -LiteralPath $OutPath) { Remove-Item -LiteralPath $OutPath -Force }
    Move-Item -LiteralPath $tmp -Destination $OutPath
}

# ---------- 1) git identity ----------
Step '1/8 git revision'
Require-Tool git
$commit = (git -C $repo rev-parse --short HEAD).Trim()
$dirtyOutput = @(git -C $repo status --porcelain)
$dirty = $dirtyOutput.Count -gt 0
if ($dirty) { Write-Warning 'working tree has uncommitted changes; commit before an official release' }
$revision = $commit + $(if ($dirty) { '-dirty' } else { '' })
Write-Host "  revision: $revision"

# ---------- 2) toolchain + template ----------
Step '2/8 toolchain and template check'
Require-Tool node
Require-Tool npm
Require-Tool mvn
Require-Tool dotnet
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not [IO.File]::Exists($csc)) { throw "missing csc: $csc (install .NET Framework 4.x Developer Pack / Windows has it inbox)" }
foreach ($p in @(
    (Join-Path $TemplateDir 'runtime\mysql\bin\mysqld.exe'),
    (Join-Path $TemplateDir 'runtime\mysql\bin\mysql.exe'),
    (Join-Path $TemplateDir 'runtime\redis\redis-server.exe'),
    (Join-Path $TemplateDir 'runtime\python\python.exe'),
    (Join-Path $TemplateDir 'runtime\jre\bin\java.exe')
)) {
    if (-not [IO.File]::Exists($p)) { throw "template incomplete, missing: $p (check -TemplateDir)" }
}
$nodeVersion = (& node --version).Trim()
$mvnVersion = ((& mvn -v | Select-Object -First 1) -join '')
Write-Host "  node=$nodeVersion mvn=$mvnVersion"

# ---------- 3) frontend ----------
Step '3/8 frontend build (vue2)'
Push-Location (Join-Path $repo 'vue2')
try {
    if (-not (Test-Path 'node_modules')) {
        & npm ci --registry=https://registry.npmmirror.com
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
    }
    & npm run build:prod
    if ($LASTEXITCODE -ne 0) { throw 'npm run build:prod failed' }
} finally { Pop-Location }
$distIndex = Join-Path $repo 'vue2\dist\index.html'
if (-not [IO.File]::Exists($distIndex)) { throw 'frontend build output missing: vue2\dist\index.html' }

# ---------- 4) embed dist into the jar, then build backend ----------
Step '4/8 backend build (mvn clean package)'
$staticDir = Join-Path $repo 'api\src\main\resources\static'
if (Test-Path $staticDir) { Remove-Item -LiteralPath $staticDir -Recurse -Force }
[IO.Directory]::CreateDirectory($staticDir) | Out-Null
Copy-Item -Path (Join-Path $repo 'vue2\dist\*') -Destination $staticDir -Recurse -Force
Push-Location $repo
try {
    & mvn clean package
    if ($LASTEXITCODE -ne 0) { throw "mvn clean package failed (exit $LASTEXITCODE)" }
} finally { Pop-Location }
$jar = @(Get-ChildItem -LiteralPath (Join-Path $repo 'api\target') -Filter '*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*original*' })
if ($jar.Count -ne 1) { throw "expected exactly one api jar under api\target, found $($jar.Count)" }
$jar = $jar[0]
Write-Host "  jar: $($jar.Name) ($([math]::Round($jar.Length / 1MB, 1)) MB)"

# ---------- 5) stage the package ----------
Step '5/8 stage release folder'
if (Test-Path $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
& robocopy $TemplateDir $staging /E /XD data config logs out /XF *.zip /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "template copy failed (robocopy exit $LASTEXITCODE)" }
$global:LASTEXITCODE = 0
# never ship database data or runtime state even if the template has some
foreach ($stateDir in @((Join-Path $staging 'runtime\mysql\data'), (Join-Path $staging 'config'), (Join-Path $staging 'logs'))) {
    if (Test-Path $stateDir) { Remove-Item -LiteralPath $stateDir -Recurse -Force }
}
# app jar from this build
[IO.Directory]::CreateDirectory((Join-Path $staging 'app')) | Out-Null
Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $staging 'app\oms.jar') -Force
# scripts always from the repo, not the template
$pkgStaging = Join-Path $staging 'package\windows'
[IO.Directory]::CreateDirectory($pkgStaging) | Out-Null
Clear-Directory $pkgStaging
Copy-Item -Path (Join-Path $repo 'package\windows\*') -Destination $pkgStaging -Recurse -Force
# Desktop host + Start/Stop helpers at package root; stub is joined with the zip in step 8
$stubPath = Join-Path $OutDir 'QihangOMS-SetupStub.exe'
& (Join-Path $PSScriptRoot 'Compile-NativeExes.ps1') -LauncherOutDir $staging -StubOut $stubPath
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-oms.bat') -Destination (Join-Path $staging 'Start-OMS.bat') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'stop-oms.bat') -Destination (Join-Path $staging 'Stop-OMS.bat') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'README.txt') -Destination (Join-Path $staging 'README.txt') -Force
$startBatCn = ([char]0x542F).ToString() + ([char]0x52A8).ToString() + 'OMS.bat'
$stopBatCn = ([char]0x505C).ToString() + ([char]0x6B62).ToString() + 'OMS.bat'
$hostCn = ([char]0x542F).ToString() + ([char]0x822A).ToString() + 'OMS.exe'
$startExeCn = ([char]0x542F).ToString() + ([char]0x52A8).ToString() + 'OMS.exe'
$stopExeCn = ([char]0x505C).ToString() + ([char]0x6B62).ToString() + 'OMS.exe'
$readmeCn = ([char]0x4F7F).ToString() + ([char]0x7528).ToString() + ([char]0x8BF4).ToString() + ([char]0x660E).ToString() + '.txt'
Copy-Item -LiteralPath (Join-Path $staging 'Start-OMS.bat') -Destination (Join-Path $staging $startBatCn) -Force
Copy-Item -LiteralPath (Join-Path $staging 'Stop-OMS.bat') -Destination (Join-Path $staging $stopBatCn) -Force
Copy-Item -LiteralPath (Join-Path $staging 'QihangOMS.exe') -Destination (Join-Path $staging $hostCn) -Force
Copy-Item -LiteralPath (Join-Path $staging 'Start-OMS.exe') -Destination (Join-Path $staging $startExeCn) -Force
Copy-Item -LiteralPath (Join-Path $staging 'Stop-OMS.exe') -Destination (Join-Path $staging $stopExeCn) -Force
Copy-Item -LiteralPath (Join-Path $staging 'README.txt') -Destination (Join-Path $staging $readmeCn) -Force
$installerSrc = Join-Path $pkgStaging 'installer'
if (Test-Path -LiteralPath $installerSrc) { Remove-Item -LiteralPath $installerSrc -Recurse -Force }
# sql set: generated base schema + every docs\sql\*.sql (new features need no build changes)
$sqlStaging = Join-Path $staging 'sql'
[IO.Directory]::CreateDirectory($sqlStaging) | Out-Null
Clear-Directory $sqlStaging
& (Join-Path $PSScriptRoot 'New-SanitizedSchema.ps1') -Source (Join-Path $repo 'docs\qihang-oms.sql') -Destination (Join-Path $sqlStaging 'base-schema.sql')
Copy-Item -Path (Join-Path $repo 'docs\sql\*.sql') -Destination $sqlStaging -Force
$expectedSql = @('base-schema.sql') + @((Get-ChildItem -LiteralPath (Join-Path $repo 'docs\sql') -Filter '*.sql').Name)
$actualSql = @((Get-ChildItem -LiteralPath $sqlStaging -Filter '*.sql').Name)
$expectedSql = @($expectedSql | Sort-Object)
$actualSql = @($actualSql | Sort-Object)
if (($expectedSql -join '|') -ne ($actualSql -join '|')) {
    throw ('sql set mismatch. expected: ' + ($expectedSql -join ', ') + '; actual: ' + ($actualSql -join ', '))
}
# intel sidecar sources from the repo (no venv, no secrets)
$sidecarStaging = Join-Path $staging 'intel-sidecar'
[IO.Directory]::CreateDirectory($sidecarStaging) | Out-Null
Clear-Directory $sidecarStaging
& robocopy (Join-Path $repo 'intel-sidecar') $sidecarStaging /E /XD .venv __pycache__ vendor .pytest_cache /XF .env /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "intel-sidecar copy failed (robocopy exit $LASTEXITCODE)" }
$global:LASTEXITCODE = 0

# ---------- 6) build info ----------
Step '6/8 BUILD-INFO.txt'
$info = [Collections.Generic.List[string]]::new()
$info.Add('QihangOMS release build')
$info.Add('revision: ' + $revision)
$info.Add('built-at: ' + $buildTime.ToString('yyyy-MM-dd HH:mm:ss'))
$info.Add('built-on: ' + $env:COMPUTERNAME + ' / ' + $env:USERNAME)
$info.Add('node: ' + $nodeVersion)
$info.Add('maven: ' + $mvnVersion)
$info.Add('template: ' + $TemplateDir)
$info.Add('')
$info.Add('sha256:')
$hashedItems = @()
$hashedItems += Get-Item -LiteralPath (Join-Path $staging 'app\oms.jar')
$hashedItems += Get-Item -LiteralPath (Join-Path $staging 'QihangOMS.exe')
$hashedItems += Get-Item -LiteralPath (Join-Path $staging 'WebView2Loader.dll')
$hashedItems += Get-Item -LiteralPath (Join-Path $staging 'Microsoft.Web.WebView2.Core.dll')
$hashedItems += Get-Item -LiteralPath (Join-Path $staging 'Start-OMS.exe')
$hashedItems += Get-Item -LiteralPath (Join-Path $staging 'Stop-OMS.exe')
$hashedItems += @(Get-ChildItem -LiteralPath $sqlStaging -Filter '*.sql')
$hashedItems += @(Get-ChildItem -LiteralPath $pkgStaging -Filter '*.ps1')
foreach ($item in $hashedItems) {
    $hash = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash
    $rel = $item.FullName.Substring($staging.Length + 1)
    $info.Add('  ' + $hash + '  ' + $rel)
}
[IO.File]::WriteAllLines((Join-Path $staging 'BUILD-INFO.txt'), $info, [Text.UTF8Encoding]::new($false))

# ---------- 7) fresh-install gate ----------
if ($SkipTest) {
    Write-Warning 'SkipTest set: fresh-install verification skipped (not recommended for releases)'
} else {
    Step '7/8 fresh-install gate (Test-FreshDatabase.ps1)'
    & (Join-Path $PSScriptRoot 'Test-FreshDatabase.ps1') -MysqlRoot (Join-Path $TemplateDir 'runtime\mysql')
    if ($LASTEXITCODE -ne 0) { throw 'fresh-install gate FAILED - package not produced' }
}

# ---------- 8) zip + setup exe ----------
Step '8/8 zip and setup exe'
$stamp = $buildTime.ToString('yyyyMMdd-HHmm') + '-' + $revision
$zipName = 'QihangOMS-' + $stamp + '.zip'
$zipPath = Join-Path $OutDir $zipName
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
Compress-Archive -Path $staging -DestinationPath $zipPath -Force
$setupPath = Join-Path $OutDir ('QihangOMS-Setup-' + $stamp + '.exe')
New-SfxInstaller -StubPath $stubPath -ZipPath $zipPath -OutPath $setupPath
Remove-Item -LiteralPath $stubPath -Force
Write-Host ''
Write-Host ('release zip:   ' + $zipPath) -ForegroundColor Green
Write-Host ('release setup: ' + $setupPath) -ForegroundColor Green
Write-Host 'install: run the Setup exe (upgrade keeps runtime\mysql\data and config); zip overlay still works'
