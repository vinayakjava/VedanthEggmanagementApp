# Phase 0 Research: Egg Business Manager

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-11-07

Each item records **Decision / Rationale / Alternatives considered**. All Technical Context unknowns
are resolved here; nothing remains marked NEEDS CLARIFICATION.

---

## R1. SDK levels and toolchain

- **Decision**: `minSdk = 31` (owner requirement). `compileSdk = targetSdk = latest stable API at
  project start` — verify on day one in Android Studio's SDK Manager; expected API 37 (Android 17),
  and never below API 36. JDK 17 toolchain for Gradle/AGP. Kotlin 2.x with K2 compiler, KSP for
  Room and Hilt. Versions are pinned once in `gradle/libs.versions.toml` (latest stable at setup)
  and upgraded deliberately.
- **Rationale**: Constitution "Technology & Platform Constraints" requires tracking the latest
  stable API. API 31 removes most legacy storage/permission branches (scoped storage only, no
  `WRITE_EXTERNAL_STORAGE`), and has mature Keystore and BiometricPrompt support.
- **Alternatives**: Pinning exact library versions in the plan — rejected; they go stale before
  implementation starts. Lower minSdk — rejected by owner.
- **Note**: The current development machine has JDK 11 only; JDK 17+ (bundled with Android Studio)
  is required (see quickstart).
- **Recorded at setup (2026-11-08)**: `compileSdk = targetSdk = 36` (latest platform installed via
  SDK Manager), `minSdk = 31`. Build JDK: Temurin 17 (`~/.jdks/temurin-17`). Toolchain: AGP 8.13.0,
  Gradle 8.14.3, Kotlin 2.2.20, KSP 2.2.20-2.0.3.

## R2. Module structure

- **Decision**: Four Gradle modules — `:app` (presentation + DI + Android entry points),
  `:domain` (pure Kotlin/JVM library), `:data` (Android library), `:core-testing` (shared fakes and
  builders for tests).
- **Rationale**: Constitution VII requires domain with no Android/Room dependencies; a JVM-only
  module makes that rule compiler-enforced. Three production modules keep build times and
  complexity low for a single-app project.
- **Alternatives**: Single module with packages (rule only by convention — rejected); feature
  modules per screen (overhead not justified at this size).

## R3. Money and quantity representation

- **Decision**:
  - Money: `Long` paise in storage and domain, wrapped in a `@JvmInline value class Money(val paise: Long)`.
  - Quantity: `Long` thousandths of a unit (`quantityMilli`, 3 decimals per spec).
  - Price per unit: `Long` paise.
  - Line total: `BigDecimal(quantityMilli) × BigDecimal(pricePaise) ÷ 1000`, scale 0,
    `RoundingMode.HALF_UP` → paise. This is the **only** rounding point (FR-021, BR-008).
  - Pieces: `quantityMilli × piecesPerUnit` must be divisible by 1000, otherwise a validation error.
  - All arithmetic through `Math.addExact`/`multiplyExact` (overflow → domain error), never
    `Float`/`Double`.
- **Rationale**: Constitution II. Integers make SQL `SUM()` exact and fast. Max entry ₹99,99,99,999.99
  ≈ 10¹¹ paise, far below `Long.MAX` (9.2×10¹⁸), so sums of 100k+ entries cannot overflow.
- **Alternatives**: `BigDecimal` stored as TEXT (exact, but SQL sums impossible); `REAL` columns
  (forbidden).

## R4. Ledger model: one table for all money movements

- **Decision**: A single `ledger_entry` table holds every entry type: `OPENING_BALANCE`, `SELL`, `BUY`,
  `PAYMENT_RECEIVED`, `PAYMENT_MADE`. Each row stores a positive `amount_paise` and a signed
  `balance_effect_paise` computed by **one** domain function (`BalanceEffect.of(type, amount)`):
  SELL +, BUY −, PAYMENT_RECEIVED −, PAYMENT_MADE +, OPENING_BALANCE ± by direction.
  Customer balance = `SUM(balance_effect_paise) WHERE status = 'ACTIVE'`. There is **no stored
  balance column**.
- **Rationale**: Centralized ledger calculation (architecture req. 5). Edits, type changes and
  customer changes become row-status changes, not cross-table moves. With an index on
  `(customer_id, status, entry_date, created_at)`, per-customer sums and running balances (window
  `SUM() OVER`) over 100k rows complete in milliseconds in SQLite, so a cached balance (and its
  drift risk) is unnecessary. Opening balance as an entry gives it a natural ledger line and
  revision history (FR-019).
- **Alternatives**: Separate trade and payment tables (more joins/unions, type change needs a
  cross-table move); cached `customer.balance` (drift risk, Constitution II requires a verifiable
  recompute anyway).

## R5. Edits, voids and the audit trail

- **Decision**: Rows are append-only. An edit marks the current row `SUPERSEDED` and inserts a
  corrected row in the same transaction, with `root_entry_id` (stable id of the logical entry) and
  `supersedes_entry_id`. A void sets `status = VOIDED` (the only in-place change, plus `void_*`
  fields). Every create/edit/void/type-change/customer-change/opening-change writes an `audit_log`
  row with before/after JSON snapshots and an optional reason. A type change creates a new logical
  entry (new root, new reference in the new series); the old root's last row is `SUPERSEDED` with
  `replaced_by_root_id`, which drives the "Changed to <ref>" label.
- **Rationale**: Constitution II (no silent edits, audit trail) and spec FR-033–FR-035.
- **Alternatives**: In-place update plus audit (violates the "not silently edited" spirit and
  complicates point-in-time reports); reversing journal entries (accurate, but ledgers show noise
  lines the owner did not ask for).

## R6. Reference numbers and duplicate-save protection

- **Decision**: A `reference_sequence(entry_type, next_value)` table is incremented inside the save
  transaction; format `S-000001`, `B-…`, `RP-…`, `PC-…`, `OB-…`. Each save carries a client-generated
  UUID `client_request_id` with a UNIQUE index, so a retried or double-tapped save cannot create a
  second row (FR SC/US3-8). ViewModels also disable Save while a save is in progress.
- **Rationale**: Never-reused numbers (BR-014), transactional, gap-free under normal operation.
- **Alternatives**: `MAX(ref)+1` (race-prone); autoincrement id as reference (not per type).

## R7. Database encryption at rest

- **Decision**: Use **SQLCipher for Android** (`net.zetetic:sqlcipher-android`, Community Edition,
  free, BSD-style licence) through Room's `SupportOpenHelperFactory`. The 256-bit database key is
  random, generated on first launch, and stored only wrapped by an Android Keystore AES-256-GCM key
  (not user-authentication-bound) in `noBackupFilesDir`. The release must use a SQLCipher build that
  supports 16 KB memory pages (required for Android 15+ devices).
- **Rationale**: Constitution IV ("SHOULD encrypt; plan MUST document"). It protects data if the
  device storage is extracted (rooted device, forensic copy). The key is *not* bound to the login
  password, because scheduled background backups (FR-048) must read the database while the app is
  locked; the app lock is therefore a UI-level gate. This trade-off is accepted and documented.
- **Migration impact**: Encrypted from schema v1, so no plaintext→encrypted migration is ever needed.
  Room migrations run unchanged on SQLCipher. `MigrationTestHelper` must use the same open-helper
  factory.
- **Alternatives**: No DB encryption (simpler, relies on file-based encryption only — rejected for
  financial data); key derived from the password (breaks background backup and password recovery).

## R8. Authentication and secrets

- **Decision**:
  - Login password and security answers: PBKDF2-HMAC-SHA256, 16-byte random salt per secret,
    600,000 iterations (iterations stored per record so they can be raised later). Constant-time
    comparison.
  - Security answers are normalized before hashing (trim, collapse whitespace, Unicode NFKC,
    lowercase with `Locale.ROOT`).
  - Lockout: `failed_attempts` and `lockout_until` persisted; thresholds per FR-006. If the wall clock
    moves backwards, the lockout is kept (compared with the stored last-seen time).
  - Biometric: AndroidX `BiometricPrompt` with a `CryptoObject` over a Keystore key created with
    `setUserAuthenticationRequired(true)` and `setInvalidatedByBiometricEnrollment(true)`;
    `KeyPermanentlyInvalidatedException` ⇒ biometric unlock disabled with a notice (FR-008).
    Allowed authenticator: `BIOMETRIC_STRONG`; password is always the fallback.
  - Session: an in-memory `SessionManager` (Locked/Unlocked) with an inactivity timer reset on any
    pointer input, and a background timer via `ProcessLifecycleOwner`.
  - Recents preview: `setRecentsScreenshotEnabled(false)` on API 33+, `FLAG_SECURE` on API 31–32
    (FR-007). Screenshots stay allowed on 33+ so the owner can share screens.
- **Rationale**: Constitution IV. PBKDF2 ships with the platform (no extra library); 600k iterations
  follows current OWASP guidance for PBKDF2-SHA256, about 0.5–1 s on mid-range phones, run on
  `Dispatchers.Default`.
- **Alternatives**: Argon2id via a native library (stronger, but adds a native dependency and the
  16 KB page concern); `EncryptedSharedPreferences` (deprecated by AndroidX Security).

## R9. Backup format and encryption

- **Decision**: Physical, already-encrypted snapshot with a passphrase-wrapped key. See
  [contracts/backup-format.md](./contracts/backup-format.md).
  1. Inside a read transaction, `ATTACH` a new file with a fresh random 256-bit key *K* and run
     `sqlcipher_export()`. This gives a consistent snapshot that is never plaintext on disk.
  2. KEK = PBKDF2-HMAC-SHA256(passphrase, salt, 600k). It is derived once when the passphrase is
     set and then stored wrapped by a Keystore key, so background backups never need the passphrase
     typed.
  3. File = magic + header JSON (format/app/schema version, created time, device model, KDF params,
     salt, payload length, payload SHA-256) + `AES-256-GCM(KEK, K, AAD = header bytes)` + snapshot
     bytes.
  4. Restore: parse header → derive KEK from the entered passphrase → unwrap K (fails ⇒ "Incorrect
     passphrase") → verify SHA-256 (fails ⇒ "damaged") → open the snapshot read-only with K → check
     `user_version` ≤ current schema (else "newer version") → re-key into a staging DB with the device
     key → read summary counts.
- **Rationale**: FR-044–FR-052. The physical snapshot reuses Room migrations for older backups,
  restore is byte-exact (SC-006), and no plaintext ever touches storage. GCM AAD binds the header;
  the SHA-256 plus SQLCipher's per-page HMAC detect payload damage.
- **Alternatives**: Logical JSON export (needs a hand-written importer per schema version);
  encrypting a plaintext DB copy (temporary plaintext on disk); Tink streaming AEAD (an extra
  dependency, no benefit over the SQLCipher snapshot).

## R10. Restore execution: process-restart state machine

- **Decision**: Restore is done by a `DatabaseBootstrapper` that runs **before Room opens**, driven by
  a persisted state file (`noBackupFilesDir/restore/state.json`):
  `STAGED → SWAPPING → VERIFYING → COMMITTED` (or `ROLLED_BACK`).
  - The UI stages and validates the backup (R9), shows the summary, and on confirmation writes
    `STAGED` and restarts the process.
  - On startup the bootstrapper moves the current DB to `safety/`, moves staging into place, opens it
    (Room migrations run), runs `PRAGMA integrity_check`, carries over the device's `backup_record`
    rows, sets the restored account to require sign-in, then marks `COMMITTED` and deletes the safety
    copy after the first successful sign-in.
  - Any failure, or a crash in `SWAPPING`/`VERIFYING`, moves the safety copy back on the next start
    (`ROLLED_BACK`).
  The same bootstrapper takes a **pre-migration snapshot** (copy of the encrypted file, last 2
  kept) whenever the on-disk `user_version` is lower than the app's schema version.
- **Rationale**: FR-051 and SC-007. Swapping a file under a live Room instance and Hilt-singleton DAOs
  is unsafe; a restart is the only clean way. The persisted state makes every crash point
  recoverable.
- **Alternatives**: Closing and reopening Room in-process (stale DAO/Flow references across the
  app — rejected).

## R11. Google Drive integration

- **Decision**:
  - Authorization: Google Identity Services `AuthorizationClient`
    (`Identity.getAuthorizationClient`), requesting only `https://www.googleapis.com/auth/drive.appdata`.
    The first grant is interactive; later calls (including from workers) return a token silently or
    report that a resolution is needed ⇒ status "Google account disconnected", and the owner is asked
    to reconnect.
  - Transport: Drive REST v3 via OkHttp + kotlinx.serialization (resumable upload, list, download,
    delete) in a thin `DriveClient`, instead of the heavy `google-api-services-drive` client.
  - Storage: Drive `appDataFolder`. Files carry `appProperties` (createdAt, trigger, appVersion,
    schemaVersion, device, sizeBytes) so the restore list renders without downloading.
  - Retention: after each successful automatic upload, list automatic backups and delete all but the
    newest 10 (FR-049).
  - Google Cloud: one free project, OAuth consent screen, and an Android OAuth client registered with
    the release package name and signing SHA-1 (plus a debug client). `drive.appdata` is not a
    restricted scope, so no paid security assessment is involved.
- **Rationale**: Constitution I, V, X — narrowest scope, Drive only for backup, no paid service.
  Google Play services are needed only for this optional feature; every core feature works without
  them.
- **Alternatives**: `drive.file` (backups visible in the user's Drive, so the user can delete or move
  them by accident); full Drive scope (forbidden); Credential Manager sign-in (not needed —
  AuthorizationClient handles account choice).

## R12. Scheduled and manual backup execution

- **Decision**: WorkManager.
  - Automatic: unique `PeriodicWorkRequest` (`drive-auto-backup`, `ExistingPeriodicWorkPolicy.UPDATE`)
    with a 24 h or 7 d interval and a flex window aligned to the chosen time window. Constraints:
    `UNMETERED` (Wi-Fi only) or `CONNECTED`, `requiresBatteryNotLow`, optional `requiresCharging`,
    `requiresStorageNotLow`. Exponential backoff starting at 30 min.
  - Manual Drive and device backups: unique `OneTimeWorkRequest`s (expedited where quota allows), so
    they survive leaving the screen.
  - Workers call `setForeground` with a `dataSync` foreground-service notification for long uploads.
  - Consecutive failed or missed periods are counted in `backup_status`. A notification is posted at 3
    (FR-055); a missed-schedule check also runs at app start, since a worker that never runs cannot
    notify.
  - A restore cancels the backup work and holds a mutex shared with the backup workers.
- **Rationale**: Constitution V. WorkManager survives reboots and app updates and respects Doze.
- **Alternatives**: AlarmManager (exact alarms need extra permission, no constraints); a foreground
  service (battery cost, user-visible).

## R13. Documents: PDF, CSV, Excel

- **Decision**:
  - **PDF**: platform `android.graphics.pdf.PdfDocument` with Canvas and `StaticLayout` (correct
    Devanagari shaping using system Noto fonts). A small in-house table layout engine handles
    pagination, header, footer and page numbers. A4 for reports/statements; a one-page A5-width
    receipt.
  - **CSV**: RFC 4180, UTF-8 **with BOM** (so Excel shows Hindi correctly), comma separator,
    machine-readable amounts with 2 decimals and no grouping, ISO dates.
  - **Excel**: a minimal in-house `.xlsx` writer (SpreadsheetML in a ZIP via `java.util.zip`):
    inline strings, numeric cells with `#,##0.00` format, date cells, bold header, frozen header row.
  - All rendering runs on `Dispatchers.Default`. Files go to `cacheDir/exports/` and are shared via
    `FileProvider`; files older than 24 h are deleted at start and daily (FR-043).
- **Rationale**: Constitution X (no paid or heavy libraries). Apache POI is about 10 MB+ and has
  Android compatibility issues. The required layouts are tabular and simple.
- **Alternatives**: Apache POI (size, compatibility); fastexcel (viable Apache-2.0 fallback if the
  in-house writer proves costly); HTML→PDF via WebView print (asynchronous, hard to test, needs a UI
  context).

## R14. Sharing and WhatsApp

- **Decision**: `Intent.ACTION_SEND` with a `FileProvider` URI and `FLAG_GRANT_READ_URI_PERMISSION`,
  wrapped in `Intent.createChooser`. "Share on WhatsApp" sets the package to `com.whatsapp`, or
  `com.whatsapp.w4b` if only Business is installed (declared in `<queries>`), and adds the
  best-effort `jid` extra `91XXXXXXXXXX@s.whatsapp.net` to pre-address the chat. If the package is
  missing or the extra is ignored, the generic chooser is used. Balance reminders are `text/plain`.
- **Rationale**: FR-041/042 with no messaging API and no paid service.
- **Alternatives**: WhatsApp Business API (paid, server-based — forbidden); `wa.me` links (text only,
  no attachment).

## R15. Localization (English + Hindi)

- **Decision**: All strings in `values/strings.xml` and `values-hi/strings.xml`; `locales_config.xml`.
  Per-app language via `AppCompatDelegate.setApplicationLocales()` (AppCompat, `autoStoreLocales`),
  which uses the system per-app language on API 33+ and back-ports to 31–32. Documents render with
  `context.createConfigurationContext(locale)`. Numbers via ICU `NumberFormat` with
  `en-IN` / `hi-IN-u-nu-latn` (Indian grouping, Latin digits). Dates use `DateTimeFormatter` with the
  chosen locale. The predefined security questions exist in both languages under stable ids, so
  answers stay valid when the language changes.
- **Rationale**: FR-061a. No restart, no data change.
- **Alternatives**: Manual `Configuration` overrides (fragile, deprecated patterns).

## R16. Navigation and adaptive layout

- **Decision**: Navigation Compose with type-safe `@Serializable` routes. Two graphs (auth, main).
  `NavigationSuiteScaffold` (Material 3 adaptive) switches between a bottom bar (compact), a
  navigation rail (medium) and a drawer (expanded) by `WindowSizeClass`. Customers →
  Customer detail/Ledger uses `ListDetailPaneScaffold` (two panes on expanded widths). The lock screen
  is an overlay above the `NavHost`, so the back stack and unsaved form state survive locking
  (US1-8). See [contracts/navigation.md](./contracts/navigation.md).
- **Rationale**: Owner requirement (adaptive layouts), Material 3, state preservation.
- **Alternatives**: Navigating to a lock route (destroys or complicates state restoration).

## R17. Testing stack

- **Decision**:
  - JUnit 4 (unified for JVM and instrumented tests), kotlinx-coroutines-test, Turbine (Flow
    assertions), Google Truth, Kotest property testing (money/ledger invariants only).
  - Room DAO, migration, SQLCipher, crypto and backup tests run instrumented, because SQLCipher's
    native library and Keystore need a device or emulator.
  - Drive client tests use OkHttp MockWebServer.
  - Compose UI tests use `createAndroidComposeRule` with Hilt test components and fake repositories.
  - Fakes are preferred over mocks; MockK only at framework seams.
  - Coverage via Kover (domain module ≥ 90% lines; ledger/money packages 100% branches).
- **Rationale**: Constitution VIII. Deterministic tests with an injected `Clock`, dispatchers and an
  in-memory or temporary database.
- **Alternatives**: Robolectric for DAO tests (cannot load SQLCipher native libraries reliably);
  JUnit 5 (mixed support on Android instrumentation).

## R18. Release, signing and upgrades

- **Decision**:
  - `applicationId` is `com.eggbusinessmanager.app` — **confirmed by the owner on 2026-11-08**.
    It can never change after the first shared APK.
  - One release keystore kept outside the repository; signing values come from
    `~/.gradle/gradle.properties` or environment variables. APK Signature Scheme v2 + v3.
  - `versionCode = major×10000 + minor×100 + patch`; `versionName` follows semver.
  - R8 full mode with keep rules for Room, kotlinx.serialization and SQLCipher.
  - Release checklist: upgrade-install test over the previous APK, plus restoring a previous release's
    backup (Constitution quality gates).
  - The app refuses to open a DB with `user_version` greater than its schema (downgrade guard) and
    shows the "update required" message.
- **Rationale**: Constitution III; FR-064.
- **Alternatives**: App Bundle only (not installable by sideloading).
