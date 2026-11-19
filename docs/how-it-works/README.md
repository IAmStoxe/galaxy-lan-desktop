# How Galaxy LAN Desktop works

The Galaxy phone runs the Android apps and hosts everything on its Wi-Fi address. The browser receives pictures of a separate virtual desktop and sends mouse and keyboard input back to that display.

```mermaid
---
config:
  theme: base
  themeVariables:
    fontFamily: 'system-ui, sans-serif'
    fontSize: '15px'
    primaryTextColor: '#172B44'
    actorBkg: '#FFFFFF'
    actorBorder: '#6583A4'
    actorTextColor: '#172B44'
    actorLineColor: '#8B9FB4'
    signalColor: '#315579'
    signalTextColor: '#172B44'
    noteBkgColor: '#FFFFFF'
    noteBorderColor: '#9CB1C7'
    noteTextColor: '#172B44'
  sequence:
    mirrorActors: false
    width: 112
    height: 40
    actorMargin: 32
    messageMargin: 19
    noteMargin: 8
    boxTextMargin: 7
    diagramMarginX: 35
---
sequenceDiagram
    accTitle: From a Galaxy virtual display to a browser
    accDescr: The phone creates a virtual display. The browser signs in and the phone streams frames over HTTPS. Browser input travels back to the virtual display.
    box rgb(225,238,255) LAN viewer
        participant B as Web browser
    end
    box rgb(220,244,237) Galaxy phone
        participant G as HTTPS server
        participant D as Android desktop
    end
    rect rgb(243,248,255)
        Note over G,D: Turn on the desktop
        G->>D: Create virtual display
    end
    rect rgb(240,250,247)
        Note over B,D: View and control the desktop
        B->>G: Sign in and connect
        D-->>G: H.264 or JPEG frames
        G-->>B: Stream over HTTPS
        B->>G: Click or type
        G->>D: Apply input
    end
```

## What pairing does

For APK setup, you approve Android’s Wireless debugging pairing on the phone. The app’s bundled ADB client connects back to the same phone on `127.0.0.1`. That gives it an authorized Android shell with which to install the bundled files and control the services. The USB installers use the shell over an approved USB debugging connection. Both paths require your approval and work without root. [Android’s ADB guide](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi) · [App controller](../../src/tile/WirelessController.java)

Once the desktop is running, the browser connects on port `8443` over HTTPS with the desktop password.

## Explore by topic

- [Browser desktop](browser-desktop.md) explains the virtual display, streaming, and input.
- [LAN services](lan-services.md) explains the ports, passwords, service switches, and stored files.
- [Phone setup](../phone-setup.md) walks through APK installation and pairing with screenshots.
