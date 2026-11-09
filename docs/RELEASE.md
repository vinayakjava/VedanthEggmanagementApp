# Egg Business Manager — Release 1.0.1

## Version information

| Item | Value |
|---|---|
| App name | Egg Business Manager |
| Application ID | `com.eggbusinessmanager.app` (permanent — never change it) |
| Version name | 1.0.1 |
| Version code | 10001 (`major*10000 + minor*100 + patch`) |
| Minimum Android | Android 12 (API 31) |
| Target / compile SDK | Android 17 (API 37, latest stable) |
| Database schema | version 1 — `data/schemas/com.eggbusinessmanager.data.db.EggBusinessDatabase/1.json` (frozen) |
| Signing | APK Signature Scheme v3 (covers every supported Android version, 12+), RSA 4096, certificate `CN=Egg Business Manager, C=IN`, valid until 2054 |
| Certificate SHA-1 | `88:DD:52:D2:FA:54:43:17:52:F4:13:23:26:01:BA:A4:84:50:11:9B` |
| Certificate SHA-256 | `9A:08:3D:05:9A:B6:77:13:C4:F4:C5:91:32:F9:C2:23:62:B4:E8:21:04:F6:CD:64:DE:62:29:D0:AE:84:1E:9B` |

The fingerprints are public information (they identify the publisher); they are needed for the Google
Drive set-up below. The passwords and the private key are never written in this repository.

## Building the signed APK

Prerequisites: JDK 17, Android SDK with platform 37 and build-tools 37, and the release keystore with
its Gradle properties (see "Signing keystore" below).

```powershell
.\gradlew clean :app:assembleRelease
```

Output: `app\build\outputs\apk\release\app-release.apk`. The release build is shrunk and obfuscated
by R8, strips debug/info logging, and is signed with the release key from `~/.gradle/gradle.properties`.
If those properties are missing the build produces an **unsigned** APK — never distribute that.

Copy it to `dist\EggBusinessManager-<version>.apk` (the `dist` folder is not committed) and record its
SHA-256 so anyone can check the file they received:

```powershell
Get-FileHash dist\EggBusinessManager-1.0.0.apk -Algorithm SHA256
```

## Release validation checklist

Run before every release; record the results in the release notes below.

1. Quality gates: `.\gradlew :domain:test :domain:koverVerify :app:testDebugUnitTest detekt :app:lintRelease`
2. All device tests on the oldest and newest supported Android: `.\gradlew :data:connectedDebugAndroidTest :app:connectedDebugAndroidTest`
   with an API 31–36 and an API 37 emulator connected (they run on both).
3. `scripts\check-no-destructive-migration.ps1` — no destructive database fallback.
4. Signature: `apksigner verify --verbose --print-certs dist\EggBusinessManager-<version>.apk`
   (v3 verified, certificate SHA-256 as above).
5. Permissions (`aapt2 dump permissions`): declared by the app — `INTERNET` (Google Drive backup only)
   and `POST_NOTIFICATIONS` (backup failure alerts); added by libraries — `WAKE_LOCK`,
   `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` (WorkManager, automatic
   backup) and `USE_BIOMETRIC`/`USE_FINGERPRINT` (fingerprint unlock); plus AndroidX's private
   `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. No dangerous permissions besides notifications.
6. Manifest: not debuggable, `allowBackup=false`, only `MainActivity` exported.
7. Smoke test of the real signed APK on a fresh emulator: `python scripts\release_check.py smoke --serial <device>`.
8. Upgrade test with real signed APKs (see "Verifying an upgrade keeps the data").

## Installation

See `docs/USER_GUIDE.md` § 1. In short: copy the APK to the phone, open it from Files, allow
"Install unknown apps" for that app, tap Install.

Developer/USB install: `adb install dist\EggBusinessManager-1.0.0.apk`

Phones that had a **development build** must uninstall it first (back up from the app first if it holds
real data): development builds are signed with the debug key, and Android only updates an app with an
APK signed by the same key.

## Updating to a new version

For the owner: install the new APK over the installed one — **do not uninstall**. All data stays.

For the developer, every new version:

1. Increase the version in `app/build.gradle.kts` (`versionMajor/Minor/Patch`); the version code must
   always go up.
2. **Never change** the application ID or the signing key.
3. **Database changes**: schema 1 is frozen. Any change to entities needs `EggBusinessDatabase.VERSION`
   + 1, the new exported schema JSON committed, an explicit `Migration` and a `MigrationTestHelper` test
   from every released version. Never use destructive migration (checked by step 3 above).
4. Run the whole validation checklist, including the upgrade test from the previous released APK.

### Verifying an upgrade keeps the data

Build the current version and a newer patch from the same sources, both release-signed:

```powershell
.\gradlew :app:assembleRelease;                         copy app\build\outputs\apk\release\app-release.apk dist\check-old.apk
.\gradlew :app:assembleRelease -PebmUpgradePatch=2;     copy app\build\outputs\apk\release\app-release.apk dist\check-new.apk
adb -s <device> uninstall com.eggbusinessmanager.app
adb -s <device> install dist\check-old.apk
python scripts\release_check.py seed --serial <device>
adb -s <device> install -r dist\check-new.apk            # update in place, data kept
python scripts\release_check.py verify --serial <device> --version 1.0.2 --build 10002
```

`seed` creates the account, a customer with an opening balance, a sale with money received on the
spot; `verify` signs in after the update and checks the customer, the balance (₹2,300 to receive),
both references (S-000001, RP-000001) and that the new version is running.

## Signing keystore — keep it safe and backed up

| File | Location |
|---|---|
| Keystore | `C:\Users\<you>\.ebm-release\ebm-release.jks` (alias `ebm-release`) |
| Password | `C:\Users\<you>\.ebm-release\keystore-password.txt` |
| Gradle settings | `C:\Users\<you>\.gradle\gradle.properties` (`EBM_STORE_FILE`, `EBM_STORE_PASSWORD`, `EBM_KEY_ALIAS`, `EBM_KEY_PASSWORD`) |

> **If the keystore or its password is lost, no update can ever be installed over 1.0.0 again.**
> Every owner would have to back up, uninstall and reinstall. Back it up now.

Backup steps:

1. Copy `ebm-release.jks` to **at least two** offline places, e.g. an encrypted USB drive kept at
   home and another kept elsewhere (or a password manager that stores files).
2. Store the password **separately** from the keystore file, e.g. in a password manager, or on paper
   in a safe place. Never in the same folder, email or chat as the keystore.
3. Never commit the keystore, the password or `gradle.properties` to Git, and never send them over
   WhatsApp or email. The repository's `.gitignore` excludes `*.jks`, `*.keystore` and `dist/`.
4. Check the backup: on another computer, `keytool -list -keystore ebm-release.jks` with the
   password must show alias `ebm-release` and the SHA-256 above.
5. Once the backup is verified, you can delete `keystore-password.txt` from the PC — the build reads
   the password from `gradle.properties`.

To build on another computer: copy the keystore there and add the four `EBM_*` lines to that
computer's `~/.gradle/gradle.properties` (or set them as environment variables).

## Google Drive backup for the release build

Google identifies the app by package name **and** signing certificate. In the Google Cloud project's
**Credentials**, add an **Android OAuth client** with package `com.eggbusinessmanager.app` and the
release **SHA-1** above (keep the debug one for development builds). The consent screen needs the
`drive.appdata` scope; while it is in "Testing", add each owner's Google account as a test user, or
publish the consent screen. Until then, backups to the phone work and Drive shows "can't connect".

## Release notes — 1.0.1 (2026-11-08)

- Dashboard: Today / This week / This month with sales, purchases, money received and paid and net
  cash; top 5 customers who owe the most; backup reminder after 7 days without a backup; a guide on a
  new account.
- Backups: delete single Google Drive backups from the history; after a restore, set a new login
  password without the old one; a notification when automatic backups miss 3 scheduled periods.
- APK: `dist/EggBusinessManager-1.0.1.apk`, SHA-256
  `e643addf72c27e84090ca761ad79f28b57c6aa2776b651fb3c57348cedcb7003`; signature verified (release
  certificate); smoke test of the signed APK passed (version 1.0.1, build 10001).
- Tests: unit and database suites pass; new feature tests pass; the full app suite was interrupted
  when the build PC slept (20 of 32 passed before that) and is to be re-run.

## Release notes — 1.0.0

First production release. Customers with opening balances; sales and purchases by variety and unit
with live totals and on-the-spot payment; payments with advances; edit and delete with full history;
dashboard and transactions with filters; nine reports with PDF, Excel and CSV export; sharing of
reports and one-page receipts; encrypted manual and automatic backups to the phone or Google Drive
with safe restore; shop/owner profiles, security settings, display preferences, English and Hindi.

### Validation results (2026-11-08)

| Check | Result |
|---|---|
| Domain unit tests + coverage gate (≥ 90 %) | Pass |
| App unit tests, detekt, lint (debug and release, compileSdk 37) | Pass, no findings |
| Database device tests — Android 16 (API 36) emulator | 55 / 55 pass |
| App UI tests incl. end-to-end, receipts, on-the-spot payment, language — Android 16 emulator | 29 / 29 pass |
| Signature (`apksigner verify`) | Verifies, v3, release certificate (SHA-256 above), 1 signer |
| Manifest | not debuggable, `allowBackup=false`; only `MainActivity` exported (plus WorkManager's job service, guarded by `BIND_JOB_SERVICE`) |
| Smoke test of the signed 1.0.0 APK (`release_check.py seed`) | Pass: sign-up, customer, sale with payment now, balance, About shows 1.0.0 (build 10000) |
| Upgrade 1.0.0 → 1.0.1 with signed APKs (`adb install -r`, `release_check.py verify`) | Pass: account, customer, ₹2,300 balance, S-000001 and RP-000001 all intact |
| Android 17 (API 37) | Compiled, linted and targeted. Device tests could not run: the API 37 emulator image crashes in its graphics driver on this PC before any app starts. **Install once on a real Android 17 phone before wide distribution.** |

APK: `dist/EggBusinessManager-1.0.0.apk`, 25.5 MB (includes the encrypted-database engine for
all four CPU types), SHA-256 `27b4b21c21d3a41fb2138b13f497398626d20addfa1eafe1324ade006ff893f3`.

Known follow-up: on the Customers screen the floating "Add customer" button's label is reported
blank to accessibility tools (it is drawn correctly); check it with TalkBack in the accessibility pass.
