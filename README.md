# SSH VPN Client Android

Native Android MVP на Kotlin + Jetpack Compose для VPN-клиента, который использует SSH как транспорт.

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

## Что реализовано

- CRUD для SSH-конфигураций.
- CRUD для SSH-ключей.
- Переиспользование одного SSH-ключа в разных конфигурациях через `privateKeyId`.
- Запрет удаления SSH-ключа, если он используется конфигурациями.
- Выбор активной конфигурации.
- Главный экран со статусом VPN и кнопкой Connect / Disconnect.
- Room для обычных данных.
- `EncryptedSharedPreferences` для секретов:
  - пароль конфига;
  - приватный ключ;
  - passphrase приватного ключа.
- `VpnService`, `SshConnectionManager`, `VpnTunnelManager`, `Tun2SocksManager`.
- SSH-подключение через password или private key.
- Проверка fingerprint, если он указан.
- KeepAlive interval для SSH-сессии.
- Локальный Kotlin TUN forwarding без стороннего native `tun2socks`.
- TCP из TUN проксируется через SSH `direct-tcpip` channels.
- DNS из VPN обрабатывается как DNS-over-TCP через SSH.
- Диагностические логи подключения: по умолчанию свёрнуты, есть копирование в clipboard.
- Автоматическое переподключение при обрыве SSH-сессии до явного Disconnect.
- Quick Settings плитка `shadow-ssh` для Connect / Disconnect из шторки Android.

## Сетевые ограничения

- TCP-трафик из Android VPN идёт через SSH.
- DNS-запросы из VPN идут через SSH как DNS-over-TCP к DNS-серверам из `VpnTunnelManager`.
- Произвольный non-DNS UDP пока не проксируется через SSH и отбрасывается локальным forwarding layer.
- `enableUdpForwarding` пока оставлен как experimental flag; текущая реализация явно пишет в diagnostics, что поддержаны TCP и DNS.
- Если SSH-сессия обрывается, приложение закрывает текущие TUN/forwarding/SSH ресурсы и переподключается с backoff от 2 до 30 секунд.
- Diagnostics не обрезаются по количеству строк в рамках текущего подключения и сбрасываются только при новом пользовательском Connect.

Точки интеграции:

- `app/src/main/java/com/stansful/sshvpnclient/vpn/Tun2SocksManager.kt`
- `app/src/main/java/com/stansful/sshvpnclient/vpn/KotlinTunForwarder.kt`
- `app/src/main/java/com/stansful/sshvpnclient/vpn/VpnProtectedSocketFactory.kt`

Раньше TUN forwarding зависел от локального AAR `hevtunnel-1.0.1-kotlin19.aar`.
Теперь эта зависимость удалена: приложение само читает IPv4-пакеты из Android `VpnService`,
локально терминирует TCP-сессии приложений и открывает SSH `direct-tcpip` каналы до целевых адресов.
DNS UDP/53 обрабатывается как DNS-over-TCP через SSH.

## Требования для локального запуска

- macOS/Linux.
- JDK 17+ или JBR из Android Studio.
- Android Studio или Android SDK с установленным API 35.
- Gradle 8.x или Gradle Wrapper.
- Android emulator или физическое устройство с включённым USB debugging.

Если Android SDK не найден автоматически, создай `local.properties` в корне проекта:

```properties
sdk.dir=/Users/<user>/Library/Android/sdk
```

## Быстрый старт

```bash
./scripts/check-env.sh
./scripts/create-gradle-wrapper.sh
./scripts/build-debug.sh
./scripts/install-debug.sh
```

Если wrapper уже создан, `create-gradle-wrapper.sh` можно не запускать.

## Release APK

Для локальной установки можно собрать release APK с локальным keystore. Скрипт создаст его автоматически в `.local/signing/`; эта директория игнорируется git:

```bash
./scripts/build-release.sh
```

Выходной файл:

```text
app/build/outputs/apk/release/app-release.apk
```

Этот APK можно устанавливать на телефон, но локальный keystore не подходит для production-дистрибуции.

Для production release APK передай свой keystore через переменные окружения:

```bash
export SSH_VPN_RELEASE_STORE_FILE=/absolute/path/release.keystore
export SSH_VPN_RELEASE_STORE_PASSWORD='store-password'
export SSH_VPN_RELEASE_KEY_ALIAS='key-alias'
export SSH_VPN_RELEASE_KEY_PASSWORD='key-password'
./scripts/build-release.sh
```

Выходной файл:

```text
app/build/outputs/apk/release/app-release.apk
```

Production release-ключи и пароли нельзя хранить в репозитории.

## Скрипты

- `./scripts/check-env.sh` - проверяет Java, Android SDK и Gradle/Wrapper.
- `./scripts/create-gradle-wrapper.sh` - создаёт Gradle Wrapper через доступный Gradle или cached distribution.
- `./scripts/build-debug.sh` - собирает debug APK.
- `./scripts/build-release.sh` - собирает installable release APK; использует production signing переменные или локальный ignored keystore.
- `./scripts/install-debug.sh` - устанавливает debug APK на подключённое устройство.
- `./scripts/lint.sh` - запускает Android lint для debug variant.
- `./scripts/test.sh` - запускает unit tests.
- `./scripts/clean.sh` - очищает Gradle build outputs.

## Ручные команды

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
    config/
    key/
    local/
    secret/
  domain/
    model/
    repository/
    usecase/
  ui/
    main/
    configs/
    configedit/
    keys/
    keyedit/
  vpn/
```

## Секреты

Секретные данные не хранятся в Room:

- `SshConfig.password`
- `SshPrivateKey.privateKey`
- `SshPrivateKey.passphrase`

Room хранит только secret id, а значения лежат в encrypted preferences.

## Проверка VPN-разрешения

При нажатии Connect приложение вызывает `VpnService.prepare(...)`. Если Android требует подтверждение пользователя, откроется системный permission dialog.

## Quick Settings Tile

Приложение предоставляет плитку `shadow-ssh` для шторки Android. Добавление плитки в конкретное место шторки Android не разрешает делать без действия пользователя, поэтому её нужно один раз добавить через редактирование быстрых настроек.

Поведение плитки:

- если VPN подключён, подключается или переподключается - нажатие отправляет Disconnect;
- если VPN отключён - нажатие запускает текущую выбранную конфигурацию;
- если VPN permission ещё не выдан, нет выбранной конфигурации или в режиме `selected-apps` нет выбранных приложений - открывается главный экран приложения.
