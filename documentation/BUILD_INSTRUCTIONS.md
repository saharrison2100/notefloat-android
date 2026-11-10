# Build and release

## Requirements

- JDK 17
- Android SDK with platform 34 and build-tools (set `ANDROID_HOME`)
- Gradle 8.7 (or the wrapper)

## Debug build

```
cd android
gradle :app:assembleDebug
```

## Release build

Signing values are read from `android/keystore.properties` (git-ignored):

```
storeFile=/absolute/path/notefloat-release.jks
storePassword=...
keyAlias=notefloat
keyPassword=...
```

or from the environment as `NF_STORE_FILE`, `NF_STORE_PASSWORD`, `NF_KEY_ALIAS`, `NF_KEY_PASSWORD`.
Without either, Gradle still builds but produces an unsigned APK.

```
cd android
gradle :app:assembleRelease
```

Output: `android/app/build/outputs/apk/release/app-release.apk` (release, minified, signed).

## Checks before publishing

```
gradle :app:testDebugUnitTest                      # 11 unit tests on the note markup engine
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -3
```

`aapt2 dump badging` should report `com.notefloat.app`, `versionCode 11`, `versionName 1.1.0`,
`sdkVersion 24`. The certificate must stay the same across releases or Android refuses to upgrade
an installed copy.

## Backend

The two functions in `base44/` are deployed to Base44 as `nfAuth` and `nfSync`; the APK is compiled
against `API_BASE_URL` in `android/app/build.gradle.kts`. Changing the deployment means rebuilding
the APK, because the URL is baked into `BuildConfig`.
