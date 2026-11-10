# Architecture

## Layers

```
ui/        14 screens, built with a tiny view toolkit (Ui.kt) instead of XML layouts
floating/  OverlayService + HandleController + PanelController + Glass (the overlay windows)
notes/     NoteRepository (CRUD, autosave, draft recovery) and RichText (markup -> spans)
voice/     VoiceRecorder (MediaRecorder, pause/resume, playback)
reminders/ ReminderScheduler, ReminderReceiver, BootReceiver
clipboard/ ClipboardCapture (user-initiated read, preview, save)
networking/Api (HTTPS + bearer token), SyncEngine (push/pull), SyncWorker (WorkManager)
storage/   Room entities, DAOs, database, Prefs, SecureStore
```

Room is the single source of truth for the UI. The UI never talks to the network; `SyncEngine` is the
only thing that reads and writes over HTTPS, and it only moves rows between Room and the backend.

## Why the overlay is built in code

The floating windows are added with `WindowManager` from a foreground service, so they cannot use an
AppCompat theme or an inflated layout that expects an Activity context. Views are assembled
programmatically (`Glass.kt` for the translucent surfaces) which also keeps the handle cheap to
rebuild when the user changes edge, size or opacity.

Overlay windows use `FLAG_NOT_FOCUSABLE` while collapsed so typing in the app underneath is never
interrupted; the panel requests focus only when a text field is opened.

## Data model

- `NoteEntity` - id (String, client generated), title, body (markup text), categoryId, colorLabel,
  pinned, archived, trashed, trashedAt, conflictOf, createdAt, updatedAt, serverUpdatedAt, syncState.
- `CategoryEntity`, `TagEntity`, `VoiceRecordingEntity`, `ReminderEntity`, `ClipboardCaptureEntity`,
  `SyncLogEntity` - same id/timestamps/syncState convention.
- `syncState` is CLEAN, DIRTY or DELETED; `SyncEngine` pushes DIRTY rows and tombstones them on delete,
  so a delete on one device reaches the others instead of the note reappearing.

## Sync

Offline first. Every edit marks its row DIRTY. A sync round trip pushes dirty rows with the last
server cursor and receives changes since that cursor. Conflicts are never resolved by throwing data
away: the losing version becomes a "<title> (conflict copy)" note. Full contract in
`API_SPECIFICATION.md`.

## Permissions

| Permission | When it is requested |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | onboarding, before the sidebar starts |
| `POST_NOTIFICATIONS` | before the first reminder or when starting the sidebar on Android 13+ |
| `RECORD_AUDIO` | first tap of record |
| `SCHEDULE_EXACT_ALARM` | optional, when the user wants reminders to the minute |

Each request goes through `PermissionGateActivity`, which explains the reason, opens the right system
screen, and closes. The app degrades instead of failing when a permission is refused.

## Known gaps in 1.1.0

1. Speech-to-text transcription is scaffolded (`VoiceRecordingEntity.transcript`) but no transcription
   provider is wired, so the field stays empty.
2. Audio upload to the cloud is off by default and there is no upload path yet; only voice metadata syncs.
3. No instrumented tests, because the build environment has no emulator. Verification for 1.1.0 is
   compile + unit tests + static inspection of the merged manifest and dex, plus live tests of the
   backend functions.
