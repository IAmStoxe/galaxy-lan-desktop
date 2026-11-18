#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$WithTile,
    [switch]$BuildOnly,
    [switch]$NoStart
)

$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$build = Join-Path $project '.build'
$payload = Join-Path $build 'payload'
$remote = '/data/local/tmp/galaxy-lan-desktop'
New-Item -Path $payload -ItemType Directory -Force | Out-Null

function Invoke-Native {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$(Split-Path $Program -Leaf) failed (exit $LASTEXITCODE)."
    }
}

function Get-Dependency {
    param([string]$Filename, [string]$Uri, [string]$SHA256)
    $target = Join-Path $build $Filename
    if (!(Test-Path $target)) {
        Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile "$target.download"
        if ((Get-FileHash "$target.download").Hash -ne $SHA256) {
            throw "Unexpected SHA-256 for $Filename."
        }
        Move-Item "$target.download" $target
    }
    if ((Get-FileHash $target).Hash -ne $SHA256) { throw "Unexpected SHA-256 for $target." }
    return $target
}

if (!$SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$sdkJar = Join-Path $SdkRoot 'platforms\android-34\android.jar'
if (!(Test-Path $sdkJar)) { throw 'Install Android SDK Platform 34 or pass -SdkRoot.' }
$buildTools = Join-Path $SdkRoot 'build-tools\34.0.0'
$d8 = Join-Path $buildTools 'd8.bat'
if (!(Test-Path $d8)) { throw 'Install Android SDK Build-Tools 34.0.0.' }
$go = (Get-Command go).Source
if (!$JavaHome) {
    $JavaHome = Split-Path (Split-Path (Get-Command javac).Source)
}
$javac = Join-Path $JavaHome 'bin\javac.exe'
$jar = Join-Path $JavaHome 'bin\jar.exe'
$env:JAVA_HOME = $JavaHome

$gosamba = Get-Dependency 'gosamba' `
    'https://github.com/ahmetozer/gosamba/releases/download/v1.3.8/gosamba-v1.3.8-linux-arm64' `
    '0F72E81AE91C95D4C6ACD4130F9E82558AB7ED2BCE9D82B58A70ADA925C2D1C1'
Copy-Item $gosamba $payload -Force
Copy-Item (Join-Path $project 'device\*') $payload -Force

Push-Location $project
try {
    $env:GOOS = 'linux'
    $env:GOARCH = 'arm64'
    $env:CGO_ENABLED = '0'
    Invoke-Native $go @('build','-trimpath','-o',"$payload\dexbrowser",'./src')
    Invoke-Native $go @('build','-trimpath','-o',"$payload\web",'./cmd/web')
} finally {
    Pop-Location
}

$classes = Join-Path $build 'classes'
New-Item -Path $classes -ItemType Directory -Force | Out-Null
foreach ($name in @('HeadlessDex','DexFrame','DexVideo','DexInput')) {
    Invoke-Native $javac @('--release','8','-cp',$sdkJar,'-d',$classes,"$project\src\$name.java")
    Invoke-Native $d8 @('--min-api','34','--lib',$sdkJar,'--output',"$payload\$name.jar","$classes\$name.class")
}

if ($WithTile) {
    $upstream = Get-Dependency 'libadb.so' `
        'https://raw.githubusercontent.com/tytydraco/LADB/60f48029cf9d8e0bc848ca41a7bd76694d4ab796/app/src/main/jniLibs/arm64-v8a/libadb.so' `
        '47EA035FA5ED57F6149A2B025BBBD4B21584C355C05D0400416804715E4C12DE'
    $tile = Join-Path $project 'src\tile'
    $package = Join-Path $build 'package'
    $tileClasses = Join-Path $build 'tile-classes'
    New-Item -Path "$package\lib\arm64-v8a",$tileClasses -ItemType Directory -Force | Out-Null
    Copy-Item $upstream "$package\lib\arm64-v8a\libadb.so" -Force
    $javaFiles = @(Get-ChildItem $tile -Filter '*.java' | ForEach-Object FullName)
    Invoke-Native $javac (@('--release','8','-cp',$sdkJar,'-d',$tileClasses) + $javaFiles)
    Invoke-Native $jar @('cf',"$build\tile-classes.jar",'-C',$tileClasses,'.')
    Invoke-Native $d8 @('--min-api','34','--lib',$sdkJar,'--output',$package,"$build\tile-classes.jar")
    Invoke-Native "$buildTools\aapt2.exe" @('compile','--dir',"$tile\res",'-o',"$build\res.zip")
    Invoke-Native "$buildTools\aapt2.exe" @('link','-I',$sdkJar,'--manifest',"$tile\AndroidManifest.xml",'-o',"$build\unsigned.apk","$build\res.zip")
    Push-Location $package
    try {
        Invoke-Native "$buildTools\aapt.exe" @('add',"$build\unsigned.apk",'classes.dex','lib/arm64-v8a/libadb.so')
    } finally { Pop-Location }
    Invoke-Native "$buildTools\zipalign.exe" @('-f','4',"$build\unsigned.apk","$build\aligned.apk")
    $keystore = Join-Path $build 'tile.p12'
    $keyPassword = Join-Path $build 'tile-signing.dpapi'
    try {
        if (!(Test-Path $keystore)) {
            $env:GALAXY_TILE_SIGNING = [Guid]::NewGuid().ToString('N')
            Invoke-Native "$JavaHome\bin\keytool.exe" @('-genkeypair','-storetype','PKCS12','-keystore',$keystore,'-storepass:env','GALAXY_TILE_SIGNING','-alias','tile','-keyalg','RSA','-validity','3650','-dname','CN=Galaxy LAN Desktop')
            ConvertTo-SecureString $env:GALAXY_TILE_SIGNING -AsPlainText -Force | ConvertFrom-SecureString | Set-Content $keyPassword
        } else {
            $storedPassword = Get-Content $keyPassword | ConvertTo-SecureString
            $env:GALAXY_TILE_SIGNING = [Net.NetworkCredential]::new('', $storedPassword).Password
        }
        Invoke-Native "$buildTools\apksigner.bat" @('sign','--ks',$keystore,'--ks-pass','env:GALAXY_TILE_SIGNING','--out',"$build\galaxy-lan-tile.apk","$build\aligned.apk")
        Invoke-Native "$buildTools\apksigner.bat" @('verify',"$build\galaxy-lan-tile.apk")
    } finally {
        Remove-Item Env:GALAXY_TILE_SIGNING -ErrorAction SilentlyContinue
    }
}

if ($BuildOnly) {
    Write-Host "Built device files in $payload"
    exit 0
}

$adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
if (!(Test-Path $adb)) { throw 'Install Android SDK Platform-Tools.' }
if (!$Serial) {
    $devices = @(Invoke-Native $adb @('devices') | Where-Object { $_ -match '^\S+\s+device\s*$' })
    if ($devices.Count -ne 1) { throw 'Connect and authorize one device or pass -Serial from adb devices.' }
    $Serial = ($devices[0] -split '\s+')[0]
}
$device = @('-s',$Serial)
$abi = (Invoke-Native $adb ($device + @('shell','getprop','ro.product.cpu.abi'))).Trim()
$api = (Invoke-Native $adb ($device + @('shell','getprop','ro.build.version.sdk'))).Trim()
if ($abi -ne 'arm64-v8a' -or $api -ne '34') { throw "Expected Android 14 ARM64, found API $api and $abi." }

$stage = "$remote/install-$([Guid]::NewGuid().ToString('N'))"
Invoke-Native $adb ($device + @('shell',"umask 077; mkdir -p '$stage'; chmod 700 '$remote' '$stage'"))
foreach ($name in @('dexbrowser','web','gosamba','HeadlessDex.jar','DexFrame.jar','DexVideo.jar','DexInput.jar','lan-services.sh','provision.sh','welcome.html')) {
    Invoke-Native $adb ($device + @('push',"$payload\$name","$stage/$name"))
}
$install = @"
set -eu
cd '$remote'
if [ -f lan-services.sh ]; then sh lan-services.sh stop; fi
cd '$stage'
chmod 700 dexbrowser web gosamba
chmod 400 HeadlessDex.jar DexFrame.jar DexVideo.jar DexInput.jar
mv dexbrowser web gosamba HeadlessDex.jar DexFrame.jar DexVideo.jar DexInput.jar lan-services.sh provision.sh welcome.html '$remote/'
cd '$remote'
rmdir '$stage'
sh provision.sh
"@
Invoke-Native $adb ($device + @('shell',$install.Replace("`r`n","`n")))

if ($WithTile) {
    Invoke-Native $adb ($device + @('install','--user','0','-r',"$build\galaxy-lan-tile.apk"))
    $setup = Invoke-Native $adb ($device + @('shell','am','broadcast','--user','0','--include-stopped-packages','-a','org.galaxylan.desktop.PREPARE','-n','org.galaxylan.desktop/.PairController'))
    if (!($setup -match 'Broadcast completed: result=1')) { throw 'Could not prepare the tile storage. Unlock the phone and retry.' }
    Invoke-Native $adb ($device + @('shell','cmd','statusbar','add-tile','org.galaxylan.desktop/.BrowserTile'))
    Write-Host 'For tile startup after a reboot, complete the optional Wireless debugging pairing in the README.'
}
if (!$NoStart) {
    Invoke-Native $adb ($device + @('shell','sh',"$remote/lan-services.sh",'start'))
    Invoke-Native $adb ($device + @('shell','sh',"$remote/lan-services.sh",'browser-on'))
}
Write-Host 'See the README for retrieving the generated passwords and operating the services.'
