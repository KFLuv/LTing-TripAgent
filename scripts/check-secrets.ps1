param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"
$patterns = @(
    'sk-[A-Za-z0-9_-]{16,}',
    '(?i)AI_API_KEY\s*=\s*[^\s#]{8,}',
    '(?i)api[_-]?key\s*=\s*[^\s#]{8,}',
    '(?i)Authorization\s*:\s*Bearer\s+[A-Za-z0-9._-]{20,}'
)

$files = & git -C $Root -c core.quotepath=false ls-files --cached --others --exclude-standard
if ($LASTEXITCODE -ne 0) {
    throw "Not a Git repository: $Root"
}

$findings = @()
foreach ($relative in $files) {
    $path = Join-Path $Root $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        continue
    }
    $lines = Get-Content -LiteralPath $path -ErrorAction SilentlyContinue
    if ($null -eq $lines) {
        continue
    }
    for ($lineNo = 0; $lineNo -lt $lines.Count; $lineNo++) {
        foreach ($pattern in $patterns) {
            if ($lines[$lineNo] -match $pattern) {
                $findings += "${relative}:$($lineNo + 1)"
                break
            }
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Error ("Potential secrets found:`n" + (($findings | Sort-Object -Unique) -join "`n"))
    exit 1
}

Write-Host "No high-confidence secrets found in tracked files."
