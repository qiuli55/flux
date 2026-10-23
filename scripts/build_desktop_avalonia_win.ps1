# 构建 Flux 原生桌面客户端（Avalonia / .NET 8）Windows 自包含产物。
#
# 用法（在仓库根执行）:
#   pwsh scripts/build_desktop_avalonia_win.ps1            # 默认 win-x64
#   pwsh scripts/build_desktop_avalonia_win.ps1 win-arm64
# 产物落在 apps/desktop-avalonia/dist/<RID>/，压缩包在 apps/desktop-avalonia/release/。
# 只能产出 Windows 包；Linux 包用 scripts/build_desktop_avalonia_linux.sh（交叉发布仍需在对应系统上跑通验证）。
param(
    [string]$Rid = "win-x64"
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$App = Join-Path $Root "apps/desktop-avalonia"
$Out = Join-Path $App "dist/$Rid"
$Pkg = Join-Path $App "release"

dotnet publish (Join-Path $App "Flux.Desktop.csproj") -c Release -r $Rid --self-contained true -o $Out
if ($LASTEXITCODE -ne 0) { throw "dotnet publish 失败（$Rid）" }

New-Item -ItemType Directory -Force -Path $Pkg | Out-Null
$Zip = Join-Path $Pkg "flux-desktop-$Rid.zip"
if (Test-Path $Zip) { Remove-Item $Zip }
Compress-Archive -Path (Join-Path $Out "*") -DestinationPath $Zip
Write-Host "已生成 $Zip"
Write-Host "运行: $(Join-Path $Out 'Flux.Desktop.exe')（需要仓库内 backend/ 与 .venv，或设置 FLUX_REPO_ROOT / FLUX_PYTHON）"
