# Changelog

## 1.2.0 - 10 November 2026

Google account sign-in and Google Drive backup.

- **Sign in with Google.** Google Identity Services, no client id or secret stored in the app or the
  APK. The access token is verified with Google by the account service (userinfo plus tokeninfo)
  before a session is issued, so the client cannot claim an email address it does not own. Password
  sign-in still works, and an account created through Google refuses a password attempt with a clear
  message instead of a server error.
- **Google Drive backup.** One snapshot file, `notefloat-notes.json`, in the app's own hidden
  `appDataFolder`, reached with the `drive.appdata` scope and nothing wider. Back up now, restore, or
  two-way merge; the background sync worker now also runs the Drive backup on the same schedule when
  Drive is connected.
- **Merge rules for Drive**, the same never-discard policy as the account sync: identical rows collapse,
  a genuine two-sided edit keeps both, the newer edit keeps the id and the other becomes
  "(conflict copy)". Audio bytes are never part of the snapshot and a local recording path is never
  replaced by a remote row.
- New **Google and Drive** screen, reachable from More and from Storage and sync, showing which Google
  account is connected, the last backup time, and the disconnect action.
- `documentation/GOOGLE_DRIVE_SETUP.md`: the one-time Google Cloud registration, with the package name
  and the release certificate SHA-1 the console asks for.
- 10 new JVM unit tests (21 in total) for the merge rules and the snapshot codec, including a foreign
  or truncated file that must not crash or wipe anything.

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
