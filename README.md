# SSH VPN Client Android

Native Android MVP на Kotlin + Jetpack Compose для VPN-клиента, который использует SSH как транспорт.

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
- UDP forwarding как experimental flag в конфигурации.

## Важное ограничение MVP

`VpnService` и SSH-сессия подготовлены, но полноценный packet forwarding из TUN в SSH требует подключения реального tun2socks/native engine. Точка интеграции находится в:

`app/src/main/java/com/stansful/sshvpnclient/vpn/Tun2SocksManager.kt`

Без этой интеграции приложение покрывает модели, хранение, UI, lifecycle VPN-сервиса и SSH-сессию, но не является production-ready системным VPN.

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

## Скрипты

- `./scripts/check-env.sh` - проверяет Java, Android SDK и Gradle/Wrapper.
- `./scripts/create-gradle-wrapper.sh` - создаёт Gradle Wrapper через доступный Gradle или cached distribution.
- `./scripts/build-debug.sh` - собирает debug APK.
- `./scripts/install-debug.sh` - устанавливает debug APK на подключённое устройство.
- `./scripts/lint.sh` - запускает Android lint для debug variant.
- `./scripts/test.sh` - запускает unit tests.
- `./scripts/clean.sh` - очищает Gradle build outputs.

## Ручные команды

```bash
./gradlew :app:assembleDebug
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
