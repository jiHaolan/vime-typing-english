param([string]$Prebuilt = (Join-Path $PSScriptRoot '../../work/rime-prebuilt'),
      [Parameter(Mandatory=$true)][string]$EngineBuild,
      [string]$BuildRoot = (Join-Path $PSScriptRoot 'native-build/source-core'),
      [string]$Sdk = 'E:/Android/Sdk')
$ErrorActionPreference = 'Stop'
$enginePath = [IO.Path]::GetFullPath($EngineBuild).Replace('\','/')
if (-not (Test-Path -LiteralPath (Join-Path $enginePath 'receipt.json'))) { throw 'Verified source-engine receipt required' }
$versionMatch = [regex]::Match((Get-Content -LiteralPath (Join-Path $PSScriptRoot 'app/build.gradle.kts') -Raw), 'versionName\s*=\s*"([0-9.]+)"')
if (-not $versionMatch.Success) { throw 'Application version not found' }
$distributionVersion = $versionMatch.Groups[1].Value
$cmake = Join-Path $Sdk 'cmake/3.31.6/bin/cmake.exe'
$ninja = Join-Path $Sdk 'cmake/3.31.6/bin/ninja.exe'
$toolchain = Join-Path $Sdk 'ndk/28.0.13004108/build/cmake/android.toolchain.cmake'
foreach ($abi in @('arm64-v8a','armeabi-v7a','x86_64')) {
    $build = Join-Path $BuildRoot $abi
    & $cmake -S (Join-Path $PSScriptRoot 'native') -B $build -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" "-DANDROID_ABI=$abi" '-DANDROID_PLATFORM=android-24' '-DANDROID_STL=c++_shared' '-DCMAKE_BUILD_TYPE=Release' "-DRIME_PREBUILT=$([IO.Path]::GetFullPath($Prebuilt).Replace('\','/'))" "-DVIME_ENGINE_BUILD=$enginePath" "-DVIME_DISTRIBUTION_VERSION=$distributionVersion"
    if ($LASTEXITCODE -ne 0) { throw 'Native configure failed' }
    & $cmake --build $build -j 3
    if ($LASTEXITCODE -ne 0) { throw 'Native build failed' }
    $dest = Join-Path $PSScriptRoot "app/src/main/jniLibs/$abi"
    New-Item -ItemType Directory -Path $dest -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $build 'libvime_rime.so') -Destination $dest
    $arch = if ($abi -eq 'arm64-v8a') { 'aarch64-linux-android' } elseif ($abi -eq 'armeabi-v7a') { 'arm-linux-androideabi' } else { 'x86_64-linux-android' }
    Copy-Item -LiteralPath (Join-Path $Sdk "ndk/28.0.13004108/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/$arch/libc++_shared.so") -Destination $dest
    & (Join-Path $Sdk 'ndk/28.0.13004108/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe') --strip-unneeded (Join-Path $dest 'libvime_rime.so')
    & (Join-Path $Sdk 'ndk/28.0.13004108/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe') --strip-unneeded (Join-Path $dest 'libc++_shared.so')
}
