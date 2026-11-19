# LAN services

The phone app has one switch per service. Connect from another device on the same LAN using the phone’s Wi-Fi IPv4 address. After a reboot, start the services you want. If Android turned Wireless debugging off, the APK setup may require re-enabling it in Developer options before the app can start them. [Service launcher](../../device/lan-services.sh) · [Phone setup](../phone-setup.md#after-setup)

| Service | Port | Access |
| --- | --- | --- |
| Browser desktop and Utilities | HTTPS `8443` | Sign in as `dex` with the generated desktop password. The app shows the self-signed certificate’s SHA-256 fingerprint under **Show password**. |
| SMB share | TCP `4450` | Connect to `Files` as `files` with its own password. Use a client that supports a custom port, SMB signing, and SMB encryption. |
| Static website | HTTP `8080` | Visitors on the LAN can read files you publish. The server accepts `GET` and `HEAD`. |

## Where files live

- **SMB share** uses `GalaxyLANShare` in the phone’s shared storage. In the browser, **Utilities** manages files in that same folder directly. People who sign in to the desktop can still use Utilities to work with those files when the SMB switch is off. [Share setup](../../device/provision.sh) · [Utilities](../../src/manage.go)
- **Static website** serves the `www` directory under `/data/local/tmp/galaxy-lan-desktop`. Updating its `index.html` replaces the default page. [Static file server](../../cmd/web/main.go) · [Website file instructions](../../README.md#files-and-services)

The **Utilities** page can also start and stop SMB and the website. It is available while the browser desktop is on. Use the phone app to control those services at any time. [Utilities service controls](../../src/manage.go)

[How it works](README.md) · [Browser desktop](browser-desktop.md)
