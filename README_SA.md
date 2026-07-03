# shadow-ssh - описание для системного аналитика

## Назначение

`shadow-ssh` - Android-приложение, которое создаёт локальный VPN-интерфейс на устройстве и использует SSH-сервер или публичный Xray-compatible proxy profile как транспорт до внешних сайтов и сервисов.

Цель пользователя: выбрать SSH-конфигурацию или публичный `opensource` профиль, подключиться и направить сетевой трафик приложений через выбранный туннель без изменения серверной части.

## Участники

- Пользователь Android-устройства.
- Android OS:
  - выдаёт VPN permission;
  - создаёт `VpnService` TUN interface;
  - применяет split tunneling по package names;
  - отображает Quick Settings tile.
- Приложение `shadow-ssh`.
- SSH-сервер пользователя.
- Публичный proxy-сервер из `opensource` конфигурации.
- Публичный источник списков конфигураций.
- Внешние сайты и сервисы.

## Общая схема трафика

```mermaid
flowchart TD
    A["Android apps"] --> B["Android VpnService TUN"]
    B --> C["Kotlin TUN forwarder inside shadow-ssh"]
    C --> D["Protected SSH socket outside VPN routing"]
    D --> E["SSH server"]
    E --> F["Target websites and services"]
    C --> G["Diagnostics UI"]
    B --> H["Official Xray-core binding"]
    H --> I["Selected public VLESS/VMess/Trojan profile"]
    I --> F
```

Приложение защищает SSH socket от попадания обратно в VPN routing. Иначе SSH-соединение начало бы маршрутизироваться через собственный VPN-интерфейс и соединение могло бы зависнуть или оборваться.

Для `opensource` режима приложение передаёт TUN fd официальному Xray-core Android binding. Xray сам управляет выбранным публичным протоколом и транспортом, а приложение отвечает за UI, storage, split tunneling, lifecycle, socket protect и проверку доступности.

## Основной сценарий подключения

1. Пользователь создаёт или выбирает SSH-конфигурацию.
2. Пользователь добавляет SSH private key или пароль.
3. Пользователь нажимает `Connect`.
4. Приложение проверяет:
   - выбрана ли конфигурация;
   - есть ли VPN permission;
   - если выбран режим `Selected apps`, выбран ли хотя бы один package.
5. Приложение открывает защищённый TCP socket до SSH-сервера.
6. SSH-клиент проходит аутентификацию.
7. Android создаёт VPN TUN interface.
8. Приложение запускает Kotlin TUN forwarding layer с текущей SSH-сессией.
9. TCP-трафик приложений открывается через SSH `direct-tcpip`.
10. DNS-запросы обрабатываются как DNS-over-TCP через SSH.
11. Статус на главном экране становится `Connected`.

## Сценарий opensource

`opensource` - отдельная глобальная вкладка рядом с `shadow-ssh`.

Первый вход:

1. Пользователь открывает вкладку `opensource`.
2. Приложение показывает предупреждение: публичные конфигурации используются на риск пользователя, разработчик/автор приложения не несёт ответственности за их безопасность и безопасность пользователя.
3. Если пользователь нажимает `Нет`, приложение возвращает его на вкладку `shadow-ssh`.
4. Если пользователь нажимает `Согласен`, версия согласия сохраняется в settings. При изменении текста предупреждения можно поднять версию и показать согласие заново.
5. На экране `opensource` всегда остаётся риск-баннер.

Импорт и синхронизация:

- Публичный список загружается из `https://hub.mos.ru/zieng2/wl/raw/main/list_universal.txt`.
- Автосинхронизация выключена по умолчанию. Если пользователь включает её в настройках, WorkManager запускает sync каждые 6 часов с flex-окном 1 час при наличии сети, не низком заряде батареи и принятом предупреждении.
- Устройство специально не пробуждается, wake lock не используется.
- Пользователь может нажать `Refresh` вручную.
- Пользователь может добавить один share link вручную или импортировать bulk-текст из clipboard.
- Поддерживаются share links `vless://`, `vmess://`, `trojan://`.
- Дубли удаляются по canonical fingerprint.
- Исходный share link хранится как секрет; Room хранит metadata, fingerprint и UI-состояние.

Работа со списком:

- Тап по профилю делает его активным.
- Рядом с профилем доступны `Copy`, `Edit`, `Delete`.
- Долгое нажатие включает multi-select.
- В multi-select можно выбрать все профили и удалить выбранные.
- Профили можно фильтровать поиском и по протоколу.

Подключение и проверка:

1. Пользователь выбирает профиль.
2. Пользователь нажимает `Connect`.
3. Если сейчас активен SSH VPN, приложение сначала отправляет controlled disconnect.
4. Запускается отдельный `OpenSourceVpnService`.
5. Android создаёт TUN interface с теми же split tunneling правилами.
6. Xray-core получает TUN fd и JSON-конфиг выбранного профиля.
7. Статус приложения становится `Connected`.

Проверка профиля открывает запрос к YouTube через временный Xray SOCKS endpoint. Проверка всех профилей выполняется последовательно, без массового параллелизма, чтобы не перегревать устройство и не создавать лишнюю нагрузку на сеть.

## Режимы VPN

### Proxy

Через туннель идут все приложения, которые Android направляет в VPN.

Использование: режим по умолчанию для пользователя, которому нужен полный VPN.

### Selected apps

Через туннель идут только выбранные приложения.

Особенности:

- список приложений включает пользовательские и системные приложения;
- есть поиск;
- выбор сохраняется после перезапуска;
- если список пустой, подключение запрещается и показывается сообщение `нет выбранных приложений`;
- при изменении списка или режима во время активного VPN приложение делает controlled reconnect.

## Состояния подключения

```mermaid
stateDiagram-v2
    [*] --> Disconnected
    Disconnected --> Connecting: Connect
    Connecting --> Connected: SSH + VPN ready
    Connecting --> Error: failure
    Connected --> Reconnecting: SSH interrupted
    Reconnecting --> Connected: reconnect success
    Reconnecting --> Error: unrecoverable failure
    Connected --> Disconnecting: Disconnect
    Reconnecting --> Disconnecting: Disconnect
    Disconnecting --> Disconnected
    Error --> Connecting: Connect
```

Reconnect продолжается до явного `Disconnect`.

При обычном разрыве SSH приложение не закрывает Android VPN interface:

1. Статус меняется на `Reconnecting`.
2. Kotlin TUN forwarder отвязывается от старой SSH-сессии и закрывает связанные с ней TCP flow.
3. Первый SSH reconnect запускается без искусственной задержки.
4. После успешной аутентификации новая JSch `Session` подставляется в существующий forwarder.
5. Статус возвращается в `Connected`.

Повторные неудачи используют backoff от 250 ms до 5 секунд. SSH reconnect использует timeout 8 секунд. Если VPN interface или TUN forwarder недоступен, приложение выполняет полный rebuild pipeline.

Существующие TCP/TLS flow не переносятся между SSH-сессиями и должны быть переоткрыты клиентским приложением. Новые SYN во время короткой паузы временно не отклоняются, поэтому TCP stack Android может повторить их после восстановления transport.

### Возврат из Doze/блокировки экрана

SSH transport может оставаться доступным после сна устройства, пока отдельные TCP/TLS/DoT соединения приложений уже стали недействительными из-за NAT timeout или приостановки сети. Поэтому успешный `Check tunnel` сам по себе не гарантирует жизнеспособность старых app sockets.

Если экран был выключен не менее 60 секунд, приложение событийно проверяет текущие TUN-сессии и сбрасывает только те, которые простаивали не менее 30 секунд. Клиенты получают TCP RST и создают новые соединения через уже работающие VPN interface и SSH session. Недавно активные фоновые соединения сохраняются.

Механизм не использует wake lock, периодические ping или новый polling и не будит устройство во время сна.

Если SSH session остаётся живой, но DNS или TUN forwarding начинают массово отказывать, приложение считает это деградацией forwarding layer. DNS сначала идёт как DNS-over-TCP через SSH к DNS-серверу из Android VPN settings. Если TCP/53 не отвечает, forwarder пробует DoH fallback к Cloudflare через SSH на порт 443. Если несколько DNS-запросов подряд не проходят даже после fallback, `SshVpnService` пересобирает Android VPN interface и Kotlin forwarder. Это закрывает сценарий, когда `Check tunnel` зелёный, но браузер и приложения не открывают сайты.

## Диагностика

Diagnostics нужны для пользовательского debug без adb.

Пишутся:

- выбранная конфигурация без секретов;
- auth type;
- Android network capabilities;
- результат защиты SSH socket;
- SSH connect/auth lifecycle;
- fingerprint server/key;
- reconnect attempts;
- tunnel check result;
- terminal lifecycle/failures;
- forwarding layer warnings.

Не пишутся:

- private key;
- password;
- passphrase;
- terminal commands;
- terminal remote output.

Diagnostics по умолчанию скрыты на главном экране. В Settings есть переключатель `Debug logs`. Если он включён, на главном экране появляется свёрнутый блок diagnostics с кнопкой копирования.

## Check tunnel

Кнопка `Check tunnel` доступна после успешного подключения.

Проверка открывает SSH `direct-tcpip` channel до:

```text
youtube.com:443
```

Результат отображается цветом кнопки:

- серый - проверка ещё не выполнялась;
- зелёный - проверка успешна;
- красный - проверка неуспешна.

Важно: эта проверка подтверждает живость SSH transport. Она не является полной проверкой Android TUN, DNS forwarding и старых app sockets.

## SSH terminal

Terminal - дополнительный пользовательский инструмент, доступный при активном подключении.

Функция выключена по умолчанию и включается persisted-переключателем `SSH terminal` в Settings. Когда она выключена, terminal panel не создаётся, shell-channel не открывается, а уже активная terminal session немедленно закрывается.

Поведение:

- открывает SSH shell-channel на текущей SSH-сессии;
- работает в expandable panel на главном экране;
- ввод команд выполняется через Android keyboard;
- network write выполняется на IO-потоке, не на UI thread;
- при Disconnect shell-channel закрывается;
- terminal output хранится только в UI state и не попадает в diagnostics.

Терминал не является отдельным VPN-транспортом. Он использует ту же SSH-сессию, что и VPN.

## Хранение данных

Обычные данные:

- SSH configuration metadata;
- SSH key metadata;
- public proxy profile metadata;
- public proxy fingerprint and source;
- selected config;
- selected public proxy profile;
- settings;
- selected app package names.

Секреты:

- SSH password;
- private key;
- private key passphrase.
- raw public proxy share link.

Секреты не хранятся в Room. В Room хранится только secret id.

Активная схема secret storage:

- Tink AEAD;
- ciphertext в обычном private `SharedPreferences`;
- Base64 encoding;
- associated data = secret id;
- keyset через Android Keystore-backed `AndroidKeysetManager`.

Есть legacy migration из старого `EncryptedSharedPreferences`. Deprecated storage используется только как источник старых данных во время миграции.

В формах password, private key content и passphrase маскируются звёздочками по умолчанию. Кнопка глаза меняет только отображение текущего UI-поля и не изменяет способ хранения. Копирование private key/passphrase выполняется напрямую в Android clipboard и не попадает в diagnostics.

## UI settings

Настройки сохраняются после перезапуска приложения:

- `Debug logs`;
- `SSH terminal`;
- active global tab:
  - `shadow-ssh`;
  - `opensource`;
- accepted opensource warning version;
- theme mode:
  - `System`;
  - `Light`;
  - `Dark`;
  - `Custom`;
- RGB-цвета для `Custom`;
- VPN mode;
- selected app package names.

## Quick Settings tile

Android Quick Settings tile называется `shadow-ssh`.

Поведение:

- если VPN подключён, подключается или переподключается - тап отправляет Disconnect;
- если VPN отключён - тап запускает текущую выбранную конфигурацию;
- если требуется действие пользователя, открывается главный экран.

Tile нельзя автоматически добавить в шторку или поставить в конкретную позицию. Это ограничение Android.

## Обновление приложения

При запуске главного экрана приложение автоматически проверяет GitHub Releases, но не чаще одного раза в 24 часа. В Settings также доступна ручная проверка.

Сценарий:

1. Выполняется публичный запрос `GET /repos/stansful/ssh-vpn-client-kotlin/releases/latest` без GitHub token.
2. `tag_name` сравнивается с установленным `versionName` по SemVer; поддерживаются tags с префиксом `v` и без него.
3. Если версия новее, показывается modal с release notes и действиями `Later`, `Open release`, `Download`; для уже проверенного APK действие меняется на `Install`.
4. `Open release` использует полученный от GitHub `html_url`.
5. `Download` передаёт `browser_download_url` системному DownloadManager. UI показывает процент и объём скачанных данных в сворачиваемой панели; системное скачивание продолжает работать независимо от открытого экрана.
6. После скачивания проверяются digest, package name, versionName, versionCode и signing certificate. Валидный APK и metadata сохраняются как `ReadyToInstall` после пересоздания процесса.
7. `Install` при необходимости направляет пользователя в системное разрешение `Install unknown apps`, затем передаёт APK стандартному Android installer, где пользователь подтверждает обновление.

Metadata проверки, незавершённой загрузки и проверенного APK сохраняются после пересоздания процесса. Ручная кнопка проверки остаётся доступной во время скачивания и визуально показывает выполняемую проверку. Одновременные network checks и повторные download jobs не дублируются. Прогресс опрашивается только во время активной загрузки и с более редким интервалом в paused-состоянии. Сетевые, JSON, hash и package операции выполняются вне Main thread.

Ограничения:

- Android не разрешает обычному приложению полностью бесшумную установку; требуется системное пользовательское подтверждение.
- Новый APK должен иметь больший `versionCode` и тот же signing certificate.
- Все production releases должны подписываться одним постоянным keystore.

## Технические ограничения

- Поддержаны TCP и DNS UDP/53.
- Произвольный non-DNS UDP не проксируется.
- SSH-серверная часть не меняется.
- Для `opensource` используется официальный Xray-core Android binding, собранный из закреплённых исходников.
- Публичные proxy-серверы не контролируются приложением. Пользователь принимает риск до входа во вкладку, риск-баннер остаётся всегда.
- Автосинхронизация публичных конфигов выключена по умолчанию; при включении выполняется не чаще одного раза в 6 часов и только при доступной сети и не низком заряде батареи.
- Производительность зависит от:
  - latency до SSH-сервера;
  - latency и качества публичного proxy-сервера;
  - производительности устройства;
  - cipher/KEX SSH-сессии;
  - количества параллельных TCP-соединений;
  - сетевых ограничений оператора или Wi-Fi.
- Интерактивный terminal зависит от shell defaults на сервере.

## Производительность и устойчивость

- Тяжёлые компоненты Room, Tink и VPN создаются лениво, поэтому не блокируют холодный старт до фактической необходимости.
- UI-экраны читают только metadata. Расшифровка password/private key происходит на IO-потоке только для подключения или редактирования.
- Diagnostics не ограничены по количеству строк до следующего Connect, но обрабатываются пакетно и показываются виртуализированным списком.
- Неактивные Compose-экраны прекращают сбор Flow по lifecycle.
- Поиск приложений имеет debounce 200 ms, а результат PackageManager кэшируется на 5 минут.
- Смена режима VPN или selected apps при активном соединении объединяется в один controlled reconnect; параллельные reconnect-задачи не создаются.
- SSH terminal и VPN connection loop выполняют блокирующий I/O вне UI-потока и отменяются вместе с владельцем lifecycle/service.
- В production-коде отсутствуют `GlobalScope` и `runBlocking`.

Pagination для app picker не применяется: Android `PackageManager` возвращает локальный snapshot без page API, а отображение большого списка виртуализировано.

## Сборочные артефакты

Debug APK:

```text
build/app/outputs/apk/debug/app-debug.apk
```

Release APK:

```text
build/app/outputs/apk/release/app-release.apk
```

Release APK:

- локально подписывается автоматически, если production signing env не задан;
- использует R8 minification;
- использует resource shrinking;
- проверяется через `apksigner verify --verbose`.

## Acceptance checklist

- Пользователь может создать SSH-конфигурацию.
- Пользователь может добавить private key без passphrase.
- Connect создаёт VPN и SSH-сессию.
- Disconnect останавливает VPN.
- При обрыве SSH приложение сохраняет Android VPN interface и переподключает SSH transport.
- При недоступном TUN forwarder приложение выполняет полный fallback rebuild.
- В `Selected apps` без выбранных приложений Connect запрещён.
- Пользователь может открыть вкладку `opensource` только после принятия предупреждения.
- Вкладка `opensource` сохраняет active tab после перезапуска.
- Публичные профили импортируются из remote source, clipboard и manual add.
- Дубли публичных профилей не размножаются в списке.
- Пользователь может выбрать, скопировать, отредактировать и удалить публичный профиль.
- Multi-select позволяет удалить несколько публичных профилей и выбрать все.
- Check selected/check all проверяют публичные профили через Xray без массового параллелизма.
- Connect в `opensource` создаёт VPN через Xray-core и выбранный публичный профиль.
- Diagnostics копируются в clipboard.
- Check tunnel меняет состояние кнопки.
- Terminal принимает команды без `NetworkOnMainThreadException`.
- Release APK устанавливается на устройство.
