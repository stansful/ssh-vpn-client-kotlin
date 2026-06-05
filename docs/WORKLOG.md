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

- Full production-grade SSH-over-TUN forwarding requires platform/device testing and a real SSH/tun2socks integration.
- UDP forwarding is represented as an experimental configuration flag for MVP.
- Secrets must not be logged or stored in plain config/key records.

## Change Log

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
