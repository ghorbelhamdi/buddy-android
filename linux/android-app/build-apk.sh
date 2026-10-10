#!/bin/bash
# Build a signed APK from a plain-Java Android project, on the phone, without Gradle.
#   build-apk.sh <project-dir>
# Project layout:
#   <project>/AndroidManifest.xml   (with package="..." on <manifest>)
#   <project>/java/**.java
#   <project>/res/...               (optional)
#   <project>/assets/...            (optional)
#   <project>/app.properties        (optional: versionCode=1, versionName=1.0, minSdk=26)
# Output: <project>/build/app.apk
set -euo pipefail
P=$(cd "${1:?usage: build-apk.sh <project-dir>}" && pwd)
SDK_JAR=${SDK_JAR:-$HOME/android-sdk/android-36/android.jar}
KEYDIR=$HOME/.buddy/keys
OUT=$P/build

prop() { [ -f "$P/app.properties" ] && sed -n "s/^$1=//p" "$P/app.properties" | head -1 || true; }
VCODE=$(prop versionCode); VCODE=${VCODE:-1}
VNAME=$(prop versionName); VNAME=${VNAME:-1.0}
MINSDK=$(prop minSdk); MINSDK=${MINSDK:-26}

[ -f "$SDK_JAR" ] || { echo "android.jar missing at $SDK_JAR. Run setup-toolchain.sh first." >&2; exit 1; }
for t in javac d8 aapt2 apksigner keytool python3; do
  command -v $t >/dev/null || { echo "$t missing. Run setup-toolchain.sh first." >&2; exit 1; }
done
[ -f "$P/AndroidManifest.xml" ] || { echo "No AndroidManifest.xml in $P" >&2; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "1/5 Resources"
RES=()
if [ -d "$P/res" ] && [ -n "$(ls -A "$P/res")" ]; then
  aapt2 compile --dir "$P/res" -o "$OUT/res.zip"
  RES=("$OUT/res.zip")
fi
ASSETS=()
[ -d "$P/assets" ] && ASSETS=(-A "$P/assets")
aapt2 link -I "$SDK_JAR" --manifest "$P/AndroidManifest.xml" \
  --min-sdk-version "$MINSDK" --target-sdk-version 36 \
  --version-code "$VCODE" --version-name "$VNAME" \
  "${ASSETS[@]}" --java "$OUT/gen" -o "$OUT/base.apk" "${RES[@]}"

echo "2/5 Java"
javac --release 8 -classpath "$SDK_JAR" -Xlint:-options -encoding UTF-8 -nowarn \
  -d "$OUT/classes" $(find "$P/java" "$OUT/gen" -name '*.java')

echo "3/5 Dex"
d8 --release --min-api "$MINSDK" --lib "$SDK_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "4/5 Package"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/aligned.apk" <<'EOF'
import sys, zipfile
src, dex, dst = sys.argv[1:]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    entries = [(i, zin.read(i.filename)) for i in zin.infolist()]
    entries.append((zipfile.ZipInfo("classes.dex"), open(dex, "rb").read()))
    entries[-1][0].compress_type = zipfile.ZIP_DEFLATED
    for info, data in entries:
        out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        out.compress_type = info.compress_type
        out.external_attr = info.external_attr
        if out.compress_type == zipfile.ZIP_STORED:  # align stored data (resources.arsc) to 4 bytes
            start = zout.fp.tell() + 30 + len(out.filename.encode())
            out.extra = b"\0" * ((-start) % 4)
        zout.writestr(out, data)
EOF

echo "5/5 Sign"
if [ ! -f "$KEYDIR/apps.jks" ]; then
  mkdir -p "$KEYDIR"; chmod 700 "$KEYDIR"
  head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24 > "$KEYDIR/password"
  chmod 600 "$KEYDIR/password"
  keytool -genkeypair -keystore "$KEYDIR/apps.jks" -alias apps -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$(cat "$KEYDIR/password")" -keypass "$(cat "$KEYDIR/password")" -dname "CN=Buddy apps" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYDIR/apps.jks" --ks-key-alias apps --ks-pass "file:$KEYDIR/password" \
  --out "$OUT/app.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/app.apk"
echo "Built $OUT/app.apk"
