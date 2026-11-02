#!/usr/bin/env bash
# Builds Buddy's native libraries for arm64 Android, all from source, into app/src/main/jniLibs/arm64-v8a:
#   libproot.so + libproot-loader.so  PRoot (Termux's fork), runs the built-in Linux
#   libtalloc.so                      talloc, PRoot's memory allocator
#   libandroid-shmem.so               System V shared memory for PRoot's guests
#   libbuddypty.so                    the terminal's pty helper (pty.c)
# With ANDROID_NDK_HOME set it uses that NDK (CI); otherwise Termux's clang on the phone
# (pkg install clang make unzip readelf). Sources are downloaded once and checked by SHA-256.
set -euo pipefail
cd "$(dirname "$0")"
HERE=$PWD
API=30
PROOT_VERSION=5.1.107.102
PROOT_SHA256=30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852
TALLOC_VERSION=2.5.0
TALLOC_SHA256=912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007
SHMEM_VERSION=0.7
SHMEM_SHA256=1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867

OUT=$HERE/../app/src/main/jniLibs/arm64-v8a
WORK=$HERE/build
mkdir -p "$OUT" "$WORK/src" "$WORK/lib" "$WORK/include/sys"

if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
  BIN=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin)
  CC="$BIN/clang --target=aarch64-linux-android$API"
  STRIP=$BIN/llvm-strip OBJCOPY=$BIN/llvm-objcopy OBJDUMP=$BIN/llvm-objdump
else
  CC="clang --target=aarch64-linux-android$API"
  STRIP=llvm-strip OBJCOPY=llvm-objcopy OBJDUMP=llvm-objdump
fi
LDPAGE=-Wl,-z,max-page-size=16384

fetch() { # url file sha256
  local f=$WORK/src/$2
  if [[ ! -f $f ]]; then curl -fsSL -o "$f.part" "$1" && mv "$f.part" "$f"; fi
  echo "$3  $f" | sha256sum -c --quiet || { rm -f "$f"; echo "checksum mismatch: $2" >&2; exit 1; }
}

# Termux's clang always adds its own library path; the app's libraries must not carry it.
finish() { # built file, name in the APK
  if readelf -dW "$1" | grep -q RUNPATH; then
    env -u LD_PRELOAD "${PREFIX:-}/glibc/bin/patchelf" --remove-rpath "$1" 2>/dev/null || patchelf --remove-rpath "$1"
  fi
  $STRIP --strip-unneeded "$1"
  cp "$1" "$OUT/$2"
  echo "Built $2"
}

fetch "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz" "talloc-$TALLOC_VERSION.tar.gz" $TALLOC_SHA256
fetch "https://github.com/termux/libandroid-shmem/archive/refs/tags/v$SHMEM_VERSION.tar.gz" "shmem-$SHMEM_VERSION.tar.gz" $SHMEM_SHA256
fetch "https://github.com/termux/proot/archive/v$PROOT_VERSION.zip" "proot-$PROOT_VERSION.zip" $PROOT_SHA256
cd "$WORK"
rm -rf "talloc-$TALLOC_VERSION" "libandroid-shmem-$SHMEM_VERSION" "proot-$PROOT_VERSION"
tar xzf "src/talloc-$TALLOC_VERSION.tar.gz"
tar xzf "src/shmem-$SHMEM_VERSION.tar.gz"
unzip -q "src/proot-$PROOT_VERSION.zip"

# talloc: one source file; talloc-config.h answers what its waf configure would have found on Android
T=$WORK/talloc-$TALLOC_VERSION
cp "$HERE/talloc-config.h" config.h
$CC -fPIC -O2 -fvisibility=hidden -D__STDC_WANT_LIB_EXT1__=1 -D_GNU_SOURCE \
  -I. -I"$T" -I"$T/lib/replace" -c "$T/talloc.c" -o talloc.o
$CC -shared -Wl,-soname,libtalloc.so $LDPAGE talloc.o -o lib/libtalloc.so
cp "$T/talloc.h" include/

# libandroid-shmem (its own Makefile's flags). _PATH_TMP is Termux's addition to paths.h, which the NDK lacks: the
# value Termux's clang gives it, so a build is the same with either compiler (it only names where keyed segments link).
S=$WORK/libandroid-shmem-$SHMEM_VERSION
$CC -fpic -std=c11 -O2 -Wall '-D_PATH_TMP="/data/data/com.termux/files/usr/tmp/"' -c "$S/shmem.c" -o shmem.o
$CC -shared -Wl,-soname,libandroid-shmem.so -Wl,--version-script="$S/exports.txt" $LDPAGE shmem.o \
  -o lib/libandroid-shmem.so -llog -landroid
cp "$S/shm.h" include/sys/shm.h

# PRoot, as Termux builds it, with the loader as its own file (Buddy passes its path in PROOT_LOADER).
# One source file gets string.h only through Termux's headers, so it's added for the NDK.
sed -i '1i #include <string.h>' "proot-$PROOT_VERSION/src/extension/ashmem_memfd/ashmem_memfd.c"
# CPPFLAGS/LDFLAGS go in the environment so the Makefile's own additions still apply. The loader links with its own
# flags, and the NDK's linker would otherwise lay it out for 4 KB pages only.
CPPFLAGS="-I$WORK/include -DARG_MAX=131072 -DVERSION=\\\"$PROOT_VERSION\\\"" LDFLAGS="-L$WORK/lib $LDPAGE" LOADER_LDFLAGS="$LDPAGE" \
  make -s -C "proot-$PROOT_VERSION/src" proot loader/loader \
  CC="$CC" LD="$CC" STRIP="$STRIP" OBJCOPY="$OBJCOPY" OBJDUMP="$OBJDUMP" HAS_LOADER_32BIT= \
  PROOT_WITH_LIBANDROID_SHMEM=true PROOT_UNBUNDLE_LOADER=/nonexistent

finish lib/libtalloc.so libtalloc.so
finish lib/libandroid-shmem.so libandroid-shmem.so
finish "proot-$PROOT_VERSION/src/proot" libproot.so
cp "proot-$PROOT_VERSION/src/loader/loader" "$OUT/libproot-loader.so" && $STRIP "$OUT/libproot-loader.so" && echo "Built libproot-loader.so"

# the pty helper; jni.h comes with the NDK, or with Termux's JDK on the phone
JNI=()
if command -v javac >/dev/null; then
  J=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")/include
  JNI=(-I"$J" -I"$J/linux")
fi
$CC -shared -fPIC -O2 -Wall $LDPAGE "${JNI[@]}" -o pty.so "$HERE/pty.c"
finish pty.so libbuddypty.so
