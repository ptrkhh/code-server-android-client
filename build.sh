#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"

ANDROID_JAR=android-real.jar
ANDROID_JAR_URL="https://dl.google.com/android/repository/platform-28_r06.zip"
ANDROID_JAR_SHA256=d20853d289a5161d9cf1c1268d7923076487d4fa68c970b0733e2d5a0ccfc3c5
OUT=build
APK_UNSIGNED=$OUT/app-unsigned.apk
APK=codeserver.apk
KS=keystore.jks

# Android SDK Platform 28 stub jar (the compile + d8 classpath). Not vendored in
# the repo -- fetched once from Google's official platform package and cached
# locally (gitignored). Checksum-verified so a partial/bad download fails loudly.
if [ ! -f "$ANDROID_JAR" ]; then
  echo "[0/5] fetch Android SDK Platform 28 jar -> $ANDROID_JAR"
  tmp=$(mktemp -d)
  curl -fSL --retry 4 -o "$tmp/platform.zip" "$ANDROID_JAR_URL"
  unzip -j -o "$tmp/platform.zip" 'android-9/android.jar' -d "$tmp"
  mv "$tmp/android.jar" "$ANDROID_JAR"
  rm -rf "$tmp"
fi
echo "$ANDROID_JAR_SHA256  $ANDROID_JAR" | sha256sum -c - >/dev/null \
  || { echo "ERROR: $ANDROID_JAR failed checksum; delete it and rebuild"; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT"

echo "[1/5] aapt2 compile+link: resources + manifest"
aapt2 compile --dir res -o "$OUT/res-compiled.zip"
aapt2 link -o "$OUT/res.apk" \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  -R "$OUT/res-compiled.zip" \
  --auto-add-overlay \
  --min-sdk-version 21 --target-sdk-version 28

echo "[2/5] compile java (ecj)"
mkdir -p "$OUT/classes"
ecj -d "$OUT/classes" -nowarn \
  -cp "$ANDROID_JAR" \
  $(find src -name '*.java')

echo "[3/5] d8: classes.dex"
d8 --lib "$ANDROID_JAR" --min-api 21 --output "$OUT" \
  $(find "$OUT/classes" -name '*.class')

echo "[4/5] add dex into apk"
cp "$OUT/res.apk" "$APK_UNSIGNED"
( cd "$OUT" && zip -j -q "../$APK_UNSIGNED" classes.dex )

echo "[5/5] sign"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias key -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=CodeServer,O=Termux,C=US"
fi
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$APK" "$APK_UNSIGNED"

echo "DONE -> $(pwd)/$APK"
