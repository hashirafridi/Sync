# Sync

Personal Android tool for publishing to Facebook Pages: pick a Page, supply media either
from the phone or by pasting a video URL, edit the caption, publish through the official
Meta Graph API.

Sideloaded only. Never goes near the Play Store.

Application id: `com.hashir.sync`

---

## Modules

| Module | What it is | Why |
|---|---|---|
| `:core-api` | **Plain Kotlin/JVM.** All Graph API calls, DTOs, token exchange. | No Android dependency, so a website can reuse it verbatim later, and it unit-tests on the JVM in milliseconds with MockWebServer. |
| `:downloader` | Android library. yt-dlp wrapper. | Quarantines ~100 MB of native payload and its packaging rules. Also the seam that lets a server-side downloader replace it later. |
| `:app` | Android app. Compose UI, Room, tokens, WorkManager, Facebook login SDK. | Everything device-specific. |

All three compile into one APK. There is no server; the phone calls `graph.facebook.com`
directly.

Two rules keep `:core-api` portable:

1. **No Android types cross the boundary.** No `Context`, `Uri`, `Bitmap`, `LiveData`.
   Media is passed as `File`/`InputStream`. A stray `import android.*` will not resolve,
   because the module does not depend on the Android SDK at all.
2. **Credentials are constructor parameters, never globals.** `GraphConfig(appId, appSecret)`.
   Nothing in `:core-api` reads `BuildConfig`. Today `:app` passes the secret in; a server
   would pass it from an environment variable.

---

## Setup

Credentials and key hashes: see `META-APP.local.md` (git-ignored).

Meta app settings that must match this project:

| Field | Value |
|---|---|
| Package Name | `com.hashir.sync` |
| Default Activity Class Name | `com.hashir.sync.MainActivity` |
| Mode | **Development** (never submit for App Review) |

Each teammate must be added under **App Roles** as a Tester and accept the invite.
Without that their login returns zero Pages. This is a dashboard setting, not app code.

`local.properties` (git-ignored) supplies the build with:

```properties
sdk.dir=<android sdk path>
FACEBOOK_APP_ID=...
FACEBOOK_CLIENT_TOKEN=...
FACEBOOK_APP_SECRET=...
```

`FACEBOOK_APP_SECRET` is required for the short-lived to long-lived token exchange and
therefore ships inside the APK. Acceptable **only** because this is sideloaded to people
who already hold a role on the Meta app. A blocker for any public release.

The app builds and runs with these blank; the dashboard shows a "not configured" state.

---

## Building

Toolchain on this machine:

| Tool | Location |
|---|---|
| JDK 21 | `C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot` |
| Android SDK | `C:\Users\HP Computers\AppData\Local\Android\Sdk` |
| Android Studio | `C:\Program Files\Android\Android Studio` |
| Gradle home | `H:\gradle-home` |

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot"
$env:GRADLE_USER_HOME = "H:\gradle-home"

.\gradlew assembleDebug      # build
.\gradlew test               # JVM tests, no device needed
.\gradlew installDebug       # build and push to a connected phone
```

`GRADLE_USER_HOME` is redirected to H: deliberately. The default is `C:\Users\...\.gradle`,
and C: does not have room for the Gradle cache alongside Android Studio and the SDK.

---

## Running on a phone

1. Phone: Settings, About, tap **Build number** seven times, then Developer Options and
   enable **USB debugging**.
2. Plug in over USB and accept the RSA fingerprint prompt.
3. Verify and install:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
   .\gradlew installDebug
   ```

The emulator is not a substitute: no Facebook app installed (so the login branch differs)
and no hardware video encoder.

---

## Progress

- [x] **M1** Skeleton: modules, Hilt, Room, navigation, theme, dashboard
- [x] **M2** Facebook login, long-lived token exchange, Keystore-encrypted token store
- [x] **M3** Page import with pagination, grouped dashboard, CREATE_CONTENT gating
- [x] **M4** Multi-account: logOut dance, WEB_ONLY, per-account refresh and disconnect
- [x] **M5** Photo and text publishing, scheduling, error classification
- [x] **M6** Page detail screen, post editor, Photo Picker (no storage permission)
- [x] **M7** WorkManager queue, foreground service, retry, queue/history screen
- [x] **M8** Video publish: `video_reels` three-phase upload, `/videos` fallback
      (built and unit-tested; not yet exercised on a device. Mid-file resume across
      process death still needs the upload session id persisting and reusing.)
- [x] **M9** Downloader: probe, resolution chips, download with progress, caption prefill,
      in-place yt-dlp update (built; not yet exercised on a device)
- [x] **M10** Media3 re-encode safety net for VP9/Opus sources
- [x] **M11** Scheduling, reels quota indicator, settings, search, upload progress

Tests: 40 green (31 in `:core-api`, 9 in `:downloader`).

Full plan: `C:\Users\HP Computers\.claude\plans\i-want-to-build-eager-pearl.md`
