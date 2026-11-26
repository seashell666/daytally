# Build the Windows desktop client into a single EXE.
# All Python build tooling stays inside C:\windows软件编译\venv.
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Python = "C:\windows软件编译\venv\Scripts\python.exe"
$Icon = Join-Path $Root "build\daytally.ico"

if (-not (Test-Path -LiteralPath $Python)) {
    throw "找不到构建用 Python：$Python。请先在 C:\windows软件编译 下保留 venv。"
}

if (-not (Test-Path -LiteralPath $Icon)) {
    & $Python (Join-Path $Root "tools\make_icon.py")
}

& $Python -m PyInstaller `
    --noconfirm `
    --clean `
    --windowed `
    --onefile `
    --name "日刻数据中台" `
    --icon $Icon `
    --add-data "$Root\web;web" `
    --collect-submodules webview `
    --collect-all clr_loader `
    --distpath (Join-Path $Root "dist") `
    --workpath (Join-Path $Root "build\pyi") `
    --specpath (Join-Path $Root "build") `
    (Join-Path $Root "app.py")

Write-Host ""
Write-Host "EXE: $(Join-Path $Root 'dist\日刻数据中台.exe')"
