# Galaxy LAN Desktop

Run an interactive Android desktop in a browser, share files over SMB, and host static files from a Samsung phone on your LAN. The desktop uses a virtual display with Samsung’s secondary launcher on the tested device. It works without a TV or root.

Tested on a Samsung Galaxy S22 Ultra SM-S908U1 running Android 14. The installer checks for Android 14 and ARM64. Other models and firmware may differ because display capture and input use Android shell APIs.

## Install from Linux, macOS, or Windows

1. Install [Go 1.25 or newer](https://go.dev/dl/), a JDK, and the [Android SDK](https://developer.android.com/tools) with **Platform-Tools**, **SDK Platform 34**, and **Build-Tools 34.0.0**. Set `ANDROID_HOME` and `JAVA_HOME` if the installer needs help finding them. The tested build used JDK 11. Use Bash and curl on Linux or macOS. Use [PowerShell 7](https://learn.microsoft.com/powershell/scripting/install/installing-powershell-on-windows) on Windows.
2. On the phone, turn on **USB debugging** in **Developer options**. Connect by USB and approve the computer. Connect the phone and browser to the same trusted Wi-Fi network.
3. From Bash on Linux or macOS, run:

   ```bash
   bash ./install.sh
   ```

   With multiple devices attached, pass `--serial YOUR_SERIAL` from `adb devices`. Use `--with-tile` for the optional Quick Settings tile, `--build-only` to compile without a phone, or `--no-start` to install without starting the services.

   On Windows, run:

   ```powershell
   pwsh -File .\Install.ps1
   ```

   With several devices connected, pass `-Serial YOUR_SERIAL` using the serial shown by `adb devices`. Use `-BuildOnly` to compile without a connected phone or `-NoStart` to install without starting any services. The installer prints the current LAN addresses.

## Use the desktop and shares

| Service | Address | Access |
| --- | --- | --- |
| Desktop and Utilities | `https://PHONE_IP:8443/` | User `dex` and the generated browser password |
| SMB share | `smb://PHONE_IP:4450/Files` | User `files` and a separate generated SMB password |
| Static website | `http://PHONE_IP:8080/` | Any client on the LAN can read files in the dedicated `www` directory |

Retrieve each password over USB with the Android SDK’s `adb`. Add `-s YOUR_SERIAL` before `shell` if needed.

```powershell
adb shell cat /data/local/tmp/galaxy-lan-desktop/dex-password
adb shell cat /data/local/tmp/galaxy-lan-desktop/smb-password
```

The desktop uses a per-phone self-signed certificate. Compare the browser’s certificate fingerprint with the certificate pulled over USB before trusting it.

```powershell
adb pull /data/local/tmp/galaxy-lan-desktop/dex-cert.pem
certutil -decode dex-cert.pem dex-cert.cer
certutil -hashfile dex-cert.cer SHA256
```

Open **Utilities** on the desktop to start or stop the web and SMB services, browse the share, create folders, or upload and download files. The SMB server requires SMB signing and encryption. Use an SMB client that supports a custom TCP port. Share data lives in `GalaxyLANShare` on the phone. Browser uploads support up to 64 MiB per file.

Push site files to `/data/local/tmp/galaxy-lan-desktop/www/`. Add the device serial where needed.

```powershell
adb push .\index.html /data/local/tmp/galaxy-lan-desktop/www/index.html
```

Run the following after reboot or an IP address change. If a process is already running on the old address, run `stop` first. The desktop can also be launched from the optional tile.

```powershell
adb shell sh /data/local/tmp/galaxy-lan-desktop/lan-services.sh start
adb shell sh /data/local/tmp/galaxy-lan-desktop/lan-services.sh browser-on
```

The same script accepts `status`, `browser-off`, and `stop`. Reinstalling preserves the site files, share contents, and generated credentials.

## Optional Quick Settings tile

Install with `--with-tile` in Bash or `-WithTile` in PowerShell to add a **DeX Browser** tile. The tile can toggle an already running desktop helper without enabling Wireless debugging. To start the desktop after a reboot without USB, pair the tile’s local controller once.

1. Enable **Wireless debugging** on the phone and approve your trusted Wi-Fi network.
2. Keep USB connected and run `bash ./pair-tile.sh --serial YOUR_SERIAL` on Linux or macOS, or `pwsh -File .\Pair-Tile.ps1 -Serial YOUR_SERIAL` on Windows.
3. Follow its prompt to use the port and code from Android’s **Pair device with pairing code** dialog. Keep the dialog open during pairing.
4. Tap **DeX Browser** to start or stop the desktop.

The pairing script grants the tile permission to turn on Android Wireless debugging when its helper is unavailable. Android still controls trusted-network approval. The controller pairs locally and runs the fixed desktop start command. To revoke access, forget its pairing in Developer options, disable Wireless debugging, and uninstall `org.galaxylan.desktop` if you no longer need the tile.

Use the same installer and keep its generated signing files under `.build` for tile upgrades. A new signing key requires uninstalling the old tile and pairing again.

## Dependencies

The installer downloads [GoSamba v1.3.8](https://github.com/ahmetozer/gosamba/releases/tag/v1.3.8) and, for the optional tile, the ARM64 ADB binary from [LADB](https://github.com/tytydraco/LADB/tree/60f48029cf9d8e0bc848ca41a7bd76694d4ab796). It verifies pinned SHA-256 hashes and keeps both downloads in the ignored `.build` directory. Upstream licenses apply to those downloads. Neither binary is distributed in this repository.
