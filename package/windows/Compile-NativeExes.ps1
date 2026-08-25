<#
Compile-NativeExes.ps1 - build QihangOMS.exe (WebView2 desktop host via dotnet),
Start/Stop helpers and Setup stub with inbox csc. Called from Build-Package.ps1.
#>
param(
    [Parameter(Mandatory = $true)][string]$LauncherOutDir,
    [string]$StubOut,
    [string]$Icon
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$srcDir = Join-Path $here 'installer'
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not [IO.File]::Exists($csc)) { throw "missing csc: $csc" }
$launcherCs = Join-Path $srcDir 'Launcher.cs'
$hostProj = Join-Path $srcDir 'QihangOMS.csproj'
$setupCs = Join-Path $srcDir 'Setup.cs'
$manifest = Join-Path $srcDir 'app.manifest'
if (-not [IO.File]::Exists($launcherCs)) { throw "missing $launcherCs" }
if (-not [IO.File]::Exists($hostProj)) { throw "missing $hostProj" }
if (-not [IO.File]::Exists($setupCs)) { throw "missing $setupCs" }
if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) { throw 'missing tool: dotnet (needed to build QihangOMS.exe / WebView2 host)' }

if (-not $Icon) { $Icon = Join-Path $srcDir 'app.ico' }
$iconArgs = @()
if ($Icon -and [IO.File]::Exists($Icon)) { $iconArgs = @("/win32icon:$Icon") }

function Invoke-Csc([string[]]$cscArgs) {
    & $csc @cscArgs
    if ($LASTEXITCODE -ne 0) { throw ('csc failed: ' + ($cscArgs -join ' ')) }
}

[IO.Directory]::CreateDirectory($LauncherOutDir) | Out-Null

$common = @('/nologo', '/optimize+', '/codepage:65001', '/platform:anycpu')
$hostOut = Join-Path $LauncherOutDir '_host-build'
if (Test-Path -LiteralPath $hostOut) { Remove-Item -LiteralPath $hostOut -Recurse -Force }
& dotnet publish $hostProj -c Release --nologo -o $hostOut
if ($LASTEXITCODE -ne 0) { throw 'dotnet publish QihangOMS.csproj failed' }
$requiredHost = @('QihangOMS.exe', 'Microsoft.Web.WebView2.Core.dll', 'Microsoft.Web.WebView2.WinForms.dll')
foreach ($name in $requiredHost) {
    $src = Join-Path $hostOut $name
    if (-not [IO.File]::Exists($src)) { throw "host publish missing: $name" }
    Copy-Item -LiteralPath $src -Destination (Join-Path $LauncherOutDir $name) -Force
}
$cfg = Join-Path $hostOut 'QihangOMS.exe.config'
if ([IO.File]::Exists($cfg)) { Copy-Item -LiteralPath $cfg -Destination (Join-Path $LauncherOutDir 'QihangOMS.exe.config') -Force }
$loader = @(Get-ChildItem -LiteralPath $hostOut -Filter 'WebView2Loader.dll' -Recurse)
if ($loader.Count -lt 1) { throw 'host publish missing: WebView2Loader.dll' }
$loaderPick = @($loader | Where-Object { $_.FullName -match 'win-x64|x64' })
if ($loaderPick.Count -lt 1) { $loaderPick = $loader }
Copy-Item -LiteralPath $loaderPick[0].FullName -Destination (Join-Path $LauncherOutDir 'WebView2Loader.dll') -Force
Remove-Item -LiteralPath $hostOut -Recurse -Force

Invoke-Csc ($common + @('/target:exe', ('/out:' + (Join-Path $LauncherOutDir 'Start-OMS.exe'))) + $iconArgs + @($launcherCs))
Invoke-Csc ($common + @('/target:exe', '/define:STOP', ('/out:' + (Join-Path $LauncherOutDir 'Stop-OMS.exe'))) + $iconArgs + @($launcherCs))

if ($StubOut) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($StubOut)) | Out-Null
    $refs = @(
        '/reference:System.Windows.Forms.dll',
        '/reference:System.Drawing.dll',
        '/reference:System.IO.Compression.dll',
        '/reference:System.IO.Compression.FileSystem.dll'
    )
    $stubArgs = $common + @('/target:winexe', ('/out:' + $StubOut)) + $iconArgs + $refs
    if ([IO.File]::Exists($manifest)) { $stubArgs += ('/win32manifest:' + $manifest) }
    $stubArgs += $setupCs
    Invoke-Csc $stubArgs
}

Write-Host ('  native exes: ' + (Join-Path $LauncherOutDir 'QihangOMS.exe') + ' (WebView2), Start-OMS.exe, Stop-OMS.exe' + $(if ($StubOut) { ', stub ' + $StubOut } else { '' }))
