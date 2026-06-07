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
