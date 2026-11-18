#!/usr/bin/env bash
set -euo pipefail

project=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$project/tools/android.sh"

serial=
sdk_root=$(default_sdk_root)
while [ "$#" -gt 0 ]; do
    case "$1" in
        --serial|--sdk-root)
            [ "$#" -ge 2 ] && [ -n "$2" ] || fail "Missing value for $1."
            case "$1" in --serial) serial=$2 ;; --sdk-root) sdk_root=$2 ;; esac
            shift ;;
        -h|--help)
            printf '%s\n' 'Usage: ./pair-tile.sh [--serial SERIAL] [--sdk-root DIR]'
            exit 0 ;;
        *) fail "Unknown option: $1" ;;
    esac
    shift
done
resolve_sdk_root
select_device

printf '%s\n' 'On the phone, open Developer options → Wireless debugging → Pair device with pairing code.' \
    'Keep that dialog open. Use the port and code shown in the dialog.'
printf 'Pairing port: '
IFS= read -r port
case "$port" in
    ''|*[!0-9]*|??????*) fail 'Invalid port.' ;;
esac
port=$((10#$port))
[ "$port" -ge 1 ] && [ "$port" -le 65535 ] || fail 'Invalid port.'

printf 'Six-digit pairing code: '
IFS= read -rs code
printf '\n'
case "$code" in
    ''|*[!0-9]*) fail 'Invalid pairing code.' ;;
esac
[ "${#code}" -eq 6 ] || fail 'Invalid pairing code.'
result=$(phone shell am broadcast --user 0 --include-stopped-packages \
    -n org.galaxylan.desktop/.PairController --ei port "$port" --es code "$code")
unset code
case "$result" in
    *'Broadcast completed: result=1'*) ;;
    *) fail 'Pairing failed. Open a fresh pairing dialog and retry.' ;;
esac
phone shell pm grant org.galaxylan.desktop android.permission.WRITE_SECURE_SETTINGS ||
    fail 'Pairing completed, but the permission to enable Wireless debugging after reboot failed.'
printf '%s\n' 'Paired. Use the DeX Browser tile to start or stop the desktop.'
