# Changelog — RPCSX-UI-Android (Ouroboros fork)

Changes on top of upstream `RPCSX/rpcsx-ui-android`. Developed with AI assistance (Claude).
The emulator performance work lives in the companion [rpcsx core fork](https://github.com/Ouroboros420/rpcsx) — see its `CHANGELOG.md`.

## v1.0.0

### Features
- **Sustained Performance Mode** (Settings, default **on**) — asks Android to hold steady CPU/GPU clocks instead of brief turbo bursts. Long PS3 sessions are heat-limited, so steady clocks keep frame times consistent and avoid thermal-throttle stutter. The setting carries an in-app description; turn it off for maximum short-burst speed. No-op on devices that don't support it.

### Updates / channels
- **Update channels now point to this fork** (`Ouroboros420/rpcsx-ui-android` for the app, `Ouroboros420/rpcsx` for the core) instead of the upstream RPCSX repos. This stops the app from repeatedly offering to "update" to the official builds (which would downgrade this custom build).
- **Migrates an already-stored upstream channel** to the fork on launch, so the redirect also takes effect on an in-place reinstall (not only on a clean install).
- Core/app update releases are expected on those forks: the core asset must be named `librpcsx-android-<abi>-<arch>.so` (e.g. `librpcsx-android-arm64-v8a-armv8-a.so`) and the app asset `rpcsx-release.apk`; tag the release to match the installed version to avoid an update prompt.

### Versioning
- App version is now **1.0.0** (`v1.0.0`) instead of the placeholder `local` (which showed as "ui: vlocal").

### Docs
- README rewritten: accurate description (PS3 emulation on Android), an honest note about the AI-assisted nature of this fork, build notes, and credits to the RPCS3/RPCSX teams.
