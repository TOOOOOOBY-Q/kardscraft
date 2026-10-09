param([string]$KardsRoot='E:\SteamLibrary\steamapps\common\KARDS')
$ErrorActionPreference='Stop'
if (Get-Process 'kards-Win64-Shipping' -ErrorAction SilentlyContinue) { throw 'Close KARDS before installing the bridge.' }
$projectRoot=$PSScriptRoot
$gameBin=Join-Path ([IO.Path]::GetFullPath($KardsRoot)) 'kards\Binaries\Win64'
$modsRoot=Join-Path $gameBin 'ue4ss\Mods'
if (-not (Test-Path -LiteralPath (Join-Path $modsRoot 'mods.txt'))) { throw 'The existing UE4SS installation was not found. No game files changed.' }
$source=Join-Path $projectRoot 'kards-mod\KardsBridge\Scripts'
$scriptNames=@('main.lua','bridge_core.lua','kards_adapter.lua','bridge_settings.lua','camera_follow.lua')
foreach($name in $scriptNames) { if (-not(Test-Path -LiteralPath (Join-Path $source $name))) { throw "Missing source $name" } }
$backup=Join-Path $projectRoot ('tools\install-backups\'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$relativeFiles=@('mods.txt','ConsoleEnablerMod\Scripts\main.lua') + @($scriptNames | ForEach-Object { 'KardsBridge\Scripts\'+$_ })
$records=@()
foreach($relative in $relativeFiles) {
    $target=Join-Path $modsRoot $relative
    $exists=Test-Path -LiteralPath $target
    $records+=@{relative=$relative;existed=$exists}
    if($exists) {
        $saved=Join-Path $backup $relative
        New-Item -ItemType Directory -Path (Split-Path $saved) -Force | Out-Null
        Copy-Item -LiteralPath $target -Destination $saved
    }
}
@{modsRoot=$modsRoot;files=$records} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $backup 'manifest.json') -Encoding utf8
$dest=Join-Path $modsRoot 'KardsBridge\Scripts'
New-Item -ItemType Directory -Path $dest -Force | Out-Null
foreach($name in $scriptNames) { Copy-Item -LiteralPath (Join-Path $source $name) -Destination (Join-Path $dest $name) -Force }
$bridgeDir=Join-Path $projectRoot 'bridge'
New-Item -ItemType Directory -Path $bridgeDir -Force | Out-Null
$luaDirectory=$bridgeDir.Replace('\','\\').Replace('"','\"')
[IO.File]::WriteAllText((Join-Path $dest 'bridge_settings.lua'),('return {directory="'+$luaDirectory+'",damageMode="memory_setter"}'),[Text.UTF8Encoding]::new($false))
$modsFile=Join-Path $modsRoot 'mods.txt'
$mods=[IO.File]::ReadAllText($modsFile)
if($mods -match '(?m)^\s*KardsBridge\s*:') { $mods=[regex]::Replace($mods,'(?m)^\s*KardsBridge\s*:\s*\d+','KardsBridge : 1') }
else { $mods="KardsBridge : 1`r`n"+$mods }
[IO.File]::WriteAllText($modsFile,$mods,[Text.UTF8Encoding]::new($false))
$console=Join-Path $modsRoot 'ConsoleEnablerMod\Scripts\main.lua'
if(Test-Path -LiteralPath $console) {
    $text=[IO.File]::ReadAllText($console).Replace('UEHelpers.FindFName("F10")','UEHelpers.FindFName("F11")')
    [IO.File]::WriteAllText($console,$text,[Text.UTF8Encoding]::new($false))
}
$configDir=Join-Path $projectRoot 'minecraft-mod\run\config'
New-Item -ItemType Directory -Path $configDir -Force | Out-Null
$configPath=Join-Path $configDir 'kardscraft.properties'
$config=if(Test-Path -LiteralPath $configPath) { [IO.File]::ReadAllText($configPath) } else { '' }
$property='bridgeDir='+$bridgeDir.Replace('\','/')
$existingBridge=[regex]::Match($config,'(?m)^bridgeDir=([^\r\n]*)')
# Java's Properties.store escapes the drive colon. Preserve the existing file
# byte-for-byte when the directory already matches; retain its line endings otherwise.
$configuredBridge=if($existingBridge.Success) { $existingBridge.Groups[1].Value.Replace('\:',':').Replace('\\','/') } else { '' }
if($configuredBridge -ne $bridgeDir.Replace('\','/')) {
    if($existingBridge.Success) { $config=$config.Substring(0,$existingBridge.Index)+$property+$config.Substring($existingBridge.Index+$existingBridge.Length) }
    else { $config+="`r`n"+$property+"`r`n" }
    [IO.File]::WriteAllText($configPath,$config,[Text.UTF8Encoding]::new($false))
}
Write-Host "Installed KardsBridge. Backup: $backup"
Write-Host "Shared bridge directory: $bridgeDir"
Write-Host 'Start KARDS yourself from Steam in a training match, then use Run-Minecraft.cmd.'
