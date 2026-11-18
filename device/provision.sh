#!/system/bin/sh
set -eu
umask 077
base=/data/local/tmp/galaxy-lan-desktop
cd "$base"
mkdir -p /storage/emulated/0/GalaxyLANShare www
if [ ! -s smb-password ]; then
    head -c 30 /dev/urandom | base64 > smb-password
fi
if [ ! -s smb.toml ]; then
    hash=$(tr -d '\r\n' < smb-password | ./gosamba hash)
    [ "${#hash}" -eq 32 ]
    case "$hash" in *[!a-fA-F0-9]*) exit 1 ;; esac
    cat > smb.toml.new <<EOF
[server]
signing = "required"
encryption = "required"
mdns = false
[[share]]
name = "Files"
path = "/storage/emulated/0/GalaxyLANShare"
guest_ok = false
[[user]]
name = "files"
nt_hash = "$hash"
allow_shares = ["Files"]
EOF
    mv smb.toml.new smb.toml
fi
if [ ! -e www/index.html ]; then
    cp welcome.html www/index.html
fi
