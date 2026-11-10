#!/usr/bin/env bash
# 原版安卓端：不用 Gradle，直接 aapt2 → javac → d8 → zipalign → apksigner
# 产物：../release/LightMirror-<versionName>.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT/../release}"

# ---- 找 Android SDK ----
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  for c in "$HOME/Android/Sdk" "/usr/local/lib/android/sdk" \
           "/c/Users/${USERNAME:-$USER}/AppData/Local/Android/Sdk" "/c/Android/Sdk"; do
    if [ -d "$c" ]; then SDK="$c"; break; fi
  done
fi
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "找不到 Android SDK：请设置 ANDROID_HOME 指向 SDK 目录" >&2
  exit 1
fi
echo "SDK: $SDK"

# build-tools / platform 取已安装的最高版本，别写死版本号
BT="$(ls -d "$SDK/build-tools/"* 2>/dev/null | grep -E '/[0-9]+(\.[0-9]+)*$' | sort -V | tail -1 || true)"
AJ="$(ls "$SDK/platforms"/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
if [ -z "$BT" ] || [ -z "$AJ" ]; then
  echo "SDK 里缺 build-tools 或 platforms，请先装：sdkmanager \"platforms;android-36\" \"build-tools;36.0.0\"" >&2
  exit 1
fi
echo "build-tools: $(basename "$BT")   platform: $(basename "$(dirname "$AJ")")"

OUT="$ROOT/build"
KS="$ROOT/keystore/lightmirror.jks"
PASS="lightmirror"

# 版本号只在 AndroidManifest.xml 里定义一次，这里读出来传给 aapt2，避免两处写不一致
VC="$(grep -o 'android:versionCode="[0-9]*"' "$ROOT/AndroidManifest.xml" | head -1 | grep -o '[0-9]*')"
VN="$(grep -o 'android:versionName="[^"]*"' "$ROOT/AndroidManifest.xml" | head -1 | sed 's/.*="//; s/"$//')"
if [ -z "$VC" ] || [ -z "$VN" ]; then
  echo "AndroidManifest.xml 里没读到 versionCode / versionName" >&2
  exit 1
fi
echo "version: $VN ($VC)"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT_DIR"

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJ" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 29 --target-sdk-version 36 \
  --version-code "$VC" --version-name "$VN" \
  "$OUT/res.zip"

echo "[3/6] javac"
javac -nowarn -encoding UTF-8 -source 8 -target 8 -classpath "$AJ" \
  -d "$OUT/classes" $(find "$ROOT/java" "$OUT/gen" -name '*.java')

echo "[4/6] d8"
"$BT/d8" --lib "$AJ" --min-api 29 --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "[5/6] package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" \
    -alias lightmirror -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=LightMirror, OU=App, O=LightMirror, L=Hangzhou, C=CN" >/dev/null 2>&1
fi
APK="$OUT_DIR/LightMirror-$VN.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$APK" | head -3
rm -rf "$OUT"
ls -lh "$APK"
