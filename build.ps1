param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [switch]$SkipBuild,
    [switch]$Install,
    [switch]$Log
)

$ErrorActionPreference = 'Stop'
if (!$JavaHome -and (Test-Path 'C:\Program Files\Android\Android Studio\jbr')) {
    $JavaHome = 'C:\Program Files\Android\Android Studio\jbr'
}
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if (!$AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:ANDROID_HOME = $AndroidSdk

$buildTools = Get-ChildItem (Join-Path $AndroidSdk 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
if (!(Test-Path $apksigner)) { throw 'Install Android SDK Build-Tools first.' }

$propertiesPath = Join-Path $PSScriptRoot 'gradle.properties'
$properties = Get-Content $propertiesPath -Raw
$versionMatch = [regex]::Match($properties, '(?m)^moduleVersionCode=(\d+)\r?$')
if (!$versionMatch.Success) { throw 'Missing moduleVersionCode in gradle.properties.' }
$nextVersion = [int]$versionMatch.Groups[1].Value + 1

Push-Location $PSScriptRoot
try {
    $apk = Join-Path $PSScriptRoot 'build\app\outputs\apk\debug\app-debug.apk'
    if ($SkipBuild) {
        if (!(Test-Path -LiteralPath $apk)) { throw 'No existing debug APK. Run without -SkipBuild first.' }
        $output = $apk
    } else {
        & .\gradlew.bat :app:assembleDebug "-PmoduleVersionCode=$nextVersion"
        if ($LASTEXITCODE -ne 0) { throw 'Build failed; version was not incremented.' }
        & $apksigner verify --print-certs $apk | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed.' }
        $builds = Join-Path $PSScriptRoot 'builds'
        New-Item -ItemType Directory -Path $builds -Force > $null
        $output = Join-Path $builds "HorizonOverrides-1.0.$nextVersion.apk"
        Copy-Item -LiteralPath $apk -Destination $output
        $properties = $properties -replace '(?m)^moduleVersionCode=\d+', "moduleVersionCode=$nextVersion"
        Set-Content -LiteralPath $propertiesPath -Value $properties -NoNewline -Encoding UTF8
        Write-Host "Built and debug-signed: $output"
    }

    if (!$Install) { return }

    $adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
    $devices = @(& $adb devices | Select-String '\tdevice$' | ForEach-Object { ($_ -split '\t')[0] })
    if (!$devices) { throw 'No device is connected and authorized.' }
    if ($devices.Count -gt 1) { Write-Host "Installing to $($devices[0]) of $($devices.Count) devices." }
    & $adb -s $devices[0] install -r $output
    if ($LASTEXITCODE -ne 0) { throw 'ADB install failed.' }

    if (!$Log) {
        Write-Host 'Installed. Enable the module, then reboot or restart com.oculus.horizon to load it.'
        return
    }
    & $adb -s $devices[0] logcat -c
    Write-Host 'Module log follows (HorizonConfig); Ctrl+C to stop.'
    & $adb -s $devices[0] logcat -s 'HorizonConfig:V'
} finally {
    Pop-Location
}
