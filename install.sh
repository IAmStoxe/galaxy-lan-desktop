#!/usr/bin/env bash
set -euo pipefail
umask 077

project=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$project/tools/android.sh"

serial=
sdk_root=$(default_sdk_root)
java_home=${JAVA_HOME:-}
with_tile=0
build_only=0
no_start=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        --serial|--sdk-root|--java-home)
            [ "$#" -ge 2 ] && [ -n "$2" ] || fail "Missing value for $1."
            case "$1" in
                --serial) serial=$2 ;;
                --sdk-root) sdk_root=$2 ;;
                --java-home) java_home=$2 ;;
            esac
            shift ;;
        --with-tile) with_tile=1 ;;
        --build-only) build_only=1 ;;
        --no-start) no_start=1 ;;
        -h|--help)
            printf '%s\n' 'Usage: ./install.sh [--serial SERIAL] [--sdk-root DIR] [--java-home DIR]' \
                '                    [--with-tile] [--build-only] [--no-start]'
            exit 0 ;;
        *) fail "Unknown option: $1" ;;
    esac
    shift
done

resolve_sdk_root
if [ "$build_only" -eq 0 ]; then
    select_device
    abi=$(phone shell getprop ro.product.cpu.abi | tr -d '\r')
    api=$(phone shell getprop ro.build.version.sdk | tr -d '\r')
    [ "$abi" = arm64-v8a ] && [ "$api" = 34 ] || fail "Expected Android 14 ARM64, found API $api and $abi."
fi

sdk_jar="$sdk_root/platforms/android-34/android.jar"
build_tools="$sdk_root/build-tools/34.0.0"
[ -f "$sdk_jar" ] || fail 'Install Android SDK Platform 34 or pass --sdk-root.'
[ -x "$build_tools/d8" ] || fail 'Install Android SDK Build-Tools 34.0.0.'
command -v go >/dev/null || fail 'Install Go 1.25 or newer.'
if [ -n "$java_home" ]; then
    java_home=$(CDPATH= cd -- "$java_home" && pwd) || fail 'JDK folder not found. Pass --java-home.'
    export JAVA_HOME="$java_home"
    export PATH="$java_home/bin:$PATH"
fi
command -v javac >/dev/null || fail 'Install a JDK or pass --java-home.'

cache="$project/.build"
build="$cache/bash"
payload="$build/payload"
remote=/data/local/tmp/galaxy-lan-desktop
mkdir -p "$payload" "$build/classes"

verify_dependency() {
    if command -v sha256sum >/dev/null; then
        digest=$(sha256sum < "$1")
    else
        digest=$(shasum -a 256 < "$1")
    fi
    [ "${digest%% *}" = "$2" ] || fail "Unexpected SHA-256 for $1."
}

get_dependency() {
    (
        target="$cache/$1"
        if [ ! -f "$target" ]; then
            part=$(mktemp "$target.download.XXXXXXXX")
            trap 'rm -f -- "$part"' EXIT
            curl --fail --location --silent --show-error "$2" -o "$part"
            verify_dependency "$part" "$3"
            mv "$part" "$target"
        fi
        verify_dependency "$target" "$3"
    )
}

get_dependency gosamba \
    https://github.com/ahmetozer/gosamba/releases/download/v1.3.8/gosamba-v1.3.8-linux-arm64 \
    0f72e81ae91c95d4c6acd4130f9e82558ab7ed2bce9d82b58a70ada925c2d1c1
cp "$cache/gosamba" "$project"/device/* "$payload/"
cd "$project"
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -o "$payload/dexbrowser" ./src
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -o "$payload/web" ./cmd/web
for name in HeadlessDex DexFrame DexVideo DexInput; do
    javac --release 8 -cp "$sdk_jar" -d "$build/classes" "$project/src/$name.java"
    "$build_tools/d8" --min-api 34 --lib "$sdk_jar" \
        --output "$payload/$name.jar" "$build/classes/$name.class"
done

if [ "$with_tile" -eq 1 ]; then
    get_dependency libadb.so \
        https://raw.githubusercontent.com/tytydraco/LADB/60f48029cf9d8e0bc848ca41a7bd76694d4ab796/app/src/main/jniLibs/arm64-v8a/libadb.so \
        47ea035fa5ed57f6149a2b025bbbd4b21584c355c05d0400416804715e4c12de
    tile="$project/src/tile"
    package="$build/package"
    generated="$build/generated-java"
    assets="$build/apk-assets-bundle"
    mkdir -p "$package/lib/arm64-v8a" "$build/tile-classes" "$generated" "$assets/licenses"
    cp "$cache/libadb.so" "$package/lib/arm64-v8a/"
    set -- dexbrowser web gosamba HeadlessDex.jar DexFrame.jar DexVideo.jar DexInput.jar lan-services.sh provision.sh welcome.html
    (cd "$payload"; jar cfM "$assets/server.zip" "$@")
    if command -v sha256sum >/dev/null; then
        (cd "$payload"; sha256sum "$@" | sha256sum) | awk '{print $1}' > "$assets/payload-revision.txt"
    else
        (cd "$payload"; shasum -a 256 "$@" | shasum -a 256) | awk '{print $1}' > "$assets/payload-revision.txt"
    fi
    cp "$project/LICENSE" "$assets/licenses/Galaxy LAN Desktop.txt"
    cp "$project"/third-party/*.txt "$assets/licenses/"
    cp "$(go env GOROOT)/LICENSE" "$assets/licenses/Go.txt"
    cp "$(go list -m -f '{{.Dir}}' golang.org/x/net)/LICENSE" "$assets/licenses/Go x_net.txt"
    "$build_tools/aapt2" compile --dir "$tile/res" -o "$build/res.zip"
    "$build_tools/aapt2" link -I "$sdk_jar" --manifest "$tile/AndroidManifest.xml" -A "$assets" -0 zip \
        --java "$generated" -o "$build/unsigned.apk" "$build/res.zip"
    javac --release 11 -encoding UTF-8 -cp "$sdk_jar" -d "$build/tile-classes" \
        "$tile"/*.java "$generated/org/galaxylan/desktop/R.java"
    jar cf "$build/tile-classes.jar" -C "$build/tile-classes" .
    "$build_tools/d8" --min-api 34 --lib "$sdk_jar" --output "$package" "$build/tile-classes.jar"
    (
        cd "$package"
        "$build_tools/aapt" add "$build/unsigned.apk" classes.dex lib/arm64-v8a/libadb.so
    )
    "$build_tools/zipalign" -f 4 "$build/unsigned.apk" "$build/aligned.apk"
    keystore="$build/tile.p12"
    keypass="$build/tile-signing.secret"
    if [ ! -f "$keystore" ]; then
        head -c 30 /dev/urandom | base64 > "$keypass"
        keytool -genkeypair -storetype PKCS12 -keystore "$keystore" -storepass:file "$keypass" \
            -alias tile -keyalg RSA -validity 3650 -dname 'CN=Galaxy LAN Desktop'
    fi
    [ -s "$keypass" ] || fail 'The tile signing password file is missing. Restore it before rebuilding.'
    "$build_tools/apksigner" sign --ks "$keystore" --ks-pass "file:$keypass" \
        --out "$build/galaxy-lan-tile.apk" "$build/aligned.apk"
    "$build_tools/apksigner" verify "$build/galaxy-lan-tile.apk"
fi

if [ "$build_only" -eq 1 ]; then
    printf 'Built device files in %s\n' "$payload"
    exit 0
fi

stage=$(phone shell "umask 077; mkdir -p '$remote' && chmod 700 '$remote' && mktemp -d '$remote/install-XXXXXXXX'" | tr -d '\r')
case "$stage" in
    "$remote"/install-[a-zA-Z0-9]*) ;;
    *) fail 'Could not create the staging directory on the phone.' ;;
esac
case "${stage#"$remote"/install-}" in
    *[!a-zA-Z0-9]*) fail 'Unexpected staging directory on the phone.' ;;
esac
for name in dexbrowser web gosamba HeadlessDex.jar DexFrame.jar DexVideo.jar DexInput.jar lan-services.sh provision.sh welcome.html; do
    phone push "$payload/$name" "$stage/$name"
done
phone shell "
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
"
if [ "$with_tile" -eq 1 ]; then
    phone install --user 0 -r "$build/galaxy-lan-tile.apk"
    setup=$(phone shell am broadcast --user 0 --include-stopped-packages \
        -a org.galaxylan.desktop.PREPARE -n org.galaxylan.desktop/.PairController)
    case "$setup" in
        *'Broadcast completed: result=1'*) ;;
        *) fail 'Could not prepare the tile storage. Unlock the phone and retry.' ;;
    esac
    phone shell cmd statusbar add-tile org.galaxylan.desktop/.BrowserTile
    printf '%s\n' 'For tile startup after a reboot, complete the optional Wireless debugging pairing in the README.'
fi
if [ "$no_start" -eq 0 ]; then
    phone shell sh "$remote/lan-services.sh" start
    phone shell sh "$remote/lan-services.sh" browser-on
fi
printf '%s\n' 'See the README for retrieving the generated passwords and operating the services.'
