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

- SSH-over-TUN forwarding now has a native tun2socks engine, but still requires platform/device testing for production confidence.
- TCP and DNS are routed through SSH; arbitrary non-DNS UDP is still not proxied.
- UDP forwarding is still represented as an experimental configuration flag.
- Secrets must not be logged or stored in plain config/key records.

## Change Log

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
