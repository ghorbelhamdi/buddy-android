# Third-party components

Buddy's built-in Linux runs through **proot**, which ships inside the APK as native libraries
(`app/src/main/jniLibs/arm64-v8a/`, copied from Termux's aarch64 packages; renamed to `lib*.so` so
Android extracts them, and with their library search paths adjusted with `patchelf`).

| File in the APK | Component | Version | Licence | Source |
|---|---|---|---|---|
| `libproot.so`, `libproot-loader.so` | PRoot (Termux fork) | 5.1.107.96 | GPL-2.0 ([text](proot-LICENSE.txt)) | https://github.com/termux/proot · build recipe: https://github.com/termux/termux-packages/tree/master/packages/proot |
| `libtalloc.so` | talloc | 2.5.0 | LGPL-3.0-or-later ([text](talloc-LICENSE.txt)) | https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz |
| `libandroid-shmem.so` | libandroid-shmem | 0.7 | BSD-3-Clause ([text](libandroid-shmem-LICENSE.txt)) | https://github.com/termux/libandroid-shmem |

Downloaded at runtime, not bundled: Alpine Linux (minirootfs and packages, various open-source licences,
https://alpinelinux.org), Claude Code (Anthropic, installed with Anthropic's official installer under
Anthropic's terms), Codex (OpenAI, from npm), Android build-tools and the Android platform (Google,
Android SDK terms), fetched only when developer mode's toolchain is set up.

Fonts: Geist and Geist Mono (SIL Open Font License, `app/src/main/assets/fonts/OFL-Geist.txt`).
Icons: Material Symbols (Apache-2.0).
