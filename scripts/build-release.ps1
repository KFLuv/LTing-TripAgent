param(
    [string]$JavaHome = "E:\JDK11"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $root "backend"
$desktop = Join-Path $root "desktop"
$jar = Join-Path $backend "target\chat-gpt.jar"

& (Join-Path $root "scripts\check-secrets.ps1")

if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
}

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if ($mvn) {
    Push-Location $backend
    try {
        & $mvn.Source -B clean package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
    } finally {
        Pop-Location
    }
} else {
    if (-not (Test-Path -LiteralPath $jar)) {
        throw "Maven is unavailable and no prebuilt backend jar exists."
    }
    Write-Warning "Maven is unavailable; using existing backend/target/chat-gpt.jar."
}

Push-Location $desktop
try {
    if (-not (Test-Path -LiteralPath "node_modules")) {
        npm ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed" }
    }
    npm run jre
    if ($LASTEXITCODE -ne 0) { throw "JRE build failed" }
    npx electron-builder --win portable --publish never
    if ($LASTEXITCODE -ne 0) { throw "Electron build failed" }
} finally {
    Pop-Location
}

Get-ChildItem -LiteralPath (Join-Path $desktop "release_app") -Filter "*.exe" |
    Select-Object FullName, Length, LastWriteTime
