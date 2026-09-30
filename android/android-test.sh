#!/usr/bin/env bash
# Run the instrumented tests on a headless KVM emulator (AVD k2m_test), and always shut the emulator
# down afterwards -- it eats several GB of RAM and CPU while idle. Usage: ./android-test.sh
# Set K2M_REAL_CBZ=/path/to/real.cbz to also run a real Komikku archive through the converter.
set -uo pipefail
cd "$(dirname "$0")"
export JAVA_HOME=${JAVA_HOME:-$HOME/android-build/jdk} ANDROID_HOME=${ANDROID_HOME:-$HOME/android-build/sdk}
ADB=$ANDROID_HOME/platform-tools/adb
SERIAL=emulator-5554
stop_emulator() {
  "$ADB" -s $SERIAL emu kill >/dev/null 2>&1
  for _ in $(seq 15); do pgrep -f '[q]emu-system.*-avd k2m_test' >/dev/null || return 0; sleep 1; done
  pkill -f '[q]emu-system.*-avd k2m_test'
}
trap stop_emulator EXIT

if ! "$ADB" -s $SERIAL get-state >/dev/null 2>&1; then
  # sg kvm: the kvm group only applies to fresh processes, not long-lived shells.
  sg kvm -c "nohup $ANDROID_HOME/emulator/emulator -avd k2m_test -no-window -no-audio -no-snapshot -gpu swiftshader_indirect -no-boot-anim > /tmp/emulator.log 2>&1 &"
fi
"$ADB" wait-for-device
for _ in $(seq 120); do
  [ "$("$ADB" -s $SERIAL shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
  sleep 2
done
# The emulator's disk persists: a stale copy would make the optional real-archive test run (and fail) on old data.
"$ADB" -s $SERIAL shell rm -f /data/local/tmp/k2m_real.cbz >/dev/null 2>&1
[ -n "${K2M_REAL_CBZ:-}" ] && "$ADB" -s $SERIAL push "$K2M_REAL_CBZ" /data/local/tmp/k2m_real.cbz >/dev/null
./gradlew connectedDebugAndroidTest --console=plain -q
rc=$?
title="k2m instrumented tests $([ $rc = 0 ] && echo passed || echo FAILED)"
if [ -r ~/.config/gotify/cli.json ]; then
  url=$(python3 -c "import json,os;print(json.load(open(os.path.expanduser('~/.config/gotify/cli.json')))['url'])")
  tok=$(python3 -c "import json,os;print(json.load(open(os.path.expanduser('~/.config/gotify/cli.json')))['token'])")
  curl -s -X POST "$url/message" -F "title=$title" -F "message=android-test.sh" -F "priority=$([ $rc = 0 ] && echo 2 || echo 8)" -H "X-Gotify-Key: $tok" >/dev/null
fi
exit $rc
