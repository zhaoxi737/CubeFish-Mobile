# 取上游 CI 预编译的 Android JRE8 产物（jre8-pojav），解压到 assets/components/jre
#
# 用法（需要 gh CLI 已登录）：
#   powershell -ExecutionPolicy Bypass -File scripts/fetch-jre.ps1
#
# 说明：JRE 不在上游仓库里（被 .gitignore 排除），CI 每次构建时用 GitHub Actions artifact 注入。
# 这里用 gh 直接拉同名 artifact，并做大小校验；artifact 过期时脚本会提示改用替代产物。

$ErrorActionPreference = 'Stop'
$repo = 'AngelAuraMC/angelauramc-openjdk-build'
$artifact = 'jre8-pojav'
$dest = Join-Path $PSScriptRoot '..\app_pojavlauncher\src\main\assets\components\jre'
$tmp = Join-Path $env:TEMP 'jre8-pojav.zip'

Write-Host "[1/4] 查询 artifact: $repo / $artifact"
$arts = gh api "repos/$repo/actions/artifacts?per_page=100" | ConvertFrom-Json
$pick = $arts.artifacts | Where-Object { $_.name -eq $artifact } | Select-Object -First 1
if (-not $pick) { throw "没找到 artifact $artifact（可能已过期）。可改用 FCL-Team/Android-OpenJDK-Build 的产物。" }
if ($pick.expired) { throw "artifact $artifact 已过期，请改用 FCL-Team/Android-OpenJDK-Build 的产物。" }
Write-Host ("       id={0} size={1}MB created={2}" -f $pick.id, [math]::Round($pick.size_in_bytes / 1MB, 1), $pick.created_at)

Write-Host "[2/4] 下载到 $tmp"
cmd /d /c "gh api repos/$repo/actions/artifacts/$($pick.id)/zip > `"$tmp`" 2>NUL"
$size = (Get-Item $tmp).Length
if ($size -lt 10MB) { throw "下载失败：文件只有 $([math]::Round($size / 1KB))KB" }
Write-Host ("       {0}MB sha256={1}" -f [math]::Round($size / 1MB, 1), (Get-FileHash $tmp -Algorithm SHA256).Hash)

Write-Host "[3/4] 解压到 $dest"
New-Item -ItemType Directory -Force $dest | Out-Null
Expand-Archive -LiteralPath $tmp -DestinationPath $dest -Force
Get-ChildItem $dest | Select-Object Name, Length | Format-Table -AutoSize

Write-Host "[4/4] 完成。现在可以运行 ./gradlew :app_pojavlauncher:assembleDebug"

Write-Host "[5/5] 取 JRE17 / JRE21（arm64，内置到 APK 里，避免真机联网下载 GitHub 超时）"
$extDir = Join-Path $PSScriptRoot '..\app_pojavlauncher\src\main\assets\components\jre-external'
New-Item -ItemType Directory -Force $extDir | Out-Null
$external = @(
  @{ tag = 'download_jre17'; name = 'jre17-android-arm64.tar.xz' },
  @{ tag = 'download_jre21'; name = 'jre21-android-arm64.tar.xz' }
)
foreach ($item in $external) {
  $dest = Join-Path $extDir $item.name
  if ((Test-Path $dest) -and ((Get-Item $dest).Length -gt 20MB)) { Write-Host "      已有 $($item.name)，跳过"; continue }
  $url = "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/$($item.tag)/$($item.name)"
  cmd /d /c "gh release download $($item.tag) -R AngelAuraMC/angelauramc-openjdk-build -p $($item.name) -D `"$extDir`" --clobber 2>NUL"
  if (Test-Path $dest) { Write-Host ("      {0} {1}MB" -f $item.name, [math]::Round((Get-Item $dest).Length / 1MB, 1)) }
}