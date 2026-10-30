param(
    [Parameter(Mandatory = $true)][string]$Output,
    [Parameter(Mandatory = $true)][string]$VersionName,
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$Gradle = (Join-Path $PSScriptRoot 'gradlew.bat'),
    [string]$Python = 'python',
    [string]$PreviousApk,
    [switch]$Validation,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
if (!$JavaHome -or !$AndroidSdk) { throw 'Set JAVA_HOME and ANDROID_HOME or pass -JavaHome and -AndroidSdk.' }
$releaseArgs = @((Join-Path $PSScriptRoot 'tools\build_release.py'), '--output', $Output,
    '--version-name', $VersionName, '--version-code', $VersionCode, '--java-home', $JavaHome,
    '--sdk', $AndroidSdk, '--gradle', $Gradle)
if ($PreviousApk) { $releaseArgs += @('--previous-apk', $PreviousApk) }
if ($Validation) { $releaseArgs += '--validation' }
if ($Offline) { $releaseArgs += '--offline' }
& $Python @releaseArgs
if ($LASTEXITCODE -ne 0) { throw 'Release candidate preparation failed. No upload was performed.' }
