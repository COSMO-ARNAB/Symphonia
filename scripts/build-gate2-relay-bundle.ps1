param(
    [Parameter(Mandatory)][string]$NodeSdk,
    [Parameter(Mandatory)][string]$AndroidWorker,
    [Parameter(Mandatory)][string]$BundleDirectory,
    [string]$Ndk = "$env:LOCALAPPDATA/Android/Sdk/ndk/28.2.13676358"
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $true
$repo = Split-Path -Parent $PSScriptRoot
$signaling = Join-Path $repo "signaling"
$probe = Join-Path $repo "android/gate2-harness/src/debug/relay-probe"
$llvm = Join-Path $Ndk "toolchains/llvm/prebuilt/windows-x86_64"
$worker = (Resolve-Path -LiteralPath $AndroidWorker).Path
$nodeHeader = (Resolve-Path -LiteralPath "$NodeSdk/include/node").Path
$nodeLibrary = (Resolve-Path -LiteralPath "$NodeSdk/bin/arm64-v8a/libnode.so").Path

$elf = & "$llvm/bin/llvm-readelf.exe" --file-header --program-headers $worker
if (-not ($elf -match "Machine:\s+AArch64") -or -not ($elf -match "/system/bin/linker64")) {
    throw "AndroidWorker must be an ARM64 Android executable"
}

$bundle = [IO.Path]::GetFullPath($BundleDirectory)
$assets = Join-Path $bundle "assets"
$libraries = Join-Path $bundle "jniLibs/arm64-v8a"
New-Item -ItemType Directory -Force $assets, $libraries | Out-Null

$staging = Join-Path ([IO.Path]::GetTempPath()) ("symphonia-relay-" + [Guid]::NewGuid())
New-Item -ItemType Directory $staging | Out-Null
try {
    npm --prefix $signaling run build
    Copy-Item -LiteralPath "$signaling/package.json", "$signaling/package-lock.json" -Destination $staging
    Copy-Item -LiteralPath "$signaling/dist" -Destination $staging -Recurse
    Copy-Item -LiteralPath "$probe/phone-relay.cjs", "$probe/probe.cjs", "$probe/tone.html" -Destination $staging
    Copy-Item -LiteralPath "$signaling/public/mediasoup-client.esm.js" -Destination "$staging/client.js"
    npm --prefix $staging ci --omit=dev --ignore-scripts --no-audit --no-fund
    ffmpeg -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=440:sample_rate=48000" -t 2 -ac 2 -c:a libopus -b:a 64k -frame_duration 20 "$staging/tone.ogg"

    tar.exe -a -cf "$assets/phone-relay.zip" -C $staging phone-relay.cjs package.json dist node_modules
    tar.exe -a -cf "$assets/probe.zip" -C $staging probe.cjs node_modules client.js tone.ogg tone.html

    Copy-Item -LiteralPath $nodeLibrary -Destination "$libraries/libnode.so" -Force
    Copy-Item -LiteralPath $worker -Destination "$libraries/libmediasoup_worker.so" -Force
    Copy-Item -LiteralPath "$llvm/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" -Destination $libraries -Force
    & "$llvm/bin/clang++.exe" --target=aarch64-linux-android29 -fPIC -shared -std=c++20 -I $nodeHeader "$probe/probe.cpp" -L $libraries -lnode -o "$libraries/librelay_probe.so"
} finally {
    $resolvedStaging = (Resolve-Path -LiteralPath $staging).Path
    $stagingParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if ((Split-Path -Parent $resolvedStaging) -eq $stagingParent -and
        (Split-Path -Leaf $resolvedStaging) -match '^symphonia-relay-[a-f0-9-]{36}$') {
        Remove-Item -LiteralPath $resolvedStaging -Recurse -Force
    }
}
Write-Output $bundle
