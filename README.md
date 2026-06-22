<p align="center">
  <img src=".github/icon.png" width="120" alt="RPCSX-Clanker icon">
</p>

<h1 align="center">RPCSX-Clanker <sub>(experimental)</sub></h1>

A native Android front-end for **PlayStation 3** emulation on arm64. A continuously-updated
fork of [RPCSX/rpcsx-ui-android](https://github.com/RPCSX/rpcsx-ui-android). The actual
emulation is done by the separate
[RPCSX-Clanker core](https://github.com/Ouroboros420/rpcsx), loaded at runtime.

It ships as the **`net.rpcsx.clanker`** package so it installs side-by-side with official
RPCSX.

---

## Two requests if you use this build

This is **not original work**. [**RPCS3**](https://rpcs3.net/) is the PlayStation 3
emulator for desktop (PC / laptop). The unofficial **RPCSX** group ported RPCS3 to Android,
but that port fell behind and is now running an outdated RPCS3 base. What this project does
is bring the **latest RPCS3 changes into the RPCSX Android app and core to update it**, with
heavy AI assistance. If this build is useful to you, please honour these two things:

### 1. Donate to the teams who actually built the emulator
Support them:
- **RPCS3** — <https://rpcs3.net/> · Patreon: <https://www.patreon.com/Nekotekina>
- **RPCSX** — <https://github.com/RPCSX/rpcsx>

### 2. Play Demon's Souls online — and find me

---

### Honest note on how it's made
Updates here — and especially the work to merge newer
[RPCS3](https://github.com/RPCS3/rpcs3) changes into the core — are done with heavy
**AI assistance** (Claude). Treat it as **experimental**: verify before relying on it, and
expect rough edges.

### Why this exists
The RPCSX Android port is built on an older RPCS3 snapshot and had stopped tracking upstream,
leaving Android a generation behind the desktop emulator. This project exists to **close that
gap** — porting current RPCS3 fixes and features into the RPCSX Android app and core. It is
not a permanent splinter fork: all credit for the emulator and the original UI belongs to the
**RPCS3** and **RPCSX** developers, who are welcome to take anything useful from here.

> Piracy is not permitted. Do not ask for games or system files.

---

## Things to be cautious about

<details open>
<summary><b>Known issues, trade-offs &amp; gotchas</b></summary>

- **Experimental & AI-assisted** — expect bugs and rough edges; verify before relying on it.
- **Fresh, separate install.** Because the package was renamed to `net.rpcsx.clanker` for
  side-by-side use with official RPCSX, this is a **clean, empty install** — games, configs,
  saves, caches and firmware are **not** carried over. Set things up again.
- **The core ships separately.** The emulation `.so` is downloaded/updated by the in-app
  updater (or side-loaded), independent of this APK. Pair the app with a matching-or-newer
  core build.
- **RPCN online toggle isn't fully authoritative yet.** A **per-game config** can pin a
  title online and override the global RPCN enable/disable toggle, and an open session isn't
  torn down on disable. To reliably go offline you may also need to clear the per-game
  config's online/net setting. (Proper fix is on the TODO.)
- **RPCN credentials** (`rpcn.yml`, derived password + token) are stored in app-internal
  storage (not MTP/USB/other-app readable) and excluded from backup. Server-certificate
  verification is still off (same as upstream), so avoid adding untrusted custom RPCN hosts
  on a network you do not control.
- **Battery-saver is default ON** (it helped on the test device); other power toggles
  (sustained perf, CPU affinity, low-power WFE, thermal, ADPF) are **default OFF and
  unproven** — enabling them may regress fps/stability.
- **Smooth-shaders toggle was removed** — the async-interpreter path freezes on the current
  backend; the core forces the safe synchronous path.
- **Box-art covers are off** — remote cover loading was unreliable, so the library shows an
  icon-only grid for now.
- **Configurable patches** (sliders/dropdowns) aren't supported yet — only on/off patches.
</details>

---

## Changes vs upstream RPCSX app

<details>
<summary><b>UI / theming (Material 3)</b></summary>

- **Card-style Material 3 settings** — grouped, sectioned cards instead of a flat toggle list.
- **Clanker Settings hub** — all fork extras under one entry with click-through sub-screens (Themes, Features, Netplay, Patch Manager).
- **Theme controls** — light/dark/system mode, Material You (dynamic color where supported), AMOLED black-background option.
- **Custom accent color** — reusable picker (hex + RGB sliders + preset swatches) recoloring the Material 3 primary group; default accent is the launcher lavender (#BBADDE).
- **Recolored launcher icon** — adaptive icon recolored to the lavender/purple family (duotone luminance recolor across all densities).
- **Game library polish** — game-version (APP_VER) badge on covers; one-tap folder import for ISO/disc dumps with a fixed scan loop and labeled add buttons.
- **Game-view theming** — optional rounded corners + border drawn by a non-clipping overlay (now applied to library tiles for the in-game safe path).
</details>

<details>
<summary><b>Patch Manager (new)</b></summary>

- **JNI bridge to the core patch engine** (list / toggle / version) + a full Patch Manager screen: download official patches, import local patches, per-patch toggles.
- **Game-centric grouping** — collapsible per-game groups with an enabled-count, dedup of duplicate patch rows, and a "show all-serial patches" option.
- **Search** by game name, serial, or author (groups stay collapsible during search).
- **Per-game patches** surfaced inside the per-game Configure screen.
- Fixed a **force-close** from duplicate LazyColumn keys (one program hash hosts many patches; fix keys by the full group-identity tuple) and a toggle bug that flipped unrelated rows sharing a hash.
</details>

<details>
<summary><b>Per-game configuration (new)</b></summary>

- **JNI bridge + per-game configuration screen** by title id (fixes per-game config + patches for disc/ISO titles).
- **One-tap community config** — fetches the official RPCS3 config DB (`api.rpcs3.net`), previews the recommended YAML in a scrollable dialog before applying.
- **"What changed" surfacing** — per-section "N changed" headers, a total "N settings differ from default" card, per-setting markers.
- Categories and the patches list collapsed by default; per-game **Clear Cache** (with a fix for it silently doing nothing).
- Extra settings: performance overlay toggle, Video Decoder trace-log toggle.
</details>

<details>
<summary><b>RPCN / netplay settings (new — netplay made usable on Android)</b></summary>

- **RPCN JNI bindings** (config, host list, account create/login, enable) — RPCN was previously entirely unconfigurable on Android (no JNI, no UI).
- **Netplay (RPCN) settings screen** — enable + connection status / test, account create → email token → sign-in, and server pick/add/remove (default `np.rpcs3.net`).
- **Per-game online-server switch** ("easy button" DNS host-switch) in the per-game config screen, with a bundled + remote community server registry.
- Confirmed working end-to-end on-device (account login + per-game community-server switch).
</details>

<details>
<summary><b>Updater (arch-aware core delivery)</b></summary>

- **Auto-select the best core arch variant per device** when fetching the core `.so`.
- Updater fixes: don't re-offer an already-installed version; no `-null` arch in the version string; pick the newest release by timestamp (not list order); accept pre-releases; don't nag to "update" over a side-loaded custom core, but still notify when a genuinely newer core build exists.
- App versioning by calendar date + commit time (**CalVer**), consistent with the core.
</details>

<details>
<summary><b>Packaging &amp; core/device integration</b></summary>

- **Renamed to `net.rpcsx.clanker`** (label "RPCSX-Clanker") for side-by-side install; DocumentProvider authority derived from the applicationId (avoids `INSTALL_FAILED_CONFLICTING_PROVIDER`). Kotlin namespace stays `net.rpcsx`.
- Fork-owned update channels; ProGuard rules pinning the JNI entry points the native core calls by name.
- **Battery-saver toggle** (default ON), **sustained performance / CPU-affinity / low-power WFE** toggles (default OFF), **auto-limit compile threads** on low-RAM devices (device-scaled memory budget over JNI), **thermal-status listener** feeding a core frame cap, **ADPF scheduler hints** feed (default OFF).
- **Back button opens the in-game quick menu** instead of exiting (reliable on Android 13+).
- **Crash/exit reporting** — reports why the previous session died (OOM vs native crash).
- Bundled + extract overlay PS-button icons (fixes missing dialog glyphs).
</details>

## Tried but failed / reverted

<details>
<summary>Expand</summary>

- **Smooth-shaders (async shader interpreter) toggle** — added (briefly default-ON), defaulted OFF, then the toggle was **removed** because the async path freezes on the current backend; the core plumbing stays dormant until a non-blocking re-land.
- **Keystore-backed EncryptedSharedPreferences for RPCN credentials** — added then **reverted** in favour of a simpler fix: credentials now live in app-private internal storage (not MTP/USB/other-app readable) and are excluded from backup. They are not additionally encrypted-at-rest, which is acceptable for an app-private file.
- **Box-art / pseudo-3D cover tiles** — multiple cover-loading attempts (GameTDB by title id, multi-region fallback, pseudo-3D) were cut back to an icon-only grid because remote cover loading was unreliable on-device; code left dormant.
- **In-game rounded-corner/border overlay** — the in-game variant was reverted (it risked clipping the render surface); rounded corners/border moved to library tiles.
</details>

## Pending / TODO

<details>
<summary>Expand</summary>

- **RPCN UX** — a connection-status indicator and a clearer create-vs-login flow. (Credential storage is already off external/world-readable storage; see above.)
- **RPCN offline toggle** — make the global enable toggle authoritative over per-game online overrides and tear down a live session on disable.
- **Configurable patch values** — patches with `[Configurable Values]` need the core to expose the patch config schema plus UI.
- **Smooth-shaders re-land** once a non-blocking, boot-only preload is validated.
- **ADPF default tuning** — currently default-OFF/unproven; needs on-device A/B before any default-ON.
- **Box-art cover tiles** — revive once on-device remote cover loading is reliable.
</details>
