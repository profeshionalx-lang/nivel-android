@AGENTS.md

> **Рабочий процесс:** исследование → план в issue → код → code review субагентом → PR. Подробно: [WORKFLOW.md](./WORKFLOW.md)

# Nivel Android — нативное приложение для тренеров по паделю

## Что за проект

Нативный Android-клиент Nivel (Kotlin + Jetpack Compose). Тренер ведёт сессии, записывает аудио
тренировки в фоне, получает AI-инсайт-карточки; ученик видит прогресс. Полный порт веб-версии.

**Бэкенд и база — общие с вебом** (`profeshionalx-lang/NIVEL`). Это приложение — ещё один клиент
того же REST API и той же базы Supabase. Не дублируй бизнес-логику и не создавай отдельную базу.

## Стек

| Слой | Технология |
|------|-----------|
| Язык / UI | Kotlin / Jetpack Compose |
| Навигация | Navigation Compose |
| DI | Hilt |
| Сеть | Retrofit + OkHttp + kotlinx.serialization |
| Async | Coroutines + Flow |
| Кэш | Room (кэш чтения, не источник правды) |
| Токен | DataStore (encrypted) |
| Запись | Foreground Service `microphone` + AudioRecord/MediaRecorder (AAC/m4a) |
| Заливка | WorkManager |
| Auth | Гречка (WebView → Firebase ID token) + Google Sign-In fallback → bearer-JWT |

## Архитектура (целевая)

```
app/
  di/                — Hilt-модули
  data/
    remote/          — Retrofit API (/api/v1/*), DTO, интерсептор с bearer-токеном
    local/           — Room (DAO, entities) — кэш
    repository/      — репозитории: единый вход для UI, склейка remote+local
  domain/            — модели и use-cases
  feature/
    auth/            — вход (WebView Гречки + Google)
    dashboard/       — дашборд
    sessions/        — список и карточка тренировки
    recorder/        — фоновая запись + статусы заливки
    insights/        — инсайт-карточки
    goals/           — цели
    students/        — управление учениками (trainer)
  service/
    RecordingService — foreground service записи
    upload/          — WorkManager-воркеры заливки
```

## Ключевые решения

- **REST, а не прямой Supabase**: приложение НЕ ходит в Supabase напрямую. Только через `/api/v1/*`
  бэкенда NIVEL (там авторизация и бизнес-логика). Это держит одну сущность с вебом.
- **Bearer вместо куки**: токен получаем обменом Firebase ID token на эндпоинте бэкенда, храним в
  DataStore, шлём в `Authorization: Bearer`.
- **Room — только кэш**: источник правды всегда сервер.
- **Запись переживает всё**: foreground service + WorkManager; запись на 90 мин не теряется при локе,
  сворачивании, обрыве сети.

## Контракт API

Endpoints `/api/v1/*` определяются и реализуются в эпике «Фундамент» (репо NIVEL). Актуальный контракт —
в дизайн-доке `docs/plans/2026-06-03-nivel-android-native-design.md` и в issue эпика «Фундамент».
Если endpoint ещё не готов — мокай по контракту, помечай TODO с номером issue, двигай UI.

---

## Git-правила

**Никогда не пушить напрямую в `main`.** Только feature-ветки и PR.

```bash
git checkout -b feat/<issue-number>-<slug>
git push -u origin feat/<issue-number>-<slug>
gh pr create --title "..." --body "Closes #<number> ..."
```

Один PR — одна логическая задача.

## Работа с GitHub Issues

Те же правила, что в основном репо: взять `ready-for-agent` → `in-progress` (+ Status на доске) →
ветка → PR (`Closes #N`) → `in_review` → комментарий с результатом. Доска:
https://github.com/users/profeshionalx-lang/projects/4

## Зависимости между задачами

Задачи помечены трек-метками в теле (например `Параллельно с: …`, `Зависит от: #…`). Бери только те,
у которых все зависимости закрыты или замокаемы по контракту. Несколько агентов могут брать задачи из
одного трека параллельно, если у них нет общих файлов.
