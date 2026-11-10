# Google sign-in and Google Drive: one-time setup

NoteFloat asks Google for access when the user taps a button, and Google only answers for apps that
are registered in a Google Cloud project. That registration is a one-time job of about three minutes
and it is done once by whoever publishes the app, not by every user.

Nothing secret goes into the app. There is no client id, no client secret and no API key in the source
or in the APK: Play services resolves the registration from the package name and the signing
certificate.

## What the app already knows about itself

| Field | Value |
| --- | --- |
| Package name | `com.notefloat.app` |
| Release certificate SHA-1 | `93:CD:E9:6C:A7:ED:BA:CC:CF:F7:62:74:FE:31:07:10:EF:3F:C8:30` |
| Scopes requested | `userinfo.email`, `userinfo.profile`, `drive.appdata` |

`drive.appdata` is the narrowest Drive scope that exists. It reaches only the app's own hidden folder
in the user's Drive. NoteFloat cannot list, read or change any other file in that Drive, and no other
app can read NoteFloat's folder.

## Steps

1. Open https://console.cloud.google.com and create a project, or pick an existing one. Any name works.
2. **APIs and services, Library**: enable **Google Drive API**. Sign-in itself needs no API enabled,
   but the backup does.
3. **APIs and services, OAuth consent screen**: choose **External**, fill in the app name, the support
   email and the developer email. Save.
4. On the same screen, **Data access, Add or remove scopes**: add
   `.../auth/userinfo.email`, `.../auth/userinfo.profile` and `.../auth/drive.appdata`.
5. **Credentials, Create credentials, OAuth client ID**:
   - Application type: **Android**
   - Package name: `com.notefloat.app`
   - SHA-1 certificate fingerprint: the value in the table above
   - Create.
6. Still on the consent screen, add the Google accounts that will use the app under **Test users**.
   While the app is in **Testing** mode only those accounts can sign in, which is fine for personal use
   and needs no verification. Publishing it to "In production" for the `drive.appdata` scope requires
   Google's review, which is only worth doing if the app is handed to strangers.
7. Install NoteFloat 1.2.0 or later, open **More, Google and Drive**, tap **Continue with Google**.

## If you test with a debug build

`gradlew :app:assembleDebug` signs with the local debug keystore, which has a different fingerprint.
Add a second Android OAuth client with the debug SHA-1:

```
keytool -list -v -alias androiddebugkey -keystore ~/.android/debug.keystore \
  -storepass android -keypass android | grep SHA1
```

The debug package name is `com.notefloat.app.debug`, so use that in the client as well.

## What goes wrong, and what the message means

| Message | Cause |
| --- | --- |
| "This build is not registered for Google access yet" | Step 5 was not done, or the SHA-1 does not match the certificate that signed the installed APK. Recheck with `apksigner verify --print-certs NoteFloat.apk`. |
| "Add a Google account to this phone first" | The phone has no Google account added in Android settings. |
| "Google Play services is out of date" | Update Play services from the Play Store. |
| "Access blocked: NoteFloat has not completed verification" | The account is not in the Test users list while the app is in Testing mode. |
| "Google Drive access expired" | Access tokens last about an hour and the renewal was declined. Tap Connect Google Drive again. |

## Optional hardening: pin the audience on the server

The account service verifies every Google token with Google before it issues a session: it calls
`userinfo` (which account is this) and `tokeninfo` (was this token really issued to an app, and is it
still valid). That already stops a client from simply claiming an email address.

If you want the account service to accept tokens only from your own project, set the environment
variable `GOOGLE_OAUTH_AUDIENCE` on the `nfAuth` function to the client id from step 5 (comma
separated if there is more than one). Tokens minted for any other project are then refused.
