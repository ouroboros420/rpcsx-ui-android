<div align="center">

# RPCSX-UI-Android (Ouroboros fork)

*A native Android app for playing PlayStation 3 games, built on the RPCSX project's RPCS3-derived PS3 emulation core.*

</div>

> **Warning:** Do not ask for links to games or system files. Piracy is not permitted in this repository or in the upstream project's channels.

## What this is

This is a **personal fork** of [RPCSX-UI-Android](https://github.com/RPCSX/rpcsx-ui-android), the Android front-end for the RPCSX emulator. The app provides the user interface, game/library management, GPU-driver loading (libadrenotools / Turnip), input handling and the on-screen controls; the actual PS3 emulation is performed by the native **rpcsx core** (an RPCS3-derived library), which is built separately and loaded at runtime.

It targets **arm64 Android devices** (PlayStation 3 emulation).

## What's different in this fork

This fork carries arm64/Android-focused performance work, build fixes, and quality-of-life changes on top of upstream. See [`CHANGELOG.md`](CHANGELOG.md) for the full list.

> **Honesty note on AI involvement:** the modifications in this fork — the app-side changes here, and the ARM64 optimizations and fixes in the companion [rpcsx core fork](https://github.com/Ouroboros420/rpcsx) — were developed with **heavy assistance from an AI coding agent (Claude)**, under human direction and review. The underlying emulator and front-end are the work of the **RPCS3** and **RPCSX** teams; this fork only adds changes on top of theirs, and full credit for each ported upstream change is kept in the individual commit messages.

## Requirements

- Android 10+ (arm64-v8a)
- A custom GPU (Turnip) driver is recommended on Adreno devices; the app can download and load one from **Settings → GPU drivers**.

## Building

`./gradlew assembleDebug` (or `assembleRelease`) with Android Studio's bundled JDK + the Android SDK/NDK. The app downloads/side-loads the native core `.so` at runtime, so building the app does not build the emulator core — see the [rpcsx core fork](https://github.com/Ouroboros420/rpcsx) for that.

## Credits

- [RPCSX](https://github.com/RPCSX) team — the emulator and Android front-end.
- [RPCS3](https://github.com/RPCS3/rpcs3) team — the PS3 emulation core this is derived from.
- Fork modifications: Ouroboros420, with AI assistance (Claude).

## License

GPLv2, except directories or files that carry their own license.
