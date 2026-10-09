param([string]$KardsRoot='E:\SteamLibrary\steamapps\common\KARDS')
$ErrorActionPreference='Stop'
$output=Join-Path $PSScriptRoot ('tools\diagnostics\'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $output -Force | Out-Null
$bridgeDir=Join-Path $PSScriptRoot 'bridge'
$config=Join-Path $PSScriptRoot 'minecraft-mod\run\config\kardscraft.properties'
if(Test-Path -LiteralPath $config) {
    $line=Get-Content -LiteralPath $config | Where-Object { $_ -match '^bridgeDir=' } | Select-Object -Last 1
    if($line) {
        $configuredDir=$line.Substring(10).Trim().Replace('\\','\').Replace('\:',':')
        if($configuredDir) { $bridgeDir=$configuredDir } else { $bridgeDir=$env:TEMP }
    }
    Copy-Item -LiteralPath $config -Destination (Join-Path $output 'kardscraft.properties')
}
$state=Join-Path $bridgeDir 'KardsBridge_state.json'
if(Test-Path -LiteralPath $state) {
    Copy-Item -LiteralPath $state -Destination (Join-Path $output 'KardsBridge_state.json')
    try {
        $s=Get-Content -LiteralPath $state -Raw -Encoding utf8 | ConvertFrom-Json
        $s | Select-Object protocol,sessionId,matchId,inMatch,ai,mySide,myTurn,turn,status,ready,busy,geometryReady,damageMode,backend,serverPersistenceVerified,missingFields,referenceIssues,busyReasons,unreadableCards,unpositionedCards,lastDamage,damageLogError,results | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $output 'bridge-summary.json') -Encoding utf8
    } catch { $_.Exception.Message | Set-Content -LiteralPath (Join-Path $output 'state-parse-error.txt') }
}
foreach($journal in @('KardsBridge_damage.jsonl','KardsBridge_damage.jsonl.1')) {
    $journalPath=Join-Path $bridgeDir $journal
    if(Test-Path -LiteralPath $journalPath) { Copy-Item -LiteralPath $journalPath -Destination (Join-Path $output $journal) }
}
$logs=@{
    'minecraft-latest.log'=Join-Path $PSScriptRoot 'minecraft-mod\run\logs\latest.log'
    'UE4SS-tail.log'=Join-Path $KardsRoot 'kards\Binaries\Win64\ue4ss\UE4SS.log'
    'build.log'=Join-Path $PSScriptRoot 'tools\build.log'
}
foreach($name in $logs.Keys) {
    if(Test-Path -LiteralPath $logs[$name]) { Get-Content -LiteralPath $logs[$name] -Tail 600 | Set-Content -LiteralPath (Join-Path $output $name) -Encoding utf8 }
}
@{collectedAt=(Get-Date).ToString('o');bridgeDir=$bridgeDir;stateExists=(Test-Path -LiteralPath $state);gameWasNotStarted=$true} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'collection.json') -Encoding utf8
Write-Host "Diagnostics saved locally: $output"
Write-Host 'No game was started and no files were uploaded.'
