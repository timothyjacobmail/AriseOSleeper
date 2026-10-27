# Arise O Sleeper!

A standalone Android verse alarm app, extracted from Install Lock. Open this folder directly in Android Studio.

- Application ID: `com.example.ariseosleeper`
- App module: `app`
- Java: 21
- Android SDK: compile API 37.1, target API 36, minimum API 30
- No dependency on the Install Lock or Delayed Secrets projects

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
