# Builds java/src -> 42/media/java/MordhauCombat.jar
# Usage: powershell -ExecutionPolicy Bypass -File build.ps1
param(
    [string]$PzDir  = "D:\SteamLibrary\steamapps\common\ProjectZomboid",
    [string]$JdkDir = ""
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

if (-not $JdkDir) {
    $JdkDir = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-25*" |
        Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
}
$javac = Join-Path $JdkDir "bin\javac.exe"
$jar   = Join-Path $JdkDir "bin\jar.exe"
if (-not (Test-Path $javac)) { throw "javac not found in '$JdkDir'" }

$cp = @(
    (Join-Path $PzDir "projectzomboid.jar"),
    (Join-Path $PzDir "ZombieBuddy.jar")
) -join ";"

$src     = Join-Path $root "java\src"
$classes = Join-Path $root "java\build\classes"
$out     = Join-Path $root "42\media\java\MordhauCombat.jar"

if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force $classes, (Split-Path $out) | Out-Null

$files = Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$argFile = Join-Path $root "java\build\sources.txt"
$files | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content -Encoding ASCII $argFile

# Target Java 17 bytecode (same as Viewpoint) so ZombieBuddy's ByteBuddy can read the advice classes.
# -source/-target instead of --release: PZ classes are Java 25 class files.
& $javac -encoding UTF-8 -source 17 -target 17 -Xlint:-options -nowarn -cp $cp -d $classes "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$tempJar = Join-Path $root "java\build\MordhauCombat.jar"
if (Test-Path $tempJar) { Remove-Item -Force $tempJar }
& $jar --create --file $tempJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

try {
    [System.IO.File]::Copy($tempJar, $out, $true)
    Write-Host "Built $out" -ForegroundColor Green
} catch {
    Write-Host "Compiled $tempJar (Close Project Zomboid to unlock $out)" -ForegroundColor Yellow
}
