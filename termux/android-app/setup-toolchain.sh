#!/data/data/com.termux/files/usr/bin/bash
# One-time setup of the on-phone Android build tools (about 300 MB). Safe to run again.
set -euo pipefail
need=()
for p in "openjdk-17:javac" "aapt2:aapt2" "d8:d8" "apksigner:apksigner" "python:python3"; do
  command -v "${p#*:}" >/dev/null || need+=("${p%%:*}")
done
[ ${#need[@]} -gt 0 ] && yes | pkg install -y "${need[@]}"

JAR=$HOME/android-sdk/android-36/android.jar
if [ ! -f "$JAR" ]; then
  echo "Downloading the Android 36 platform (android.jar)…"
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/p.zip" https://dl.google.com/android/repository/platform-36_r02.zip
  mkdir -p "$HOME/android-sdk"
  unzip -q -o "$tmp/p.zip" -d "$HOME/android-sdk"
  rm -rf "$tmp"
fi
echo "Toolchain ready: $(javac -version 2>&1), android.jar at $JAR"
