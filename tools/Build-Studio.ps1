param([string]$Cmgen = (Join-Path $PSScriptRoot '../.tools/filament-1.72.1/bin/cmgen.exe'))
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not (Test-Path -LiteralPath $Cmgen)) { throw 'Pass -Cmgen pointing to official Filament 1.72.1 cmgen.' }
Push-Location $projectRoot
try {
    python tools/generate_studio.py
    if ($LASTEXITCODE -ne 0) { throw 'Studio source generation failed' }
    & $Cmgen --quiet --size=128 --format=ktx --deploy=.tools/studio/prefilter .tools/studio/cartridge_studio.hdr
    if ($LASTEXITCODE -ne 0) { throw 'Studio prefilter failed' }
    $destination = Join-Path $projectRoot 'app/src/main/assets/lighting'
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    Copy-Item -LiteralPath '.tools/studio/prefilter/prefilter_ibl.ktx' -Destination (Join-Path $destination 'studio_ibl.ktx')
} finally { Pop-Location }
