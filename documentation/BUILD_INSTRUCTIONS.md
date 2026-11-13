# Build and release

## Requirements

- JDK 17
- Android SDK with platform 36 and build-tools 36.0.0 (set `ANDROID_HOME`)
- No separate Gradle install: the wrapper is committed (`./gradlew`, Gradle 8.14.3)

## Debug build

```
cd android
./gradlew :app:assembleDebug
```

## Release build

Signing values are read from `android/keystore.properties` (git-ignored):

```
storeFile=notefloat-release.jks   # relative to android/, or use an absolute path
storePassword=...
keyAlias=notefloat
keyPassword=...
```

or from the environment as `NF_STORE_FILE`, `NF_STORE_PASSWORD`, `NF_KEY_ALIAS`, `NF_KEY_PASSWORD`.
Without either, Gradle still builds but produces an unsigned APK.

```
cd android
./gradlew :app:assembleRelease
```

Output: `android/app/build/outputs/apk/release/app-release.apk` (release, minified, signed).

## Checks before publishing

```
./gradlew :app:testDebugUnitTest                   # 21 unit tests (markup engine, Drive merge)
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -3
```

`aapt2 dump badging` should report `com.notefloat.app`, `versionCode 13`, `versionName 1.3.0`,
`targetSdkVersion 36`, `sdkVersion 24`. The certificate must stay the same across releases or Android refuses to upgrade
an installed copy.

## Backend

The two functions in `base44/` are deployed to Base44 as `nfAuth` and `nfSync`; the APK is compiled
against `API_BASE_URL` in `android/app/build.gradle.kts`. Changing the deployment means rebuilding
the APK, because the URL is baked into `BuildConfig`.
