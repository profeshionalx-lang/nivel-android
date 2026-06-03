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
| Авторизация | Гречка (WebView, перехват Firebase ID token) + Google Sign-In (fallback) → обмен на bearer-JWT |

## Рабочий процесс

См. [WORKFLOW.md](WORKFLOW.md) и [AGENTS.md](AGENTS.md). Задачи ведутся через GitHub Issues
(эпики + микрозадачи) и доску [NIVEL Android](https://github.com/users/profeshionalx-lang/projects/4).
