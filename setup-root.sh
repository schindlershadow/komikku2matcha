#!/usr/bin/env bash
# One-time root setup for k2m-server: firewall rules for the LAN, and the Apache route for remote access.
# Safe to run again: nothing is added twice. The Apache file is backed up first and restored if the new
# config doesn't pass `apache2ctl configtest`.
#
#   sudo ./setup-root.sh
#
# Optional environment: LAN (default: this host's subnet, e.g. 192.168.1.0/24), PORT (8765).
# The Apache step (remote access at https://HOST/matcha/) only runs when APACHE_CONF (the SSL vhost file),
# APACHE_ANCHOR (an existing line of that file to insert the route after) and HOST are all set.
set -euo pipefail

PORT=${PORT:-8765}
LAN=${LAN:-$(ip -4 route | awk '/proto kernel.*scope link/ {print $1; exit}')}
CONF=${APACHE_CONF:-}
ANCHOR=${APACHE_ANCHOR:-}
HOST=${HOST:-}
[[ -n $LAN ]] || { echo "Couldn't detect the LAN subnet; set LAN=192.168.x.0/24" >&2; exit 1; }

[[ $EUID -eq 0 ]] || { echo "Run with sudo: sudo $0" >&2; exit 1; }

echo "== Firewall (ufw)"
ufw allow from "$LAN" to any port "$PORT" proto tcp comment 'k2m-server (Komikku to Matcha app)'
ufw allow proto udp from "$LAN" port 8134 comment 'X4 discovery replies to k2m-server'

echo "== Apache"
if [[ -z $CONF || -z $ANCHOR || -z $HOST ]]; then
    echo "skipped (set APACHE_CONF, APACHE_ANCHOR and HOST to add the /matcha/ remote-access route)"
elif grep -q 'ProxyPass /matcha/' "$CONF"; then
    echo "/matcha/ route already present in $CONF"
else
    [[ $(grep -cF "$ANCHOR" "$CONF") -eq 1 ]] || { echo "Couldn't find the /reader/ proxy line exactly once in $CONF; add the route by hand (see README)." >&2; exit 1; }
    backup="$CONF.bak-$(date +%Y%m%d-%H%M%S)"
    cp -p "$CONF" "$backup"
    echo "backup: $backup"
    sed -i "\|$ANCHOR|a ProxyPass /matcha/ http://127.0.0.1:$PORT/\nProxyPassReverse /matcha/ http://127.0.0.1:$PORT/" "$CONF"
    if ! apache2ctl configtest; then
        echo "configtest failed: restoring the backup" >&2
        cp -p "$backup" "$CONF"
        exit 1
    fi
    systemctl reload apache2
    echo "added the /matcha/ route and reloaded Apache"
fi

echo "== Check"
sleep 1
lan=$(curl -s -m 5 "http://127.0.0.1:$PORT/api/health" || true)
echo "server:              ${lan:-NOT RESPONDING (systemctl --user status k2m-server)}"
if [[ -n $HOST ]]; then
    remote=$(curl -s -m 5 --resolve "$HOST:443:127.0.0.1" "https://$HOST/matcha/api/health" || true)
    echo "via Apache (HTTPS):  ${remote:-NOT RESPONDING}"
fi
echo
echo "App download on the phone: http://$(hostname -I | awk '{print $1}'):$PORT/app.apk"
