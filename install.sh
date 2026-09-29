#!/usr/bin/env bash
# One-step k2m-server install for a Linux box with systemd: Python venv + dependencies, the panel/OCR
# converter, a config, and a user service that starts on boot. Safe to run again.
#
#   ./install.sh            no questions asked; everything below is detected or defaulted
#
# Optional:
#   --input DIR    where the app uploads Komikku CBZs (default ~/Manga/Komikku)
#   --output DIR   where converted books go           (default ~/Manga/Matcha)
#   --minimal      skip PyTorch/YOLO/OCR: white-gutter panel split only, no OCR (much smaller)
#   --no-firewall  don't open the LAN port in ufw
#   --uninstall    stop and remove the service (keeps your books and config)
# An NVIDIA GPU is detected (nvidia-smi) and gets the CUDA build of PyTorch, otherwise the CPU build.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
INPUT=$HOME/Manga/Komikku OUTPUT=$HOME/Manga/Matcha MINIMAL=0 FIREWALL=1
REPO=${K2M_REPO:-schindlershadow/komikku2matcha}
UNIT=$HOME/.config/systemd/user/k2m-server.service
while [[ $# -gt 0 ]]; do
  case $1 in
    --input) INPUT=$2; shift 2;;
    --output) OUTPUT=$2; shift 2;;
    --no-firewall) FIREWALL=0; shift;;
    --uninstall)
      systemctl --user disable --now k2m-server 2>/dev/null || true; rm -f "$UNIT"; systemctl --user daemon-reload
      echo "Removed the k2m-server service. Kept: books, config (~/.config/k2m-server), and this folder."; exit 0;;
    --minimal) MINIMAL=1; shift;;
    -h|--help) sed -n '2,13p' "$0"; exit 0;;
    *) echo "unknown option: $1" >&2; exit 1;;
  esac
done
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }
command -v git >/dev/null || { echo "git is required (to fetch the converter): sudo apt install git" >&2; exit 1; }
command -v systemctl >/dev/null || { echo "systemd is required (this sets the server up as a service)" >&2; exit 1; }
python3 -c 'import venv, ensurepip' 2>/dev/null || { echo "Python venv support is missing: sudo apt install python3-venv python3-pip" >&2; exit 1; }

echo "== Python environment"
[[ -x $HERE/.venv/bin/python ]] || python3 -m venv "$HERE/.venv"
PIP=$HERE/.venv/bin/pip
if [[ $MINIMAL == 1 ]]; then
  "$PIP" install -q Pillow
else
  if command -v nvidia-smi >/dev/null && nvidia-smi -L >/dev/null 2>&1; then IDX=https://download.pytorch.org/whl/cu128; echo "NVIDIA GPU found: CUDA build of PyTorch"
  else IDX=https://download.pytorch.org/whl/cpu; echo "No NVIDIA GPU: CPU build of PyTorch (slower OCR)"; fi
  "$PIP" install -q --index-url "$IDX" torch torchvision
  "$PIP" install -q ultralytics huggingface_hub Pillow mokuro
fi

echo "== Converter (matcha-reader)"
[[ -d $HERE/matcha-reader || -d $HERE/../matcha-reader ]] \
  || git clone -q --depth 1 https://github.com/eszter007/matcha-reader.git "$HERE/matcha-reader"

echo "== Config"
mkdir -p "$INPUT" "$OUTPUT"
CONF=$HOME/.config/k2m-server/config.json
if [[ ! -f $CONF ]]; then
  mkdir -p "$(dirname "$CONF")"
  INPUT=$INPUT OUTPUT=$OUTPUT PY=$HERE/.venv/bin/python python3 - "$CONF" <<'PY'
import json, os, sys
json.dump({"input": os.environ["INPUT"], "output": os.environ["OUTPUT"], "python": os.environ["PY"]}, open(sys.argv[1], "w"), indent=2)
PY
  echo "wrote $CONF"
else
  echo "keeping existing $CONF"
fi

echo "== Service"
mkdir -p "$(dirname "$UNIT")"
cat > "$UNIT" <<UNIT
[Unit]
Description=komikku2matcha server (Komikku CBZ -> Matcha Reader books, for the Android app)
After=network-online.target

[Service]
ExecStart=$(command -v python3) $HERE/k2m_server.py
WorkingDirectory=$HERE
Restart=on-failure
RestartSec=5
Nice=5

[Install]
WantedBy=default.target
UNIT
systemctl --user daemon-reload
systemctl --user enable --now k2m-server
loginctl enable-linger "$USER" 2>/dev/null || echo "note: run 'sudo loginctl enable-linger $USER' so it starts at boot without a login"

if [[ $FIREWALL == 1 ]] && command -v ufw >/dev/null && systemctl is-active --quiet ufw; then
  echo "== Firewall: ufw is on, opening the port for your LAN (sudo may ask for your password)"
  sudo "$HERE/setup-root.sh" || echo "firewall step failed; the app may not reach the server. Retry: sudo ./setup-root.sh"
fi

echo "== Android app"
if [[ ! -f $HERE/komikku2matcha.apk ]]; then
  if curl -fsSL -m 120 -o "$HERE/komikku2matcha.apk.tmp" "https://github.com/$REPO/releases/latest/download/komikku2matcha.apk"; then
    mv "$HERE/komikku2matcha.apk.tmp" "$HERE/komikku2matcha.apk"; echo "downloaded the latest release APK"
    curl -fsSL -m 30 -o "$HERE/komikku2matcha.apk.json" "https://github.com/$REPO/releases/latest/download/komikku2matcha.apk.json" || true
  else
    rm -f "$HERE/komikku2matcha.apk.tmp"; echo "no release APK found; build it from android/ (see README)"
  fi
fi

sleep 2
IP=$(ip -4 route get 1.1.1.1 2>/dev/null | grep -oP 'src \K[0-9.]+' || hostname -I | awk '{print $1}')
PORT=$(python3 -c "import json;print(json.load(open('$CONF')).get('port', 8765))")
echo
echo "== Done"
if curl -s -m 5 "http://127.0.0.1:$PORT/api/health" >/dev/null; then echo "server: running"
else echo "server: NOT RESPONDING (journalctl --user -u k2m-server)"; fi
[[ -f $HERE/komikku2matcha.apk ]] && echo "1. On your phone, open  http://$IP:$PORT/app.apk  and install it"
echo "2. In the app's Settings enter:"
echo "     Home URL  http://$IP:$PORT"
echo "     Token     $(python3 "$HERE/k2m_server.py" --print-token)"
echo "3. Tap Save & test, pick Komikku's download folder, then Sync."
echo "Remote access from outside your network is optional: see the README."
