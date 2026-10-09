param([ValidateSet('build','test','prepare','run')][string]$Task='build')
$ErrorActionPreference='Stop'
$projectRoot=$PSScriptRoot
$javaCandidates=@(
    (Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-delta'),
    $env:JAVA_HOME
)
$javaRoot=$javaCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\javac.exe')) } | Select-Object -First 1
if (-not $javaRoot) { throw 'Java 21 JDK is required. Set JAVA_HOME to a Java 21 JDK, or install the Minecraft Java runtime.' }
$env:JAVA_HOME=$javaRoot
$env:GRADLE_USER_HOME=Join-Path $projectRoot 'tools\gradle-cache'
$tempRoot=Join-Path $projectRoot 'tools\tmp'
New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
# Windows JDK pipes use a different temporary-directory property than java.io.tmpdir.
$javaOptions=@(('"-Djdk.net.unixdomain.tmpdir={0}"' -f $tempRoot),('"-Djava.io.tmpdir={0}"' -f $tempRoot))
$proxyAddress=$env:HTTPS_PROXY
if (-not $proxyAddress) { $proxyAddress=$env:HTTP_PROXY }
if ($proxyAddress) {
    $proxyUri=[Uri]$proxyAddress
    if ($proxyUri.Scheme -eq 'http' -and -not $proxyUri.UserInfo) {
        $javaOptions += "-Dhttp.proxyHost=$($proxyUri.Host)","-Dhttp.proxyPort=$($proxyUri.Port)","-Dhttps.proxyHost=$($proxyUri.Host)","-Dhttps.proxyPort=$($proxyUri.Port)"
    }
}
$env:JAVA_TOOL_OPTIONS=($javaOptions -join ' ')
[string[]]$gradleTasks=@(switch($Task) {
    'build' { @('build') }
    'test' { @('test') }
    'prepare' { @('downloadAssets','configureClientLaunch') }
    'run' { @('runClient') }
})
Push-Location (Join-Path $projectRoot 'minecraft-mod')
try {
    & '.\gradlew.bat' @gradleTasks '--console=plain' '--no-daemon'
    $result=$LASTEXITCODE
    if ($result -ne 0) { throw "Gradle failed (exit $result)." }
} finally { Pop-Location }
