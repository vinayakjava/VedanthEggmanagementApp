# Fails if any destructive Room migration fallback is used (Constitution III).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$hits = Get-ChildItem -Path (Join-Path $root 'app'), (Join-Path $root 'data') -Recurse -Include *.kt, *.kts, *.java -File |
    Where-Object { $_.FullName -notmatch '\\build\\' } |
    Select-String -Pattern 'fallbackToDestructiveMigration' -SimpleMatch
if ($hits) {
    $hits | ForEach-Object { Write-Host "$($_.Path):$($_.LineNumber): $($_.Line.Trim())" }
    Write-Error 'Destructive migration fallback found. Write an explicit Migration instead.'
    exit 1
}
Write-Host 'OK: no destructive migration fallback found.'
