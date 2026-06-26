# code-server-android-client

Android APK client for [code-server](https://github.com/coder/code-server) — runs VS Code in the browser, wrapped in a native Android app.

Built entirely in [Termux](https://termux.dev/) without Android Studio.

## What it does

Launches a WebView that connects to a running code-server instance. Appears as "VS Code" on your launcher.

## Requirements

- Android 5.0+ (API 21)
- A running code-server instance (local or remote)
- Termux (to build)

## Build dependencies (Termux)

```bash
pkg install aapt2 ecj d8 apksigner curl unzip
```

## Build

```bash
bash build.sh
```

Output: `codeserver.apk`

The first build downloads the Android SDK Platform 28 jar (~72 MB) from Google
and caches it locally as `android-real.jar` (gitignored, checksum-verified);
later builds reuse it.

## Install

```bash
adb install codeserver.apk
# or sideload directly on device
```

## Project structure

```
src/          Java source
res/          Android resources
gen/          Generated files
AndroidManifest.xml
build.sh      Build script (no Android Studio needed)
icon.svg      App icon source
```
