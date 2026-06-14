# SSH VPN Client Android - Worklog

## Working Rule

Before each implementation block this file is updated with the planned change.
After each block it is updated with the actual result, verification status, and any limits.

## Initial Context

- Repository is empty at start.
- Target: native Android app written in Kotlin.
- UI: Jetpack Compose.
- Architecture: MVVM with `data`, `domain`, `vpn`, and `ui` layers.
- Storage: Room-like structured persistence is planned; secrets must be stored outside plain entities.
- VPN transport: Android `VpnService` plus SSH tunnel abstractions.

## MVP Scope

1. Create Android/Kotlin project structure.
2. Add domain models for SSH configs, SSH private keys, and VPN state.
3. Implement repositories for configs and keys with separated secret storage.
4. Prevent deleting a private key while it is referenced by configurations.
5. Build Compose screens:
   - main VPN status screen;
   - configuration list;
   - add/edit configuration;
   - SSH key list;
   - add/edit SSH key.
6. Add VPN and SSH manager interfaces/classes.
7. Add README with local setup instructions.
8. Add shell scripts for building, installing, linting, and cleaning.

## Known Limits For This Pass

- SSH-over-TUN forwarding now has an in-project Kotlin forwarding engine, but still requires platform/device testing for production confidence.
- TCP and DNS are routed through SSH; arbitrary non-DNS UDP is still not proxied.
- UDP forwarding is still represented as an experimental configuration flag.
- Secrets must not be logged or stored in plain config/key records.

## Change Log

### 2026-06-15 - Before Block 48

Plan:

- Reduce battery drain and heating introduced by aggressive idle TCP cleanup.
- Replace per-session idle cleanup timers with a single low-frequency maintenance loop.
- Keep protection against `remote-read` saturation by closing idle keep-alive sessions only under session pressure.
- Rebuild debug/release and rerun lint/unit checks.

Result:

- Reduced cleanup wakeups:
  - removed per-session idle cleanup scheduling;
  - added one forwarder maintenance loop that runs every 10 seconds.
- Made idle cleanup adaptive:
  - FIN-finished sessions are still cleaned after 10 seconds;
  - idle keep-alive sessions are only closed when active TCP sessions exceed 96;
  - pressure cleanup targets 72 active sessions and only closes sessions idle for at least 35 seconds.
- This should reduce battery/radio churn from constantly closing and reopening normal browser keep-alive connections while keeping protection against `remote-read` saturation.

Verification:

- `./scripts/build-debug.sh`: success.
- `git diff --check`: success.
- `env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ./gradlew lintDebug testDebugUnitTest`: not run; sandbox escalation was rejected by automatic usage-limit review.
- `./scripts/build-release.sh`: not run; sandbox escalation was rejected by automatic usage-limit review.
- APK status:
  - debug: `build/app/outputs/apk/debug/app-debug.apk` - 23M, updated;
  - release: `build/app/outputs/apk/release/app-release.apk` - 3.7M, not rebuilt in this block.

### 2026-06-15 - Before Block 47

Plan:

- Diagnose the remaining `remote-read worker pool is saturated` log after client-FIN cleanup.
- Add idle session cleanup for silent browser/Google keep-alive TCP channels that never send FIN.
- Track TCP session activity on ACK, payload, remote reads, and writes so active live traffic remains open.
- Rebuild debug/release and rerun lint/unit checks.

Result:

- Diagnosed the new log:
  - there were still no stale client-finished cleanup diagnostics;
  - `remote-read` saturation therefore likely came from idle keep-alive TCP channels that stayed open without FIN.
- Added per-session idle cleanup:
  - each TCP proxy session tracks last activity using Android elapsed realtime;
  - activity is refreshed on SYN, ACK/window updates, client payload, client FIN, SSH channel connect, remote reads, and client writes;
  - idle non-closed sessions are reset and closed after 20 seconds of silence;
  - active live/streaming flows remain open because remote reads and ACKs refresh the activity timestamp.
- Added limited diagnostics:
  - `TUN TCP closed idle session after ...`;
  - remote-read saturation failure now includes `activeSessions=...` for follow-up debugging.

Verification:

- `./scripts/build-debug.sh`: success.
- `env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ./gradlew lintDebug testDebugUnitTest`: success.
- `./scripts/build-release.sh`: success.
- `env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home /Users/stansful/Library/Android/sdk/build-tools/37.0.0/apksigner verify --verbose build/app/outputs/apk/release/app-release.apk`: success, v2 signed, 1 signer.
- `git diff --check`: success.
- APK outputs:
  - debug: `build/app/outputs/apk/debug/app-debug.apk` - 23M;
  - release: `build/app/outputs/apk/release/app-release.apk` - 3.7M.

### 2026-06-15 - Before Block 46

Plan:

- Diagnose the new log where the forwarding layer reaches `remote-read worker pool is saturated`.
- Fix TCP session lifecycle so client-closed browser connections release SSH direct TCP reader threads.
- Preserve the split control/read executor design from Block 45.
- Rebuild debug/release and rerun lint/unit checks.

Result:

- Diagnosed the new log:
  - SSH transport and VPN interface were healthy;
  - the remaining stall came from `remote-read worker pool is saturated`;
  - this points to stale SSH direct TCP reader tasks staying alive after browser-side TCP FIN.
- Fixed TCP session lifecycle:
  - client FIN now marks the session as client-finished;
  - pending client writes are cleared after FIN;
  - the SSH channel output stream is closed to send JSch `channel.eof()` without disconnecting the channel;
  - if the remote side does not finish after client FIN, a scheduled cleanup closes that stale session after 10 seconds;
  - added limited diagnostics for stale client-finished session cleanup.
- Verified JSch source behavior locally: `Channel.getOutputStream().close()` sends `channel.eof()` rather than disconnecting the full channel.

Verification:

- `./scripts/build-debug.sh`: success.
- `env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ./gradlew lintDebug testDebugUnitTest`: success.
- `./scripts/build-release.sh`: success.
- `env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home /Users/stansful/Library/Android/sdk/build-tools/37.0.0/apksigner verify --verbose build/app/outputs/apk/release/app-release.apk`: success, v2 signed, 1 signer.
- `git diff --check`: success.
- APK outputs:
  - debug: `build/app/outputs/apk/debug/app-debug.apk` - 23M;
  - release: `build/app/outputs/apk/release/app-release.apk` - 3.7M.

### 2026-06-15 - Before Block 45

Plan:

- Diagnose the browsing hang reported after Google search results open one or two sites.
- Use the attached diagnostics to identify whether SSH, DNS, TCP forwarding, or worker scheduling stalls.
- Fix the Kotlin TUN forwarder so browser connection bursts do not block the TUN read loop.
- Keep the Twitch live-stream TCP-window fix intact.
- Rebuild debug/release and run lint/unit checks.

Result:

- Diagnosed the attached log:
  - SSH transport and Android VPN setup succeeded;
  - browser traffic opened multiple TCP channels through the Kotlin TUN forwarder;
  - the hang coincided with `Kotlin TUN forwarding worker pool is saturated` diagnostics.
- Reworked `KotlinTunForwarder` scheduling:
  - removed the shared worker pool with caller-thread fallback;
  - added a queued control pool for DNS, SSH channel connect, and client-write tasks;
  - added a separate remote-read pool for long-lived SSH direct TCP readers;
  - moved delayed FIN cleanup to a scheduled cleanup executor instead of sleeping inside forwarding workers;
  - if a pool is genuinely saturated, only the affected flow is reset/rejected instead of blocking the TUN read loop.
- Kept the existing Twitch live-stream TCP receive-window behavior unchanged.

Verification:

- `./scripts/build-debug.sh`: success.
- `env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ./gradlew lintDebug testDebugUnitTest`: success.
- `./scripts/build-release.sh`: success.
- `env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home /Users/stansful/Library/Android/sdk/build-tools/37.0.0/apksigner verify --verbose build/app/outputs/apk/release/app-release.apk`: success, v2 signed, 1 signer.
- `git diff --check`: success.
- APK outputs:
  - debug: `build/app/outputs/apk/debug/app-debug.apk` - 23M;
  - release: `build/app/outputs/apk/release/app-release.apk` - 3.7M.

### 2026-06-15 - Before Block 44

Plan:

- Investigate heavy battery usage and device heating from the VPN service.
- Reduce high-frequency diagnostics churn from TUN forwarding.
- Avoid persisting the full diagnostics JSON on every log append.
- Bound forwarding worker threads and reduce periodic monitor wakeups.
- Rebuild debug/release and verify release APK signature.

Progress:

- User clarified the affected workload is live / real-time Twitch streaming.
- Additional fixes needed:
  - reject unsupported non-DNS UDP explicitly instead of blackholing QUIC/UDP attempts;
  - respect client TCP receive windows to avoid overrunning Android's local TCP buffer during long high-throughput streams.

Result:

- Reduced diagnostics disk churn:
  - diagnostics are still visible immediately in memory;
  - SharedPreferences persistence is throttled to at most once every 5 seconds;
  - disconnect/error/clear still force a final persist.
- Reduced high-frequency TUN diagnostics:
  - per-connection TCP open/failure/write-failure logs are now limited to the first 5 events per connection run;
  - DNS failure logs are limited the same way;
  - suppression messages are logged once when detail logging is capped.
- Bounded forwarding worker threads:
  - replaced unbounded cached pool with capped `ThreadPoolExecutor`;
  - worker saturation is logged in a limited way instead of allowing unbounded thread growth.
- Added empty-read backoff in TUN and SSH channel read loops to prevent tight CPU loops if a stream returns `0`.
- Reduced service connection monitor wakeup interval from 5 seconds to 15 seconds. SSH keepalive still remains configured by the user config.
- Improved live / real-time stream behavior:
  - unsupported non-DNS UDP is now rejected with ICMP port unreachable instead of being silently blackholed, helping clients fall back from QUIC/UDP to TCP faster;
  - server-to-client TCP forwarding now respects the Android client's advertised receive window before sending more data into the TUN interface;
  - worker-pool saturation now runs the task on the caller thread instead of silently dropping forwarding work.

Verification:

- `./scripts/build-debug.sh`: success.
- `env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ./gradlew lintDebug testDebugUnitTest`: success.
- `./scripts/build-release.sh`: success.
- `apksigner verify --verbose build/app/outputs/apk/release/app-release.apk`: success, v2 signed, 1 signer.
- Final APK sizes:
  - debug: `build/app/outputs/apk/debug/app-debug.apk` - 23M;
  - release: `build/app/outputs/apk/release/app-release.apk` - 3.7M.

Follow-up:

- Needs real-device battery validation over at least 1-2 hours of the same selected-apps workload, because VPN radio usage still depends on traffic volume and network quality.

### 2026-06-08 - Before Block 43

Plan:

- Explain why Gradle currently has root `build/` and module `app/build/`.
- Reconfigure module build output so the `app` module writes under root `build/app/`.
- Update scripts and documentation from `app/build/...` to `build/app/...`.
- Remove the old generated `app/build/` directory after successful reconfiguration.
- Rebuild debug/release and verify release APK signature.
- Answer where the Tink keyset and Android Keystore master key are stored.

Result:

- Reconfigured Gradle module output:
  - root project keeps `build/`;
  - `:app` now writes to `build/app/`;
  - old generated `app/build/` was removed.
- Updated release script and current documentation paths from `app/build/...` to `build/app/...`.
- Removed obsolete `app/build/` ignore entry from `.gitignore`.
- Confirmed only one build directory remains in the workspace: `./build`.
- Tink storage answer:
  - encrypted secret values: app private SharedPreferences file `ssh_vpn_tink_secrets.xml`;
  - Tink keyset: app private SharedPreferences file `ssh_vpn_tink_keyset.xml`, entry `ssh_vpn_secret_keyset`;
  - master key: Android Keystore alias `ssh_vpn_secret_master_key`.

Verification:

- `./scripts/build-debug.sh`: success.
- `./scripts/build-release.sh`: success.
- `apksigner verify --verbose build/app/outputs/apk/release/app-release.apk`: success, v2 signed, 1 signer.
- `find . -maxdepth 3 -type d -name build -print`: only `./build`.
- `git diff --check`: clean.

### 2026-06-08 - Before Block 42

Plan:

- Refresh root README so it matches the current app name, setup, build scripts, release signing, UI features, split tunneling, diagnostics, SSH terminal, secret storage, and network limits.
- Add missing `README_SA.md` for a system analyst:
  - explain the app/server/third-party-site traffic flow;
  - describe VPN modes, connection lifecycle, storage, diagnostics, and limitations;
  - document operational checks and artifacts.
- Keep documentation changes only; no Kotlin/code behavior changes.

Result:

- Rewrote root `README.md` for the current `shadow-ssh` app state:
  - current features;
  - network flow;
  - split tunneling;
  - diagnostics;
  - SSH terminal;
  - themes including `Custom`;
  - Tink secret storage;
  - Quick Settings tile;
  - release signing and R8/resource shrinking.
- Added root-level `README_SA.md` for a system analyst:
  - actors;
  - traffic flow diagram;
  - connection state diagram;
  - main scenarios;
  - data/storage model;
  - operational checks and limitations.

Verification:

- `git diff --check`: clean.
- Documentation-only change; Gradle build was not rerun.

### 2026-06-08 - Before Block 41

Plan:

- Fix terminal command submission after device logs showed `NetworkOnMainThreadException`.
- Move all terminal writes off the Compose/UI thread.
- Avoid terminal close/write failures bringing down the main VPN connection where possible.
- Keep diagnostics focused on terminal lifecycle/failures without logging command text.
- Rebuild debug/release and rerun lint/unit tests/signature checks.

Progress:

- Confirmed terminal command submission called `SshTerminalSession.sendLine()` from the UI callback.
- Moved command writes to `Dispatchers.IO`.
- Moved terminal channel close off the UI thread and removed direct stream close/flush from terminal cleanup.

Result:

- Terminal command submission no longer performs SSH writes on the Compose/UI thread.
- Terminal writes are serialized by `SshTerminalSession.sendLine()` and executed from `Dispatchers.IO`.
- Terminal cleanup now disconnects only the shell channel, without manually closing JSch streams or flushing from UI callbacks.
- Terminal write failures are reported to the terminal panel and diagnostics as lifecycle errors without logging command text.

Verification:

- `env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ./gradlew lintDebug testDebugUnitTest assembleDebug`: success.
- `./scripts/build-release.sh`: success.
- `apksigner verify --verbose app/build/outputs/apk/release/app-release.apk`: success, v2 signed, 1 signer.
- Final APK sizes:
  - debug: `app/build/outputs/apk/debug/app-debug.apk` - 23M;
  - release: `app/build/outputs/apk/release/app-release.apk` - 3.7M.

### 2026-06-08 - Before Block 40

Plan:

- Run a technical-debt pass before feature work:
  - remove remaining compile/lint warnings where practical;
  - refactor only where it reduces feature complexity or UI coupling.
- Add custom UI theme support:
  - extend settings model with `CUSTOM`;
  - persist configurable RGB/HEX colors;
  - add theme picker controls and a dedicated color selection UI;
  - default custom colors to the light theme palette.
- Add an SSH terminal panel on the main screen:
  - available during active SSH connection;
  - persistent shell channel for current connection;
  - expandable UI similar to connection diagnostics;
  - text input opens soft keyboard and sends commands to the server.
- Tune connection diagnostics:
  - add terminal lifecycle logs;
  - keep useful VPN/SSH logs and avoid secret output.
- Optimize release APK size:
  - enable minification/resource shrinking;
  - add keep rules required by reflection-heavy libraries;
  - verify signed release APK.
- Repeat technical-debt/warnings/refactor/logging pass after implementation.
- Rebuild debug/release and rerun tests/lint/signature checks.

Progress:

- Starting with the settings/domain/theme changes so the custom theme has a stable model and persistent storage before UI controls are added.
- Added the custom-theme model, persistent RGB storage, Material color-scheme mapping, system-bar contrast handling, and a dedicated Compose RGB editor.
- Next implementation block: SSH terminal lifecycle through the active SSH session, with ViewModel-owned UI state.

Result:

- Added `Custom` theme mode:
  - persisted custom RGB colors in app settings;
  - default custom colors match the light palette;
  - system bars choose icon contrast from the custom background;
  - settings sheet now shows an RGB editor only when `Custom` is selected.
- Added an expandable SSH terminal panel:
  - opens a shell channel on the active SSH session;
  - keeps command input/output in ViewModel state;
  - closes automatically on VPN disconnect;
  - logs only terminal lifecycle messages, not terminal commands or remote output.
- Refactored new UI into separate `CustomThemeControls.kt` and `TerminalPanel.kt`.
- Reduced release APK size by enabling R8 minification and resource shrinking with required keep rules.
- Cleaned technical debt found during the pass:
  - replaced remaining enum `values()` usage in touched theme selectors with `entries`;
  - switched password auth to byte-array `setPassword`;
  - made diagnostic timestamp formatting thread-safe;
  - marked the active terminal session reference volatile for reader-thread callbacks.

Verification:

- `env JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home ./gradlew lintDebug testDebugUnitTest assembleDebug`: success.
- `./scripts/build-release.sh`: success.
- `apksigner verify --verbose app/build/outputs/apk/release/app-release.apk`: success, v2 signed, 1 signer.
- `git diff --check`: clean.
- Final APK sizes:
  - debug: `app/build/outputs/apk/debug/app-debug.apk` - 23M;
  - release: `app/build/outputs/apk/release/app-release.apk` - 3.7M.

Limits:

- SSH terminal behavior is compile/build verified; it still needs device testing against a real server because interactive PTY behavior depends on server shell defaults.

### 2026-06-08 - Before Block 39

Plan:

- Add a copy button to the right side of the GitHub row in Settings.
- Keep tapping the row itself as external repository navigation.
- Copy the full repository URL to clipboard without changing stored settings.
- Rebuild debug/release and rerun checks.

Result:

- Added a copy icon button on the right side of the Settings GitHub row.
- Kept row tap behavior unchanged: tapping the row opens the GitHub repository externally.
- The copy button writes the full repository URL to clipboard.
- Migrated both GitHub copy and diagnostics copy from deprecated `LocalClipboardManager` to Compose `LocalClipboard`.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `./scripts/build-release.sh`: success.
- Verified release APK signature with Android SDK `apksigner` and Android Studio JBR:
  - v2 signature: true;
  - signers: 1.
- Verified `git diff --check`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- Updated signed release APK at `app/build/outputs/apk/release/app-release.apk`.

### 2026-06-08 - Before Block 38

Plan:

- Replace the active `EncryptedSharedPreferences` secret backend with a Tink-backed backend.
- Store encrypted secret values in ordinary private `SharedPreferences` as Base64 ciphertext.
- Use Tink AEAD with associated data bound to the secret id.
- Keep Android Keystore-backed Tink keyset storage for the encryption keyset.
- Add an idempotent legacy migration from the previous `EncryptedSharedPreferences` file.
- Ensure normal app startup does not instantiate deprecated `EncryptedSharedPreferences` after migration is complete.
- Keep old secret values untouched if migration cannot read them, and avoid logging secret contents.
- Update README and dependency list.
- Rebuild debug/release and rerun tests/lint.

Result:

- Replaced active `EncryptedSharedPreferences` usage with `TinkSecretStorage`.
- Added `com.google.crypto.tink:tink-android:1.21.0`.
- New secret storage behavior:
  - Tink AEAD encrypts every secret value;
  - associated data is bound to the secret id;
  - ciphertext is stored as Base64 in ordinary private `SharedPreferences`;
  - Tink keyset is stored through `AndroidKeysetManager` with Android Keystore master key URI.
- Added idempotent legacy migration:
  - if the old `ssh_vpn_secrets.xml` file does not exist, migration is marked complete without touching deprecated APIs;
  - if the old file exists, legacy values are read through `EncryptedSharedPreferences`, encrypted into the new Tink store, and removed from the legacy store;
  - migration failure is recorded in private migration prefs and does not delete old values;
  - lazy fallback can still read a single legacy secret when migration was not completed.
- Removed the old `EncryptedPreferencesSecretStorage` class from the active code path.
- Kept `androidx.security:security-crypto` only for legacy migration compatibility.
- Updated `AppContainer` to inject `TinkSecretStorage`.
- Updated README secret-storage description.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `./scripts/build-release.sh`: success.
- Verified release APK signature with Android SDK `apksigner` and Android Studio JBR:
  - v2 signature: true;
  - signers: 1.
- Verified `git diff --check`: success.
- Deprecated compile warnings for `EncryptedSharedPreferences` / `MasterKey` are gone; remaining warning is unrelated `LocalClipboardManager`.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- Updated signed release APK at `app/build/outputs/apk/release/app-release.apk`.

### 2026-06-08 - Before Block 37

Plan:

- Update Gradle wrapper from `9.5.0` to current stable `9.5.1`.
- Update Android Gradle Plugin from `8.13.2` to latest stable `9.2.1`.
- Migrate Compose compiler setup to the Kotlin 2.x Compose Compiler Gradle plugin.
- Update Kotlin/KSP to the newest compatible stable pair, validating compatibility by build.
- Update AndroidX, Compose BOM, Room, Security Crypto, JSch, BouncyCastle, Coroutines, and Android test dependencies to current stable releases from Google Maven/Maven Central metadata.
- Update compile/target SDK if the installed Android SDK supports it.
- Rebuild debug/release and rerun tests/lint.

Result:

- Updated Gradle wrapper from `9.5.0` to `9.5.1`.
- Ran the Gradle `wrapper` task with Android Studio JBR so wrapper metadata matches `9.5.1`.
- Updated Android Gradle Plugin from `8.13.2` to stable `9.2.1`.
- Migrated the app module to AGP 9 built-in Kotlin:
  - removed `org.jetbrains.kotlin.android`;
  - removed legacy `android.kotlinOptions`;
  - kept Java/Kotlin JVM target aligned through `compileOptions`;
  - added `org.jetbrains.kotlin.plugin.compose` `2.4.0`.
- Updated KSP from `1.9.24-1.0.20` to `2.3.9`.
- Updated SDK values:
  - `compileSdk`: `35` -> `37`;
  - `targetSdk`: `35` -> `36`;
  - `minSdk`: unchanged at `26`.
- Updated dependencies:
  - Compose BOM `2024.06.00` -> `2026.05.01`;
  - Activity Compose `1.9.1` -> `1.13.0`;
  - Core KTX `1.13.1` -> `1.19.0`;
  - Lifecycle `2.8.4` -> `2.10.0`;
  - Navigation Compose `2.7.7` -> `2.9.8`;
  - Room `2.6.1` -> `2.8.4`;
  - Security Crypto `1.1.0-alpha06` -> `1.1.0`;
  - mwiede JSch `0.2.21` -> `2.28.2`;
  - BouncyCastle `1.79` -> `1.84`;
  - Coroutines Android `1.8.1` -> `1.11.0`;
  - AndroidX Test JUnit `1.2.1` -> `1.3.0`;
  - Espresso Core `3.6.1` -> `3.7.0`.
- Updated README and wrapper helper scripts to say API 37 / Gradle 9.x / wrapper 9.5.1.
- Gradle installed missing local SDK components during verification:
  - Android SDK Platform 36;
  - Android SDK Platform 37;
  - Android SDK Build Tools 36.0.0.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `./scripts/build-release.sh`: success.
- Verified release APK signature with Android SDK `apksigner` and Android Studio JBR:
  - v2 signature: true;
  - signers: 1.
- Verified `git diff --check`: success; Git still prints a non-failing CRLF warning for untouched `gradlew.bat`.
- Remaining compile warnings are from deprecated APIs exposed by updated libraries:
  - `EncryptedSharedPreferences` / `MasterKey`;
  - `LocalClipboardManager`;
  - JSch `setPassword`.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- Updated signed release APK at `app/build/outputs/apk/release/app-release.apk`.

### 2026-06-08 - Before Block 36

Plan:

- Remove the vendored `hevtunnel-1.0.1-kotlin19.aar` dependency.
- Replace the reflective `hev-socks5-tunnel` launch path with an in-project Kotlin TUN forwarding engine.
- Terminate app TCP connections from the Android VPN interface locally and proxy them through SSH `direct-tcpip` channels.
- Handle DNS UDP port 53 through DNS-over-TCP over SSH.
- Keep arbitrary non-DNS UDP unsupported and logged, matching the previous product limitation.
- Update README to remove `hev-socks5-tunnel` setup notes.
- Rebuild debug/release and rerun checks.

Result:

- Removed Gradle dependency on `app/libs/hevtunnel-1.0.1-kotlin19.aar`.
- Deleted vendored `app/libs/hevtunnel-1.0.1-kotlin19.aar`.
- Deleted the old unused `SshSocks5Server` bridge.
- Replaced the reflective `TProxyService`/`hev-socks5-tunnel` manager path with `KotlinTunForwarder`.
- `KotlinTunForwarder` now:
  - reads IPv4 packets directly from the Android VPN interface;
  - terminates app-side TCP sessions locally;
  - opens SSH `direct-tcpip` channels to destination IP/port;
  - relays TCP payloads between Android apps and the SSH channel;
  - answers DNS UDP/53 by sending DNS-over-TCP requests through SSH;
  - logs and drops arbitrary non-DNS UDP.
- Updated reconnect recoverability wording away from the old SOCKS bridge message.
- Updated README to document the in-project Kotlin forwarding layer.
- Important limit: this is a new custom userspace forwarding engine and still needs physical-device testing with real browser/app traffic; it is not yet as battle-tested as a mature native `tun2socks` implementation.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `./scripts/build-release.sh`: success.
- Verified release APK signature with Android SDK `apksigner` and Android Studio JBR:
  - v2 signature: true;
  - signers: 1.
- Verified `git diff --check`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- Updated signed release APK at `app/build/outputs/apk/release/app-release.apk`.

### 2026-06-07 - Before Block 35

Plan:

- Add a GitHub repository link to the Settings sheet.
- Add a GitHub brand mark drawable and use it next to the link.
- Open the provided repository URL in the external browser when tapped.
- Keep the row styling aligned with the current light/dark app themes.
- Rebuild debug/release and rerun checks.

Result:

- Added a GitHub row to the Settings sheet.
- The row uses a GitHub mark drawable and opens:
  `https://github.com/stansful/ssh-vpn-client-kotlin/tree/master`
- Kept the row theme-aware:
  - light theme uses the existing soft surface variant;
  - dark theme uses the existing dark surface variant;
  - press animation uses the same scale interaction pattern as other settings tiles.
- Added `app/src/main/res/drawable/ic_github_mark.xml`.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `./scripts/build-release.sh`: success.
- Verified release APK signature with Android SDK `apksigner` and Android Studio JBR:
  - v2 signature: true;
  - signers: 1.
- Verified scoped `git diff --check` for files changed in this block: success.
- Full `git diff --check` still reports unrelated trailing whitespace in `README.md`; this file was not changed by this block.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- Updated signed release APK at `app/build/outputs/apk/release/app-release.apk`.

### 2026-06-07 - Before Block 34

Plan:

- Update the main-screen `Check tunnel` button visual states.
- Make the button neutral gray after connection and before any check result.
- Set the button to green with a check icon when the tunnel check succeeds.
- Set the button to red with a close icon when the tunnel check fails.
- Reset the result state when VPN leaves `Connected`.
- Keep colors aligned with the existing app theme for both light and dark themes.
- Rebuild debug/release and rerun checks.

Result:

- Added `TunnelCheckResult` state to the main UI state.
- The `Check tunnel` button now:
  - starts neutral gray after a successful VPN connection;
  - resets to neutral while a check is running;
  - turns green with a check icon after a successful tunnel check;
  - turns red with a close icon after a failed tunnel check;
  - resets to neutral when VPN leaves `Connected`.
- Kept colors aligned with existing app semantics:
  - neutral uses `surfaceVariant/onSurfaceVariant`;
  - success uses the same green family as connected status;
  - failure uses `colorScheme.error`.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `./scripts/build-release.sh`: success.
- Verified release APK signature with `apksigner verify --verbose`:
  - v2 signature: true;
  - signers: 1.
- Verified `git diff --check`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- Updated signed release APK at `app/build/outputs/apk/release/app-release.apk`.

### 2026-06-07 - Before Block 33

Plan:

- Add a user-facing tunnel health check button on the main screen.
- Implement the check as an SSH `direct-tcpip` TCP probe to `youtube.com:443` through the active SSH session.
- Avoid ICMP `ping` because the current tunnel supports TCP/DNS over SSH and does not proxy ICMP.
- Log the check start/result/failure into connection diagnostics.
- Disable the button unless the VPN is connected, and show a busy state while the check runs.
- Rebuild debug/release and rerun checks.

Result:

- Added a main-screen `Check tunnel` button visible only while VPN status is `Connected`.
- The button runs an SSH `direct-tcpip` TCP probe to `youtube.com:443` through the active SSH session.
- The check writes diagnostics:
  - start line with target host/port;
  - success line with elapsed milliseconds;
  - failure line with the error message.
- The button has a busy state: `Checking youtube.com...`.
- Avoided ICMP `ping` because current tunnel semantics are TCP/DNS over SSH, not ICMP forwarding.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `./scripts/build-release.sh`: success.
- Verified release APK signature with `apksigner verify --verbose`:
  - v2 signature: true;
  - signers: 1.
- Verified `git diff --check`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- Updated signed release APK at `app/build/outputs/apk/release/app-release.apk`.

### 2026-06-07 - Before Block 32

Plan:

- Add Android Quick Settings tile support for toggling the VPN from the notification shade.
- Register a `TileService` in the manifest with `android.permission.BIND_QUICK_SETTINGS_TILE`.
- Reuse existing connect/disconnect service intents instead of duplicating VPN connection logic.
- Keep system limitations explicit:
  - if VPN permission is not granted, open the main app screen to request it;
  - if no selected config or selected-apps mode has no selected apps, open the main app screen so the existing UI can show/handle the issue.
- Add tile label/subtitle/state updates for disconnected, connecting, connected, reconnecting, and error states.
- Rebuild debug/release and rerun checks.

Result:

- Added `SshVpnTileService` as Android Quick Settings tile support.
- Registered the tile service in `AndroidManifest.xml` with:
  - `android.permission.BIND_QUICK_SETTINGS_TILE`;
  - `android.service.quicksettings.action.QS_TILE`;
  - `android.service.quicksettings.TOGGLEABLE_TILE`.
- Tile behavior:
  - connected/connecting/reconnecting/disconnecting -> sends Disconnect;
  - disconnected/error -> attempts Connect;
  - missing VPN permission, missing selected config, or selected-apps mode with no selected apps -> opens the main app screen.
- Tile state updates from the existing `VpnConnectionRepository` flow:
  - active for connected/connecting/reconnecting;
  - inactive for disconnected/error;
  - unavailable while disconnecting.
- Added tile label/subtitle strings.
- Updated README with Quick Settings tile usage and Android placement limitation.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `./scripts/build-release.sh`: success.
- Verified release APK signature with `apksigner verify --verbose`:
  - v2 signature: true;
  - signers: 1.
- Verified `git diff --check`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- Updated signed release APK at `app/build/outputs/apk/release/app-release.apk`.

### 2026-06-06 - Before Block 31

Plan:

- Diagnose the attached SSH auth log.
- Add safe diagnostics for private-key authentication failures:
  - log the selected SSH key name;
  - log a fingerprint of the public key derived from the selected private key;
  - do not log private key contents or passphrases.
- Add a user-facing diagnostic hint when the server rejects the private key, so the next debug step is clear.
- Rebuild and rerun checks.

Result:

- Diagnosed the attached SSH log:
  - TCP socket to `77.239.103.155:22` connects successfully;
  - SSH KEX and server host key verification succeed;
  - Ed25519 support is working;
  - failure happens at public-key auth: `ssh-ed25519 preauth failure` followed by `Auth fail`.
- Added safe key diagnostics:
  - service logs the selected SSH key name;
  - SSH manager logs the selected private key's derived public fingerprint in OpenSSH `SHA256:...` form plus legacy md5 from JSch;
  - private key bytes and passphrase are not logged.
- Added an auth-failure diagnostic hint when the server rejects the key:
  - verify username, host, and that the derived public key is present in server `authorized_keys`.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `./scripts/build-release.sh`: success.
- Verified release APK signature with `apksigner verify --verbose`:
  - v2 signature: true;
  - signers: 1.
- Verified `git diff --check`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- Updated signed release APK at `app/build/outputs/apk/release/app-release.apk`.

### 2026-06-06 - Before Block 30

Plan:

- Fix release APK install failure caused by the unsigned release artifact.
- Change `scripts/build-release.sh` so the default no-production-key path creates and uses a local ignored keystore.
- Keep production signing via explicit environment variables.
- Add `.local/` to `.gitignore` so generated local signing material is never committed.
- Update README to clarify that the default release script output is installable but local-signed.
- Build signed release APK and verify it.

Result:

- Confirmed the install failure was caused by trying to install an unsigned release artifact.
- Updated `.gitignore` to ignore `.local/`.
- Updated `scripts/build-release.sh`:
  - production signing still uses explicit `SSH_VPN_RELEASE_*` variables;
  - when production variables are not set, the script creates a local signing config under `.local/signing/`;
  - generated local signing secrets stay outside git;
  - local PKCS12 signing uses one password for store and key so Gradle can read the key;
  - older incompatible local signing configs are rotated into `.local/signing/backup-*`.
- Updated `README.md` so the default release script output is documented as an installable local-signed APK.
- Built signed release APK:
  - `app/build/outputs/apk/release/app-release.apk`.
- Verified APK signature with `apksigner verify --verbose`:
  - v2 signature: true;
  - signers: 1.
- Verified `./scripts/build-release.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `git diff --check`: success.

### 2026-06-06 - Before Block 29

Plan:

- Add a release build script.
- Support two release modes:
  - unsigned release APK when signing credentials are not provided;
  - signed release APK when keystore credentials are provided via environment variables.
- Add Gradle release signing config that reads credentials from environment variables without storing secrets in the repository.
- Update README with release build usage and output paths.
- Build the release APK and rerun focused checks.

Result:

- Added `scripts/build-release.sh`.
- The release script now:
  - resolves Gradle the same way as existing scripts;
  - builds `:app:assembleRelease`;
  - detects whether signing credentials are complete;
  - prints the signed or unsigned release APK path according to the active mode.
- Added optional release signing config to `app/build.gradle.kts`.
- Release signing credentials are read only from environment variables or Gradle properties:
  - `SSH_VPN_RELEASE_STORE_FILE`;
  - `SSH_VPN_RELEASE_STORE_PASSWORD`;
  - `SSH_VPN_RELEASE_KEY_ALIAS`;
  - `SSH_VPN_RELEASE_KEY_PASSWORD`.
- Updated `README.md` with release build commands, output paths, and signing variable examples.
- Built unsigned release APK because signing credentials were not provided:
  - `app/build/outputs/apk/release/app-release-unsigned.apk`.
- Verified `./scripts/build-release.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `git diff --check`: success.

### 2026-06-06 - Before Block 28

Plan:

- Replace the launcher icon with an Android VectorDrawable adapted from `/Users/stansful/Downloads/ssh_vpn_black_orange.svg`.
- Preserve the supplied icon composition:
  - rounded black background;
  - orange outlined shield;
  - orange SSH key;
  - VPN tunnel arcs;
  - orange SSH label pill.
- Keep only Android-required adaptations:
  - convert unsupported SVG gradients to close solid colors;
  - replace unsupported drop shadow filter with a subtle vector shadow shape;
  - replace unsupported SVG text with vector stroke paths reading `SSH`.
- Rebuild and rerun checks.

Result:

- Replaced `app/src/main/res/drawable/ic_launcher_foreground.xml` with an Android VectorDrawable based on `/Users/stansful/Downloads/ssh_vpn_black_orange.svg`.
- Preserved the supplied composition:
  - rounded black background;
  - orange outlined shield;
  - orange SSH key;
  - orange and white VPN arcs;
  - orange `SSH` label pill.
- Kept only Android VectorDrawable compatibility adaptations:
  - flattened SVG gradients to close black/orange solid colors;
  - approximated the SVG drop shadow with a translucent vector shadow path;
  - converted the unsupported SVG text into black vector stroke paths reading `SSH`.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `git diff --check`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 27

Plan:

- Fix dark-theme backgrounds that still appear white/gray by making the shared app background and glass panels use opaque black/orange tones in dark mode.
- Remove bright white press/ripple animation from `Configurations`, `SSH Keys`, and app-picker rows.
- Fix launcher icon text from the current shape that reads like `EEH` to a clearer lowercase `ssh`.
- Rebuild and rerun checks.

Result:

- Fixed the shared Compose screen background in dark mode:
  - `Scaffold` and `TopAppBar` now use opaque theme colors instead of transparent containers;
  - the dark app background uses a black/orange vertical gradient;
  - the content background fills the whole screen before padding is applied.
- Updated system status/navigation bar colors from the persisted theme mode so the native light window theme no longer leaves white/gray system areas in dark mode.
- Removed bright white Material ripple from the main `Configurations` and `SSH Keys` buttons:
  - switched navigation cards to a custom clickable modifier with `indication = null`;
  - kept the scale press animation.
- Removed bright white Material ripple from app picker rows:
  - switched rows to custom click handling with `indication = null`;
  - kept a subtle scale press animation;
  - made the row checkbox passive so row selection remains the single interaction path.
- Made dark glass panels less transparent so the dark theme reads as black/orange rather than gray.
- Replaced the icon pill lettering with clearer lowercase `ssh` stroke paths.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `git diff --check`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 26

Plan:

- Replace the launcher icon with an adapted Android VectorDrawable based on `/Users/stansful/Downloads/ssh_vpn_app_icon.svg`.
  - Preserve the source concept: rounded icon, shield, SSH key, tunnel lines, and SSH label.
  - Adapt unsupported SVG gradients/filter/text into Android-compatible vector paths and black/orange styling.
- Rename the application label to lowercase `shadow-ssh`.
- Rework only the dark theme into a black/orange palette.
- Reduce bright white selected/pressed transitions in dark mode by using darker orange selected containers and softer surface variants.
- Keep the light theme unchanged.
- Rebuild and rerun checks.

Result:

- Replaced launcher icon with an adapted Android VectorDrawable based on the provided SVG.
  - Preserved the source concept: rounded icon, shield, key, tunnel arcs, SSH pill.
  - Converted unsupported SVG features such as gradients, filters, and text into Android vector-compatible paths.
  - Adapted the visual style to black/orange.
- Renamed the application label to lowercase `shadow-ssh`.
- Updated foreground service notification title to `shadow-ssh is active`.
- Updated native Android accent color to orange.
- Reworked only the dark color scheme:
  - black background and dark surfaces;
  - orange primary/secondary accents;
  - dark orange selected containers;
  - softer brown/orange outlines and surface variants to avoid bright white transition flashes.
- Left the light theme unchanged.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `git diff --check`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 25

Plan:

- Remove the checkbox icon from the app picker selected-count summary row.
- Keep the `Selected` label and selected count.
- Rebuild and rerun checks.

Result:

- Removed the checkbox icon from the app picker selected-count summary row.
- The summary row now shows only `Selected` on the left and the selected count on the right.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `git diff --check`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 24

Plan:

- Add split tunneling settings:
  - `vpnMode=PROXY|SELECTED_APPS`, default `PROXY`;
  - persisted selected application package names.
- Add a fullscreen selected-apps picker with search, checkboxes, user/system apps, and saved selection state.
- Apply split tunneling when establishing the Android VPN:
  - `PROXY`: keep current full-device VPN behavior;
  - `SELECTED_APPS`: call `VpnService.Builder.addAllowedApplication(...)` for each selected package.
- If `SELECTED_APPS` has no selected apps, block Connect and show a modal saying `Нет выбранных приложений`.
- If split-tunnel settings change while VPN is active, automatically reconnect.
- Remove the duplicated large status text on the main screen and keep only the colored status badge.
- Rebuild and rerun checks.

Result:

- Added split tunneling settings:
  - `VpnMode.PROXY` and `VpnMode.SELECTED_APPS`;
  - persisted selected application package names;
  - default remains `PROXY`.
- Added full-screen app picker:
  - lists installed apps through `PackageManager`, including system apps;
  - has search by app label and package name;
  - uses checkboxes and saves selection on Back/Done.
- Added `QUERY_ALL_PACKAGES` permission with lint suppression so Android 11+ can show the full installed-app list.
- Updated settings sheet:
  - added VPN mode selector;
  - added `Select apps` button and selected count for `SELECTED_APPS`.
- Updated VPN setup:
  - `PROXY` keeps current full-device routing;
  - `SELECTED_APPS` applies `VpnService.Builder.addAllowedApplication(...)`;
  - empty or fully invalid selected-app lists fail with `Нет выбранных приложений`.
- Added modal validation on Connect for `SELECTED_APPS` with no apps selected.
- Added automatic reconnect when split-tunnel mode or the selected-app set changes while VPN is active.
  - Theme/log setting changes do not trigger reconnect.
  - Selected-app changes in `PROXY` mode do not trigger reconnect because they do not affect active routing.
- Removed the duplicated large status text from the main screen and kept the colored status badge.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `git diff --check`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 23

Plan:

- Fix diagnostics disappearing from the main screen after app restart when the persisted logs toggle is enabled.
- Keep `showLogsOnMain` as an app setting, but persist the diagnostics list separately.
- Initialize VPN connection state with the last saved diagnostics on app startup.
- Preserve existing reset behavior: a new user-started `Connect` clears diagnostics.
- Rebuild and rerun checks.

Result:

- Updated `InMemoryVpnConnectionRepository` to persist diagnostics in `SharedPreferences`.
- On app startup, `VpnConnectionState` is initialized with the last saved diagnostics.
- `appendDiagnostic(...)` now saves the updated list after every new diagnostic line.
- `setConnecting(...)` and `clearDiagnostics()` still clear diagnostics and persist the empty list, so a new user-started Connect resets logs as before.
- Wired `InMemoryVpnConnectionRepository(appContext)` in `AppContainer`.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `git diff --check`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 22

Plan:

- Fix dark theme text/icons that render black inside custom glass surfaces.
- Set explicit Material content colors for glass panels, navigation buttons, settings tiles, and settings sheet.
- Remove transparency from the settings bottom sheet container.
- Rebuild and rerun checks.

Result:

- Fixed dark theme contrast by setting explicit `contentColor`:
  - glass panels now use `MaterialTheme.colorScheme.onSurface`;
  - glass navigation buttons now use `onSurface`;
  - theme selector tiles now use `onSurface`;
  - settings sheet now uses `onSurface`.
- Forced the main connect/disconnect button content color to white so icon/text stay visible.
- Removed settings sheet container transparency by using opaque `MaterialTheme.colorScheme.surface`.
- Kept the background scrim, but the settings menu surface itself is no longer translucent.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `git diff --check`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 21

Plan:

- Add persisted application settings backed by SharedPreferences:
  - show connection diagnostics on the main screen, default `false`;
  - app theme mode: system, light, dark, default `system`.
- Wire settings into `AppContainer`, `MainActivity`, and `MainViewModel`.
- Update `SshVpnTheme` with explicit black dark theme, white light theme, and system mode resolution.
- Redesign the main screen in a restrained iOS liquid-glass direction:
  - translucent glass-like panels with compact 8dp radius;
  - settings button in the top bar;
  - settings sheet with logs toggle and theme selector;
  - diagnostics spoiler appears only when the persisted logs setting is enabled.
- Add Compose animations for screen transitions, status changes, diagnostics visibility, and button press feedback.
- Rebuild and rerun checks.

Result:

- Added persisted settings:
  - `AppSettings` / `AppThemeMode`;
  - `AppSettingsRepository`;
  - `SharedPreferencesAppSettingsRepository`;
  - defaults are `showLogsOnMain=false` and `themeMode=SYSTEM`.
- Wired settings into `AppContainer`, `MainActivity`, `MainViewModel`, and `AppViewModelFactory`.
- Updated theme handling:
  - system mode follows Android system settings;
  - light mode uses a white UI scheme;
  - dark mode uses a black UI scheme.
- Redesigned the main screen:
  - added settings button in the top bar;
  - added bottom settings sheet with logs toggle and theme selector;
  - hid diagnostics from the main page unless the persisted logs toggle is enabled;
  - kept diagnostics collapsed by default with copy button and scrollable expanded text.
- Added animations:
  - navigation transitions between screens;
  - status text/color changes;
  - diagnostics visibility;
  - button/tile press scaling.
- Updated common screen background and base button interactions.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 20

Plan:

- Treat the latest timeout as a client regression because committed v9 (`747e501`) worked with the same server and port.
- Compare current uncommitted SSH connect code against v9.
- Restore the v9 direct JSch TCP connect path for the initial SSH connection instead of forcing a custom protected/bound socket before any Android VPN interface exists.
- Preserve useful diagnostics, reconnect behavior, and previous UI/documentation changes where they do not affect initial TCP connect.
- Rebuild and rerun checks.

Result:

- Compared current uncommitted SSH connect path with committed v9 (`747e501`).
- Regression source was the post-v9 pre-connect socket setup:
  - current code created a socket, locally bound it, called `VpnService.protect(socket)`, then called `Network.bindSocket(socket)`, and only after that attempted TCP connect;
  - latest device logs confirmed both `protect()` and `Network.bindSocket(...)` succeeded, but TCP connect then timed out.
- Restored the v9 TCP ordering in `VpnProtectedSocketFactory`:
  - create socket;
  - call normal `socket.connect(...)`;
  - log the connected local/remote endpoints;
  - call `VpnService.protect(socket)` on the connected socket.
- Removed active-network `Network.bindSocket(...)` from the SSH connect path.
- Kept diagnostics, reconnect loop, run-aware JSch logging, and `ServerAliveCountMax`.
- Expected new connection diagnostics:
  - `SSH socket: opening TCP socket for ...`;
  - on success, `SSH socket: TCP connected ...`;
  - then `SSH socket: protect connected socket result=true`.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 19

Plan:

- Investigate the latest connection diagnostics where the protected SSH socket still times out before authentication.
- Bind the SSH TCP socket explicitly to Android's active validated network in addition to `VpnService.protect(socket)`.
- Add diagnostics for the active-network socket bind step.
- Map Android `ECONNABORTED` TCP connect failures as a recoverable connection failure instead of `Unknown connection error`.
- Rebuild and rerun checks.

Result:

- Latest attached diagnostics show:
  - active Android network is validated Wi-Fi and not VPN;
  - `VpnService.protect(socket)` returns `true`;
  - the SSH TCP connect to `77.239.103.135:22` still times out before authentication.
- Added active-network socket binding for SSH:
  - each connection/reconnect attempt captures `ConnectivityManager.activeNetwork`;
  - the SSH socket is created, locally bound, protected with `VpnService.protect(socket)`, then bound with `Network.bindSocket(socket)` before `connect(...)`;
  - diagnostics now show whether active-network binding is available and whether `Network.bindSocket(...)` succeeded.
- Updated SSH error mapping:
  - Android `ECONNABORTED` / `Software caused connection abort` during TCP connect is now reported as `Connection timeout`;
  - active-network bind failures are reported as `Could not bind SSH socket to active Android network`.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 18

Plan:

- Create root-level `README_SA.md` for a systems analyst.
- Describe the application purpose, actors, configuration data, connection lifecycle, SSH/VPN tunnel, DNS path, reconnect behavior, and known limitations.
- Keep the document non-code-focused and useful for requirements/support discussions.

Result:

- Added root-level `README_SA.md`.
- The document explains:
  - application purpose and actors;
  - stored configuration/key data;
  - Android VPN, TUN, SOCKS5, SSH, DNS, and external site traffic path;
  - connect/reconnect/disconnect lifecycle;
  - diagnostics meaning;
  - typical failure causes;
  - current limitations;
  - key implementation files.
- No code changes were required for this documentation block.

### 2026-06-06 - Before Block 17

Plan:

- Add detailed diagnostics for SSH TCP socket setup and Android network state.
- Log socket bind/protect/connect steps and timings.
- Log active Android network transports/capabilities before SSH connect.
- Keep reconnect behavior unchanged while gathering enough data to distinguish app routing issues from external port blocking.
- Rebuild and rerun checks.

Result:

- Added `ACCESS_NETWORK_STATE` permission for diagnostics.
- Added `NetworkDiagnostics` helper:
  - logs active Android network;
  - logs transports and selected capabilities;
  - logs link summary with interface name, DNS count, and route count.
- Added protected socket diagnostics in `VpnProtectedSocketFactory`:
  - target endpoint;
  - local bind endpoint;
  - `VpnService.protect(socket)` result;
  - connect duration and local/remote endpoints on success;
  - exact socket failure class/message and elapsed time on failure.
- Wired network diagnostics before each SSH connection attempt.
- No reconnect policy change in this block.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 16

Plan:

- Fix SSH TCP connect timeout risk caused by protecting the SSH socket only after `connect(...)`.
- Create a socket file descriptor with `bind(null)`, call `VpnService.protect(socket)`, then call `connect(...)`.
- Keep reconnect behavior unchanged.
- Rebuild and rerun checks.

Result:

- Updated `VpnProtectedSocketFactory` to use the correct socket setup order:
  - create `Socket`;
  - call `socket.bind(null)` to force Android/Java to allocate a file descriptor;
  - call `VpnService.protect(socket)` before `connect(...)`;
  - then connect to the SSH endpoint.
- This protects the SSH TCP connect from stale/current VPN routes while avoiding Android returning `false` for a socket without a file descriptor.
- Reconnect behavior remains unchanged.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 15

Plan:

- Fix initial timeout UX where the app can look stuck in `Connecting`.
- Switch to `Reconnecting` immediately after the first recoverable connection failure.
- Prevent stale/cancelled SSH attempts from appending diagnostics into the current session log.
- Rebuild and rerun checks.

Result:

- On recoverable initial SSH failures, the service now switches to `RECONNECTING` before logging the failure/retry delay.
- Added run-aware diagnostics callbacks in `SshVpnService`.
  - Logs from stale connection runs are ignored after a new Connect or Disconnect changes `connectionRunId`.
- Reworked JSch internal logging in `SshConnectionManager`.
  - JSch uses a static/global logger, so replacing it per connection let old connection attempts write into a newer session log.
  - The global logger is now installed once and routes messages through an `InheritableThreadLocal` callback for the current SSH connect/session thread.
- Fixed Kotlin inline/lambda storage by marking the thread-local logger parameter `noinline`.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 14

Plan:

- Fix regression where reconnect monitoring immediately tears down an otherwise successful connection.
- Treat `hev-socks5-tunnel` native start return as non-fatal because the upstream bridge can return after starting background work.
- Avoid using `Tun2SocksManager.isRunning` as a health signal unless the app itself called stop.
- Rebuild and rerun checks.

Result:

- Attached diagnostics showed that SSH authentication and VPN setup still succeeded.
- Regression source:
  - `TUN forwarding engine started` was immediately followed by `TUN forwarding engine exited`;
  - reconnect monitor treated `Tun2SocksManager.isRunning == false` as a failure;
  - the native `TProxyStartService(...)` bridge can return after starting background native work, so that return is not a reliable engine-stop signal.
- Removed the false `isRunning = false` transition from the native start thread `finally` block.
- Removed `Tun2SocksManager.isRunning` from the reconnect health monitor.
- Reconnect now monitors SSH session health; the TUN/SOCKS stack is still restarted on SSH reconnect.
- Updated `README.md` to describe SSH-session-based reconnect accurately.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 13

Plan:

- Remove the 80-line diagnostics cap; keep diagnostics until the next user-started Connect resets connection state.
- Add automatic reconnect when the active SSH/VPN connection is interrupted.
- Keep reconnecting until the user explicitly presses Disconnect.
- Ensure UI shows a disconnect action while connecting/reconnecting.
- Rebuild and rerun checks.

Result:

- Removed the diagnostics line cap in `InMemoryVpnConnectionRepository`.
  - `setConnecting(...)` still creates a fresh `VpnConnectionState`, so diagnostics reset on a new user-started Connect.
  - Reconnect attempts use `setReconnecting(...)`, which preserves diagnostics.
- Added `VpnConnectionStatus.RECONNECTING`.
- Updated the main screen state/buttons:
  - Connecting/Reconnecting/Connected show a Disconnect action;
  - Reconnecting is displayed as a distinct status.
- Added automatic reconnect loop in `SshVpnService`:
  - monitors SSH session and TUN forwarding health every 5 seconds;
  - reconnects after interruptions until the user presses Disconnect;
  - uses backoff from 2 seconds up to 30 seconds;
  - keeps the foreground service alive between reconnect attempts.
- Added run-id cancellation checks so stale connection attempts cannot establish VPN after Disconnect.
- Cancellation cleanup no longer stops the foreground notification; explicit Disconnect and service destroy still stop it.
- Added `Session.setServerAliveCountMax(3)` so JSch keepalive detects silent SSH transport drops.
- Updated `Tun2SocksManager` to mark `isRunning = false` if the native TUN engine exits unexpectedly.
- Updated `README.md` with reconnect and diagnostics behavior.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 12

Plan:

- Investigate disconnect-time diagnostics from attached logs.
- Suppress expected close exceptions from in-flight DNS/SOCKS tasks during disconnect.
- Make TUN/SOCKS/SSH shutdown order more deterministic and idempotent.
- Rebuild and rerun checks.

Result:

- Attached logs show successful connection and disconnect-time noise:
  - repeated `DNS over SSH failed ... Socket is closed`;
  - benign JSch shutdown log `Caught an exception, leaving main loop due to Socket closed`.
- Reordered `Tun2SocksManager.stop()`:
  - stop the local SOCKS bridge first so in-flight DNS/SOCKS workers immediately see shutdown state;
  - then stop the native `hev-socks5-tunnel` engine.
- Hardened `SshSocks5Server.stop()`:
  - snapshot synchronized active socket/channel sets before iteration;
  - suppress expected shutdown errors from DNS, UDP relay, and SOCKS client workers.
- Filtered the benign JSch `Socket closed` main-loop message from user diagnostics.
- Wrapped each disconnect cleanup step in `SshVpnService` so one close failure cannot crash disconnect.
- Added disconnect diagnostics:
  - `Stopping VPN connection`;
  - `VPN connection disconnected`;
  - short cleanup warning only if a close step genuinely fails.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 11

Plan:

- Fix `VpnService.protect(socket)` failure in the protected SSH socket factory.
- Keep SSH socket protection, but call it only after the socket has a real file descriptor.
- Rebuild and rerun checks.

Result:

- Fixed `VpnProtectedSocketFactory` to connect the SSH TCP socket first, then call `VpnService.protect(socket)` on the connected socket.
- This keeps SSH socket protection in place while avoiding Android returning `false` for an unconnected socket without a ready file descriptor.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 1

Plan:

- Inspect available local Android/Gradle tooling.
- Create project scaffolding files.
- Keep implementation Kotlin-native and Compose-based.

Result:

- Repository has no Android project files and no `.git` directory.
- Local machine in this execution context has no Java Runtime, no `gradle` command, and no Android SDK at the default macOS path.
- Build verification will be documented but cannot be executed in this pass unless the environment gets Java, Gradle, and Android SDK.

### 2026-06-06 - Before Block 2

Plan:

- Add Gradle Android project scaffolding.
- Add native Kotlin package structure.
- Add Android manifest, resources, and Compose entry point.
- Add core domain/data/vpn/ui implementation files.

Result:

- Added Gradle Android project scaffolding.
- Added native Kotlin package structure.
- Added Android manifest, resources, Compose entry point, and app theme.
- Added domain models, repository contracts, validators, and use cases.
- Added Room entities/DAOs and repositories.
- Added encrypted secret storage for passwords, private keys, and passphrases.
- Added `VpnService`, SSH manager, TUN manager, and tun2socks integration placeholder.
- Added Compose screens and ViewModels for main screen, configs, config edit, keys, and key edit.
- SSH keys and configs are separate entities; configs reference keys only by `privateKeyId`.
- Key deletion is blocked when the key is used by one or more configurations.
- Full packet forwarding is not implemented in this pass; `Tun2SocksManager` is an explicit integration point.

### 2026-06-06 - Before Block 3

Plan:

- Add README with local setup and known limitations.
- Add shell scripts for building, installing, testing, and cleaning.
- Run static file checks possible in the current environment.

Result:

- Added `README.md` with local setup, scripts, architecture, secret-storage notes, and MVP limitation.
- Added shell scripts:
  - `scripts/check-env.sh`
  - `scripts/create-gradle-wrapper.sh`
  - `scripts/build-debug.sh`
  - `scripts/install-debug.sh`
  - `scripts/test.sh`
  - `scripts/clean.sh`
  - `scripts/resolve-gradle.sh`
- Added `.gitignore`.
- Verified shell syntax with `bash -n`.
- Verified scripts are executable.
- `./scripts/check-env.sh` fails in the current execution environment because JDK 17 is not installed; Gradle/Android build was not run.

### 2026-06-06 - Before Block 4

Plan:

- Re-check local Android Studio, bundled JDK, Android SDK, and Gradle availability.
- Configure local project files if SDK/JDK are available.
- Run a Gradle build.
- Fix compile/build errors and update this log with the final result.

Result:

- Found Android Studio at `/Applications/Android Studio.app`.
- Found usable bundled JBR at `/Applications/Android Studio.app/Contents/jbr/Contents/Home` with OpenJDK 21.
- Found Android SDK at `/Users/stansful/Library/Android/sdk`.
- Added `local.properties` with the SDK path.
- Updated scripts to source `scripts/env.sh`, so they can use Android Studio JBR and SDK automatically.
- Added `scripts/lint.sh`.
- Added Gradle Wrapper files using Gradle 9.5.0.
- Fixed Kotlin deprecation warning by using `Icons.AutoMirrored.Filled.ArrowBack`.
- Fixed lint code warnings:
  - removed obsolete SDK checks in `SshVpnService`;
  - switched encrypted preferences writes to Android KTX `edit`.
- Verified `./scripts/check-env.sh`: success.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success, with `testDebugUnitTest NO-SOURCE` because no tests exist yet.
- Verified `./scripts/lint.sh`: success.
- Debug APK built at `app/build/outputs/apk/debug/app-debug.apk`.
- Remaining lint warnings are version-availability warnings for target/compile SDK and dependencies; no lint errors.

### 2026-06-06 - Before Block 5

Plan:

- Investigate why `Save` does not work on add configuration.
- Fix private key selection in the add/edit configuration form.
- Rebuild the project and run lint/test checks.

Result:

- Reworked private key selection in `EditConfigScreen`:
  - wrapped dropdown in an anchor `Box`;
  - selection now calls a dedicated `EditConfigViewModel.selectPrivateKey`.
- Added dedicated `EditConfigViewModel.selectAuthType`.
- Added private-key defaulting in `EditConfigViewModel`:
  - when switching to private-key auth, the first available key is selected if no key is selected;
  - after returning from creating a new key, the config form auto-selects the available key when `privateKeyId` is still blank;
  - save normalizes `privateKeyId` before validation.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Verified `./scripts/test.sh`: success, with `testDebugUnitTest NO-SOURCE` because no tests exist yet.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 6

Plan:

- Fix misleading private-key SSH authentication error mapping.
- Add clearer validation for accidentally pasted `.pub` public keys.
- Rebuild and rerun checks.

Result:

- Fixed misleading SSH private-key auth error mapping:
  - JSch `Auth fail` with private key now reports `Authentication failed`;
  - `Invalid private key passphrase` is only used for errors that look passphrase-related while loading the key.
- Added explicit validation for accidentally pasted `.pub` public keys:
  - `ssh-rsa`, `ssh-ed25519`, and common ECDSA public-key prefixes are rejected with a specific message.
- Added `SshPrivateKeyValidatorTest`.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 10

Plan:

- Put connection diagnostics behind a collapsed-by-default spoiler.
- Add a copy diagnostics button.
- Fix the current traffic blackhole caused by establishing a full-device VPN route without a real TUN forwarding engine.
- Rebuild and rerun tests/lint.

Result:

- Attached diagnostics show successful SSH public-key authentication.
- The freeze starts after the VPN interface is established.
- Current `Tun2SocksManager` only marks itself running and does not forward packets, so the full-device `0.0.0.0/0` VPN route blackholes traffic.
- Added collapsed-by-default connection diagnostics on the main screen.
- Added a copy button for diagnostics.
- Replaced the `Tun2SocksManager` placeholder with a real native TUN-to-SOCKS launch path using `hev-socks5-tunnel`.
- Added local SSH SOCKS5 bridge:
  - TCP `CONNECT` opens JSch `direct-tcpip` channels through the authenticated SSH session;
  - SOCKS UDP associate handles DNS packets by converting DNS UDP to DNS-over-TCP through SSH;
  - non-DNS UDP is dropped and logged once.
- Added SSH socket protection via `VpnService.protect(socket)` before the Android default VPN route is installed.
- Added vendored `app/libs/hevtunnel-1.0.1-kotlin19.aar`.
  - The upstream AAR is published with Kotlin 2.2 metadata.
  - The vendored AAR removes only the Kotlin module metadata so the Kotlin 1.9 app can compile while using the Java/native bridge through reflection.
- Updated `README.md` with the real forwarding path and current UDP limitation.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 9

Plan:

- Fix Ed25519 SSH private key authentication support.
- Add required crypto provider/dependency for JSch on Android.
- Register provider before SSH auth.
- Rebuild and rerun tests/lint.

Result:

- Diagnosed attached JSch log:
  - SSH transport and key exchange succeeded;
  - server allowed `publickey`;
  - failure happened because `ssh-ed25519` signature was unavailable for the loaded identity.
- Added runtime dependency `org.bouncycastle:bcprov-jdk18on:1.79`.
- Forced JSch EdDSA config to BouncyCastle-backed implementations:
  - `keypairgen.eddsa`;
  - `keypairgen_fromprivate.eddsa`;
  - `ssh-ed25519`;
  - `ssh-ed448`.
- Added a diagnostic line confirming BouncyCastle-backed EdDSA setup.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 8

Plan:

- Add sanitized JSch internal diagnostics for SSH auth debugging.
- Clarify connection display versus actual host/port usage.
- Rebuild and rerun checks.

Result:

- Added sanitized JSch internal logger output to connection diagnostics.
- Added raw JSch exception message to diagnostics as `Failure detail`.
- Changed private-key auth failure message to include the selected username.
- Verified that app host/port usage is already equivalent to `ssh -i key user@host -p port`; `user@host:port` only appears as display text.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

### 2026-06-06 - Before Block 7

Plan:

- Add sanitized connection diagnostics visible to the user.
- Improve SSH private-key authentication failure details without logging secrets.
- Rebuild and rerun tests/lint.

Result:

- Added sanitized diagnostics to `VpnConnectionState` and `VpnConnectionRepository`.
- `InMemoryVpnConnectionRepository` now keeps the last 80 timestamped diagnostic lines.
- `SshVpnService` logs connection stages:
  - selected endpoint;
  - auth type;
  - service start;
  - key lookup;
  - SSH manager stages;
  - VPN interface and TUN forwarding stages;
  - failure category.
- `SshConnectionManager` logs:
  - private key loading without key contents;
  - whether passphrase is present;
  - endpoint;
  - auth method;
  - server host key fingerprint;
  - fingerprint check result.
- Main screen now shows a copyable `Connection diagnostics` panel.
- Improved private-key auth failure message to say that the server rejected the key for the selected username.
- Verified `./scripts/build-debug.sh`: success.
- Verified `./scripts/test.sh`: success.
- Verified `./scripts/lint.sh`: success.
- Updated debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
