#!/usr/bin/env bash
# Runs the Android app's JVM tests against a throwaway k2m_server.py and a fake X4 (fake_x4.py), then
# removes everything it created. The fake X4 needs a LAN address (pushes only go to LAN IPs), so it binds
# to this machine's first 192.168.x.x address.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(dirname "$HERE")
export JAVA_HOME=${JAVA_HOME:-$HOME/android-build/jdk} ANDROID_HOME=${ANDROID_HOME:-$HOME/android-build/sdk}
W=$(mktemp -d /tmp/k2m-test.XXXXXX); PIDS=()
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done; rm -rf "$W"; }
trap cleanup EXIT

LAN_IP=$(ip -4 addr show | grep -oP 'inet \K192\.168\.[0-9.]+' | head -1)
SPORT=$((20000 + RANDOM % 10000)); XPORT=$((SPORT + 1))
python3 "$HERE/make_fixtures.py" "$W/fixtures"
mkdir -p "$W/in/Test Title" "$W/out" "$W/home/config" "$W/sd"
FIX="$W/fixtures/downloads/TestSource/Test Manga_ Vol"
cp "$FIX/Chapter 2.cbz" "$W/in/Test Title/"
# One converted book up front (no OCR: quick), so every test class finds something to work with.
python3 "$ROOT/komikku2matcha.py" "$W/in" "$W/out" --no-ocr --no-fetch-covers --python "$ROOT/.venv/bin/python" > /dev/null
cat > "$W/home/config/config.json" <<JSON
{"input": "$W/in", "output": "$W/out", "python": "$ROOT/.venv/bin/python", "language": "ja",
 "host": "127.0.0.1", "port": $SPORT, "max_upload_mb": 50, "gotify": false, "fetch_covers": false}
JSON
K2M_SERVER_HOME="$W/home" python3 "$ROOT/k2m_server.py" > "$W/server.log" 2>&1 & PIDS+=($!)
python3 "$HERE/fake_x4.py" "$W/sd" "$LAN_IP" "$XPORT" > "$W/x4.log" 2>&1 & PIDS+=($!)
for _ in $(seq 50); do curl -sf "http://127.0.0.1:$SPORT/api/health" > /dev/null && break; sleep 0.2; done

export K2M_TEST_URL="http://127.0.0.1:$SPORT" K2M_TEST_X4="$LAN_IP:$XPORT" K2M_TEST_OUT="$W/out" K2M_TEST_SD="$W/sd"
export K2M_TEST_TOKEN=$(K2M_SERVER_HOME="$W/home" python3 "$ROOT/k2m_server.py" --print-token)
export K2M_TEST_FIXTURE="$FIX/Chapter 1_a1b2c3.cbz"
cd "$ROOT/android"
status=0
./gradlew testDebugUnitTest --rerun-tasks --console=plain -q > "$W/gradle.log" 2>&1 || status=$?
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
for f in sorted(glob.glob('app/build/test-results/testDebugUnitTest/*.xml')):
    r = ET.parse(f).getroot()
    for tc in r.iter('testcase'):
        fl = tc.find('failure'); sk = tc.find('skipped')
        print(f"  {'FAIL' if fl is not None else 'skip' if sk is not None else 'ok  '} {r.get('name').split('.')[-1]}.{tc.get('name')} ({tc.get('time')}s)"
              + (f": {fl.get('message')}" if fl is not None else ""))
PY
[ $status -eq 0 ] || grep -E "^e:" "$W/gradle.log" | head -20 || true
python3 "$HERE/test_server.py" || status=1
exit $status
