# Bro Liker Multi-Session v5

Native Android 12+ multi-session browser manager using AndroidX WebKit multi-profile APIs.

## Build with GitHub Actions
1. Upload the repository contents to GitHub, including `.github/workflows/build.yml`.
2. Open **Actions → Build Bro Liker APK**.
3. Push to the repository or choose **Run workflow**.
4. Download the `Bro-Liker-Multi-Session-v5` artifact from the successful run.

## Toolchain
- Android Gradle Plugin 9.3.0
- Gradle 9.5.0
- JDK 17
- Kotlin 2.3.21
- compileSdk 36
- minSdk 31 (Android 12+)
- Compose BOM 2026.06.00
- AndroidX WebKit 1.16.0

## Session model
There is no artificial `MAX_SESSIONS` constant. Sessions are persisted as metadata, while WebView profile data is stored by AndroidX WebKit. Only the currently opened session uses an active WebView instance, so the app does not try to keep thousands of WebViews resident in RAM. Practical capacity is constrained by device storage, WebView profile storage, and Android resource limits.

## Security note
The app does not export or display raw Facebook authentication cookies. Login state remains inside the corresponding persistent WebView profile.
