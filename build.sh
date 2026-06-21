#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"

ANDROID_JAR=android-real.jar
OUT=build
APK_UNSIGNED=$OUT/app-unsigned.apk
APK=codeserver.apk
KS=keystore.jks

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
