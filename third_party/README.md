# Third-party components

Buddy's built-in Linux runs through **proot**, which ships inside the APK as native libraries
(named `lib*.so` so Android extracts them). They are built from source by [`native/build.sh`](../native/build.sh):
it downloads the exact source releases below, checks their SHA-256, and compiles them with the Android NDK
in CI (or Termux's clang on a phone), the way Termux builds them.

| File in the APK | Component | Version | Licence | Source |
|---|---|---|---|---|
| `libproot.so`, `libproot-loader.so` | PRoot (Termux fork) | 5.1.107.102 | GPL-2.0 ([text](proot-LICENSE.txt)) | https://github.com/termux/proot/archive/v5.1.107.102.zip |
| `libtalloc.so` | talloc | 2.5.0 | LGPL-3.0-or-later ([text](talloc-LICENSE.txt)) | https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz |
| `libandroid-shmem.so` | libandroid-shmem | 0.7 | BSD-3-Clause ([text](libandroid-shmem-LICENSE.txt)) | https://github.com/termux/libandroid-shmem/archive/refs/tags/v0.7.tar.gz |

Downloaded at runtime, not bundled: Alpine Linux (minirootfs and packages, various open-source licences,
https://alpinelinux.org), Claude Code (Anthropic, installed with Anthropic's official installer under
Anthropic's terms), Codex (OpenAI, from npm), Android build-tools and the Android platform (Google,
Android SDK terms), fetched only when developer mode's toolchain is set up.

Fonts: Geist and Geist Mono (SIL Open Font License, `app/src/main/assets/fonts/OFL-Geist.txt`).
Icons: Material Symbols (Apache-2.0).
