# shadow-ssh

Native Android VPN client на Kotlin + Jetpack Compose. Приложение поднимает Android `VpnService`, подключается к SSH-серверу и проксирует трафик приложений через SSH `direct-tcpip` каналы.

## setup / description / fast start
1. Скачать [android studio](https://developer.android.com/studio?hl=ru), открыть проект, установить зависимости (ide по умолчанию это уже делает)
2. Создать в корне файл local.properties пример содержимого находится в файле [local.properties.example](local.properties.example)
3. Запустить скрипт [build-debug.sh](scripts/build-debug.sh), для [build-release.sh](scripts/build-release.sh) нужно в корне создать файлы из скриншота по пути docs/img.png (нейросеть в помощь)
4. В проекте не было написано ни единой строчки кода разработчиком, всё делал codex в связке с chatgpt-5.5 с высоким reasoning
5. На проект ушло примерно 6 часов, 4 из которых это просмотр youtube, остальные 2 часа потрачены написание простых запросов в стиле `Добавь кнопку с подключением / перекрась / сломалось это / давай добавим логи для дебага / нужен новый функционал Х` а также запуск на мобилке и ручное тестирование функционала
6. Цель проекта была простая - создать vpn тунель через ssh соединение, а также убедится что программирование в том виде в котором мы знаем умерло С:
7. Для создания базового функционала приложения потребовался вот такой промт:
```sh
Твоя задача помочь мне в написании тз для вайбкодинга =)
Я хочу написать приложения для андройда, 
суть приложения в том что оно создаёт vpn соединение используя стандартный ssh протокол 
для mvp достаточно только ssh соединения с массивом соединений 
а также выбором/добавлением конфигураций - основные параметры ssh соединения: 
адрес порт пользователь закрытый ключ / пароль отпечаток keepalive udp-переадресация примечание(оно будет текcтом на ui)
```

## Что умеет

- SSH VPN через password или private key.
- Password, private key content и passphrase скрыты звёздочками по умолчанию и раскрываются кнопкой глаза; private key/passphrase можно скопировать отдельной кнопкой.
- CRUD для SSH-конфигураций и приватных SSH-ключей.
- Переиспользование одного SSH-ключа в нескольких конфигурациях.
- Проверка SSH host fingerprint во время key exchange, до отправки password/private-key auth, если fingerprint указан в конфигурации.
- SSH keepalive и автоматический fast reconnect при обрыве до явного Disconnect:
  - Android VPN interface и маршруты сохраняются во время переподключения SSH;
  - после стабильного соединения первый reconnect запускается сразу; короткие/flapping попытки используют bounded backoff от 250 ms до 30 s;
  - при отсутствии `VALIDATED + NOT_VPN` сети reconnect засыпает до системного network callback;
  - полный VPN rebuild используется только как fallback, если TUN forwarding layer недоступен.
- Split tunneling:
  - `Proxy` - через туннель идут все приложения;
  - `Selected apps` - через туннель идут только выбранные приложения.
- Выбор приложений с поиском, чекбоксами и системными приложениями.
- Quick Settings tile `shadow-ssh` для Connect / Disconnect из шторки Android.
- Кнопка `Check tunnel`, которая проверяет доступность `youtube.com:443` через SSH-туннель.
- Диагностические логи подключения:
  - по умолчанию скрыты;
  - включаются в Settings;
  - раскрываются спойлером;
  - копируются в clipboard;
  - хранят bounded ring до 500 строк / 131 072 символов и сбрасываются при новом пользовательском Connect.
- SSH terminal:
  - выключен по умолчанию и включается persisted-переключателем в Settings;
  - доступен при активном подключении;
  - открывает shell-channel на текущей SSH-сессии;
  - команды отправляются с фонового IO-потока;
  - команды и remote output не пишутся в diagnostics.
- Глобальные вкладки:
  - `shadow-ssh` - основной SSH VPN режим;
  - `smart` (`Smart Connect`) - полностью автоматический выбор и восстановление публичного Xray-туннеля;
  - `opensource` - импорт и запуск публичных VLESS/VMess/Trojan конфигураций через Xray-core;
  - активная вкладка сохраняется после перезапуска.
- `opensource` режим:
  - перед первым входом показывает предупреждение о рисках публичных конфигураций;
  - риск-баннер всегда остаётся на экране вкладки;
  - автообновление публичного списка выключено по умолчанию; если включить его в настройках, WorkManager ждёт unmetered-сеть и не низкий заряд, а сам worker дополнительно выбирает физическую `VALIDATED + NOT_VPN + NOT_METERED` сеть;
  - умеет manual refresh, bulk import из clipboard, add/edit/delete/copy конфигураций;
  - убирает дубли по canonical fingerprint;
  - поддерживает выбор активного профиля, multi-select, select all и массовое удаление;
  - проверяет выбранный профиль или все профили запросом к YouTube через Xray;
  - подключает выбранный профиль отдельным Android `VpnService`.
- `Smart Connect` режим:
  - хранит каталог, выбор, test status и Tink secrets отдельно от `opensource`;
  - по нажатию выполняет refresh -> check all -> удаление unavailable/stale (кроме pinned) -> выбор минимального ping -> подключение;
  - никогда не импортирует, не показывает и не выбирает профили, имя которых содержит `🇷🇺`;
  - принимает рабочим только tunnel, получивший HTTP `2xx` от YouTube health endpoint;
  - подтверждает отказ двумя проверками с паузой 2 секунды и только затем запускает полный failover-цикл;
  - после исчерпания списка повторяет синхронизацию с bounded задержками `30 s -> 60 s -> 120 s -> 5 min -> 15 min`;
  - использует отдельный foreground `VpnService`, переживает уход UI в background и восстанавливает желаемое состояние после пересоздания процесса Android.
- Темы:
  - `System` по умолчанию;
  - `Light`;
  - `Dark` в black/orange стиле;
  - `Custom` с RGB-настройкой цветов, которые сохраняются после перезапуска.
- Ссылка на GitHub в Settings с кнопкой копирования.
- Автоматическая проверка GitHub Releases не чаще раза в 24 часа и ручная кнопка `Check for updates`.
- Обновление через системный DownloadManager и стандартный Android installer с in-app прогрессом, восстановлением кнопки `Install` после перезапуска и проверкой SHA-256, package name, SemVer, versionCode и signing certificate.
- Release APK собирается installable и локально подписанным, если production signing env не задан.

## Сетевой поток

```text
Selected Android apps / all apps
        |
        v
Android VpnService TUN interface
        |
        v
In-app Kotlin TUN forwarder
        |
        v
Protected SSH socket outside VPN routing
        |
        v
SSH server
        |
        v
Target websites / services
```

TCP-трафик из TUN проксируется через SSH `direct-tcpip`. DNS-запросы VPN обрабатываются как DNS-over-TCP через SSH. Произвольный non-DNS UDP сейчас не проксируется и отбрасывается локальным forwarding layer. Для SSH режима используется blocking TUN с MTU `8500`/MSS `8460`; Xray сохраняет собственный MTU `1500`.

## OpenSource / Xray поток

```text
Selected Android apps / all apps
        |
        v
Android VpnService TUN interface
        |
        v
Official Xray-core Android binding
        |
        v
Selected VLESS / VMess / Trojan public profile
        |
        v
Target websites / services
```

OpenSource TUN работает в dual-stack режиме: IPv4 и IPv6 default routes/DNS включаются только для Xray, тогда как IPv4-only SSH forwarder сохраняет прежний контракт. Physical network выбирается по политике active -> current -> validated fallback -> доступная `INTERNET + NOT_VPN`, поэтому cellular не блокируется во время задержки Android validation и не заменяется старым Wi-Fi после создания VPN. Перед запуском core libXray DNS инициализируется DNS-сервером из `LinkProperties` выбранной сети; dialer fd сначала проходит `VpnService.protect`, затем best-effort `Network.bindSocket`, что исключает возврат VLESS socket обратно в TUN.

Xray-core собирается из исходников официального `XTLS/libXray` с закреплённым commit:

```text
libXray: 9bb7cad11a225f1039274dc8afd9810bcf458038
Xray-core: 94ffd50060f1cfd5d7482ec90a23a92bdefdff68
gomobile: v0.0.0-20260611195102-4dd8f1dbf5d2
```

Публичный источник конфигураций:

```text
https://hub.mos.ru/zieng2/wl/raw/main/list_universal.txt
```

Автосинхронизация выключена по умолчанию. Если пользователь включает её в настройках, она планируется через WorkManager каждые 12 часов с flex-окном 4 часа, только после согласия пользователя, при не низком заряде и доступной unmetered-сети. Перед HTTP-запросом worker отдельно выбирает физическую сеть с `INTERNET + VALIDATED + NOT_VPN + NOT_METERED` и открывает соединение через `Network.openConnection`; Android VPN, объявленную как unmetered, worker отфильтровывает. Если подходящей физической сети нет, текущий sync пропускается без retry. Exponential retry от 30 минут применяется только к I/O, HTTP 408/429 и 5xx; постоянные 4xx, oversized/invalid payload и ошибки import повторно устройство не будят. VPN runtime не держит для этой задачи собственный long-lived wake lock, но WorkManager/Android могут кратковременно использовать управляемый ими wake lock на время фактического выполнения worker.

Поддерживаемые share links: `vless://`, `vmess://`, `trojan://`. Parser сохраняет исходную ссылку в Tink-backed secret storage, а в Room кладёт только metadata и fingerprint.

Smart Connect выполняет HTTP refresh через выбранный физический `Network`, передаёт тот же network в batch Xray probes и затем привязывает к нему live tunnel sockets. Это не даёт проверке случайно уйти через старый validated Wi-Fi, когда фактическим транспортом уже стала мобильная сеть. При непустом локальном каталоге используется conditional ETag refresh; полный payload принудительно скачивается только когда свежих локальных кандидатов нет.

## Fast reconnect

После обнаружения разрыва приложение оставляет Android `VpnService` TUN interface поднятым, приостанавливает только SSH transport и сразу начинает новый SSH handshake. После успешной аутентификации работающий Kotlin forwarder получает новую JSch `Session` без пересоздания VPN interface.

SSH DNS и socket явно привязываются к выбранной Android `INTERNET + NOT_VPN` физической сети до `connect()`; validated сеть предпочтительна, но cellular остаётся допустимым fallback. При переключении Wi-Fi/mobile сервис обновляет `underlyingNetworks` и создаёт SSH transport через новую сеть: существующий TCP socket мигрировать между сетями нельзя.

Уже существующие TCP/TLS flow нельзя перенести между двумя SSH-сессиями: они закрываются и переоткрываются самими приложениями. Новые TCP SYN во время короткого reconnect не отклоняются сразу, чтобы Android мог повторить SYN после восстановления transport.

Параметры восстановления:

- local health monitor: 5 секунд при включённом экране и 30 секунд при выключенном; network handoff будит monitor сигналом сразу;
- effective SSH keepalive: настройка профиля в диапазоне 15–300 секунд, не менее 120 секунд при выключенном экране; reconnect требует три подряд пропущенных keepalive-ответа, чтобы краткий Wi-Fi jitter не рвал download;
- после стабильного соединения от 30 секунд первый retry идёт без искусственной задержки; короткие flapping-сессии продолжают exponential backoff;
- connect timeout повторной попытки: 8 секунд;
- повторные неудачи: `250 ms -> 500 ms -> ... -> 30 s`;
- без usable `INTERNET + NOT_VPN` physical network сервис ждёт callback вместо периодических попыток подключения;
- если TUN forwarder или VPN interface потерян, выполняется полный rebuild pipeline.

После блокировки не менее 5 минут приложение ждёт 2 секунды для стабилизации Wi-Fi/mobile и один раз проверяет SSH transport. Только если transport действительно неисправен, forwarder отправляет RST TCP-сессиям с idle не менее 2 минут и запускает reconnect. Исправные соединения после обычного короткого сна не трогаются.

Wake recovery основан на системных `SCREEN_OFF/SCREEN_ON` событиях: сам механизм не запускает во время сна дополнительный polling/ping и не захватывает собственный wake lock; единственный probe выполняется уже после пробуждения.

Если SSH transport жив, но TUN/DNS слой начинает деградировать, обычный `Check tunnel` может оставаться успешным, потому что он открывает прямой SSH `direct-tcpip` канал и не проходит через Android TUN. Forwarder отдельно отслеживает DNS failures: сначала пробует DNS-over-TCP через SSH, затем DoH fallback к Cloudflare через SSH на `443`. После серии DNS/TUN failures сервис пересобирает Android VPN interface и Kotlin forwarder без force stop приложения.

## Производительность и потоки

- Контейнер зависимостей ленивый: Room, Tink, PackageManager и VPN-компоненты создаются только при первом использовании. Для первого кадра синхронно загружаются только небольшие UI settings.
- Room-запросы, Tink/Android Keystore и legacy migration выполняются на `Dispatchers.IO`.
- Главный экран и списки используют metadata-only Room projections: passwords, private keys и passphrases не расшифровываются для отображения карточек.
- Usage count SSH-ключей вычисляется одним `LEFT JOIN + COUNT`, без N+1 запросов.
- Compose собирает `Flow` через `collectAsStateWithLifecycle`, поэтому неактивные экраны не держат лишние collectors.
- Список установленных приложений кэшируется на 5 минут; поиск дебаунсится на 200 ms и фильтруется на `Dispatchers.Default`.
- Diagnostics восстанавливаются и сериализуются вне Main thread, поступающие строки публикуются в UI пакетами раз в 250 ms, а раскрытый список виртуализирован.
- SSH terminal использует lifecycle-bound coroutine scope на `Dispatchers.IO`; вывод читается пакетами до 32 KiB, хранится chunk-ring до 65 536 символов и публикуется не чаще раза в 250 ms. Shell закрывается при collapse, уходе экрана в background или disposal; JSch channel закрывается асинхронно вне Main thread.
- VPN connection loop выполняется на `Dispatchers.IO`. В production-коде нет `GlobalScope` и `runBlocking`.
- DNS forwarding использует fallback на DoH через SSH, чтобы не зависеть только от TCP/53 на стороне сервера/сети. Серия DNS-ошибок остаётся локальной для запросов и сама по себе не перестраивает весь VPN; восстановление SSH определяется состоянием транспорта.
- Во всех режимах SSH `direct-tcpip` receive window равен 4 MiB; внешний SSH socket и динамический JSch input buffer также допускают до 4 MiB, сохраняя `TCP_NODELAY` и `SO_KEEPALIVE`. Это оставляет минимум двукратный BDP-запас для 100 Мбит/с при RTT 106 мс.
- Upload каждого TCP flow имеет bounded 512 KiB очередь и TCP backpressure; принятые мелкие payload coalesce-ятся максимум в восемь блоков по 64 KiB, два завершённых блока переиспользуются. Один flow обрабатывает не более одного блока за control task, поэтому параллельные upload не монополизируют executor. Sticky zero-window tracker требует отдельный актуальный reopen ACK и не теряет его из-за пакета со старым sequence. FIN закрывает SSH output только после drain подтверждённого хвоста. Normal/Battery Saver/low-RAM ограничивают число flow соответственно 128/64/32, не уменьшая transport window или upload capacity.
- TUN output проходит через один bounded writer с blocking `take()` без idle wakeups. Краткий пик очереди ждёт до 5 секунд, не уничтожая весь forwarder после прежнего односекундного провала. Пул кеширует для повторного использования до 64 возвращённых полных MTU-буферов (~531 KiB при MTU 8500); это предел retained cache, а не абсолютный предел одновременно выделенных буферов при нагрузке. Packet sender имеет primitive JVM signature без boxing, cleanup запускается только по FIN/давлению.
- DNS использует отдельный bounded executor и hard timeout 10 секунд; idle timeout threads завершаются автоматически. FIN cleanup futures отменяются при раннем закрытии flow и удаляются из scheduler queue.
- Иконки PackageManager декодируются максимум двумя параллельными задачами с single-flight и хранятся в LRU до 4 MiB; cache очищается при уходе с app picker. Большие proxy imports используют batch Tink, SQLite `IN`-пакеты максимум по 900 id и одну Room-транзакцию.
- Поиск public profiles дебаунсится и фильтруется на `Dispatchers.Default`. `Check all` одним batch загружает профили и поднимает один временный Xray runtime с authenticated SOCKS inbound только на `127.0.0.1`. Каждому профилю назначаются уникальные SOCKS username и outbound route, поэтому параллельные probes не смешивают конфигурации.
- OpenSource action `Remove all unavailable tunnels except pinned` атомарно удаляет только `UNAVAILABLE && !isPinned`, очищает соответствующие encrypted secrets и сохраняет selected fallback; count берётся из полного списка независимо от UI-фильтра.
- Network probes выполняются только во время foreground-проверки с transient concurrency до 128 и timeout до 5 секунд на профиль. Battery Saver использует nominal cap 64, Android low-RAM — 32; для очень большого списка cap минимально повышается лишь настолько, чтобы уложить все пятисекундные slots в 60 секунд. Для примерно 500 быстро отвечающих профилей целевое время в normal mode составляет около 10 секунд; если все 500 дожидаются полного пятисекундного timeout, физический минимум при concurrency 128 — около 20 секунд плюс запуск core. Защитный общий budget равен 60 секундам. Хвост, который не получил полноценную проверку до hard deadline, получает `NOT_TESTED`, а не ложный `UNAVAILABLE`. Blocking JNI start/stop нельзя безопасно прервать из Kotlin, поэтому target/budget остаются best-effort на аномально зависшем native-вызове.
- Smart Connect считает 60 секунд общим budget всего refresh/check/prune/select workflow, начиная до HTTP-запроса. Progress публикуется монотонно и не более примерно 100 раз за batch; поздние callbacks после Stop игнорируются. Пустой/all-negative/инфраструктурный snapshot не уничтожает каталог. При наличии хотя бы одного текущего `AVAILABLE` статусы, prune и выбор winner коммитятся одной guarded Room-транзакцией либо целиком откатываются при handoff/settings revision.
- Xray dialer sockets привязываются к выбранной физической `NOT_VPN` сети. Некорректная конфигурация изолируется от остальных profiles, а bind/runtime failure не превращается в массовый false-negative. UI показывает live completed/total, после чего все terminal results сохраняются одной Room-транзакцией без промежуточных persistent `RUNNING`; после завершения не остаются фоновые probe workers.
- Xray native start/stop сериализованы reentrant lifecycle gate, поэтому disconnect не оставляет поздно стартовавший unowned core. Dialer/listener socket-protector controllers регистрируются один раз на binding; reconnect меняет только `AtomicReference` delegate и не накапливает callbacks/старые service closures.
- Отмена public sync немедленно disconnect-ит blocking `HttpURLConnection`, не оставляя сетевой worker ждать read timeout.

## Энергопотребление и память

- VPN runtime не захватывает собственные long-lived `WakeLock`/`WifiLock`; системно планируемый WorkManager может использовать кратковременный управляемый wake lock только на время выполнения worker. VPN foreground notification статичен и имеет low importance.
- Idle TUN writer, zero-window TCP и offline reconnect используют блокирующее/событийное ожидание вместо короткого polling.
- При выключенном экране интервалы local monitor и SSH keepalive автоматически увеличиваются и восстанавливаются без reconnect после `SCREEN_ON`.
- Smart Connect проверяет live YouTube tunnel каждые 10 секунд в первую минуту, затем раз в 30 секунд при активном экране, раз в 120 секунд с выключенным экраном и раз в 300 секунд в Battery Saver. До открытия дополнительного probe-соединения он проверяет активный/недавний RX и полностью пропускает probe во время передачи. Собственных alarm/wake lock и постоянной GPU-анимации нет.
- Перед разрушительным SSH rebuild или Smart failover раздельные RX/TX traffic counters проверяются без polling. Кроме UID процесса используется консервативный device-total fallback, потому что Android может отнести VPN payload к UID браузера. UID RX и 45 секунд после него не имеют искусственного cap; менее точный device-only RX ограничен 15 минутами, чтобы трафик исключённых приложений не скрывал мёртвый VPN навсегда. Для TX-only активности защитный максимум — 5 минут, потому что одни retransmit не доказывают живой обратный путь.
- При построении нового TUN pipeline системный Battery Saver и Android low-RAM уменьшают только число TCP flow и retained packet pool (`64/32` и `32/32` вместо `128/64`). Throughput-critical SSH/upload windows и TUN queue 256 остаются одинаковыми во всех профилях.
- Необязательные terminal иконки/вывод освобождаются или ограничиваются, когда соответствующий UI не виден; постоянных декоративных GPU-анимаций нет.
- Периодические public-source updates объединяются WorkManager и выполняются только при подходящем заряде и физической `VALIDATED + NOT_VPN + NOT_METERED` сети, к которой HTTP явно привязывается через `Network.openConnection`.

Pagination не используется для списка приложений: источник является локальным `PackageManager`, не предоставляет page API, один раз кэшируется, а UI уже виртуализирован через `LazyColumn`.

## Ограничения

- Поддержаны TCP и DNS UDP/53. Остальной UDP не туннелируется.
- Старое поле `enableUdpForwarding` сохраняется в схеме для совместимости, но no-op переключатель удалён из UI: DNS UDP/53 работает всегда, general UDP через SSH `direct-tcpip` невозможен без другого server-side транспорта.
- Если SSH fingerprint не задан, приложение показывает явное предупреждение и логирует непроверенную host identity, но сохраняет совместимость со старыми профилями. Для password auth fingerprint особенно рекомендуется.
- SSH terminal использует интерактивный PTY на сервере, поэтому поведение prompt/echo зависит от server shell.
- Quick Settings tile нельзя автоматически поставить в конкретное место шторки: пользователь должен добавить плитку через редактирование быстрых настроек Android.
- Release APK, подписанный локальным ignored keystore, подходит для установки на устройство, но не для production-дистрибуции.
- Публичные `opensource` конфигурации используются на риск пользователя: приложение не может гарантировать безопасность чужого proxy-сервера.
- Существующее TCP/TLS-соединение нельзя перенести на другой SSH/Xray server только клиентскими средствами. Приложение подавляет ложные и слишком частые rebuild/failover во время активной загрузки, а SSH forwarder перед hot-reconnect явно завершает старые client flows вместо их зависания. При реальной потере сервера или физической сети браузеру всё равно может потребоваться HTTP Range resume (`Продолжить`).
- Xray runtime core не включается в APK по умолчанию: и Smart Connect, и opensource settings умеют самостоятельно скачать совместимый `libXray` core из release assets этого же репозитория.

## Требования

- macOS или Linux.
- Android Studio с JBR 17+ или отдельный JDK 17+.
- Android SDK с API 37.
- Android NDK, Go toolchain и gomobile нужны только для сборки `libXray.aar`.
- Gradle Wrapper 9.5.1 из проекта. Gradle 9.6 пока не используется: AGP 9.2.1 вызывает в нём deprecated API.
- Android emulator или физическое устройство с включенным USB debugging.

Если Android SDK не найден автоматически, создай `local.properties` в корне проекта:

```properties
sdk.dir=/Users/<user>/Library/Android/sdk
```

## Быстрый старт

```bash
./scripts/check-env.sh
./scripts/build-debug.sh
./scripts/install-debug.sh
```

Если Gradle intermediate state сломался после обновления SDK/AGP, сначала выполни:

```bash
./scripts/clean.sh
```

## Release APK

Локально подписанный release APK:

```bash
./scripts/build-release.sh
```

Выходной файл:

```text
build/app/outputs/apk/release/app-universal-release.apk
```

Если production signing переменные не заданы, скрипт автоматически создаёт локальный keystore в `.local/signing/`. Эта директория игнорируется git.

Production signing:

```bash
export SSH_VPN_RELEASE_STORE_FILE=/absolute/path/release.keystore
export SSH_VPN_RELEASE_STORE_PASSWORD='store-password'
export SSH_VPN_RELEASE_KEY_ALIAS='key-alias'
export SSH_VPN_RELEASE_KEY_PASSWORD='key-password'
./scripts/build-release.sh
```

Release variant использует R8 minification и resource shrinking. Keep rules лежат в `app/proguard-rules.pro`.

`build-debug.sh` и `build-release.sh` не включают Xray core в APK по умолчанию, чтобы universal APK оставался лёгким. `build-release.sh` дополнительно собирает ABI-specific `libXray-<version>-<abi>.aar` assets в release output рядом с APK. Эти AAR содержат runtime-ready `classes.dex` и `libgojni.so` только для своей ABI; их нужно загрузить в GitHub Release вместе с APK. Если нужен legacy APK с core внутри, запусти сборку с `SSH_VPN_BUNDLE_XRAY_CORE=1`; в этом режиме скрипты соберут `app/libs/libXray.aar`, если файла нет.

## Xray core updates

Runtime core updater проверяет:

```text
https://api.github.com/repos/stansful/ssh-vpn-client-kotlin/releases/latest
```

Он принимает только `libXray` AAR assets из release path этого репозитория. На экране показывается только один compatible asset под runtime ABI процесса (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`). Asset должен содержать `classes.dex` и `jni/<abi>/libgojni.so`; старые AAR только с `classes.jar` будут отклонены как устаревшие. После скачивания приложение сохраняет только `classes.dex` и `libgojni.so` для текущей runtime ABI.

## Обновления приложения

Приложение запрашивает последний опубликованный stable release:

```text
https://api.github.com/repos/stansful/ssh-vpn-client-kotlin/releases/latest
```

- Токен не используется: репозиторий и release metadata публичные.
- Поддерживаются tags `2.1.0` и `v2.1.0`.
- Автопроверка запускается после старта UI и кешируется на 24 часа; ошибки автопроверки не мешают работе VPN.
- Ручная проверка находится в Settings.
- APK URL берётся только из `assets[].browser_download_url` и должен принадлежать release path этого репозитория.
- DownloadManager сохраняет APK в app-specific Downloads, показывает системное уведомление и передаёт в UI процент/объём активной загрузки. Панель прогресса можно свернуть.
- Перед установкой проверяются SHA-256 digest (если GitHub его вернул), package name, versionName, возрастающий versionCode и сертификат подписи.
- После проверки APK сохраняется как `Ready to install`, включая перезапуск приложения. Кнопка `Download` заменяется на `Install`.
- Стандартный Android installer открывается только по явному нажатию `Install`. В этот момент Android может запросить разрешение `Install unknown apps` для shadow-ssh.

`versionCode` автоматически вычисляется из `versionName` по формуле `major * 1_000_000 + minor * 1_000 + patch`. Для обновляемости все опубликованные APK должны быть подписаны одним постоянным production keystore. Потеря или замена ключа сделает обновление поверх установленной версии невозможным.

Проверка подписи:

```bash
apksigner verify --verbose build/app/outputs/apk/release/app-universal-release.apk
```

## Скрипты

- `./scripts/check-env.sh` - проверяет Java, Android SDK и Gradle/Wrapper.
- `./scripts/create-gradle-wrapper.sh` - создаёт Gradle Wrapper через доступный Gradle или cached distribution.
- `./scripts/build-debug.sh` - собирает debug APK.
- `./scripts/build-release.sh` - собирает installable release APK и ABI-specific Xray core release assets.
- `./scripts/build-xray-core.sh` - собирает официальный Xray Android binding из закреплённых исходников и кладёт `app/libs/libXray.aar` для release assets или `SSH_VPN_BUNDLE_XRAY_CORE=1`.
- `./scripts/package-xray-core-assets.sh` - нарезает `libXray.aar` на ABI-specific release assets и конвертирует binding `classes.jar` в runtime `classes.dex` через D8.
- `./scripts/install-debug.sh` - устанавливает debug APK на подключённое устройство.
- `./scripts/lint.sh` - запускает Android lint для debug variant.
- `./scripts/test.sh` - запускает unit tests.
- `./scripts/clean.sh` - очищает Gradle build outputs.

## Ручные Gradle-команды

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:installDebug
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
```

## Архитектура

```text
app/src/main/java/com/stansful/sshvpnclient/
  data/
    apps/       installed apps for split tunneling
    config/     SSH config persistence
    key/        SSH key persistence
    local/      Room and VPN state repositories
    proxy/      public proxy parser, Room repository, source sync
    smart/      isolated Smart Connect Room repository and source boundary
    secret/     Tink-backed secret storage
    settings/   app settings persistence
  domain/
    model/
    repository/
    usecase/
  ui/
    opensource/ public profile list, import, checks, connect controls
    smartconnect/ automatic Smart Connect control and settings
    main/       main screen, settings, diagnostics, terminal
    apps/       selected-apps picker
    configs/
    configedit/
    keys/
    keyedit/
    theme/
  vpn/
    Android VpnService, SSH manager, Kotlin TUN forwarder, OpenSource/Smart Xray services, QS tile
  work/
    periodic public proxy sync
  xray/
    Xray config builder and reflection bridge to libXray
```

Ключевые сетевые файлы:

- `app/src/main/java/com/stansful/sshvpnclient/vpn/SshVpnService.kt`
- `app/src/main/java/com/stansful/sshvpnclient/vpn/SshConnectionManager.kt`
- `app/src/main/java/com/stansful/sshvpnclient/vpn/SshTerminalSession.kt`
- `app/src/main/java/com/stansful/sshvpnclient/vpn/KotlinTunForwarder.kt`
- `app/src/main/java/com/stansful/sshvpnclient/vpn/Tun2SocksManager.kt`
- `app/src/main/java/com/stansful/sshvpnclient/vpn/VpnProtectedSocketFactory.kt`
- `app/src/main/java/com/stansful/sshvpnclient/vpn/OpenSourceVpnService.kt`
- `app/src/main/java/com/stansful/sshvpnclient/vpn/SshVpnTileService.kt`
- `app/src/main/java/com/stansful/sshvpnclient/xray/XrayCoreBridge.kt`
- `app/src/main/java/com/stansful/sshvpnclient/xray/XrayConfigBuilder.kt`

## Данные и секреты

Room хранит обычные сущности и secret id. Секретные значения не хранятся в Room:

- `SshConfig.password`
- `SshPrivateKey.privateKey`
- `SshPrivateKey.passphrase`

Активное secret storage решение:

- Tink AEAD шифрует значения;
- ciphertext хранится в обычном private `SharedPreferences` как Base64;
- associated data привязана к secret id;
- Tink keyset хранится через Android Keystore-backed `AndroidKeysetManager`.

Есть idempotent legacy migration из старого `EncryptedSharedPreferences` storage. Deprecated storage используется только для чтения старых данных во время миграции, если старый файл реально существует.

## Split tunneling

Режим хранится в app settings и переживает перезапуск приложения:

- `Proxy` - Android VPN builder не ограничивает приложения, через туннель идут все приложения.
- `Selected apps` - в VPN builder добавляются только выбранные package names.

Если выбран `Selected apps`, но список пустой, Connect запрещён и приложение показывает сообщение `нет выбранных приложений`.

Если split-tunnel settings меняются при активном VPN, приложение делает controlled reconnect с сохранением diagnostics.

## Quick Settings Tile

Плитка `shadow-ssh` регистрируется через `SshVpnTileService`.

Поведение:

- VPN подключён, подключается или переподключается - тап отправляет Disconnect.
- VPN отключён - тап запускает текущую выбранную конфигурацию.
- Нет VPN permission, нет выбранной конфигурации или `Selected apps` пустой - открывается главный экран приложения.

## Diagnostics и debug

Diagnostics предназначены для пользовательского debug без adb:

- SSH auth method, key fingerprint, network capabilities, socket protection, reconnect attempts.
- Tunnel check lifecycle.
- Terminal lifecycle and write/close failures.
- Ошибки forwarding layer.

Diagnostics не должны содержать приватные ключи, пароли, passphrase, SSH terminal commands или remote terminal output.

## Последняя проверенная сборка

На 2026-07-15:

- `./scripts/test.sh`: success, 144 tests, 0 failures/errors.
- `./scripts/lint.sh`: success.
- `./scripts/build-release.sh`: success, R8/resource shrinking и release lint vital пройдены.
- `apksigner verify --verbose build/app/outputs/apk/release/*.apk`: все 5 APK используют APK Signature Scheme v2, 1 signer.
- Universal APK: `build/app/outputs/apk/release/app-universal-release.apk`, 4 201 557 байт.
- SHA-256 universal APK: `2dbd401fcfb575c5c8d704d062356b15ddabfc627cf2c0cba6afdf9c0099e1ff`.
