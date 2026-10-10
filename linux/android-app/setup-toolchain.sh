#!/bin/bash
# One-time setup of the Android build tools inside Buddy's Linux (about 400 MB). Safe to run again.
#   aapt2/zipalign: Alpine's android-build-tools (edge/testing, built for ARM)
#   javac: OpenJDK 17;  d8 + apksigner: Google's build-tools jars;  android.jar: the Android 36 platform
set -euo pipefail
SDK=$HOME/android-sdk
E=https://dl-cdn.alpinelinux.org/alpine/edge
# Only android-build-tools (and what it needs) comes from edge; the rest of the system stays on stable.
grep -q '^@edge ' /etc/apk/repositories || printf '@edge %s/main\n@edge %s/community\n@edge %s/testing\n' "$E" "$E" "$E" >> /etc/apk/repositories
if ! command -v aapt2 >/dev/null || ! command -v javac >/dev/null; then
  echo "Installing Java and aapt2…"
  apk update -q
  apk add -q openjdk17-jdk python3 unzip android-build-tools@edge
fi

if [ ! -f "$SDK/build-tools/lib/d8.jar" ] || [ ! -f "$SDK/build-tools/lib/apksigner.jar" ]; then
  echo "Downloading d8 and apksigner (Android build-tools 36, 64 MB)…"
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/bt.zip" https://dl.google.com/android/repository/build-tools_r36_linux.zip
  mkdir -p "$SDK/build-tools/lib"
  unzip -q -o -j "$tmp/bt.zip" '*/lib/d8.jar' '*/lib/apksigner.jar' -d "$SDK/build-tools/lib"
  rm -rf "$tmp"
fi
cat > /usr/local/bin/d8 <<W
#!/bin/sh
exec java -Xmx1g -cp "$SDK/build-tools/lib/d8.jar" com.android.tools.r8.D8 "\$@"
W
cat > /usr/local/bin/apksigner <<W
#!/bin/sh
exec java -Xmx512m -jar "$SDK/build-tools/lib/apksigner.jar" "\$@"
W
chmod 755 /usr/local/bin/d8 /usr/local/bin/apksigner

JAR=$SDK/android-36/android.jar
if [ ! -f "$JAR" ]; then
  echo "Downloading the Android 36 platform (android.jar, 66 MB)…"
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/p.zip" https://dl.google.com/android/repository/platform-36_r02.zip
  mkdir -p "$SDK"
  unzip -q -o "$tmp/p.zip" -d "$SDK"
  rm -rf "$tmp"
fi
echo "Toolchain ready: $(javac -version 2>&1), $(aapt2 version 2>&1 | head -1), android.jar at $JAR"
