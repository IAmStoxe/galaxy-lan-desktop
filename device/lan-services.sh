#!/system/bin/sh
# Run through the authorized USB connection: adb shell sh /data/local/tmp/galaxy-lan-desktop/lan-services.sh start
set -eu
base=/data/local/tmp/galaxy-lan-desktop
tile="$base/browser-state"
shared=/storage/emulated/0/Android/data/org.galaxylan.desktop/files
if [ -d "$shared" ] && [ -w "$shared" ]; then tile="$shared"; fi
export DEX_STATE_DIR="$tile"
cd "$base"
umask 077

service_pids() {
    for pid in $(pidof "$1" || true); do
        [ "$(readlink "/proc/$pid/exe" || true)" != "$base/$1" ] || echo "$pid"
    done
}

action=${1:-status}
if [ "$action" = browser-toggle ]; then
    if [ -n "$(service_pids dexbrowser)" ] && [ -f "$tile/listening" ] && [ -f "$tile/enabled" ]; then
        action=browser-off
    else
        action=browser-on
    fi
fi
case "$action" in
    start|web-start|smb-start)
        address=$(ip -4 -o addr show wlan0 | awk '{split($4,a,"/"); print a[1]; exit}')
        [ -n "$address" ] || { echo "Connect the phone to Wi-Fi first."; exit 1; }
        if [ "$action" != smb-start ] && [ -z "$(service_pids web)" ]; then
            nohup "$base/web" "$address:8080" "$base/www" >web.log 2>&1 </dev/null &
        fi
        if [ "$action" != web-start ] && [ -z "$(service_pids gosamba)" ]; then
            nohup "$base/gosamba" -config "$base/smb.toml" -listen "$address:4450" -mdns=false >smb.log 2>&1 </dev/null &
        fi
        sleep 1
        if [ "$action" = web-start ]; then [ -n "$(service_pids web)" ]; exit; fi
        if [ "$action" = smb-start ]; then [ -n "$(service_pids gosamba)" ]; exit; fi
        [ -n "$(service_pids web)" ] && [ -n "$(service_pids gosamba)" ] || { echo "Check web.log and smb.log in $base"; exit 1; }
        echo "Web http://$address:8080/    SMB $address port 4450 share Files"
        ;;
    browser-on)
        address=$(ip -4 -o addr show wlan0 | awk '{split($4,a,"/"); print a[1]; exit}')
        [ -n "$address" ] || { echo "Connect to Wi-Fi first."; exit 1; }
        mkdir -p "$tile"
        touch "$tile/enabled"
        if [ -z "$(service_pids dexbrowser)" ]; then
            nohup "$base/dexbrowser" "$address" >dex-browser.log 2>&1 </dev/null &
        fi
        sleep 1
        [ -n "$(service_pids dexbrowser)" ] && [ -f "$tile/listening" ] || { echo "Check dex-browser.log"; exit 1; }
        echo "https://$address:8443/"
        ;;
    browser-state)
        if [ -n "$(service_pids dexbrowser)" ] && [ -f "$tile/enabled" ] && [ -f "$tile/listening" ]; then
            echo on
        else
            echo off
        fi
        ;;
    browser-off)
        rm -f "$tile/enabled"
        ;;
    stop|web-stop|smb-stop)
        case "$action" in
            web-stop) names=web ;;
            smb-stop) names=gosamba ;;
            stop) names="web gosamba dexbrowser" ;;
        esac
        for name in $names; do
            for pid in $(service_pids "$name"); do kill "$pid"; done
        done
        ;;
    status)
        for name in web gosamba dexbrowser; do
            pid=$(service_pids "$name")
            echo "$name: ${pid:-stopped}"
        done
        ;;
    dex)
        input keyevent KEYCODE_WAKEUP
        cmd statusbar click-tile com.sec.android.desktopmode.uiservice/.DesktopModeTile
        ;;
    *) echo "Usage: $0 {start|stop|status|dex|browser-on|browser-off}"; exit 2 ;;
esac
