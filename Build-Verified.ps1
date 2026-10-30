param(
    [string]$JavaHome = 'E:\Program Files\Android\Android Studio\jbr',
    [string]$AndroidSdk = 'E:\Android\Sdk',
    [string]$Gradle = (Join-Path $PSScriptRoot 'gradlew.bat'),
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$oldJava = $env:JAVA_HOME
$oldAndroid = $env:ANDROID_HOME
$oldOptions = $env:JAVA_OPTS
$oldTools = $env:JAVA_TOOL_OPTIONS
$socketDir = Join-Path (Split-Path $AndroidSdk -Parent) 'jtmp'
try {
    if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'JDK not found' }
    if (!(Test-Path -LiteralPath $Gradle)) { throw 'Gradle not found' }
    New-Item -ItemType Directory -Path $socketDir -Force | Out-Null
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidSdk
    $env:JAVA_OPTS = '-Xmx2048m -Dfile.encoding=UTF-8'
    # Keep JBR socket files outside the desktop app's virtualized temporary directory.
    $env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=' + $socketDir.Replace('\', '/')
    Push-Location -LiteralPath $PSScriptRoot
    try {
        & $Gradle --no-daemon --console=plain '-Dorg.gradle.jvmargs=' '-Dorg.gradle.internal.http.connectionTimeout=10000' '-Dorg.gradle.internal.http.socketTimeout=10000' :app:writeRuntimeInventory assembleDebug testDebugUnitTest lintDebug
        if ($LASTEXITCODE -ne 0) { throw "Build/tests failed: $LASTEXITCODE" }
        & $Python tools/verify_notices.py app/src/main/assets --inventory app/build/reports/runtime-components.json
        if ($LASTEXITCODE -ne 0) { throw 'Packaged notice or dependency metadata mismatch' }
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $oldJava
    $env:ANDROID_HOME = $oldAndroid
    $env:JAVA_OPTS = $oldOptions
    $env:JAVA_TOOL_OPTIONS = $oldTools
}
