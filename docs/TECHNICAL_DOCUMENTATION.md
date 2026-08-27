# shadow-ssh Android: техническая документация

Дата ревью проекта: 2026-07-16.

Документ описывает текущее устройство Android-приложения `shadow-ssh`: архитектуру, основные сценарии, хранение данных, VPN runtime, Public Routes (`OpenSource` внутри кода)/Xray runtime, обновления, сборку, безопасность и известные ограничения.

## 1. Назначение приложения

`shadow-ssh` - нативный Android VPN-клиент на Kotlin и Jetpack Compose. Приложение имеет три рабочих режима:

- `shadow-ssh`: VPN поверх SSH. Android `VpnService` поднимает TUN-интерфейс, а пользовательский TCP/DNS forwarder прокидывает трафик через SSH `direct-tcpip` каналы.
- `smart` (`Smart Connect`): изолированно обновляет и проверяет публичный Xray-каталог, удаляет подтверждённо недоступные профили, выбирает минимальный ping и автоматически восстанавливает VPN при подтверждённом отказе.
- `Public Routes` (persisted/internal id `opensource`): VPN поверх публичных VLESS/VMess/Trojan конфигураций. Android `VpnService` поднимает TUN-интерфейс, а runtime Xray core обрабатывает TUN и выбранный proxy profile.

Все режимы используют общий routing mode, общий выбор приложений, общую тему, updater приложения и process-wide VPN state/lease. Smart-каталог, его Room rows, selection, sync metadata и Tink secrets не пересекаются с OpenSource. Одновременно активной может быть только одна VPN-сессия.

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
- Runtime layer: `SshVpnService`, `SmartConnectVpnService`, `OpenSourceVpnService`, `SshConnectionManager`, `SmartConnectCatalogManager`, `VpnTunnelManager`, `Tun2SocksManager`, `XrayCoreBridge`.

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
- `smart` (`Smart Connect`).
- `Public Routes` (internal id `opensource`).

Активная вкладка сохраняется в настройках (`activeGlobalTab`).

`shadow-ssh` использует `SshVpnNavGraph`:

- `MAIN` - главный экран подключения SSH VPN.
- `CONFIGS` - список SSH конфигураций.
- `EDIT_CONFIG` - создание/редактирование SSH конфигурации.
- `KEYS` - список приватных ключей.
- `EDIT_KEY` - создание/редактирование приватного ключа.
- `APP_PICKER` - выбор приложений для `Selected apps`.

`smart` и `Public Routes` отображаются отдельными route внутри `GlobalTabsHost` и используют единый глобальный app picker без переключения на SSH navigation graph.

## 7. Общие настройки приложения

Модель `AppSettings` хранит:

- `showLogsOnMain` - показывать diagnostics на SSH главной.
- `showLogsOnOpenSource` - показывать diagnostics на OpenSource вкладке.
- `showLogsOnSmartConnect` - показывать diagnostics на Smart Connect вкладке.
- `showTerminalOnMain` - показывать SSH terminal panel.
- `themeMode` - `System`, `Light`, `Dark`, `Custom`.
- `customThemeColors` - палитра custom theme.
- `vpnMode` - `Proxy` или `Selected apps`.
- `selectedAppPackages` - набор package name для `Selected apps`.
- `activeGlobalTab` - последняя выбранная вкладка.
- `openSourceConsentVersion` - принятая версия предупреждения OpenSource.
- `showOpenSourceWarningOnEnter` - показывать warning dialog при переходе на OpenSource.
- `openSourceRiskBannerExpanded` - раскрытость warning banner.
- `openSourceAutoUpdateEnabled` - автообновление public configs, по умолчанию выключено.
- `smartConnectConsentVersion` и `showSmartConnectWarningOnEnter` - отдельное согласие и warning policy Smart Connect.

Настройки хранятся в `SharedPreferencesAppSettingsRepository` в файле `ssh-vpn-client-settings`.

## 8. Routing mode и selected apps

Доступны два режима маршрутизации:

- `Proxy`: весь трафик приложений идет через VPN.
- `Selected apps`: через VPN идут только приложения из `selectedAppPackages`.

Режим общий для SSH, Smart Connect и OpenSource.

`VpnTunnelManager.applySplitTunnelSettings()`:

- Для `Proxy` не добавляет package filters и маршрутизирует все приложения.
- Для `Selected apps` вызывает `VpnService.Builder.addAllowedApplication()` для каждого выбранного package.
- Если выбран `Selected apps`, но список пустой, подключение блокируется ошибкой `No apps selected`.

App picker:

- Читает установленные приложения через `PackageManagerInstalledAppsRepository`.
- Кеширует список на 5 минут.
- Сортирует сначала пользовательские приложения, затем системные.
- В UI отображает label, package name, marker `System` и иконку приложения.
- Декодирует иконки максимум двумя IO-задачами, объединяет одинаковые запросы и хранит bitmap в 4 MiB LRU с TTL 5 минут между повторными открытиями экрана.

UI design system:

- Сохраняет существующие launcher icon и чёрно-оранжевую dark-палитру.
- Использует единую системную sans-serif типографику, стандартную нижнюю навигацию и grouped inset-поверхности с общей шкалой радиусов.
- Не использует blur, shader effects или бесконечные декоративные анимации; короткие press/state transitions создаются только во время пользовательского действия или смены состояния.
- Root tabs сохраняют собственное состояние, а нижняя панель скрывается на SSH secondary screens и в глобальном app picker.

Режима bypass в текущей модели нет.

## 9. Room database

База данных: `ssh-vpn-client.db`.

Версия схемы: `4`.

Entities:

Помимо SSH/OpenSource таблиц схема v4 содержит `smart_proxy_profiles`: отдельную копию metadata публичных профилей для Smart Connect. Она не имеет foreign key или DAO-пути к `proxy_profiles`, а секреты используют отдельный префикс `smart-proxy-profile-*`.

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
- `sessionOwner`: `SHADOW_SSH`, `SMART_CONNECT`, `OPEN_SOURCE` или `null`.

Реализация: `InMemoryVpnConnectionRepository`.

Особенности:

- Состояние живет в памяти через `MutableStateFlow`.
- Diagnostics буферизуются и публикуются в UI батчами раз в 250 мс.
- Diagnostics сохраняются в SharedPreferences `ssh-vpn-connection-state`.
- Diagnostics ограничены 500 строками, 131 072 символами суммарного текста и 2 048 символами на запись; TUN destination metadata редактируется до persistence.
- Persistence diagnostics ограничена интервалом 15 секунд, кроме forced updates.
- При `setConnecting()` diagnostics очищаются.
- При `setDisconnected()` transport сбрасывается в `null`.
- При `setError()` transport сбрасывается в `null`.

UI учитывает и transport, и logical owner: Smart/OpenSource не показывают чужую Xray-сессию как собственную, но блокируют операции с общим native runtime до полного teardown.

## 12. Взаимоисключение VPN-сессий

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

`ConnectSmartVpnUseCase` валидирует routing, останавливает чужую logical VPN-сессию, сохраняет `desiredActive` и запускает `SmartConnectVpnService`. Process-wide `VpnRuntimeLeaseRegistry` не позволяет поздней команде одного logical owner вытеснить уже живой runtime другого owner; новая generation того же owner безопасно инвалидирует только его старую generation.

`DisconnectVpnUseCase` отправляет disconnect intent в сервис активного транспорта.

## 13. Android VPN interface

`VpnTunnelManager` создает Android TUN:

- Session name: `Secure connection`.
- MTU/mode: SSH `8500` + blocking I/O, Xray `1500` + nonblocking I/O.
- IPv4 для обоих режимов: address `10.10.0.2/32`, route `0.0.0.0/0`, DNS `1.1.1.1`/`8.8.8.8`.
- IPv6 только для Xray: address `fd00:10:10::2/128`, route `::/0`, Cloudflare/Google IPv6 DNS. SSH остаётся IPv4-only, поскольку Kotlin forwarder не реализует IPv6.
- Android Q+: `setMetered(false)`.
- TUN owner token не позволяет cleanup старого service/run закрыть новый interface.
- Физическая сеть задаётся через `setUnderlyingNetworks` и должна иметь `INTERNET + NOT_VPN`; `VALIDATED` предпочтителен, но не обязателен для cellular fallback.
- Split tunneling через `addAllowedApplication()` для `Selected apps`.

Один `VpnTunnelManager` общий для SSH, Smart Connect и OpenSource runtime; owner token и runtime lease защищают новый TUN от cleanup устаревшей команды.

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
- Использует `StrictHostKeyChecking=yes` и custom `HostKeyRepository`: configured fingerprint проверяется во время key exchange до user authentication.
- Увеличивает JSch `max_input_buffer_size` до 4 MiB; буфер стартует малым и растёт динамически.
- Настраивает server alive interval в диапазоне 15–300 секунд; при выключенном экране effective interval не менее 120 секунд и восстанавливается без reconnect после `SCREEN_ON`.
- Использует `VpnProtectedSocketFactory`: DNS выполняется выбранной физической сетью, затем socket проходит `bind -> protect -> connect` с общим deadline и fallback по A/AAAA адресам.

Fingerprint behavior:

- Фактический server host key fingerprint логируется.
- Если expected fingerprint пустой, совместимость сохраняется, но UI/diagnostics показывают явное предупреждение о непроверенной host identity.
- Поддерживаются OpenSSH SHA-256 и legacy MD5; сравнение digest выполняется constant-time.
- При mismatch authentication не запускается, возвращается `Fingerprint mismatch`.

## 15. Kotlin TUN forwarder

`KotlinTunForwarder` читает IPv4 packets из TUN и реализует forwarding на уровне приложения.

Поддерживается:

- TCP forwarding через SSH `direct-tcpip`.
- UDP DNS на port `53`.
- DNS-over-TCP через SSH.
- DNS-over-HTTPS fallback на Cloudflare `cloudflare-dns.com` / `1.1.1.1:443`, если DNS-over-TCP деградирует.
- VoIP UDP на Telegram-рефлекторы - relay поверх TCP (см. ниже).

### 15.1 VoIP UDP relay

SSH не умеет форвардить UDP: `direct-tcpip` - это только TCP. Из-за этого звонки Telegram
раньше не работали, а обычные приложения (HTTP/HTTPS) проблем не замечали.

Рефлекторы Telegram принимают тот же самый набор датаграмм по TCP, но не на UDP-медиапорту: SYN на
`598`/`599`/`1400` дропается и с SSH-сервера, и с посторонних сетей, а TCP-транспорт слушает `443`
(проверено на `91.108.9.4`, `.67`, `.101`, `.121`). Формат кадров - тот же, что пишет
`RawTcpSocket` в tgcalls: пролог `0xEEEEEEEE` и `uint32` little-endian длина на пакет. Forwarder
этим и пользуется:

- destination-IP проверяется по анонсированным Telegram префиксам (`TelegramNetworks`,
  источник - `core.telegram.org/resources/cidr.txt`);
- на каждый UDP flow (`client ip:port` -> `reflector ip:port`) открывается один SSH `direct-tcpip`
  канал на `reflector ip:443`; если `443` не ответил, вторым кандидатом пробуется исходный
  UDP-порт, а сработавший порт запоминается на хост до конца жизни SSH-транспорта;
- в канал один раз пишется пролог `0xEEEEEEEE`, дальше каждая датаграмма уходит как
  `uint32 little-endian длина + payload` без изменений: data-пакет рефлектора уже самодостаточен
  (`peer_tag | sender_tag | big-endian размер | payload`);
- 40-байтный UDP-ping рефлектора не пересылается как есть: каждый ping - и первый, и последующие
  keepalive - превращается в 20-байтный TCP hello (`peer_tag(16) | 00 00 00 00`). На TCP-стриме
  ping был бы прочитан как data-пакет с длиной `0xFFFFFFFF`;
- сразу после первого hello relay сам отдаёт клиенту этот ping обратно: tgcalls считает TCP-порт
  рефлектора готовым по факту установленного сокета, а клиент здесь работает по UDP-ветке, где
  готовность приходит только со входящим пакетом, несущим его `peer_tag`. Без этого ответа
  кандидат-рефлектор не используется, даже когда поток уже поднят;
- входящий поток разбирается обратно на кадры и отдаётся в TUN как UDP-датаграммы от
  `reflector ip:port`, то есть с исходного UDP-порта, а не с `443`;
- лимиты: `24` одновременных flow, uplink-очередь на flow `64 KiB` и `256` датаграмм, `16`
  датаграмм за один проход control-воркера, connect timeout `2 s` на кандидата, idle-таймаут
  `45 s` с проверкой каждые `15 s`, park назначения на `5` минут, если не ответил ни один
  TCP-порт;
- downlink пишется в TUN с ограниченным ожиданием `25 ms` и дропается вместо блокировки writer.

Ограничения:

- Произвольный non-DNS UDP (QUIC, STUN, игры, WireGuard) не проксируется и отбивается ICMP
  `port unreachable`. Отказ теперь адресный, а не общий rate-limit: первая датаграмма каждого
  потока (`client ip:port` -> `dst ip:port`) получает ICMP всегда, повторы - не чаще раза в
  секунду на поток, а общий token bucket (`64` пакета, refill `15 ms`) остался только как потолок
  против флуда, чтобы writer queue не переполнилась. Это важно для клиентов на `connect`-нутых
  UDP-сокетах (QUIC, многие игры): они видят `ECONNREFUSED` сразу и уходят на TCP, вместо того
  чтобы ждать свой хендшейк-таймаут.
- Отклонённые потоки логируются с портом и подписью (`443 (QUIC)`, `3478 (STUN/TURN)`), лимит
  строк `20`; раз в минуту пишется сводка вида `N unsupported flow(s) rejected in the last 60s;
  top ports: ...`, поэтому после исчерпания лимита всё ещё видно, что именно не проходит.
- Если ни один TCP-порт рефлектора не ответил, forwarder один раз за транспорт пишет вердикт:
  проблема в egress SSH-сервера, а не в приложении (те же хосты отвечают на `443` из других
  сетей). Звонки через такой сервер не поднимутся, остальной Telegram не затронут.
- Полноценный UDP (звонки не-Telegram, игры, QUIC) доступен только в режиме `opensource`: TUN-
  инбаунд Xray несёт TCP и UDP, а VLESS/VMess/Trojan-аутбаунд умеет UDP поверх своего протокола.
- Relay работает только для рефлекторов Telegram: у других протоколов нет TCP-транспорта с той же
  семантикой датаграмм.
- Если Telegram договорился о версии стека без reflector-поддержки, звонок всё равно может не
  подняться - это решается на стороне Telegram, не в приложении.
- Legacy-поле `enableUdpForwarding` осталось только для совместимости схемы; no-op переключатель
  удалён из UI, relay включён всегда.

Оптимизации forwarder:

- Отдельные bounded executors для control tasks, DNS и remote reads; deadline/cleanup scheduler queues логически ограничены числом активных операций, а отменённые futures удаляются через `removeOnCancelPolicy`.
- Blocking TUN использует один bounded writer; переполнение переводит forwarding layer в degraded/rebuild вместо неограниченного роста памяти.
- MSS вычисляется из TUN MTU (`8460` при MTU `8500`), TCP/IP packet строится без промежуточной копии payload.
- Во всех профилях JSch local channel receive window, outer SSH socket request и dynamic input-buffer ceiling равны 4 MiB; upload queue — 512 KiB на flow, TUN write queue — 256 пакетов. Это сохраняет минимум `2 × BDP` для 100 Мбит/с при RTT 106 мс и не урезает активную передачу в Battery Saver.
- Normal/Battery Saver/Android low-RAM уменьшают только session cap `128/64/32` и retained packet pool `64/32/32`. Профиль применяется при построении нового TUN pipeline.
- Upload queue управляет advertised TCP window. Любое отправленное нулевое окно остаётся sticky до отдельного положительного reopen ACK на актуальном sequence; обычный positive packet со старым sequence не может ошибочно снять latch. FIN закрывает SSH output после drain очереди.
- Принятые payload coalesce-ятся в максимум восемь блоков по 64 KiB при capacity 512 KiB; два завершённых блока кешируются для повторного использования. Byte capacity является единственным advertised limit, поэтому мелкие сегменты не сжимают окно раньше времени. Rejected/duplicate/zero-window payload проверяется до копирования.
- Upload flush пишет максимум один 64 KiB блок за control task и повторно ставит flow в хвост executor, сохраняя fairness между параллельными upload и корректный FIN drain.
- Outbound IPv4/TCP кеширует и переиспользует до 64 возвращённых полных MTU-буферов; буфер возвращается в cache только после TUN write/drop, а payload не создаёт отдельный packet-sized массив. Лимит 64 относится к retained cache, а не к общему числу transient allocations при backlog.
- `TcpPacketSender` имеет primitive JVM signature без `Function11` boxing; advertised window снимается под сериализованным outbound/session lock.
- TUN writer блокируется на `take()` и zero-window reader на `Condition.await()`, поэтому idle path не создаёт периодических wakeups.
- DNS query имеет hard timeout 10 секунд; timeout disconnects соответствующий channel.
- DNS timeout core threads завершаются после idle.
- Periodic session maintenance отсутствует: client/remote FIN cleanup планируется событийно, сохранённые futures отменяются при close/reschedule, pressure cleanup запускается single-flight только при превышении порога. Client-FIN cleanup использует 60 секунд именно бездействия после последней half-close активности, remote-FIN TTL — 30 секунд; cleanup worker также освобождает thread stack после 60 секунд idle.
- Лимит подробных diagnostic logs.
- TCP reset для stale sessions после wake recovery.

## 16. SSH reconnect и wake recovery

`SshVpnService` работает как foreground service и возвращает `START_NOT_STICKY`. Connect/disconnect сериализованы через lifecycle `Mutex`; монотонные command/run id, захваченный конкретным run process-wide runtime lease и service-owner identity отсекают устаревшие команды до захвата общих SSH/TUN/VPN managers. Terminal transition выполняется на main thread и меняет state/foreground только после успешного `stopSelfResult(startId)`, поэтому старый disconnect/failure не может остановить уже поставленный Android новый start. При disconnect SSH transport сначала закрывается на `Dispatchers.IO`, после чего отменённый connection job получает ограниченное время на завершение; `onDestroy` также ставит тяжёлый teardown в IO service scope. Xray runtime дополнительно привязан к generation, и stale cleanup может останавливать только generation своей попытки.

Reconnect:

- Initial delay: 250 мс.
- Max delay: 30000 мс.
- Backoff сбрасывается только после стабильного соединения минимум 30 секунд; короткие flapping-сессии не образуют hot reconnect loop.
- Если usable `INTERNET + NOT_VPN` physical network отсутствует, loop suspend-ится на `StateFlow` до network callback.
- Active monitor использует cadence 5 секунд при screen-on и 30 секунд при screen-off; handoff и screen events доставляются conflated signal немедленно.
- До первого успешного подключения unrecoverable auth/key/fingerprint ошибки переводят state в `ERROR`.
- После первого успешного подключения сервис старается восстановиться автоматически.
- Если TUN pipeline жив, SSH reconnect может пройти без пересоздания Android VPN interface.
- Если forwarder деградировал или остановился, pipeline пересоздается.

Wake recovery:

- Сервис регистрирует dynamic receiver на `SCREEN_OFF` и `SCREEN_ON`.
- Wake recovery запускается только если экран был выключен минимум 5 минут и после `SCREEN_ON` ждёт 2 секунды стабилизации сети.
- Wake recovery не захватывает собственный wake lock; во время screen-off нет дополнительного polling.
- После wake сначала выполняется короткий SSH transport health-check через `direct-tcpip` к `1.1.1.1:443`.
- Только если transport stale, сбрасываются TCP sessions с idle минимум 2 минуты и SSH session отключается, что запускает reconnect loop.

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
- Output хранится chunk-буфером максимум 65 536 символов и публикуется в UI не чаще раза в 250 мс.
- Shell закрывается при collapse панели, `Activity.ON_STOP`, disposal, disconnect или смене active transport; late callbacks отсекаются generation token.

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

- Максимальная длина одной ссылки: 65 536 символов.
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
- Fingerprint lookup и массовое удаление разбиваются на SQLite-пакеты максимум по 900 bind-параметров; delete/fallback остаются одной Room-транзакцией даже для импорта/выбора до 10000 профилей.

Public sync:

- HTTP GET к `OpenSourcePolicy.SOURCE_URL`.
- Accept: `text/plain`.
- User-Agent: `shadow-ssh-android-opensource-sync`.
- Timeout: connect 10 секунд, read 15 секунд.
- Response size limit: 2 MiB.
- Поддерживается ETag через `If-None-Match`, кроме forced refresh.
- Structured cancellation watcher вызывает `HttpURLConnection.disconnect()` при отмене, поэтому blocking `responseCode`/`read` не удерживает worker до сетевого timeout; normal/error path также всегда закрывает connection и watcher.

Background sync:

- Work name: `public-proxy-source-sync`.
- Periodic interval: 12 часов.
- Flex interval: 4 часа.
- WorkManager constraints: unmetered network, battery not low.
- Перед запросом worker независимо выбирает физическую сеть с `INTERNET + VALIDATED + NOT_VPN + NOT_METERED`; Android VPN, объявленная через `setMetered(false)`, не считается подходящим transport.
- HTTP открывается через `selectedPhysicalNetwork.openConnection(url)`, поэтому public sync не маршрутизируется обратно в VPN. Если подходящей физической сети нет, worker завершает текущий запуск успешно без retry.
- Retry backoff: exponential, старт 30 минут, только для I/O и transient HTTP 408/429/5xx; permanent 4xx/parser/import ошибки не повторяются в текущем запуске.
- Max retry: 3.
- Работает только если consent принят и auto-refresh включен пользователем.
- VPN runtime не держит ради sync собственный long-lived wake lock; WorkManager/Android могут использовать кратковременный управляемый wake lock во время исполнения worker.

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
- `Remove all unavailable tunnels except pinned`: confirmation, полный unfiltered count и атомарное удаление только `UNAVAILABLE && !isPinned` с cleanup encrypted secrets.
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

- `Check selected` передаёт выбранный profile в тот же batch pipeline, что и `Check all`; `Check all` bulk-загружает все profile secrets через repository batch API.
- На весь batch создаётся один Xray runtime. Это сохраняет требование pinned core «не более одного Server» и не запускает несколько process-global Xray instances.
- `XrayConfigBuilder` создаёт один authenticated SOCKS inbound на `127.0.0.1`, отдельный tagged outbound для каждого profile и точное routing rule `SOCKS username -> outbound tag`. Пароль batch генерируется криптографически случайно.
- Lightweight clients параллельно проходят SOCKS5 auth, выполняют `CONNECT www.youtube.com:443`, TLS hostname verification и `HEAD /generate_204`. SOCKS reply без последующего TLS/HTTP ответа не считается успешной проверкой.
- Timeout одного probe — до 5 секунд. Worker pool является transient, непрерывно занимает освободившиеся slots и имеет concurrency не выше 128; nominal limits составляют 64 slots в Battery Saver и 32 на Android low-RAM. Deadline floor может минимально поднять nominal limit для очень большого batch, чтобы все пятисекундные slots помещались в оставшийся 60-секундный budget. После завершения или отмены фоновых probe workers не остаётся.
- Для примерно 500 profiles целевой end-to-end результат — около 10 секунд. Внешний защитный budget составляет 60 секунд; profile, не получивший полноценное окно до hard deadline, возвращается как `NOT_TESTED`, а не `UNAVAILABLE`.
- Некорректный config изолируется рекурсивным делением batch: rejected profile получает `UNSUPPORTED`, а остальные продолжают проверяться, пока остаётся budget. Runtime-start, physical-network bind и общие локальные ошибки дают `NOT_TESTED`, чтобы не создавать массовый false-negative.
- Dialer file descriptors сначала проходят `VpnService.protect`, затем best-effort `Network.bindSocket` к выбранной physical network. Selection использует active physical -> sticky current -> validated fallback -> любую `INTERNET + NOT_VPN`, поэтому delayed cellular validation и старый Wi-Fi не создают VPN loop/ложный handoff.
- Callback каждого завершения обновляет live `Checking tunnels X/N`; count coalesce ограничивает Compose churn, не скрывая медленные завершения.
- Во время probes прежние persisted statuses сохраняются: промежуточные `RUNNING` rows не записываются. После batch все terminal results фиксируются одной Room-транзакцией с общим timestamp.
- Закреплённый native binding выполняет start/stop через blocking JNI без cancellable context. Поэтому цель около 10 секунд и защитный 60-секундный budget являются best-effort при аномально зависшем native-вызове; безопасно «убить» его из Kotlin нельзя без отдельного Android process или изменения libXray API.
- Checks запрещены, пока Xray runtime принадлежит VPN, включая disconnect teardown. Отмена закрывает активные Java sockets, затем дожидается обязательного native cleanup перед новым запуском.
- Итоговый message содержит elapsed time и counts `available`, `unavailable`, `unsupported`, `not tested`.
- Проверки автоматически отменяются при `Activity.ON_STOP` и disposal OpenSource route.

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

Batch SOCKS test config:

- Один inbound protocol `socks` слушает динамический порт только на `127.0.0.1`.
- Authentication: username/password; общий пароль криптографически случайный и живёт только во время batch.
- UDP выключен: каждый probe выполняет TCP CONNECT, TLS verification и HTTP HEAD.
- Для каждого profile создаются уникальные username, outbound tag и routing rule по authenticated user.
- Один rejected outbound изолируется от остальных profiles вместо падения всей проверки.

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

## 24. Smart Connect runtime

Smart Connect использует отдельные `SmartProxyProfileDao`/`RoomSmartProxyProfileRepository`, `IsolatedSmartProxySourceSynchronizer`, sync preferences и Tink secret ids. Профили, имя которых содержит `🇷🇺`, отбрасываются при импорте, удаляются из существующего каталога перед batch и дополнительно исключаются UI/selection policy.

Один пользовательский Start выполняет bounded workflow:

```text
refresh isolated source
  -> one-runtime check all through youtube.com
  -> atomic Room finalization (save + prune + select)
  -> connect lowest verified latency
  -> adaptive live health
  -> confirmed failure: refresh/check/prune/select replacement
```

- Общий budget refresh/check/finalization — 60 секунд; probe-stage оставляет 3 секунды для durable commit. Непроверенный хвост остаётся `NOT_TESTED`.
- До 128 transient probes используют один authenticated batch Xray runtime и timeout до 5 секунд на профиль. Progress публикуется монотонно и coalesced.
- All-negative/zero-result snapshot не очищает каталог. При массовом отказе выполняется control request через ту же захваченную physical network; без хотя бы одного текущего `AVAILABLE` destructive prune не выполняется.
- Результаты, удаление unavailable/stale и выбор winner объединены одной guarded Room-транзакцией. Если physical network/settings revision устарела, исключение откатывает всю транзакцию; secrets удаляются только после commit.
- HTTP sync, batch probes, DNS и live Xray sockets используют один захваченный `Network`; dialer fd проходит `VpnService.protect()` и best-effort `Network.bindSocket()`. Поэтому мобильная сеть не попадает обратно в TUN и не зависит от Wi‑Fi route/DNS.
- До открытия дополнительного live-health соединения проверяется активный или недавний RX. Если payload движется, probe вообще не создаётся: это защищает длинный download от публичных proxy с лимитом одновременных streams.
- Первый tunnel проверяется двумя отрицательными YouTube probes с паузой 2 секунды. Уже подтверждённый tunnel переключается только после не менее трёх отрицательных раундов и 30 секунд непрерывной ошибки. Stop, handoff и routing revision проверяются после confirmation и прямо перед durable `UNAVAILABLE`, поэтому инфраструктурная гонка не отравляет профиль.
- Непрерывный UID RX и 45-секундное recent-RX окно не разрешают health-check разрушить активное скачивание; device-only fallback ограничен 15 минутами, TX-only — 5 минутами. Это учитывает Android VPN accounting, но не позволяет трафику исключённого приложения скрывать мёртвый tunnel бесконечно; polling/wake lock не используются.
- Profile после подтверждённого health failure получает 15-минутный monotonic cooldown, после повторной runtime-ошибки — 2-минутный. Cooldown profiles не пробуются batch-ом и не участвуют в выборе, поэтому клиент не переключается по кругу A → B → A.
- Cadence здорового tunnel: 10 секунд в первую минуту, затем 30 секунд при активном экране, 120 секунд screen-off и 300 секунд в Battery Saver. После первого failure подтверждающие раунды временно идут раз в 10 секунд. Retry backoff: 30/60/120 секунд, 5/15 минут, но ожидание сокращается до ближайшего окончания profile cooldown.
- Persisted `desiredActive` восстанавливается при входе во вкладку, если VPN permission сохранён; без permission stale flag очищается и UI просит явный Start.

## 25. OpenSource VPN runtime

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
- Lifecycle-команды сериализуются через `Mutex` и защищаются command/run id; остановка native core разблокирует connection loop до ожидания его завершения, а teardown выполняется в IO service scope.
- Blocking native `runFromJson`/`stopXray` дополнительно проходят через один reentrant lifecycle gate. Disconnect ждёт завершения текущего native start, а отменённый start перед освобождением gate останавливает свою generation; поздно стартовавший unowned core остаться не может.
- Проверяет выбранный profile.
- Проверяет наличие Xray core.
- Проверяет selected apps.
- Создает Android VPN interface.
- Регистрирует один стабильный socket-protector controller на binding и при reconnect заменяет только текущий delegate, чтобы native controller list не накапливал Java Proxy/старые service closures.
- Для каждого запуска берёт DNS endpoint из `LinkProperties` выбранной physical network и вызывает Android `libXray.initDns` через тот же protected dialer controller; cleanup вызывает `resetDns`.
- Dialer и listener используют разные delegates: outbound проходит protect + best-effort bind, listener только `protect`.
- Передает TUN fd в Xray binding.
- Запускает Xray из inline JSON config.
- Мониторит `xrayCoreBridge.isRunning()` и generation-scoped socket-routing failure каждые 10 секунд при screen-on и 30 секунд при screen-off; screen/network/protector event прерывает ожидание conflated signal.
- При unexpected stop запускает reconnect loop.
- При отсутствии usable `INTERNET + NOT_VPN` physical network suspend-ится до callback без reconnect polling.

Reconnect:

- Initial delay: 250 мс.
- Max delay: 30000 мс.
- На ошибке service чистит Xray runtime и Android VPN interface.

## 26. Обновление приложения

Основные классы:

- `GitHubAppUpdateRepository`.
- `AndroidAppUpdateDownloader`.
- Общий `AppUpdateUiState` и одинаковый update UI используются на вкладках SSH,
  OpenSource и Smart Connect; каждая вкладка наблюдает единый process-wide downloader.

Release check:

- GitHub latest release API: `https://api.github.com/repos/stansful/ssh-vpn-client-kotlin/releases/latest`.
- Strict SemVer для tag/version.
- Автоматическая проверка запускается через 1500 мс при инициализации `MainViewModel` на SSH главной.
- Успешная автоматическая проверка кешируется на 24 часа.
- Manual check force-режим.
- Ручная кнопка `Check for updates` доступна и в Smart Connect settings; это обновление
  приложения, отдельное от расположенного там же обновления Xray runtime core.
- Поддерживается ETag/304.
- Network выбирается как validated non-VPN, если доступен.

APK asset selection:

- Предпочитается APK для первого supported ABI устройства.
- Если ABI asset не найден, fallback на universal APK.
- Сохраняется совместимость со старым single APK release.

Download:

- Используется app-owned resumable `HttpURLConnection` downloader: системный DownloadManager
  намеренно не используется, поскольку его отдельный UID может остаться в
  `WAITING_FOR_NETWORK` при активном full-tunnel VPN.
- `ValidatedPhysicalNetworkSelector` перебирает `ConnectivityManager.allNetworks`, исключает VPN
  и выбирает validated Ethernet/Wi-Fi/cellular. Загрузчик делает до четырёх ограниченных попыток,
  начиная с app-owned default route (через SSH, если он активен) и чередуя его с заново выбранной физической сетью,
  поэтому переживает Wi-Fi/cellular handoff и блокировку прямого маршрута.
- Redirects обрабатываются вручную: только HTTPS, не более пяти переходов и только GitHub/
  `*.githubusercontent.com`.
- Destination: external app-specific Downloads `/updates`.
- Незавершённый файл хранится как `.part`; HTTP Range + If-Range позволяют продолжить загрузку
  после обрыва сети или перезапуска процесса. После исчерпания автоматических попыток UI показывает
  `Resume update download`, не требуя повторного запроса GitHub release metadata.
- Проверяются опубликованный размер APK и hard limit 512 MiB.
- Показывается progress: downloaded bytes, total bytes и percent.

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

## 27. Сборка и release artifacts

Основные scripts:

- `scripts/test.sh` - unit tests.
- `scripts/lint.sh` - lint.
- `scripts/build-debug.sh` - debug APK.
- `scripts/build-release.sh` - release APK.
- `scripts/build-xray-core.sh` - pinned build `libXray.aar`.
- `scripts/package-xray-core-assets.sh` - ABI-specific Xray core release assets.

Release APK:

- `appVersionName = 2.5.6`.
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

## 28. Manifest и permissions

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

## 29. Безопасность и privacy

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
- Configured JSch host key pin проверяется до authentication; профиль без pin остаётся совместимым, но явно помечается небезопасным.
- Если fingerprint не задан, SSH host authenticity не закреплена.
- Xray runtime core доверяется release repository и Android package sandbox, но это исполняемый native code.

## 30. Производительность и батарея

Текущие меры:

- Xray core вынесен из APK и скачивается по ABI.
- Release build включает R8 и resource shrinking.
- PackageManager app list кешируется на 5 минут.
- Package icons декодируются максимум двумя параллельными `Dispatchers.IO` задачами, одинаковые запросы объединяются single-flight, результат хранится в LRU до 4 MiB с TTL 5 минут и повторно используется после закрытия app picker.
- Public proxy import получает существующие fingerprints batch-запросами, staging secrets делает одним durable Tink commit, а Room switch выполняет одной транзакцией.
- Public config auto-refresh выключен по умолчанию; если пользователь включает его, WorkManager запускается раз в 12 часов с flex 4 часа и constraints `network unmetered` + `battery not low`. Worker дополнительно требует физическую `VALIDATED + NOT_VPN + NOT_METERED` сеть и открывает HTTP через `Network.openConnection`.
- VPN runtime не держит собственный long-lived wake/wifi lock. WorkManager/Android могут использовать кратковременный управляемый wake lock на время worker.
- SSH wake recovery event-driven: только screen on/off receiver во время foreground service, один probe после сна от 5 минут.
- SSH/Xray local monitor замедляется при screen-off; SSH keepalive увеличивается минимум до 120 секунд.
- TUN writer и zero-window TCP используют blocking wait; periodic session maintenance отсутствует.
- Outbound TCP кеширует до 64 возвращённых MTU-буферов в normal и до 32 в Battery Saver/low-RAM; sender не выполняет primitive boxing. Профили используют соответственно 128/64/32 flow, но одинаковые bounded 512 KiB upload queues и TUN queue 256. Retained-pool cap не является пределом transient allocations при backlog.
- Diagnostics и terminal output публикуются в UI батчами раз в 250 мс; terminal ring ограничен 65 536 символами и имеет отдельный revision для auto-scroll после заполнения.
- Diagnostics persistence throttled до 15 секунд.
- Xray/OpenSource checks используют один native runtime и bounded transient pool до 128 authenticated SOCKS/TLS probes с timeout до 5 секунд. Для примерно 500 profiles pipeline целится примерно в 10 секунд при защитном 60-секундном budget, публикует live coalesced progress и сохраняет terminal results одной Room-транзакцией без `RUNNING`. Проверки не пересекаются с Xray VPN runtime.
- Smart Connect не держит WorkManager/alarm/wake lock: health живёт только внутри foreground VPN service, интервалы адаптируются к экрану/Battery Saver, а network/settings события будят conflated channel.
- Перед Smart failover и SSH rebuild RX/TX counters защищают активные передачи: UID RX откладывает teardown без искусственного лимита, device-only fallback — максимум на 15 минут, TX-only — на 5 минут.
- Smart и OpenSource updater core используют process-wide single-flight mutex для общих `.tmp`/target files; уже проверенный asset переиспользуется после ожидания.
- ViewModel flows используют `SharingStarted.WhileSubscribed(5_000)` там, где это подходит UI.

Потенциально дорогие операции:

- `Check all` может кратковременно открыть до 128 параллельных local SOCKS/TLS probes и соответствующих Xray outbound connections.
- Xray tunnel checks выполняют реальный network probe через один temporary authenticated SOCKS inbound; после операции workers и runtime закрываются.
- Package icon rendering в app picker идет из PackageManager, но concurrency ограничен и bitmap кешируется bounded LRU.
- Kotlin TUN forwarder держит worker pools, пока активен SSH VPN.

## 31. Локализация

Текущая база:

- Часть строк вынесена в `app/src/main/res/values/strings.xml`.
- `strings.xml` сейчас содержит английские строки для notifications, QS tile, OpenSource warning, diagnostics labels и settings labels.

Текущее ограничение:

- В Compose-коде все еще есть hardcoded English strings.

Рекомендуемый контракт для дальнейшей локализации:

- Новый пользовательский текст добавлять в `strings.xml`.
- Для новых языков добавлять `values-<locale>/strings.xml`.
- ViewModel status messages, которые показываются пользователю, тоже постепенно выносить в UI/resource layer или в отдельный message abstraction, чтобы не смешивать domain/runtime и language text.

## 32. Тестовая поверхность

Точное количество тестов в документации намеренно не фиксируется: источником истины служит отчёт актуального запуска `scripts/test.sh`.

Основные unit test suites:

- `ProxyShareLinkParserTest` - parser VLESS/VMess/Trojan, limits, failures.
- `XrayConfigBuilderTest` - генерация Xray JSON.
- `GitHubAppUpdateRepositoryTest` - выбор APK asset по ABI/universal fallback.
- `AndroidAbiTest` - runtime ABI и asset matching.
- `SemanticVersionTest` - SemVer parsing/comparison.
- `AppUpdateDownloadStateTest` - progress state.
- `SshPrivateKeyValidatorTest` - private key validation.
- `WakeRecoveryPolicyTest` - screen off/on policy.
- `ReconnectBackoffTest` - exponential backoff.
- `ConnectionPowerPolicyTest` - cadence, network handoff и stable-connection backoff policy.
- `TunPacketWriterTest` - ownership/recycle pooled packets и корректная длина TUN write.
- `CoalescedActivityTimestampTest` - ограничение частоты activity timestamp updates.
- `TunForwarderConfigTest` - normal/low-RAM flow limits и pressure thresholds.
- `BoundedTerminalOutputBufferTest` - ограничение terminal output по символам и chunks.
- `ProxySourceSyncNetworkSelectionTest` - выбор физической validated non-VPN unmetered сети для background sync.
- `SmartConnectPolicyTest`/`SmartConnectViewModelPolicyTest` - ranking, `🇷🇺` exclusion, deadlines и terminal-result accumulation.
- `VpnTrafficActivityMonitorTest` - RX/TX liveness policy для длинных download/upload.
- `VpnRuntimeLeaseRegistryTest`/`VpnLifecyclePolicyTest` - logical owner isolation и stale command races.
- `RoomSmartProxyProfileRepositoryBatchTest` - isolated secrets, batch queries и guarded atomic finalization.
- `XrayCoreDownloadGateTest` - process-wide single-flight core downloads.

Что не покрыто автоматикой:

- Реальное Android `VpnService` поведение.
- Реальный TUN packet forwarding на устройстве.
- Реальный Xray native runtime start/stop на устройстве.
- Android installer/unknown sources OEM screens.
- QS tile на разных Android версиях.

## 33. Основные известные ограничения

- SSH режим полноценно проксирует TCP и DNS, плюс VoIP UDP на Telegram-рефлекторы поверх TCP.
  Произвольный UDP (QUIC, STUN, игры) не поддержан и отбивается rate-limited ICMP.
- Legacy `enableUdpForwarding` не показывается в UI и не означает full UDP forwarding.
- OpenSource зависит от скачанного Xray runtime core. Без core подключение заблокировано.
- Smart Connect также зависит от Xray core, но установить/обновить его можно прямо из Smart settings без перехода в OpenSource.
- Обновление уже загруженного Xray native core может требовать restart приложения.
- Public source может отдавать stale/unsupported configs. Они импортируются с metadata и помечаются status checks.
- При реальном обрыве physical network или proxy уже существующий TCP download невозможно бесшовно перенести на другой tunnel; приложение избегает ложного teardown при живом RX, но окончательное продолжение реального разрыва зависит от HTTP Range/resume сервера и браузера.
- Автоматический public sync не должен запускаться без consent и отключается настройкой auto-refresh.
- App updater зависит от GitHub releases и корректной публикации APK assets.

## 34. Типовые runtime сценарии

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
7. Выбирается active/sticky physical `INTERNET + NOT_VPN` network и её DNS из `LinkProperties`.
8. Android TUN interface создается через `VpnTunnelManager` с IPv4+IPv6 routes.
9. Xray binding получает protected dialer, listener protector, physical DNS, TUN fd и JSON config.
10. `VpnConnectionRepository` публикует `CONNECTED` с `activeTransport = XRAY`; поздний socket-protection failure будит monitor и запускает reconnect.

### Автоматическое подключение Smart Connect

1. Пользователь нажимает центральную кнопку Start; `ConnectSmartVpnUseCase` сохраняет `desiredActive` и получает logical runtime lease.
2. `SmartConnectVpnService` ждёт usable physical `NOT_VPN` network без polling.
3. Изолированный источник обновляется; `🇷🇺` и повреждённые secret rows удаляются до проверки.
4. Один batch проверяет все свежие профили через YouTube, после чего guarded Room-транзакция фиксирует только terminal results, prune и winner.
5. Минимальный подтверждённый ping запускается как Xray TUN и повторно проверяется live endpoint.
6. При двух отрицательных probes и отсутствии активного RX профиль помечается unavailable; сервис повторяет полный цикл и выбирает замену.
7. Handoff/settings/Stop инвалидируют текущую revision и никогда не превращаются в profile failure.

### Refresh public configs

1. Пользователь нажимает refresh или worker запускается по расписанию.
2. `PublicProxySourceSynchronizer` скачивает source.
3. `ProxyShareLinkParser` парсит конфиги.
4. `RoomProxyProfileRepository` делает upsert по fingerprint.
5. Старые remote profiles источника помечаются stale.
6. Выбор active profile нормализуется через `ensureSelection()`.

### Check all OpenSource configs

1. `OpenSourceViewModel.checkAll()` берёт полный набор profile ids независимо от search/filter.
2. Repository одним batch загружает Room rows и расшифровывает raw URIs.
3. Один временный Xray runtime создаёт authenticated SOCKS inbound и отдельный user -> outbound route для каждого profile.
4. Bounded worker pool параллельно выполняет SOCKS/TLS/HTTP probes до 5 секунд и публикует live completed/total.
5. Terminal results сохраняются одной Room-транзакцией; revision fingerprint не позволяет результату старого URI обновить отредактированный profile.
6. Normal target для ~500 profiles — около 10 секунд, safety budget — 60 секунд; непроверенный хвост остаётся `NOT_TESTED`.

### Remove unavailable OpenSource tunnels

1. UI считает `UNAVAILABLE && !isPinned` по полному unfiltered списку и показывает кнопку только при count > 0.
2. После confirmation ViewModel блокирует concurrent check/sync/cleanup.
3. Room-транзакция повторно выбирает подходящие rows, удаляет их пакетами до 900 ids и выбирает fallback, если active row удалён.
4. Соответствующие Tink secrets очищаются best-effort; pinned и все статусы кроме `UNAVAILABLE` сохраняются.

## 35. Правила для будущих изменений

- Любой новый VPN state должен учитывать `activeTransport` и `sessionOwner`, иначе Smart/OpenSource UI снова начнут читать чужое состояние.
- Любое изменение split tunneling должно проходить через общий `AppSettings.vpnMode` и `selectedAppPackages`.
- Raw credentials и raw proxy URI нельзя хранить в Room или логах.
- Новые OpenSource operations должны уважать pinned behavior: pinned не перемещается автоматически и не попадает в bulk select all.
- Smart catalog и secrets нельзя объединять с OpenSource: синхронизация, selection, cleanup и test statuses должны оставаться раздельными.
- Новые background задачи должны иметь battery/network constraints и не держать wake lock без отдельного обоснования.
- Любой новый release asset selection должен сохранять ABI-specific preference и universal fallback.
- Новые пользовательские строки нужно выносить в resources, чтобы не ухудшать будущую локализацию.
