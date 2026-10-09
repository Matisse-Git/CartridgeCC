param([string]$Sdk = $env:ANDROID_HOME, [ValidateSet('Surface', 'Battery')][string]$Probe = 'Surface')
$ErrorActionPreference = 'Stop'
if (-not $Sdk) { $Sdk = Join-Path $PSScriptRoot '../.tools/sdk' }
$clang = Join-Path $Sdk 'ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
$objcopy = Join-Path $Sdk 'ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-objcopy.exe'
$destination = Join-Path $PSScriptRoot '../.tools/probe'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$source = if ($Probe -eq 'Battery') { 'gba_sram_probe' } else { 'gba_probe' }
$romName = if ($Probe -eq 'Battery') { 'battery-probe.gba' } else { 'cartridge-probe.gba' }
& $clang --target=arm-none-eabi -march=armv4t -nostdlib -fuse-ld=lld '-Wl,-Ttext=0x08000000' `
    (Join-Path $PSScriptRoot "probe/$source.S") -o (Join-Path $destination "$source.elf")
if ($LASTEXITCODE -ne 0) { throw 'Probe assembly failed' }
& $objcopy -O binary (Join-Path $destination "$source.elf") (Join-Path $destination $romName)
if ($LASTEXITCODE -ne 0) { throw 'Probe export failed' }
Write-Output (Join-Path $destination $romName)
