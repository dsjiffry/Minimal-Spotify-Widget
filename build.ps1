# Builds NowPlayingWidget.apk without Gradle: aapt2 + javac + d8 + zipalign + apksigner.
#   .\build.ps1    -> NowPlayingWidget.apk (signed with a local dev key; sideload with adb)
# SDK: -Sdk <path>, else $env:ANDROID_HOME / $env:ANDROID_SDK_ROOT / %LOCALAPPDATA%\Android\Sdk.
param([string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }))
if (-not (Test-Path $Sdk)) { throw "Android SDK not found: set ANDROID_HOME or pass -Sdk <path>" }
# Native tools print notes on stderr; failures are detected via exit codes (Check) instead.
$ErrorActionPreference = "Continue"
Push-Location $PSScriptRoot
try {
    $bt = Get-ChildItem "$Sdk\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
    $jar = Get-ChildItem "$Sdk\platforms" -Directory -Filter "android-*" | Sort-Object { [int]($_.Name -replace '\D') } -Descending |
        Select-Object -First 1 | ForEach-Object { Join-Path $_.FullName "android.jar" }
    if (Test-Path build) { Remove-Item -Recurse -Force build }
    New-Item -ItemType Directory -Force build\classes, build\dex, build\flat, build\gen | Out-Null

    function Check($what) { if ($LASTEXITCODE -ne 0) { throw "$what failed ($LASTEXITCODE)" } }
    function Rel($items) { $items | ForEach-Object { Resolve-Path -Relative $_.FullName } }

    # Resources (+ generated R.java)
    & "$bt\aapt2.exe" compile --dir res -o build\flat; Check "aapt2 compile"
    $flats = Rel (Get-ChildItem build\flat -Filter *.flat)
    & "$bt\aapt2.exe" link -I $jar --manifest AndroidManifest.xml --java build\gen -o build\unsigned.apk $flats; Check "aapt2 link"

    # Code
    $srcs = Rel (Get-ChildItem src, build\gen -Recurse -Filter *.java)
    javac -nowarn -Xlint:-options --release 8 -classpath $jar -encoding UTF-8 -d build\classes $srcs; Check "javac"
    jar cf build\classes.jar -C build\classes .; Check "jar"
    & "$bt\d8.bat" --release --min-api 31 --lib $jar --output build\dex build\classes.jar; Check "d8"

    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open((Resolve-Path build\unsigned.apk).Path, "Update")
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Resolve-Path build\dex\classes.dex).Path, "classes.dex") | Out-Null
    $zip.Dispose()
    & "$bt\zipalign.exe" -p -f 4 build\unsigned.apk build\aligned.apk; Check "zipalign"
    if (-not (Test-Path signing.keystore)) {
        cmd /c 'keytool -genkeypair -keystore signing.keystore -storepass android -keypass android -alias app -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Dev" 2>nul'; Check "keytool"
    }
    & "$bt\apksigner.bat" sign --ks signing.keystore --ks-pass pass:android --ks-key-alias app --out NowPlayingWidget.apk build\aligned.apk; Check "apksigner"
    Write-Host "Built $PSScriptRoot\NowPlayingWidget.apk"
} finally {
    Pop-Location
}
