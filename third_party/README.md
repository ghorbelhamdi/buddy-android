# Third-party components

Buddy's built-in Linux runs through **proot**, which ships inside the APK as native libraries
(named `lib*.so` so Android extracts them). They are built from source by [`native/build.sh`](../native/build.sh)
with the Android NDK in CI (or Termux's clang on a phone), the way Termux builds them. The sources are here:
proot and libandroid-shmem as git submodules at their release tags, and the three files of talloc that the build
uses (`talloc.c`, `talloc.h`, `lib/replace/replace.h`), unchanged from the talloc 2.5.0 release.

| File in the APK | Component | Version | Licence | Source |
|---|---|---|---|---|
| `libproot.so`, `libproot-loader.so` | PRoot (Termux fork) | 5.1.107.102 | GPL-2.0 ([text](proot-LICENSE.txt)) | [`proot/`](proot), https://github.com/termux/proot (tag v5.1.107.102) |
| `libtalloc.so` | talloc | 2.5.0 | LGPL-3.0-or-later ([text](talloc-LICENSE.txt)) | [`talloc/`](talloc), from https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz |
| `libandroid-shmem.so` | libandroid-shmem | 0.7 | BSD-3-Clause ([text](libandroid-shmem-LICENSE.txt)) | [`libandroid-shmem/`](libandroid-shmem), https://github.com/termux/libandroid-shmem (tag v0.7) |

Downloaded at runtime, not bundled: Alpine Linux (minirootfs and packages, various open-source licences,
https://alpinelinux.org), Claude Code (Anthropic, installed with Anthropic's official installer under
Anthropic's terms), Codex (OpenAI, from npm), Android build-tools and the Android platform (Google,
Android SDK terms), fetched only when developer mode's toolchain is set up.

Fonts: Geist and Geist Mono (SIL Open Font License, `app/src/main/assets/fonts/OFL-Geist.txt`).
Icons: Material Symbols (Apache-2.0).
