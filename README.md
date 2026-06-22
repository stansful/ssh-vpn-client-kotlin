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
суть приложения в тому что оно создаёт vpn соединение используя стандартный ssh протокол 
для mvp достаточно только ssh соединения с массивом соединений 
а также выбором/добавлением конфигураций - основные параметры ssh соединения: 
адрес порт пользователь закрытый ключ / пароль отпечаток keepalive udp-переадресация примечание(оно будет текcтом на ui)
```

## Что умеет

- SSH VPN через password или private key.
- Password, private key content и passphrase скрыты звёздочками по умолчанию и раскрываются кнопкой глаза; private key/passphrase можно скопировать отдельной кнопкой.
- CRUD для SSH-конфигураций и приватных SSH-ключей.
- Переиспользование одного SSH-ключа в нескольких конфигурациях.
- Проверка SSH host fingerprint, если он указан в конфигурации.
- SSH keepalive и автоматический fast reconnect при обрыве до явного Disconnect:
  - Android VPN interface и маршруты сохраняются во время переподключения SSH;
  - первый reconnect запускается сразу, повторные ошибки используют bounded backoff от 250 ms до 5 s;
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
  - не ограничены 80 строками и сбрасываются при новом пользовательском Connect.
- SSH terminal:
  - выключен по умолчанию и включается persisted-переключателем в Settings;
  - доступен при активном подключении;
  - открывает shell-channel на текущей SSH-сессии;
  - команды отправляются с фонового IO-потока;
  - команды и remote output не пишутся в diagnostics.
- Темы:
  - `System` по умолчанию;
  - `Light`;
  - `Dark` в black/orange стиле;
  - `Custom` с RGB-настройкой цветов, которые сохраняются после перезапуска.
- Ссылка на GitHub в Settings с кнопкой копирования.
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

TCP-трафик из TUN проксируется через SSH `direct-tcpip`. DNS-запросы VPN обрабатываются как DNS-over-TCP через SSH. Произвольный non-DNS UDP сейчас не проксируется и отбрасывается локальным forwarding layer.

## Fast reconnect

После обнаружения разрыва приложение оставляет Android `VpnService` TUN interface поднятым, приостанавливает только SSH transport и сразу начинает новый SSH handshake. После успешной аутентификации работающий Kotlin forwarder получает новую JSch `Session` без пересоздания VPN interface.

Уже существующие TCP/TLS flow нельзя перенести между двумя SSH-сессиями: они закрываются и переоткрываются самими приложениями. Новые TCP SYN во время короткого reconnect не отклоняются сразу, чтобы Android мог повторить SYN после восстановления transport.

Параметры восстановления:

- local health polling: 2 секунды;
- effective SSH keepalive: не более 10 секунд, один пропущенный ответ;
- первый retry после активного разрыва: без искусственной задержки;
- connect timeout повторной попытки: 8 секунд;
- повторные неудачи: `250 ms -> 500 ms -> ... -> 5 s`;
- если TUN forwarder или VPN interface потерян, выполняется полный rebuild pipeline.

После длительной блокировки экрана приложение выполняет лёгкое wake recovery без переподключения SSH и пересоздания VPN interface. Если экран был выключен не менее 60 секунд, forwarder отправляет RST только TCP-сессиям, которые простаивали не менее 30 секунд. Это заставляет приложения переоткрыть зависшие TLS/DoT keep-alive соединения после Doze, не затрагивая недавно активные фоновые потоки.

Wake recovery основан на системных `SCREEN_OFF/SCREEN_ON` событиях: дополнительные polling, ping и wake lock не используются.

## Производительность и потоки

- Контейнер зависимостей ленивый: Room, Tink, PackageManager и VPN-компоненты создаются только при первом использовании. Для первого кадра синхронно загружаются только небольшие UI settings.
- Room-запросы, Tink/Android Keystore и legacy migration выполняются на `Dispatchers.IO`.
- Главный экран и списки используют metadata-only Room projections: passwords, private keys и passphrases не расшифровываются для отображения карточек.
- Usage count SSH-ключей вычисляется одним `LEFT JOIN + COUNT`, без N+1 запросов.
- Compose собирает `Flow` через `collectAsStateWithLifecycle`, поэтому неактивные экраны не держат лишние collectors.
- Список установленных приложений кэшируется на 5 минут; поиск дебаунсится на 200 ms и фильтруется на `Dispatchers.Default`.
- Diagnostics восстанавливаются и сериализуются вне Main thread, поступающие строки публикуются в UI пакетами, а раскрытый список виртуализирован.
- SSH terminal использует lifecycle-bound coroutine scope на `Dispatchers.IO`; вывод читается пакетами до 32 KiB.
- VPN connection loop выполняется на `Dispatchers.IO`. В production-коде нет `GlobalScope` и `runBlocking`.

Pagination не используется для списка приложений: источник является локальным `PackageManager`, не предоставляет page API, один раз кэшируется, а UI уже виртуализирован через `LazyColumn`.

## Ограничения

- Поддержаны TCP и DNS UDP/53. Остальной UDP не туннелируется.
- `enableUdpForwarding` оставлен как experimental flag, но текущий forwarding layer явно пишет в diagnostics, что поддержаны только TCP и DNS.
- SSH terminal использует интерактивный PTY на сервере, поэтому поведение prompt/echo зависит от server shell.
- Quick Settings tile нельзя автоматически поставить в конкретное место шторки: пользователь должен добавить плитку через редактирование быстрых настроек Android.
- Release APK, подписанный локальным ignored keystore, подходит для установки на устройство, но не для production-дистрибуции.

## Требования

- macOS или Linux.
- Android Studio с JBR 17+ или отдельный JDK 17+.
- Android SDK с API 37.
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
build/app/outputs/apk/release/app-release.apk
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

Проверка подписи:

```bash
apksigner verify --verbose build/app/outputs/apk/release/app-release.apk
```

## Скрипты

- `./scripts/check-env.sh` - проверяет Java, Android SDK и Gradle/Wrapper.
- `./scripts/create-gradle-wrapper.sh` - создаёт Gradle Wrapper через доступный Gradle или cached distribution.
- `./scripts/build-debug.sh` - собирает debug APK.
- `./scripts/build-release.sh` - собирает installable release APK.
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
    secret/     Tink-backed secret storage
    settings/   app settings persistence
  domain/
    model/
    repository/
    usecase/
  ui/
    main/       main screen, settings, diagnostics, terminal
    apps/       selected-apps picker
    configs/
    configedit/
    keys/
    keyedit/
    theme/
  vpn/
    Android VpnService, SSH manager, Kotlin TUN forwarder, QS tile
```

Ключевые сетевые файлы:

- `app/src/main/java/com/stansful/sshvpnclient/vpn/SshVpnService.kt`
- `app/src/main/java/com/stansful/sshvpnclient/vpn/SshConnectionManager.kt`
- `app/src/main/java/com/stansful/sshvpnclient/vpn/SshTerminalSession.kt`
- `app/src/main/java/com/stansful/sshvpnclient/vpn/KotlinTunForwarder.kt`
- `app/src/main/java/com/stansful/sshvpnclient/vpn/Tun2SocksManager.kt`
- `app/src/main/java/com/stansful/sshvpnclient/vpn/VpnProtectedSocketFactory.kt`
- `app/src/main/java/com/stansful/sshvpnclient/vpn/SshVpnTileService.kt`

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

На 2026-06-22:

- `./scripts/build-debug.sh`: success.
- `./gradlew testDebugUnitTest lintDebug`: success.
- `./scripts/build-release.sh`: success.
- `apksigner verify --verbose build/app/outputs/apk/release/app-release.apk`: success, APK Signature Scheme v2, 1 signer.
- Debug APK: `build/app/outputs/apk/debug/app-debug.apk` около 23M.
- Release APK: `build/app/outputs/apk/release/app-release.apk` около 3.7M.
