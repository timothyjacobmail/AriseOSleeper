# Arise O Sleeper!

A standalone Android verse alarm app, extracted from Install Lock. Open this folder directly in Android Studio.

- Application ID: `com.example.ariseosleeper`
- App module: `app`
- Java: 21
- Android SDK: compile API 37.1, target API 36, minimum API 30
- No dependency on the Install Lock or Delayed Secrets projects

## Morning briefing

Open **Morning briefing** in alarm settings. After the final successful wake-up check, the app reads one briefing each local day, including while the screen is locked. Android requires one unlock after restarting before personal data is available. If a routine finishes before that unlock, return to the alarm within an hour after unlocking to hear the pending briefing.

- Allow notification access for WhatsApp, WhatsApp Business and Gmail previews. Collection starts after access is granted and keeps at most 500 deduplicated previews. Previews older than 24 hours are pruned during collection or briefing. Each briefing summarizes new previews since the last completed briefing within the previous 12 hours. Android may hide sensitive content. Notification previews reflect messages received by the phone.
- Allow calendar access to read upcoming and ongoing events today and tomorrow from calendars synced to the phone, including recurring and all-day events.
- **Connect Google Tasks** requests only read-only task access and checks incomplete tasks due today or tomorrow across task lists. Enable Google Tasks API and create an Android OAuth client for the package and signing SHA-1 displayed in **Google Tasks setup help**. Configure the consent screen and test users as required. Maps demo keys cannot authorize Tasks. Reconnect if Google authorization expires.
- **Read briefing now**, **Replay last briefing**, and **Stop reading** control speech. Speech uses Android text-to-speech and media volume. Replay uses the saved text without fetching Tasks again.
- Notification data and the generated summary stay in private app storage. No AI service receives them. Tasks sends an authorized request to Google only when preparing a briefing or checking the connection. Speech engines may use their own online voices depending on Android settings.
- If a permission or connection is missing, the briefing says which source is unavailable. An active wake-up routine takes priority over briefing playback.

## Build and test

Configure the Android SDK in an untracked `local.properties` file. On Windows:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

Run device tests only on an isolated emulator:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Install and open

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.ariseosleeper/.VerseAlarmActivity
```

The package name and version are unchanged by the folder migration, so this APK can update the existing Arise O Sleeper! installation without resetting its data. Install Lock's policies can still restrict the app on a managed phone.

See [the alarm guide](docs/verse-alarm.md) for permissions, passage entry, alarm behavior, and device acceptance checks. The Inter font license is included in `app/src/main/assets/licenses/Inter-OFL.txt`.

## Source history

Migrated from the `ariseosleeper` module at Install Lock commit `30e2a09c49e1361a88dbfd331a3f9e1f5f759f8d`. The new project has its own Gradle settings, wrapper, version catalog, sources, resources, and tests. The original code remains recoverable from the Install Lock repository history.

## Migration validation

On 2026-10-27, the standalone debug APK and instrumentation APK built successfully, all 28 JVM tests passed, and the debug signing certificate matched the previously installed alarm app. All 31 migrated source, resource, and test files were checked byte for byte before the old module folder was removed. The 18 alarm instrumentation tests had passed on an isolated emulator before this directory-only migration; they were compiled again here. No phone installation or settings were changed during the migration.
