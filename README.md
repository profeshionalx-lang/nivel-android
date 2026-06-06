# Nivel Android

Нативное Android-приложение для **Nivel** — платформы для тренеров по паделю и их учеников.

Полный нативный порт (Kotlin + Jetpack Compose). Главная фича — **фоновая запись тренировки**
(foreground service): тренер пишет звук в кармане с заблокированным экраном, запись автоматически
уходит в существующий конвейер «транскрипция → AI-инсайты».

## Важно: одна сущность с вебом

Это приложение и веб-версия Nivel (`profeshionalx-lang/NIVEL`) — **два клиента одного бэкенда** и
**одной базы Supabase**. База — единственный источник правды. Всё, что тренер делает из приложения,
мгновенно видно ученику в вебе. Никакой второй, отдельной системы нет.

- **Бэкенд + REST API:** `profeshionalx-lang/NIVEL` (Next.js), endpoints `/api/v1/*`
- **База:** Supabase (общая с вебом)
- **Этот репо:** только Android-клиент

## Дизайн и план

Полный дизайн-документ и роадмап по этапам:
[`docs/plans/2026-06-03-nivel-android-native-design.md`](docs/plans/2026-06-03-nivel-android-native-design.md)

## Стек

| Слой | Технология |
|------|-----------|
| Язык | Kotlin |
| UI | Jetpack Compose |
| Навигация | Navigation Compose |
| DI | Hilt |
| Сеть | Retrofit + OkHttp + kotlinx.serialization |
| Асинхронность | Coroutines + Flow |
| Локальный кэш | Room (кэш чтения; источник правды — Supabase) |
| Хранение токена | DataStore |
| Фоновая запись | Foreground Service (`microphone`) + AudioRecord/MediaRecorder |
| Фоновая заливка | WorkManager (устойчива к обрыву сети и закрытию приложения) |
| Авторизация | Гречка (Chrome Custom Tabs + deep link `nivel://auth/callback`, перехват Firebase ID token) + Google Sign-In (fallback) → обмен на bearer-JWT |

## Сборка и установка APK на телефон (сайдлоад, без Play Store)

Приложение ставится напрямую APK-файлом — магазин не используется.

### Где взять APK

1. **Через CI (рекомендуется).** Workflow `.github/workflows/android-ci.yml` собирает APK на
   каждый push в `main` и каждый PR. Открой вкладку **Actions** → нужный запуск → раздел
   **Artifacts** → скачай `nivel-trainer-debug-apk` (zip с `app-debug.apk`).
   - На push тега `v*` (например `v0.1.0`) дополнительно собирается **release** APK
     (`nivel-trainer-release-apk`) — подписанный, если в репозитории заданы секреты подписи
     (см. ниже). Без секретов release-APK получается **unsigned** и на телефон не ставится.
2. **Локально.**
   ```bash
   ./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
   ./gradlew assembleRelease    # → app/build/outputs/apk/release/app-release*.apk
   ```
   `assembleRelease` без настроенного keystore даёт **unsigned** APK (`app-release-unsigned.apk`).

### Установка на телефон

1. Скинь APK на телефон (кабель, Telegram «Избранное», облако — любой способ).
2. Включи установку из неизвестных источников: **Настройки → Приложения → Специальный доступ →
   Установка неизвестных приложений** → выбери приложение, через которое открываешь APK
   (Files / браузер), и разреши установку. На старых Android: **Настройки → Безопасность →
   Неизвестные источники**.
3. Открой APK в файловом менеджере и нажми «Установить».

> Для повседневной установки достаточно **debug** APK — он подписан автоматическим debug-ключом
> и сразу ставится. **Release** нужен только для финальной раздачи.

## Подпись release-сборки (signing)

Release-APK подписывается настоящим keystore. Keystore — секрет, его **нет** в репозитории.

### Локально

1. Сгенерируй keystore (один раз):
   ```bash
   keytool -genkey -v -keystore release.keystore -alias nivel \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Создай `keystore.properties` в корне репозитория (он в `.gitignore`):
   ```properties
   storeFile=release.keystore
   storePassword=ПАРОЛЬ_ХРАНИЛИЩА
   keyAlias=nivel
   keyPassword=ПАРОЛЬ_КЛЮЧА
   ```
   Шаблон — `keystore.properties.example`.
3. `./gradlew assembleRelease` теперь даст подписанный `app-release.apk`.

### В CI (GitHub Secrets)

Чтобы CI собирал **подписанный** release на тег `v*`, добавь секреты репозитория
(`Settings → Secrets and variables → Actions`):

| Секрет | Что это |
|--------|---------|
| `SIGNING_KEY_BASE64` | keystore в base64: `base64 -w0 release.keystore` |
| `SIGNING_STORE_PASSWORD` | пароль хранилища |
| `SIGNING_KEY_ALIAS` | алиас ключа (например `nivel`) |
| `SIGNING_KEY_PASSWORD` | пароль ключа |

Без этих секретов CI всё равно соберёт release-APK, но **unsigned** (с предупреждением в логе).

> `google-services.json` для CI **не требуется** — плагин Google Services сейчас не подключён.
> Если нативный Google Sign-In включат (issue #5), добавь `google-services.json` как секрет
> `GOOGLE_SERVICES_JSON` и шаг в workflow, материализующий его перед сборкой.

## Рабочий процесс

См. [WORKFLOW.md](WORKFLOW.md) и [AGENTS.md](AGENTS.md). Задачи ведутся через GitHub Issues
(эпики + микрозадачи) и доску [NIVEL Android](https://github.com/users/profeshionalx-lang/projects/4).
