# Changelog

## 1.1.0 - 10 November 2026

Complete native rebuild. The 1.0.0 file is preserved in `legacy-v1.0/`.

- Kotlin + Gradle project with Room, WorkManager, coroutines and Material 3 theming.
- Floating sidebar rebuilt: edge switching by drag, adjustable opacity and height, double tap for
  quick capture, long press to hide, notification actions (Show/Hide, Quick note, Stop).
- All 14 screens: welcome, dashboard, all notes, quick capture, voice, reminders, clipboard history,
  categories, editor, search, floating settings, settings, storage and sync, more.
- Notes: rich markup, categories, tags, colours, pin, archive, duplicate, trash, restore, draft recovery.
- Voice recording with pause/resume, playback, rename and attach to a note.
- Reminders with repeat, snooze, done, history, reboot and time-change recovery.
- Clipboard capture, user-initiated only.
- Optional cloud sync: Base44 account service (PBKDF2-SHA256, 100k iterations, server-side sessions)
  and a sync service with last-writer-wins plus conflict copies.
- Fixes made while getting 1.1.0 to a clean release build:
  - adaptive icon split into v26 and a legacy fallback for API 24-25;
  - `ui.button` call sites corrected for the no-argument lambda;
  - R8 keep rules for Tink annotations pulled in by `security-crypto`;
  - password iteration count reduced to the platform's 100k cap.
- 11 JVM unit tests covering the note markup engine (`gradle :app:testDebugUnitTest`).

## 1.0.0 - 10 November 2026

First release: floating overlay tab, local notes list, search, pin, light and dark themes.
