#!/bin/bash
# Build the watch APK with aapt2 + javac + d8 (no Gradle, no AndroidX).
set -e
cd "$(dirname "$0")"

SDK_JAR=/opt/android-sdk/platforms/android-28/android.jar
D8_JAR=/opt/android-sdk/build-tools/34.0.0/lib/d8.jar
BT=/usr/lib/android-sdk/build-tools/29.0.3
AAPT2="$BT/aapt2"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

for tool in "$AAPT2" "$ZIPALIGN"; do
  [ -x "$tool" ] || { echo "missing $tool (apt install aapt android-sdk-build-tools)"; exit 1; }
done
[ -f "$SDK_JAR" ] || { echo "missing android.jar"; exit 1; }
[ -f "$D8_JAR" ] || { echo "missing d8.jar"; exit 1; }

rm -rf build
mkdir -p build/gen build/classes build/dex

echo "[1/6] icon"
python3 make_icon.py

echo "[2/6] resources"
"$AAPT2" compile --dir res -o build/res.zip
"$AAPT2" link \
  -o build/app.ap_ \
  -I "$SDK_JAR" \
  --manifest AndroidManifest.xml \
  --auto-add-overlay \
  -R build/res.zip \
  --java build/gen \
  --min-sdk-version 21 \
  --target-sdk-version 27 \
  --version-code 130 \
  --version-name v2.0_beta

echo "[3/6] javac"
find src build/gen -name '*.java' > build/sources.txt
javac -source 8 -target 8 -nowarn \
  -bootclasspath "$SDK_JAR" \
  -d build/classes \
  @build/sources.txt 2>&1 | grep -v "warning: \[options\]" || true
[ -f build/classes/io/dsh/watch/MainActivity.class ] || { echo "javac failed"; exit 1; }

echo "[4/6] dex"
find build/classes -name '*.class' > build/classes.txt
java -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --lib "$SDK_JAR" --min-api 21 \
  --output build/dex @build/classes.txt

echo "[5/6] package"
python3 - <<'PY'
import zipfile
apk_zip = "build/app.ap_"
out = "build/unsigned.apk"
with zipfile.ZipFile(apk_zip) as src, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
    for item in src.infolist():
        dst.writestr(item, src.read(item.filename))
    dst.write("build/dex/classes.dex", "classes.dex")
print("packaged", out)
PY
"$ZIPALIGN" -f 4 build/unsigned.apk build/aligned.apk

echo "[6/6] sign"
KEYSTORE=keystore.jks
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias dshwatch \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass dshwatch -keypass dshwatch \
    -dname "CN=DSH Watch, OU=dsh, O=dsh, L=earth, C=CN" >/dev/null 2>&1
  echo "created $KEYSTORE"
fi
mkdir -p ../dist
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:dshwatch --key-pass pass:dshwatch \
  --out ../dist/dsh-watch.apk build/aligned.apk
"$APKSIGNER" verify --print-certs ../dist/dsh-watch.apk | head -5
ls -lh ../dist/dsh-watch.apk
echo DONE
