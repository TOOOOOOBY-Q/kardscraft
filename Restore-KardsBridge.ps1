param([Parameter(Mandatory=$true)][string]$BackupDirectory)
$ErrorActionPreference='Stop'
if(Get-Process 'kards-Win64-Shipping' -ErrorAction SilentlyContinue) { throw 'Close KARDS before restoring.' }
$backup=(Resolve-Path -LiteralPath $BackupDirectory).Path
$manifest=Get-Content -LiteralPath (Join-Path $backup 'manifest.json') -Raw | ConvertFrom-Json
$modsRoot=[IO.Path]::GetFullPath($manifest.modsRoot)
$allowed=@('mods.txt','ConsoleEnablerMod\Scripts\main.lua','KardsBridge\Scripts\main.lua','KardsBridge\Scripts\bridge_core.lua','KardsBridge\Scripts\kards_adapter.lua','KardsBridge\Scripts\bridge_settings.lua','KardsBridge\Scripts\camera_follow.lua')
foreach($record in $manifest.files) {
    if($record.relative -notin $allowed) { throw 'Unexpected backup entry.' }
    $target=[IO.Path]::GetFullPath((Join-Path $modsRoot $record.relative))
    if(-not $target.StartsWith($modsRoot.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid restore path.' }
    if($record.existed) { Copy-Item -LiteralPath (Join-Path $backup $record.relative) -Destination $target -Force }
    elseif(Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target }
}
Write-Host 'Restored only the files changed by this bridge installer.'
