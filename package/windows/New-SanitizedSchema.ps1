param(
    [Parameter(Mandatory=$true)][string]$Source,
    [Parameter(Mandatory=$true)][string]$Destination
)

$ErrorActionPreference = 'Stop'
$sourcePath = [IO.Path]::GetFullPath($Source)
$destinationPath = [IO.Path]::GetFullPath($Destination)
$allowedSeedTables = @('sys_menu', 'sys_dict_type', 'sys_dict_data', 'sys_task')
$lines = [IO.File]::ReadAllLines($sourcePath, [Text.Encoding]::UTF8)
$output = [Collections.Generic.List[string]]::new()
$capturingDdl = $false

$output.Add('SET NAMES utf8mb4;')
$output.Add('SET FOREIGN_KEY_CHECKS = 0;')
foreach ($line in $lines) {
    if ($line -match '^(DROP TABLE IF EXISTS|CREATE TABLE) ') { $capturingDdl = $true }
    if ($capturingDdl) {
        $output.Add($line)
        if ($line.TrimEnd().EndsWith(';')) { $capturingDdl = $false }
        continue
    }
    if ($line -match '^INSERT INTO `([^`]+)` ' -and $allowedSeedTables -contains $Matches[1]) {
        $output.Add($line)
    }
}

$output.Add('')
$seedFile = Join-Path $PSScriptRoot '..\..\docs\sql\ensure-login-config.sql'
$output.AddRange([string[]][IO.File]::ReadAllLines($seedFile, [Text.Encoding]::UTF8))
$output.Add('SET FOREIGN_KEY_CHECKS = 1;')

$parent = Split-Path -Parent $destinationPath
[IO.Directory]::CreateDirectory($parent) | Out-Null
[IO.File]::WriteAllLines($destinationPath, $output, [Text.UTF8Encoding]::new($false))
