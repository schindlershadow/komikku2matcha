#!/usr/bin/env bash
# Build the app and publish it on the server: the release APK as ../komikku2matcha.apk (served at /app.apk)
# plus ../komikku2matcha.apk.json with its version and SHA-256 (served at /api/app/version, read by the app's
# update check). Bump versionCode/versionName in app/build.gradle.kts first; an equal code is not an update.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME=${JAVA_HOME:-$HOME/android-build/jdk} ANDROID_HOME=${ANDROID_HOME:-$HOME/android-build/sdk}
./gradlew assembleRelease assembleDebug --console=plain -q
APK=app/build/outputs/apk/release/app-release.apk
code=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)
name=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
cp "$APK" ../komikku2matcha.apk.tmp && mv ../komikku2matcha.apk.tmp ../komikku2matcha.apk
cp app/build/outputs/apk/debug/app-debug.apk ../komikku2matcha-debug.apk
python3 - "$code" "$name" <<'PY'
import hashlib, json, os, sys
apk = "../komikku2matcha.apk"
info = {"versionCode": int(sys.argv[1]), "versionName": sys.argv[2], "size": os.path.getsize(apk),
        "sha256": hashlib.sha256(open(apk, "rb").read()).hexdigest()}
open("../komikku2matcha.apk.json", "w").write(json.dumps(info) + "\n")
print(f"published {info['versionName']} (code {info['versionCode']}, {info['size']} bytes)")
PY

# Also publish to GitHub Releases (tag v<version>), which is where fresh installs and the app's update check
# look besides the server. Skipped, not failed, when gh or the repo isn't there yet.
REPO=${K2M_REPO:-schindlershadow/komikku2matcha}
if command -v gh >/dev/null && gh repo view "$REPO" >/dev/null 2>&1; then
  tag="v$name"
  if gh release view "$tag" -R "$REPO" >/dev/null 2>&1; then
    gh release upload "$tag" -R "$REPO" --clobber ../komikku2matcha.apk ../komikku2matcha.apk.json
  else
    gh release create "$tag" -R "$REPO" --title "$name" --generate-notes --latest ../komikku2matcha.apk ../komikku2matcha.apk.json
  fi
  echo "published $tag to GitHub ($REPO)"
else
  echo "GitHub release skipped ($REPO not found or gh missing)"
fi
