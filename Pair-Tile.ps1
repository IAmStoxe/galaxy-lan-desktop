#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Serial,
    [string]$SdkRoot = $env:ANDROID_HOME
)

$ErrorActionPreference = 'Stop'
if (!$SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
if (!(Test-Path $adb)) { throw 'Install Android SDK Platform-Tools.' }
$state = & $adb -s $Serial get-state
if ($LASTEXITCODE -ne 0 -or $state -ne 'device') { throw 'Authorize the phone over USB first.' }

Write-Host 'On the phone, go to Developer options → Wireless debugging → Pair device with pairing code.'
Write-Host 'Keep that dialog open. Use the port shown in that dialog.'
$portText = Read-Host 'Pairing port'
$port = 0
if (![int]::TryParse($portText, [ref]$port) -or $port -lt 1 -or $port -gt 65535) { throw 'Invalid port.' }
$secret = Read-Host 'Six-digit pairing code' -AsSecureString
$code = [Net.NetworkCredential]::new('', $secret).Password
try {
    if ($code -notmatch '^\d{6}$') { throw 'Invalid pairing code.' }
    $result = & $adb -s $Serial shell am broadcast --user 0 --include-stopped-packages -n org.galaxylan.desktop/.PairController --ei port $port --es code $code
    if ($LASTEXITCODE -ne 0 -or $result -notmatch 'Broadcast completed: result=1') { throw 'Pairing failed. Open a fresh pairing dialog and retry.' }
} finally {
    $code = $null
    $secret.Dispose()
}
& $adb -s $Serial shell pm grant org.galaxylan.desktop android.permission.WRITE_SECURE_SETTINGS
if ($LASTEXITCODE -ne 0) { throw 'Pairing completed, but the permission to enable Wireless debugging after reboot failed.' }
Write-Host 'Paired. Use the DeX Browser tile to start or stop the desktop.'
