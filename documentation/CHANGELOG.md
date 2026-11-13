# Changelog

## 1.3.0 - 13 November 2026

Platform and toolchain upgrade, so the app targets Android 16 and can be distributed again.

- **targetSdk 36 (Android 16)**, compileSdk 36. Google Play has required API 36 for new apps and
  updates since 31 August 2026, so 1.2.0 could no longer be uploaded. Edge-to-edge is now enforced by
  the platform; the shell already pads for system bars and the keyboard, and that path was verified
  against the new behaviour rather than opted out of (the opt-out flag is ignored from API 36).
- **Toolchain**: AGP 8.5.2 to 8.13.2, Kotlin 1.9.24 to 2.3.21, KSP 1.9.24-1.0.20 to 2.3.14,
  Gradle wrapper 8.14.3 committed to the repo (it was missing, so builds depended on a locally
  installed Gradle). `kotlinOptions` migrated to the `compilerOptions` DSL, which Kotlin 2.3 requires.
  AGP 9 was deliberately not taken: it replaces the Kotlin plugin with built-in Kotlin and needs its
  own migration, kept as a separate step.
- **Libraries**: Room 2.6.1 to 2.8.5, WorkManager 2.9.1 to 2.11.2, core-ktx to 1.18.0, activity to
  1.12.4, lifecycle to 2.10.0, Material to 1.13.0, appcompat to 1.7.1, recyclerview to 1.4.0,
  constraintlayout to 2.2.2, coroutines to 1.11.0, play-services-auth to 21.6.0. Pinned to the
  releases that still compile against API 36; the newest core and activity lines already demand
  compileSdk 37.
- **security-crypto moves to the 1.1.0 stable release.** `EncryptedSharedPreferences` is deprecated
  there, but it is kept on purpose: swapping the storage now would make every stored session
  unreadable and silently sign everyone out. The Keystore-backed replacement is planned as its own
  change with a migration path.
- **Launcher shortcuts**: long-press the icon for New note and Voice memo. Published dynamically, with
  no shortcuts xml to drift out of sync with the app id.
- **Themed icon**: monochrome layer added so Android 13+ can tint NoteFloat with the wallpaper palette.
- Room schema export moved to a top-level `ksp` block, and one crashing lint detector
  (`NullSafeMutableLiveData`, a bug in lifecycle 2.10.0's lint) is disabled so release builds complete.

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
