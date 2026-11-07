# Egg Business Manager

An offline-first Android app for a small egg trading shop: purchases, sales, customer ledgers,
payments, reports and encrypted backups. All business data stays on the phone.

Design documents: [`specs/001-egg-business-manager/`](specs/001-egg-business-manager/) (spec, plan,
data model, contracts, tasks). Engineering rules: [`.specify/memory/constitution.md`](.specify/memory/constitution.md).

## Requirements

| Tool | Version |
|------|---------|
| JDK | 17 or newer (Android Studio's bundled JBR works) |
| Android SDK | platform 36, build-tools 36.0.0, platform-tools |
| Device / emulator | Android 12 (API 31) or newer |

`local.properties` (not committed) must point at the SDK: `sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`.

## Build and run

```powershell
./gradlew assembleDebug                       # build the debug APK
./gradlew installDebug                        # install on a running emulator/device
./gradlew :domain:test :app:testDebugUnitTest # JVM unit tests
./gradlew connectedDebugAndroidTest           # instrumented tests (needs an emulator/device)
./gradlew lint                                # Android lint
./scripts/check-no-destructive-migration.ps1  # schema-safety gate
```

## Modules

| Module | Contents | Depends on |
|--------|----------|-----------|
| `:app` | Compose UI, Material 3 theme, navigation, ViewModels, Hilt wiring | `:domain`, `:data` |
| `:domain` | Pure Kotlin: models, use cases, repository interfaces, errors | — |
| `:data` | Room + SQLCipher database, DataStore, Keystore, repository implementations | `:domain` |
| `:core-testing` | Fakes, fixed clock and test helpers | `:domain` |

Rules: `:domain` never imports Android. ViewModels call use cases only. Every financial write runs in a
database transaction. See `specs/001-egg-business-manager/plan.md`.

## Data safety

- The database is encrypted (SQLCipher). Its key is created on first launch and protected by the
  Android Keystore. Android system backup is disabled; the app's own encrypted backups (coming in a
  later phase) are the only backup path.
- Room schemas are exported to `data/schemas/` and **must be committed**. Every schema change needs an
  explicit migration and a migration test. Destructive migration is forbidden.

## Release signing

Never commit a keystore. Put signing values in `~/.gradle/gradle.properties`:

```properties
EBM_STORE_FILE=C:/path/outside/repo/egg-business-release.jks
EBM_STORE_PASSWORD=...
EBM_KEY_ALIAS=...
EBM_KEY_PASSWORD=...
```

Keep an offline backup copy of the keystore. **If it is lost, users can never upgrade their installed
app again** — they would have to uninstall, losing local data that is not backed up.

The `applicationId` (`com.eggbusinessmanager.app`) must be confirmed before the first APK is shared and
can never change afterwards.
