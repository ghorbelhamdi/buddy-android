---
name: android-app
description: Create, build, install and update a small Android app (APK) directly on this phone, without Gradle or a computer. Use when the user asks to make, build, change or install an Android app.
---

# Building Android apps on this phone

Everything runs in Buddy's built-in Linux (Alpine) on the phone. There is no Gradle and no Android Studio. Use plain Java and the Android framework only (no AndroidX, no Kotlin, no external libraries).

The scripts live next to this file. `SKILL_DIR` below means this skill's folder:
`$HOME/.buddy/work/.claude/skills/android-app`.

## 1. Toolchain (first time only)

Run `bash $SKILL_DIR/setup-toolchain.sh` (downloads about 400 MB the first time; allow up to 10 minutes). It installs OpenJDK 17, `aapt2`, `d8` and `apksigner`, and puts the Android platform at `~/android-sdk/android-36/android.jar`. It is fast when everything already exists, so just run it before the first build in a conversation.

## 2. Project layout

Create one folder per app in `~/.buddy/work/<appname>/`:

```
AndroidManifest.xml      <manifest package="com.buddyapps.<name>"> … one launcher <activity>
java/com/buddyapps/<name>/MainActivity.java
res/values/strings.xml   (optional; you can also build the UI in code)
app.properties           versionCode=1  versionName=1.0  minSdk=26
```

Rules that avoid the usual failures:
- Package names start with `com.buddyapps.` so they never clash with real apps.
- Put `package="…"` on `<manifest>`, and `android:exported="true"` on the launcher activity.
- Use `android:theme="@android:style/Theme.DeviceDefault"` (no AppCompat).
- Build the UI in Java code (LinearLayout, Button, TextView…) unless the user asks otherwise: it avoids XML layout errors.
- Java 8 syntax is fine (lambdas OK). Don't use `R.layout` unless you created that layout.
- For an icon, reuse `@android:drawable/sym_def_app_icon` or add `res/drawable/ic_launcher.xml` (a vector).

## 3. Build

`bash $SKILL_DIR/build-apk.sh ~/.buddy/work/<appname>` produces `~/.buddy/work/<appname>/build/app.apk`.
If compilation fails, read the error, fix the Java, and build again. Each update must bump `versionCode` in `app.properties`.

## 4. Install (the user taps the final button)

Android's installer and Play Protect dialogs ignore taps from accessibility apps, so you can open
the installer but **the user has to tap Install/Update and confirm (often with a fingerprint)**.

1. Call the phone tool `install_apk` with the APK's full path, e.g. `/root/.buddy/work/<appname>/build/app.apk`.
   Buddy opens Android's installer for it.
2. If it says Buddy isn't allowed to install apps yet, Android's settings page is open: ask the user to turn on
   **Allow from this source** for Buddy, wait for them to say so, then call `install_apk` again. Don't change that
   setting yourself.
3. Call `wait_for_install` with the package name (and `min_version_code` for an update). It tells the user
   "Tap Install/Update, then confirm", vibrates, and returns as soon as the app is installed. Do not tap
   Install yourself, never claim you did, and never write your own shell loop to wait.
4. If it says the installer is no longer on screen, call `install_apk` once more and then `wait_for_install`.
   If it times out, ask the user whether they want to install it.
5. Open the new app with `open_app` and `read_screen` to check it works, then report what you built,
   including that the user confirmed the install.
