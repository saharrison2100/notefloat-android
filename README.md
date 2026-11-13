# NoteFloat

Capture a note, a voice memo, a reminder or a clipboard item **without leaving the app you are in**.

NoteFloat is a native Android app. Its centrepiece is a translucent glass handle that floats on the
left or right screen edge over every other app, from a foreground service. Tap it and a compact panel
opens with quick text capture, voice recording, recent notes, clipboard capture, reminders and search.

Version 1.3.0 (versionCode 13), Android 7.0 and up, targeting Android 16 (API 36), built from Kotlin with no web views and no
third-party UI kit.

## Download

Signed release APK: see the Releases tab. The build is signed with the `notefloat` key
(SHA-256 fingerprint `39:1e:27:23:fd:a8:ad:f2:77:da:09:3b:3a:a2:05:72:18:b1:fa:f0:bb:42:28:fd:24:59:c8:51:c7:7e:75:96`),
so 1.0.0 and 1.1.0 upgrade over each other.

1. Open the APK on the phone and allow installs from that source.
2. Open NoteFloat, follow the welcome screen, and grant **Display over other apps**.
3. Optionally grant notifications (reminders) and the microphone when you first record.

## What is in 1.3.0

Platform upgrade so the app is distributable again after Google Play's API 36 deadline.

**targetSdk 36** - Play has required Android 16 targets for new apps and updates since 31 August 2026,
so 1.2.0 could no longer be uploaded. Edge-to-edge is enforced at this target and the shell pads for
system bars and the keyboard rather than opting out (the opt-out is ignored from API 36 anyway).

**Toolchain** - AGP 8.13.2, Kotlin 2.3.21, KSP 2.3.14, Gradle 8.14.3 via a wrapper that is now
committed. Room 2.8.5, WorkManager 2.11.2, Material 1.13.0, lifecycle 2.10.0, coroutines 1.11.0.
AGP 9 is the next step, not this one: it replaces the Kotlin plugin with built-in Kotlin.

**Launcher shortcuts** - long-press the icon for New note or Voice memo.

**Themed icon** - monochrome layer, so Android 13+ tints NoteFloat to match the wallpaper.

## What is in 1.2.0

**Floating sidebar** - genuine `TYPE_APPLICATION_OVERLAY` windows owned by a foreground service.
Drag vertically to move, drag across the middle of the screen to swap edge, tap to expand, double tap
for quick capture, long press to hide. Opacity, edge, height and handle side are all configurable.
The service stops itself if the overlay permission is revoked.

**Notes** - rich markup (headings, bold, italic, bullets, numbered lists, checklists), categories,
tags, colour labels, pin, archive, duplicate, trash and restore, search, autosave with draft recovery.

**Voice notes** - record, pause, resume, play, rename, attach to a note. `MediaRecorder` to app-private
storage; the microphone is requested only when you tap record and nothing records in the background.

**Reminders** - date and time, daily/weekly/monthly repeat, notification with Done and Snooze, history,
survives reboot and clock changes via `AlarmManager` plus a boot receiver.

**Clipboard capture** - reads the clipboard only when you tap it, previews, lets you edit, then saves
as a note in a category. No background clipboard monitoring.

**Cloud sync (optional)** - account sign-up/sign-in, then offline-first sync to the Base44 backend in
`base44/`. Local Room is the source of truth while offline; conflicts are kept as copies, never dropped.

**Sign in with Google** - Google Identity Services, no client id or secret in the app. The token is
verified with Google by `base44/nfAuth.ts` before a session is issued. Password sign-in still works.

**Google Drive backup (optional)** - one file, `notefloat-notes.json`, in the app's own hidden
`appDataFolder`, using the `drive.appdata` scope and nothing wider. Back up, restore, or merge both
ways with the same never-discard rules as the account sync. Registering the app once in a Google
Cloud project is required; see `documentation/GOOGLE_DRIVE_SETUP.md`.

**Privacy, updated** - Drive is the user's own storage, not ours: the app holds no client secret, asks
for the narrowest scope that works, and cannot see any other file in that Drive.

**Privacy** - no analytics, no ad SDKs, no third-party libraries beyond AndroidX and Kotlin. Audio stays
on the device unless you enable audio upload. Account deletion removes the server data in one action.
Local JSON export in Storage and sync.

## Repository layout

```
android/            Gradle project (Kotlin, minSdk 24, targetSdk 36, wrapper committed)
base44/             backend function sources (deployed separately, see documentation/API_SPECIFICATION.md)
documentation/      architecture, API contract, build instructions, Google setup, changelog
legacy-v1.0/        the original single-file 1.0.0 app, kept for reference
tests/              notes and fixtures for manual QA passes
```

## Build

See `documentation/BUILD_INSTRUCTIONS.md`. Short version:

```
cd android
./gradlew :app:assembleRelease    # needs ANDROID_HOME and a JDK 17
```

Signing reads `android/keystore.properties` (git-ignored) or `NF_STORE_FILE`, `NF_STORE_PASSWORD`,
`NF_KEY_ALIAS`, `NF_KEY_PASSWORD` environment variables. Nothing secret is committed.

## Limits worth knowing

1. Play Store distribution needs a Play-signed build; the APK here is self-signed for direct install.
2. Android 13+ asks for notification permission at runtime; without it reminders still save but stay silent.
3. Exact alarms need the "Alarms and reminders" permission on Android 12+; without it a reminder can
   arrive a few minutes late.
4. Some OEM builds (Xiaomi, Oppo, Huawei) kill foreground services aggressively. Exempting NoteFloat
   from battery optimisation is the fix.
