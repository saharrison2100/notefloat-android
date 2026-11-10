#!/usr/bin/env bash
# Builds an installable NoteFloat APK with the Android SDK command line tools only.
# No Gradle, no Android Studio required.
set -euo pipefail

SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM_JAR="$SDK/platforms/android-34/android.jar"
OUT="build"
VERSION_NAME="1.0.0"
VERSION_CODE="1"

if [ -d "$OUT" ]; then find "$OUT" -mindepth 1 -delete; fi
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== compiling resources"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"

echo "== linking resources and manifest"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM_JAR" \
  --manifest AndroidManifest.xml \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME"

echo "== compiling java"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -bootclasspath "$PLATFORM_JAR" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -v 'bootstrap class path' || true

echo "== dexing"
"$BT/d8" --lib "$PLATFORM_JAR" --min-api 24 --release --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "== packaging"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q -X ../unsigned.apk classes.dex )
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f notefloat-release.jks ]; then
  echo "== creating signing key"
  keytool -genkeypair -v -keystore notefloat-release.jks -alias notefloat \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass notefloat2026 -keypass notefloat2026 \
    -dname "CN=NoteFloat, OU=Apps, O=NoteFloat, L=Lagos, C=NG" > "$OUT/keytool.log" 2>&1
fi

echo "== signing"
"$BT/apksigner" sign --ks notefloat-release.jks --ks-key-alias notefloat \
  --ks-pass pass:notefloat2026 --key-pass pass:notefloat2026 \
  --min-sdk-version 24 --out "NoteFloat-$VERSION_NAME.apk" "$OUT/aligned.apk"

echo "== verifying"
"$BT/apksigner" verify --print-certs "NoteFloat-$VERSION_NAME.apk"
ls -lh "NoteFloat-$VERSION_NAME.apk"
