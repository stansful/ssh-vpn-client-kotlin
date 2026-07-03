# shadow-ssh Android: техническая документация

Дата ревью проекта: 2026-07-03.

Документ описывает текущее устройство Android-приложения `shadow-ssh`: архитектуру, основные сценарии, хранение данных, VPN runtime, OpenSource/Xray runtime, обновления, сборку, безопасность и известные ограничения.

## 1. Назначение приложения

`shadow-ssh` - нативный Android VPN-клиент на Kotlin и Jetpack Compose. Приложение имеет два рабочих режима:

- `shadow-ssh`: VPN поверх SSH. Android `VpnService` поднимает TUN-интерфейс, а пользовательский TCP/DNS forwarder прокидывает трафик через SSH `direct-tcpip` каналы.
- `opensource`: VPN поверх публичных VLESS/VMess/Trojan конфигураций. Android `VpnService` поднимает TUN-интерфейс, а runtime Xray core обрабатывает TUN и выбранный proxy profile.

Оба режима используют общий routing mode, общий выбор приложений, общую тему, общий updater приложения и общий репозиторий состояния VPN. Одновременно активным должен быть только один транспорт: `SSH` или `XRAY`.

## 2. Технологический стек

- Язык: Kotlin.
- UI: Jetpack Compose, Material 3, Navigation Compose.
- Android API: `minSdk 26`, `targetSdk 37`, `compileSdk 37`.
- DI: ручной `AppContainer`.
- Persistence:
  - Room `2.8.4` для структурированных сущностей.
  - SharedPreferences для пользовательских настроек и легкого runtime state.
  - Tink `1.22.0` + Android Keystore для секретов.
- VPN:
  - Android `VpnService`.
  - JSch `2.28.3` для SSH.
  - BouncyCastle `1.84` для EdDSA/Ed25519 поддержки в JSch.
  - Xray Android binding как runtime core, скачиваемый отдельно от APK.
- Background work: WorkManager `2.11.2`.
- Build: Gradle `9.5.1`, AGP `9.2.1`, Kotlin Compose plugin `2.4.0`, KSP `2.3.9`.

## 3. Структура проекта

Ключевые директории:

- `app/src/main/java/com/stansful/sshvpnclient` - основной код приложения.
- `domain/model` - доменные модели и enum-ы.
- `domain/repository` - интерфейсы репозиториев.
- `domain/usecase` - application use cases.
- `data` - реализации репозиториев, Room, SharedPreferences, Tink, updater, source sync.
- `vpn` - Android VPN services, SSH runtime, TUN forwarder, diagnostics, quick settings tile.
- `xray` - генерация Xray config, runtime loading/install bridge.
- `ui` - Compose экраны, ViewModel-и, общие UI-компоненты, темы.
- `work` - WorkManager worker для автообновления public configs.
- `scripts` - сборка APK, Xray core, release assets, lint/test/install.
- `app/src/test` - unit-тесты.

## 4. Высокоуровневая архитектура

Основной поток зависимостей:

```text
MainActivity
  -> SshVpnApplication
    -> AppContainer
      -> repositories
      -> use cases
      -> managers/services bridges
      -> ViewModel factories
```

UI не создает инфраструктуру напрямую. Экраны получают `AppContainer`, ViewModel-и получают зависимости через `AppViewModelFactory`.

Слои:

- UI layer: Compose screens и ViewModel-и. Хранит только UI state, вызывает use cases и repository methods.
- Domain layer: модели, интерфейсы репозиториев, валидация и use cases.
- Data layer: Room, Tink, SharedPreferences, GitHub update API, public source sync.
- Runtime layer: `SshVpnService`, `OpenSourceVpnService`, `SshConnectionManager`, `VpnTunnelManager`, `Tun2SocksManager`, `XrayCoreBridge`.

## 5. Application startup

`SshVpnApplication.onCreate()`:

1. Создает `AppContainer`.
2. Проверяет `openSourceConsentVersion` и `openSourceAutoUpdateEnabled`.
3. Если OpenSource consent принят и auto-refresh включен, планирует `ProxySourceSyncWorker`.
4. Иначе отменяет periodic work.

`MainActivity.onCreate()`:

1. Подписывается на `AppSettingsRepository.settings`.
2. Вычисляет active theme.
3. Обновляет status/navigation bar colors.
4. Отрисовывает `SshVpnTheme` и `GlobalTabsHost`.

## 6. Навигация и основные экраны

Глобальные вкладки:

- `shadow-ssh`.
- `opensource`.

Активная вкладка сохраняется в настройках (`activeGlobalTab`).

`shadow-ssh` использует `SshVpnNavGraph`:

- `MAIN` - главный экран подключения SSH VPN.
- `CONFIGS` - список SSH конфигураций.
- `EDIT_CONFIG` - создание/редактирование SSH конфигурации.
- `KEYS` - список приватных ключей.
- `EDIT_KEY` - создание/редактирование приватного ключа.
- `APP_PICKER` - выбор приложений для `Selected apps`.

`opensource` отображается как отдельный route внутри `GlobalTabsHost`, но использует общий `APP_PICKER` через переключение вкладки на `shadow-ssh` и навигацию в app picker.

## 7. Общие настройки приложения

Модель `AppSettings` хранит:

- `showLogsOnMain` - показывать diagnostics на SSH главной.
- `showLogsOnOpenSource` - показывать diagnostics на OpenSource вкладке.
- `showTerminalOnMain` - показывать SSH terminal panel.
- `themeMode` - `System`, `Light`, `Dark`, `Custom`.
- `customThemeColors` - палитра custom theme.
- `vpnMode` - `Proxy` или `Selected apps`.
- `selectedAppPackages` - набор package name для `Selected apps`.
- `activeGlobalTab` - последняя выбранная вкладка.
- `openSourceConsentVersion` - принятая версия предупреждения OpenSource.
- `showOpenSourceWarningOnEnter` - показывать warning dialog при переходе на OpenSource.
- `openSourceRiskBannerExpanded` - раскрытость warning banner.
- `openSourceAutoUpdateEnabled` - автообновление public configs.

Настройки хранятся в `SharedPreferencesAppSettingsRepository` в файле `ssh-vpn-client-settings`.

## 8. Routing mode и selected apps

Доступны два режима маршрутизации:

- `Proxy`: весь трафик приложений идет через VPN.
- `Selected apps`: через VPN идут только приложения из `selectedAppPackages`.

Режим общий для SSH и OpenSource.

`VpnTunnelManager.applySplitTunnelSettings()`:

- Для `Proxy` не добавляет package filters и маршрутизирует все приложения.
- Для `Selected apps` вызывает `VpnService.Builder.addAllowedApplication()` для каждого выбранного package.
- Если выбран `Selected apps`, но список пустой, подключение блокируется ошибкой `No apps selected`.

App picker:

- Читает установленные приложения через `PackageManagerInstalledAppsRepository`.
- Кеширует список на 5 минут.
- Сортирует сначала пользовательские приложения, затем системные.
- В UI отображает label, package name, marker `System` и иконку приложения.

Режима bypass в текущей модели нет.

## 9. Room database

База данных: `ssh-vpn-client.db`.

Версия схемы: `3`.

Entities:

### `ssh_configs`

Хранит SSH конфигурации:

- `id`, `name`, `host`, `port`, `username`.
- `authType`: `PASSWORD` или `PRIVATE_KEY`.
- `passwordSecretId` - ссылка на Tink secret.
- `privateKeyId` - ссылка на `ssh_private_keys`.
- `fingerprint` - optional expected host fingerprint.
- `keepAliveIntervalSec`.
- `enableUdpForwarding` - experimental flag.
- `note`.
- `isSelected`.
- `createdAt`, `updatedAt`.

### `ssh_private_keys`

Хранит metadata приватных ключей:

- `id`, `name`.
- `privateKeySecretId`.
- `passphraseSecretId`.
- `note`.
- `createdAt`, `updatedAt`.

Сам приватный ключ и passphrase в Room не лежат.

### `proxy_profiles`

Хранит public/manual proxy profiles:

- `id`, `name`, `protocol`, `host`, `port`.
- `transport`, `security`, `flow`.
- `source`: `MANUAL`, `CLIPBOARD`, `REMOTE`.
- `sourceUrl`.
- `secretId` - raw URI в Tink secret storage.
- `fingerprint` - unique canonical hash.
- `isSelected`, `isPinned`, `isStale`.
- `lastTestStatus`, `lastLatencyMs`, `lastTestAt`.
- `createdAt`, `updatedAt`, `lastSeenAt`.

Миграции:

- `1 -> 2`: добавляет `proxy_profiles`.
- `2 -> 3`: добавляет `isPinned`.

## 10. Secret storage

`TinkSecretStorage` хранит sensitive values:

- SSH passwords.
- SSH private keys.
- SSH private key passphrases.
- Raw proxy profile URI.

Схема:

- Keyset: Android Keystore URI `android-keystore://ssh_vpn_secret_master_key`.
- Preferences: `ssh_vpn_tink_secrets`.
- Associated data: `ssh-vpn-secret:<id>`.

Есть миграция legacy secrets из `EncryptedSharedPreferences` `ssh_vpn_secrets`. Миграция идемпотентная и помечается в preferences.

## 11. Глобальное состояние VPN

`VpnConnectionState`:

- `status`: `DISCONNECTED`, `CONNECTING`, `CONNECTED`, `RECONNECTING`, `DISCONNECTING`, `ERROR`.
- `activeConfigId`.
- `errorMessage`.
- `diagnostics`.
- `activeTransport`: `SSH`, `XRAY` или `null`.

Реализация: `InMemoryVpnConnectionRepository`.

Особенности:

- Состояние живет в памяти через `MutableStateFlow`.
- Diagnostics буферизуются и публикуются в UI батчами раз в 100 мс.
- Diagnostics сохраняются в SharedPreferences `ssh-vpn-connection-state`.
- Persistence diagnostics ограничена интервалом 15 секунд, кроме forced updates.
- При `setConnecting()` diagnostics очищаются.
- При `setDisconnected()` transport сбрасывается в `null`.
- При `setError()` transport сбрасывается в `null`.

UI должен учитывать `activeTransport`. SSH экран показывает активное подключение только если `activeTransport == SSH`. OpenSource экран показывает активное подключение только если `activeTransport == XRAY`.

## 12. Взаимоисключение SSH и OpenSource VPN

Одновременно может быть активен только один VPN transport.

`ConnectVpnUseCase`:

1. Валидирует выбранную SSH конфигурацию.
2. Валидирует selected apps.
3. Валидирует SSH key для private key auth.
4. Если активен `XRAY`, отправляет `OpenSourceVpnService.disconnectIntent()`.
5. Ждет до 2 секунд, пока состояние станет disconnected или transport перестанет быть `XRAY`.
6. Запускает `SshVpnService`.

`ConnectProxyVpnUseCase`:

1. Валидирует выбранный proxy profile.
2. Валидирует selected apps.
3. Если активен `SSH`, отправляет `SshVpnService.disconnectIntent()`.
4. Ждет до 2 секунд, пока состояние станет disconnected или transport перестанет быть `SSH`.
5. Запускает `OpenSourceVpnService`.

`DisconnectVpnUseCase` отправляет disconnect intent в сервис активного транспорта.

## 13. Android VPN interface

`VpnTunnelManager` создает Android TUN:

- Session name: `Secure connection`.
- MTU: `1500`.
- Private address: `10.10.0.2/32`.
- Default route: `0.0.0.0/0`.
- DNS: `1.1.1.1`, `8.8.8.8`.
- Android Q+: `setMetered(false)`.
- Split tunneling через `addAllowedApplication()` для `Selected apps`.

Один `VpnTunnelManager` общий для SSH и OpenSource runtime.

## 14. SSH VPN runtime

Основные классы:

- `SshVpnService`.
- `SshConnectionManager`.
- `VpnTunnelManager`.
- `Tun2SocksManager`.
- `KotlinTunForwarder`.
- `VpnProtectedSocketFactory`.

Поток подключения:

```text
MainViewModel.connect()
  -> ConnectVpnUseCase
    -> SshVpnService.connectIntent()
      -> SshVpnService.runConnectionLoop()
        -> SshConnectionManager.connect()
        -> VpnTunnelManager.establish()
        -> Tun2SocksManager.start()
        -> KotlinTunForwarder.start()
```

`SshConnectionManager.connect()`:

- Создает JSch session.
- При private key auth загружает ключ из Tink-backed repository.
- Настраивает BouncyCastle-backed EdDSA support.
- Настраивает `PreferredAuthentications`: `password` или `publickey`.
- Отключает `StrictHostKeyChecking` на уровне JSch, но после подключения отдельно проверяет configured fingerprint, если он задан.
- Настраивает server alive interval, максимум 60 секунд.
- Использует `VpnProtectedSocketFactory`, чтобы защитить SSH socket от маршрутизации обратно в VPN.

Fingerprint behavior:

- Фактический server host key fingerprint логируется.
- Если expected fingerprint пустой, проверка пропускается.
- Если expected fingerprint задан и не совпадает после нормализации, session отключается и выбрасывается `Fingerprint mismatch`.

## 15. Kotlin TUN forwarder

`KotlinTunForwarder` читает IPv4 packets из TUN и реализует forwarding на уровне приложения.

Поддерживается:

- TCP forwarding через SSH `direct-tcpip`.
- UDP DNS на port `53`.
- DNS-over-TCP через SSH.
- DNS-over-HTTPS fallback на Cloudflare `cloudflare-dns.com` / `1.1.1.1:443`, если DNS-over-TCP деградирует.

Ограничения:

- Произвольный non-DNS UDP не проксируется.
- `enableUdpForwarding` остается experimental flag. При включении runtime логирует, что custom forwarder поддерживает TCP и DNS UDP/53.

Оптимизации forwarder:

- Отдельные thread pools для control tasks, remote reads и cleanup.
- Ограниченные очереди.
- Session maintenance раз в 20 секунд.
- Idle cleanup под нагрузкой.
- Лимит подробных diagnostic logs.
- TCP reset для stale sessions после wake recovery.

## 16. SSH reconnect и wake recovery

`SshVpnService` работает как foreground service и возвращает `START_STICKY`.

Reconnect:

- Initial delay: 250 мс.
- Max delay: 5000 мс.
- До первого успешного подключения unrecoverable auth/key/fingerprint ошибки переводят state в `ERROR`.
- После первого успешного подключения сервис старается восстановиться автоматически.
- Если TUN pipeline жив, SSH reconnect может пройти без пересоздания Android VPN interface.
- Если forwarder деградировал или остановился, pipeline пересоздается.

Wake recovery:

- Сервис регистрирует dynamic receiver на `SCREEN_OFF` и `SCREEN_ON`.
- Wake recovery запускается только если экран был выключен минимум 60 секунд.
- Wake lock не используется.
- После wake:
  - сбрасываются TCP sessions, idle минимум 30 секунд;
  - выполняется короткий SSH transport health-check через `direct-tcpip` к `1.1.1.1:443`;
  - если transport stale, SSH session отключается, что запускает reconnect loop.

Это решает кейс, когда после сна SSH session формально жива, но старые app sockets больше не проводят трафик.

## 17. SSH diagnostics и terminal

Diagnostics:

- Все ключевые этапы подключения пишутся в `VpnConnectionRepository`.
- На SSH главной diagnostics показываются только если `showLogsOnMain == true` и активный transport не `XRAY`.
- Есть копирование diagnostics в clipboard.

Tunnel check:

- `MainViewModel.checkTunnel()` вызывает `SshConnectionManager.checkTcpForward()`.
- По умолчанию проверяется `youtube.com:443` через SSH `direct-tcpip`.
- Кнопка меняет цвет по результату: idle, success, failure.

Terminal:

- Включается настройкой `showTerminalOnMain`.
- Открывает JSch shell channel с PTY type `xterm`.
- Output хранится в UI state, максимум 120000 символов.
- Закрывается при disconnect или смене active transport.

## 18. Quick Settings tile

`SshVpnTileService`:

- Показывает статус текущего global VPN state.
- По клику:
  - если VPN connecting/connected/reconnecting/disconnecting, вызывает disconnect;
  - если disconnected/error, пытается подключить SSH mode.
- Если нет выбранной SSH config, нет selected apps или не выдано VPN permission, открывает приложение.

Tile управляет только SSH подключением. OpenSource transport может быть отключен через общий `DisconnectVpnUseCase`, если он активен.

## 19. OpenSource policy и предупреждения

`OpenSourcePolicy`:

- `CONSENT_VERSION = 1`.
- Public source: `https://hub.mos.ru/zieng2/wl/raw/main/list_universal.txt`.
- Disclaimer: public configurations are third-party and used at user risk.

При переходе на OpenSource:

- Если `showOpenSourceWarningOnEnter == true`, показывается warning dialog.
- Confirm сохраняет consent version и, если auto-refresh включен, планирует background sync.
- Back возвращает на `shadow-ssh`.

На OpenSource странице есть risk banner. Его раскрытость хранится в `openSourceRiskBannerExpanded`.

## 20. OpenSource profile import и sync

Основные классы:

- `ProxyShareLinkParser`.
- `RoomProxyProfileRepository`.
- `PublicProxySourceSynchronizer`.
- `ProxySourceSyncWorker`.
- `OpenSourceViewModel`.

Поддерживаемые share links:

- `vless://`.
- `vmess://`.
- `trojan://`.

Parser:

- Максимальная длина одной ссылки: 64 KiB.
- Максимум строк при bulk import: 10000.
- Пустые строки и строки `#...` игнорируются.
- Для VLESS/Trojan используется URI parser.
- Для VMess используется base64 JSON.
- Canonical fingerprint считается через SHA-256 по нормализованной конфигурации.

Repository import:

- Deduplicate по fingerprint.
- Raw URI сохраняется в Tink secret storage.
- Metadata сохраняется в Room.
- Для remote source старые профили, отсутствующие в новом sync, помечаются stale.
- Если выбранный профиль удален или stale, repository выбирает первый доступный профиль.

Public sync:

- HTTP GET к `OpenSourcePolicy.SOURCE_URL`.
- Accept: `text/plain`.
- User-Agent: `shadow-ssh-android-opensource-sync`.
- Timeout: connect 10 секунд, read 15 секунд.
- Response size limit: 2 MiB.
- Поддерживается ETag через `If-None-Match`, кроме forced refresh.

Background sync:

- Work name: `public-proxy-source-sync`.
- Periodic interval: 6 часов.
- Flex interval: 1 час.
- Constraints: connected network, battery not low.
- Max retry: 3.
- Работает только если consent принят и auto-refresh включен.

Manual refresh всегда force-запрос и не должен полагаться на cached ETag.

## 21. OpenSource UI и операции

OpenSource экран поддерживает:

- Risk banner.
- Search, который открывается кнопкой с лупой.
- Compact pinned filter.
- Выбор активного profile без перемещения карточки вверх.
- Long press multi-select.
- `Select all`, исключающий pinned profiles.
- Delete selected.
- Pin/unpin profile.
- Add/edit manual profile.
- Bulk import from clipboard/text.
- Scroll-to-top / scroll-to-bottom floating buttons.
- Logs panel.
- Settings bottom sheet.

Pinned behavior:

- `isPinned` сохраняется в Room.
- Pinned profiles остаются на своем месте в текущем порядке списка.
- Для просмотра pinned используется `pinnedOnly` filter.
- `Select all` не выбирает pinned profiles автоматически.

Check operations:

- `Check selected` проверяет выбранный active profile через Xray test.
- `Check all` сначала делает endpoint ping для всех видимых profiles, затем Xray tunnel check.
- Endpoint ping - TCP connect к `host:port`, timeout 1500 мс.
- Concurrency endpoint ping: 12.
- UI показывает `ping <number> ms` на карточке.
- Xray tunnel check выполняет `XrayCoreBridge.test()` и сохраняет status/latency в Room.
- Итоговый message содержит количество successful ping и counts по tunnel result: available, unavailable, unsupported.
- Проверки можно отменить.

## 22. Xray config generation

`XrayConfigBuilder` строит JSON config.

TUN config:

- Inbound protocol: `tun`.
- Tag: `tun-in`.
- TUN name: `xray0`.
- MTU: 1500.
- Sniffing enabled.
- `destOverride`: `http`, `tls`, `quic`.
- Outbound строится из выбранного `ProxyProfile`.

SOCKS test config:

- Inbound protocol: `socks`.
- Listen: `127.0.0.1`.
- Dynamic port.
- UDP enabled.
- Outbound такой же, как в TUN config.

Supported outbound protocols:

- VLESS.
- VMess.
- Trojan.

Supported transports:

- RAW.
- XHTTP.
- gRPC.
- WebSocket.
- HTTP Upgrade.
- mKCP.
- Hysteria.

Supported security:

- None.
- TLS.
- Reality.

Unknown transport/security считаются unsupported для checks и не должны запускаться в Xray.

## 23. Xray runtime core

APK по умолчанию не содержит `libXray.aar`, чтобы universal APK оставался легким. Core скачивается отдельно.

Основные классы:

- `GitHubXrayCoreUpdateRepository`.
- `XrayCoreBridge`.
- `XrayCoreStore` внутри `XrayCoreBridge.kt`.

Release source:

- GitHub latest release API: `https://api.github.com/repos/stansful/ssh-vpn-client-kotlin/releases/latest`.
- User-Agent: `shadow-ssh-android-xray-core-updater`.
- GitHub API version: `2026-03-10`.
- Скачивание разрешено только с path prefix `/stansful/ssh-vpn-client-kotlin/releases/download/`.

Asset selection:

- Ищутся `.aar` assets.
- ABI определяется по имени asset.
- Поддерживаемые ABI: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
- Universal `libXray.aar` или asset с `universal` может подходить всем ABI.
- Пользователю показывается asset для runtime ABI устройства.

Download validation:

- Max release response: 1 MiB.
- Max core download: 90 MiB.
- Optional GitHub `digest: sha256:<hash>` проверяется, если есть.
- AAR должен содержать:
  - `classes.dex`.
  - `jni/<runtimeAbi>/libgojni.so`.

Install:

- Скачанный asset кешируется в `filesDir/xray-core-downloads`.
- Установленный slim core хранится в `filesDir/xray-core/libXray.aar`.
- В slim core остаются только `classes.dex` и `jni/<runtimeAbi>/libgojni.so`.
- Перед загрузкой core распаковывается в `filesDir/xray-core/prepared`.
- `classes.dex` и native library помечаются read-only.
- Binding грузится через `DexClassLoader`.

Native library limitation:

- Android не позволяет загрузить тот же native library из другого ClassLoader в уже живом процессе.
- Если core уже был загружен, обновление может вернуть `INSTALLED_AFTER_RESTART`.
- В этом случае новый файл установлен, но приложение нужно перезапустить, чтобы использовать новый runtime.

## 24. OpenSource VPN runtime

Поток подключения:

```text
OpenSourceViewModel.connect()
  -> ConnectProxyVpnUseCase
    -> OpenSourceVpnService.connectIntent(profileId)
      -> OpenSourceVpnService.runConnectionLoop()
        -> VpnTunnelManager.establish()
        -> XrayCoreBridge.startTun()
```

`OpenSourceVpnService`:

- Foreground service.
- Проверяет выбранный profile.
- Проверяет наличие Xray core.
- Проверяет selected apps.
- Создает Android VPN interface.
- Регистрирует socket protector в Xray binding.
- Передает TUN fd в Xray binding.
- Запускает Xray из inline JSON config.
- Мониторит `xrayCoreBridge.isRunning()` каждые 5 секунд.
- При unexpected stop запускает reconnect loop.

Reconnect:

- Initial delay: 250 мс.
- Max delay: 5000 мс.
- На ошибке service чистит Xray runtime и Android VPN interface.

## 25. Обновление приложения

Основные классы:

- `GitHubAppUpdateRepository`.
- `AndroidAppUpdateDownloader`.
- UI state в `MainViewModel` и `OpenSourceViewModel`.

Release check:

- GitHub latest release API: `https://api.github.com/repos/stansful/ssh-vpn-client-kotlin/releases/latest`.
- Strict SemVer для tag/version.
- Автоматическая проверка запускается через 1500 мс при инициализации `MainViewModel` на SSH главной.
- Успешная автоматическая проверка кешируется на 24 часа.
- Manual check force-режим.
- Поддерживается ETag/304.
- Network выбирается как validated non-VPN, если доступен.

APK asset selection:

- Предпочитается APK для первого supported ABI устройства.
- Если ABI asset не найден, fallback на universal APK.
- Сохраняется совместимость со старым single APK release.

Download:

- Используется Android DownloadManager.
- Destination: external app-specific Downloads `/updates`.
- Download state восстанавливается после restart.
- Показывается progress: downloaded bytes, total bytes, percent, paused state.

Validation перед install:

- Optional GitHub SHA-256 digest, если release asset содержит digest.
- Package name.
- VersionName/SemVer.
- VersionCode должен быть выше установленного.
- Signing certificate должен совпадать с установленным приложением.

Install:

- APK отдается через FileProvider `${applicationId}.fileprovider`.
- Permission `REQUEST_INSTALL_PACKAGES`.
- Unknown-source settings открывается только по явному действию пользователя.

## 26. Сборка и release artifacts

Основные scripts:

- `scripts/test.sh` - unit tests.
- `scripts/lint.sh` - lint.
- `scripts/build-debug.sh` - debug APK.
- `scripts/build-release.sh` - release APK.
- `scripts/build-xray-core.sh` - pinned build `libXray.aar`.
- `scripts/package-xray-core-assets.sh` - ABI-specific Xray core release assets.

Release APK:

- `appVersionName = 2.5.3`.
- `versionCode = major * 1_000_000 + minor * 1_000 + patch`.
- ABI splits включены для:
  - `arm64-v8a`.
  - `armeabi-v7a`.
  - `x86`.
  - `x86_64`.
- Universal APK включен.
- Release build:
  - `minifyEnabled = true`.
  - `shrinkResources = true`.
  - ProGuard optimize rules.

Signing:

- Production signing читается из:
  - `SSH_VPN_RELEASE_STORE_FILE`.
  - `SSH_VPN_RELEASE_STORE_PASSWORD`.
  - `SSH_VPN_RELEASE_KEY_ALIAS`.
  - `SSH_VPN_RELEASE_KEY_PASSWORD`.
- Если все переменные отсутствуют, script создает локальный release keystore в `.local/signing`.
- Если переменные заданы частично, сборка падает.
- `build-release.sh` проверяет, что появились signed APK, а не только `*-unsigned.apk`.

Xray core build:

- `scripts/build-xray-core.sh` клонирует pinned upstream:
  - `libXray`: `9bb7cad11a225f1039274dc8afd9810bcf458038`.
  - `Xray-core`: `94ffd50060f1cfd5d7482ec90a23a92bdefdff68`.
  - `gomobile`: `v0.0.0-20260611195102-4dd8f1dbf5d2`.
- Требуются `git`, `go`, `python3`, `ANDROID_HOME`, Android NDK.
- Результат: `app/libs/libXray.aar`.

Xray release assets:

- `scripts/package-xray-core-assets.sh` берет source AAR.
- D8 компилирует `classes.jar` в `classes.dex`.
- Для каждого ABI создает `libXray-<appVersion>-<abi>.aar`.
- В asset остаются только base AAR entries, `classes.dex` и один `jni/<abi>/libgojni.so`.
- `.sha256` файлы сейчас не генерируются.

По умолчанию Xray core не бандлится в APK. Для forced bundled build есть Gradle property `-PbundleXrayCore=true` и env `SSH_VPN_BUNDLE_XRAY_CORE=1`.

## 27. Manifest и permissions

Permissions:

- `INTERNET`.
- `ACCESS_NETWORK_STATE`.
- `QUERY_ALL_PACKAGES`.
- `FOREGROUND_SERVICE`.
- `FOREGROUND_SERVICE_SPECIAL_USE`.
- `POST_NOTIFICATIONS`.
- `REQUEST_INSTALL_PACKAGES`.

Components:

- `MainActivity`: launcher.
- `SshVpnService`: non-exported, `BIND_VPN_SERVICE`, foreground service type `specialUse`.
- `OpenSourceVpnService`: non-exported, `BIND_VPN_SERVICE`, foreground service type `specialUse`.
- `SshVpnTileService`: exported, `BIND_QUICK_SETTINGS_TILE`.
- `FileProvider`: non-exported, grant URI permissions.

Backup:

- `android:allowBackup="false"`.
- Настроены `dataExtractionRules` и `backup_rules`.

## 28. Безопасность и privacy

Сделано:

- Секреты не хранятся в Room в открытом виде.
- Tink AEAD + Android Keystore.
- Sensitive raw proxy URI хранится как secret.
- App backup отключен.
- VPN sockets защищаются через `VpnService.protect()`, чтобы избежать loopback в VPN.
- Update APK проверяется по package, version, signing cert и optional SHA-256.
- Xray core download URL ограничен GitHub release path.
- Xray core asset проверяется на наличие `classes.dex` и native library под runtime ABI.
- Public configs имеют explicit warning/consent.

Риски и ограничения:

- Public proxy configurations являются third-party. Приложение не может гарантировать безопасность public endpoints.
- `QUERY_ALL_PACKAGES` используется для app picker и может требовать обоснования при публикации.
- JSch host key strict checking отключен, но optional fingerprint verification реализована отдельно.
- Если fingerprint не задан, SSH host authenticity не закреплена.
- Xray runtime core доверяется release repository и Android package sandbox, но это исполняемый native code.

## 29. Производительность и батарея

Текущие меры:

- Xray core вынесен из APK и скачивается по ABI.
- Release build включает R8 и resource shrinking.
- PackageManager app list кешируется на 5 минут.
- Public config auto-refresh через WorkManager с constraints `network connected` и `battery not low`.
- Нет постоянного wake lock.
- SSH wake recovery event-driven: только screen on/off receiver во время foreground service.
- Diagnostics публикуются в UI батчами.
- Diagnostics persistence throttled до 15 секунд.
- Xray/OpenSource checks имеют bounded endpoint ping concurrency.
- ViewModel flows используют `SharingStarted.WhileSubscribed(5_000)` там, где это подходит UI.

Потенциально дорогие операции:

- `Check all` может запускать много TCP endpoint pings и Xray tunnel checks.
- Xray tunnel checks выполняют реальный network probe через temporary SOCKS inbound.
- Package icon rendering в app picker идет из PackageManager, но bitmap кешируется через Compose `remember`.
- Kotlin TUN forwarder держит worker pools, пока активен SSH VPN.

## 30. Локализация

Текущая база:

- Часть строк вынесена в `app/src/main/res/values/strings.xml`.
- `strings.xml` сейчас содержит английские строки для notifications, QS tile, OpenSource warning, diagnostics labels и settings labels.

Текущее ограничение:

- В Compose-коде все еще есть hardcoded English strings.

Рекомендуемый контракт для дальнейшей локализации:

- Новый пользовательский текст добавлять в `strings.xml`.
- Для новых языков добавлять `values-<locale>/strings.xml`.
- ViewModel status messages, которые показываются пользователю, тоже постепенно выносить в UI/resource layer или в отдельный message abstraction, чтобы не смешивать domain/runtime и language text.

## 31. Тестовая поверхность

Unit tests:

- `ProxyShareLinkParserTest` - parser VLESS/VMess/Trojan, limits, failures.
- `XrayConfigBuilderTest` - генерация Xray JSON.
- `GitHubAppUpdateRepositoryTest` - выбор APK asset по ABI/universal fallback.
- `AndroidAbiTest` - runtime ABI и asset matching.
- `SemanticVersionTest` - SemVer parsing/comparison.
- `AppUpdateDownloadStateTest` - progress state.
- `SshPrivateKeyValidatorTest` - private key validation.
- `WakeRecoveryPolicyTest` - screen off/on policy.
- `ReconnectBackoffTest` - exponential backoff.

Что не покрыто автоматикой:

- Реальное Android `VpnService` поведение.
- Реальный TUN packet forwarding на устройстве.
- Реальный Xray native runtime start/stop на устройстве.
- Android installer/unknown sources OEM screens.
- QS tile на разных Android версиях.

## 32. Основные известные ограничения

- SSH режим полноценно проксирует TCP и DNS. Произвольный UDP не поддержан.
- `enableUdpForwarding` в SSH config остается experimental и не означает full UDP forwarding.
- OpenSource зависит от скачанного Xray runtime core. Без core подключение заблокировано.
- Обновление уже загруженного Xray native core может требовать restart приложения.
- Public source может отдавать stale/unsupported configs. Они импортируются с metadata и помечаются status checks.
- Автоматический public sync не должен запускаться без consent и отключается настройкой auto-refresh.
- App updater зависит от GitHub releases и корректной публикации APK assets.

## 33. Типовые runtime сценарии

### Подключение SSH VPN

1. Пользователь выбирает SSH config.
2. Нажимает `Connect`.
3. UI вызывает `ConnectVpnUseCase`.
4. Если активен Xray, он отключается.
5. `SshVpnService` стартует как foreground service.
6. JSch подключается к SSH server.
7. Android TUN interface создается через `VpnTunnelManager`.
8. `KotlinTunForwarder` начинает читать TUN и открывать SSH direct TCP channels.
9. `VpnConnectionRepository` публикует `CONNECTED` с `activeTransport = SSH`.

### Подключение OpenSource VPN

1. Пользователь выбирает proxy profile.
2. Нажимает `Connect`.
3. UI вызывает `ConnectProxyVpnUseCase`.
4. Если активен SSH, он отключается.
5. Проверяется установленный Xray core.
6. `OpenSourceVpnService` стартует как foreground service.
7. Android TUN interface создается через `VpnTunnelManager`.
8. Xray binding получает socket protector, TUN fd и JSON config.
9. `VpnConnectionRepository` публикует `CONNECTED` с `activeTransport = XRAY`.

### Refresh public configs

1. Пользователь нажимает refresh или worker запускается по расписанию.
2. `PublicProxySourceSynchronizer` скачивает source.
3. `ProxyShareLinkParser` парсит конфиги.
4. `RoomProxyProfileRepository` делает upsert по fingerprint.
5. Старые remote profiles источника помечаются stale.
6. Выбор active profile нормализуется через `ensureSelection()`.

### Check all OpenSource configs

1. `OpenSourceViewModel.checkAll()` берет видимые profiles.
2. Сбрасывает old host ping для этих ids.
3. Параллельно пингует endpoints `host:port`.
4. Пишет `ping <ms>` в UI state карточек.
5. Последовательно запускает Xray tunnel checks.
6. Сохраняет `lastTestStatus`, `lastLatencyMs`, `lastTestAt`.
7. Показывает итоговый summary.

## 34. Правила для будущих изменений

- Любой новый VPN state должен учитывать `activeTransport`, иначе SSH и OpenSource UI снова начнут читать чужое состояние.
- Любое изменение split tunneling должно проходить через общий `AppSettings.vpnMode` и `selectedAppPackages`.
- Raw credentials и raw proxy URI нельзя хранить в Room или логах.
- Новые OpenSource operations должны уважать pinned behavior: pinned не перемещается автоматически и не попадает в bulk select all.
- Новые background задачи должны иметь battery/network constraints и не держать wake lock без отдельного обоснования.
- Любой новый release asset selection должен сохранять ABI-specific preference и universal fallback.
- Новые пользовательские строки нужно выносить в resources, чтобы не ухудшать будущую локализацию.
