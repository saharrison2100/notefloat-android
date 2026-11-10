# NoteFloat

A real Android note app with a floating sidebar: a draggable tab that sits on top of
Chrome, WhatsApp or anything else, so a note can be captured without switching apps.

This is a native Android application, not a web page. The floating tab is an overlay
window drawn by a foreground service, so it stays visible across the whole system.

## Download

Grab the signed APK from the Releases page, or build it yourself.

Android 7.0 (API 24) and above. On first run, open the app and tap
**Turn on floating tab**, then grant **Display over other apps**.

## What it does

- Floating tab on the left or right edge, drag it up and down, snaps to the nearest edge
- Tap the tab for a quick note panel: type, save, close, with the keyboard working
- Long press the tab to hide it; the notification brings it back
- Full notes list with search, pin to top (long press a row), edit and delete
- Every note stored locally in SQLite, survives restarts
- Light and dark themes
- Foreground service with a persistent notification, as Android requires for overlays

## Permissions and why

| Permission | Why |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | Draws the floating tab and quick note panel above other apps |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keeps the overlay alive and honest, with a visible notification |
| `POST_NOTIFICATIONS` | Shows the ongoing notification with show/hide and stop actions |

No internet permission is requested. The app cannot phone home, and nothing leaves the device.

## Build

No Gradle, no Android Studio. The APK is assembled directly from the SDK build tools.

```bash
export ANDROID_HOME=/path/to/android-sdk   # needs build-tools 34.0.0 and platforms/android-34
export JAVA_HOME=/path/to/jdk17
./build.sh
```

Output: `NoteFloat-1.0.0.apk`, signed with `notefloat-release.jks` (git-ignored, generated on
first build). Keep that keystore: Android only accepts updates signed with the same key.

Pipeline: `aapt2 compile` -> `aapt2 link` -> `javac` -> `d8` -> `zipalign` -> `apksigner`.

## Project layout

```
AndroidManifest.xml
src/com/notefloat/app/
  MainActivity.java     notes list, search, editor, permission flow
  OverlayService.java   foreground service, overlay tab, quick note panel
  NoteStore.java        SQLite storage
  Note.java             model
  NoteAdapter.java      list rows
res/                    layouts, colours, light and dark theme, icons
build.sh                the whole build, start to finish
```

## Play Store notes

Publishing needs a signed AAB, a declared use for `SYSTEM_ALERT_WINDOW` in the Play
Console, and a short video showing the overlay. The overlay-only design is compliant,
but the declaration is mandatory.
