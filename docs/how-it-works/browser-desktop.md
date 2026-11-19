# Browser desktop

When you enable **Browser desktop**, the phone creates a 1920 × 1080 virtual display and starts the HTTPS listener. The display belongs to the phone, so the apps and windows run there. The separate display lets you use the desktop without a TV or HDMI adapter. This was tested on a Galaxy S22 Ultra with Android 14. Other firmware can handle the project’s internal display and input APIs differently. [Android virtual displays](https://developer.android.com/reference/android/hardware/display/DisplayManager) · [Display setup](../../src/HeadlessDex.java)

## From the phone to the browser

The phone feeds the virtual display to Android’s `MediaCodec` encoder. The browser decodes the H.264 stream with WebCodecs and draws it to a canvas. The encoder requests 1080p at 60 frames per second. The actual frame rate depends on the phone, browser, and network. If H.264 playback fails or WebCodecs is unavailable, the page tries a JPEG stream and shows **Compatibility view**. [Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec) · [WebCodecs VideoDecoder](https://www.w3.org/TR/webcodecs/#videodecoder-interface) · [Browser client](../../src/index.html)

## From the browser to the phone

Clicks, drags, scrolling, and supported keys go back through a secure WebSocket. The phone checks the display ID and applies those events to the virtual desktop. If the WebSocket is unavailable, the browser can send taps, swipes, and supported keys over HTTPS. [Input server](../../src/socket.go) · [HTTPS input fallback](../../src/main.go)

**Pause** disconnects the current tab. The phone’s **Browser desktop** switch or the **DeX Browser** Quick Settings tile closes the listener and removes the virtual display for everyone. [Session lifecycle](../../src/control.go)

[How it works](README.md) · [LAN services](lan-services.md)
