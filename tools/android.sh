# Shared host-side helpers for the Bash entry points.

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

default_sdk_root() {
    if [ -n "${ANDROID_HOME:-}" ]; then
        printf '%s\n' "$ANDROID_HOME"
    elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
        printf '%s\n' "$ANDROID_SDK_ROOT"
    elif [ "$(uname -s)" = Darwin ]; then
        printf '%s\n' "$HOME/Library/Android/sdk"
    else
        printf '%s\n' "$HOME/Android/Sdk"
    fi
}

select_device() {
    adb="$sdk_root/platform-tools/adb"
    [ -x "$adb" ] || fail 'Install Android SDK Platform-Tools or pass --sdk-root.'
    if [ -z "$serial" ]; then
        listing=$("$adb" devices) || fail 'Could not list Android devices.'
        serial=$(printf '%s\n' "$listing" | awk '$2 == "device" { serial = $1; count++ } END { if (count == 1) print serial }')
        [ -n "$serial" ] || fail 'Connect and authorize one phone or pass --serial from adb devices.'
    fi
    state=$("$adb" -s "$serial" get-state | tr -d '\r')
    [ "$state" = device ] || fail 'Authorize the phone for USB debugging first.'
}

resolve_sdk_root() {
    sdk_root=$(CDPATH= cd -- "$sdk_root" && pwd) || fail 'Android SDK folder not found. Pass --sdk-root.'
}

phone() {
    "$adb" -s "$serial" "$@"
}
