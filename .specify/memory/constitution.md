<!--
SYNC IMPACT REPORT
==================
Version change: (unversioned template) → 1.0.0
Bump rationale: First ratified constitution; all placeholders replaced (initial adoption = 1.0.0).

Principles defined (template placeholders → final titles):
- [PRINCIPLE_1_NAME] → I. Offline-First, Local-Only Data (NON-NEGOTIABLE)
- [PRINCIPLE_2_NAME] → II. Financial Accuracy & Ledger Integrity (NON-NEGOTIABLE)
- [PRINCIPLE_3_NAME] → III. Safe Schema Evolution & Upgrade Continuity (NON-NEGOTIABLE)
- [PRINCIPLE_4_NAME] → IV. Security by Default
- [PRINCIPLE_5_NAME] → V. Optional, Encrypted Google Drive Backup
- Added → VI. Native Android with Jetpack Compose & Material 3
- Added → VII. MVVM + Clean Architecture
- Added → VIII. Test Discipline
- Added → IX. Ease of Use for Daily Business Operations
- Added → X. Cost Control & Dependency Discipline

Sections:
- [SECTION_2_NAME] → Technology & Platform Constraints
- [SECTION_3_NAME] → Development Workflow & Quality Gates
- Governance → filled
Removed sections: none

Mapping of mandated principles (user input #1–#17) to this document:
#1 → VI · #2 → VI · #3 → VII · #4 → I, Tech Constraints · #5 → I · #6 → I · #7 → V
#8 → I · #9 → IV, V · #10 → II · #11 → II · #12 → III · #13 → V · #14 → IV, Tech
Constraints · #15 → VIII · #16 → III · #17 → X

Templates checked:
- .specify/templates/plan-template.md     ✅ no change required (Constitution Check reads this file)
- .specify/templates/spec-template.md     ✅ no change required
- .specify/templates/tasks-template.md    ✅ no change required (tests are mandatory per VIII)
- .specify/templates/checklist-template.md ✅ no change required

Deferred TODOs: none
-->

# Egg Business Manager Constitution

## Core Principles

### I. Offline-First, Local-Only Data (NON-NEGOTIABLE)

- The on-device Room database is the single source of truth for all business data.
- Every business operation (customers, sales, purchases, stock, payments, ledgers, reports,
  settings) MUST work fully with no network connection, including on first launch.
- The app MUST NOT use any backend server, Firebase (any product, including Analytics and
  Crashlytics), Supabase, or any other remote application database or sync service.
- Network access is permitted only for the optional Google Drive backup/restore defined in
  Principle V. No feature may block, degrade, or prompt because the network is unavailable.
- The `INTERNET` permission exists solely to serve backup/restore; any other network use requires
  a constitution amendment.

**Rationale**: The business must run reliably anywhere, at any time, without recurring cost or
dependence on third-party uptime, and customer data must stay on the owner's device.

### II. Financial Accuracy & Ledger Integrity (NON-NEGOTIABLE)

- `Float` and `Double` MUST NOT be used for money, rates, or any value that feeds a financial total.
- Monetary amounts MUST be stored as integer minor units (`Long`, e.g. paise) and computed with
  `BigDecimal` (or integer arithmetic) using a single, centrally defined scale and `RoundingMode`.
  Rounding happens only at defined points (line total, invoice total), never implicitly.
- Each customer MUST have an accurate ledger: every sale, payment, return, discount, and
  adjustment is recorded as a dated ledger entry linked to its source document.
- Balances MUST be derivable from ledger entries. Any stored/cached balance MUST be recomputable
  and verified against the entries by tests.
- Posted financial entries MUST NOT be silently edited or hard-deleted. Corrections are made via
  reversing or adjusting entries that preserve an audit trail (who/when/why).
- Every operation that touches more than one financial record MUST execute inside a single Room
  transaction so it either fully succeeds or leaves no trace.
- Input validation MUST reject negative quantities, invalid amounts, and impossible dates at the
  domain layer, not only in the UI.

**Rationale**: A ledger the owner cannot trust is worse than no ledger. Precision and
auditability prevent disputes with customers and silent loss of money.

### III. Safe Schema Evolution & Upgrade Continuity (NON-NEGOTIABLE)

- Every Room schema change MUST ship with an explicit `Migration` (or verified `AutoMigration`).
  `fallbackToDestructiveMigration*` is forbidden in all build types.
- Room schema export MUST be enabled and every exported schema JSON committed to the repository.
- Each migration MUST be covered by a `MigrationTestHelper` test that verifies data is preserved,
  including a test migrating from the oldest supported released version to the current one.
- Before a migration runs, the app SHOULD create a local pre-migration snapshot of the database
  so a failed upgrade can be recovered.
- The app MUST be installable by manual APK sideloading and upgradable in place by installing a
  newer APK, with all data preserved:
  - `applicationId` MUST never change.
  - All releases MUST be signed with the same release key. The keystore and its passwords MUST
    be stored outside the repository with at least one secure offline backup; losing it breaks
    all future upgrades.
  - `versionCode` MUST strictly increase with every distributed APK; `versionName` follows
    semantic versioning.
  - Downgrades are not supported; the app MUST refuse to open a database or backup created by a
    newer schema version rather than corrupt it.

**Rationale**: Users will update by installing APKs over existing installs; an upgrade that loses
data or cannot install destroys the business's records.

### IV. Security by Default

- The app MUST be protected by local authentication: a PIN/password, with optional biometric
  unlock via `BiometricPrompt` (`BIOMETRIC_STRONG` or device credential).
- PINs/passwords MUST never be stored in plain text; store only a salted hash from a slow KDF
  (PBKDF2-HMAC-SHA256 with a high iteration count, or Argon2). Repeated failures MUST trigger
  progressive lockout.
- The app MUST re-lock after a configurable inactivity timeout and when sent to background
  beyond that timeout.
- Cryptographic keys held on device MUST live in the Android Keystore.
- Sensitive data at rest SHOULD be protected by database encryption (e.g. SQLCipher); the plan
  MUST document the decision and its migration impact.
- Android Auto Backup MUST be disabled or restricted via `dataExtractionRules`/`fullBackupContent`
  so unencrypted data never leaves the device through system backup.
- Logs, crash output, and notifications MUST NOT contain PINs, keys, backup passphrases, or
  customer financial details. Release builds MUST strip debug logging.
- The app MUST follow current Android security guidance: request the minimum permissions,
  use runtime permissions, no exported components unless required, no cleartext traffic, and
  enable R8 code shrinking/obfuscation for release builds.

**Rationale**: The phone holds the complete financial history of the business and its customers;
a lost or shared device must not expose it.

### V. Optional, Encrypted Google Drive Backup

- Google Drive is used exclusively for optional backup and restore. It MUST never act as a live
  database, sync channel, or requirement for any feature. The app MUST be fully usable by a user
  who never signs in to Google.
- Access MUST use the narrowest scope possible (Drive `appDataFolder` / `drive.appdata`, or
  `drive.file`). Full Drive access is forbidden.
- Every backup MUST be encrypted on device before upload (AES-256-GCM). The key MUST be derived
  from a user-chosen backup passphrase (via a slow KDF with stored salt) so restore works on a new
  device; a Keystore-only key is insufficient for cross-device restore.
- Backup files MUST include a format version, database schema version, creation timestamp, and an
  integrity checksum. Restore MUST verify decryption, checksum, and schema compatibility before
  touching existing data.
- Restore MUST take a local safety snapshot of the current data before replacing it and MUST
  roll back to that snapshot if restore fails.
- Scheduled backups MUST use WorkManager (`PeriodicWorkRequest`) with network and battery
  constraints, exponential backoff, and unique work names so duplicates cannot be queued.
  Manual "Back up now" MUST also be available.
- The user MUST be able to see the last successful backup time and any backup failure. A failed
  backup MUST never affect local data.
- The app SHOULD also support an encrypted backup export to local device storage using the same
  format, as an offline alternative.

**Rationale**: Backups protect against phone loss or damage without turning the app into a
cloud-dependent system or exposing customer data to third parties.

### VI. Native Android with Jetpack Compose & Material 3

- The app MUST be a native Android application written in Kotlin.
- All UI MUST be built with Jetpack Compose. Legacy XML layouts/Views are not permitted except
  where a platform API offers no Compose alternative, and such cases MUST be justified in the plan.
- UI MUST use Material 3 components, theming, and typography, with light and dark themes and
  support for dynamic color where available.

**Rationale**: A single, modern, first-party toolkit maximizes performance, maintainability, and
long-term support.

### VII. MVVM + Clean Architecture

- Code MUST be organized into three layers with dependencies pointing inward only:
  - **Presentation**: Compose screens + `ViewModel`s exposing immutable UI state via `StateFlow`
    and handling user intents. No business rules or direct database access.
  - **Domain**: Pure Kotlin use cases, entities, and repository interfaces. No Android framework
    or Room dependencies; fully unit-testable on the JVM.
  - **Data**: Room entities/DAOs, repository implementations, backup/Drive and crypto adapters,
    and mappers between data and domain models.
- Dependencies MUST be provided by a single dependency-injection approach (Hilt recommended).
- Asynchronous work MUST use Kotlin coroutines and Flow with injected dispatchers; no blocking
  database or file I/O on the main thread.
- UI state MUST survive configuration changes and process death (e.g. `SavedStateHandle`) so
  unsaved user input is not lost.

**Rationale**: Clear boundaries keep financial logic testable, isolated from UI churn, and safe to
change over the long life of the app.

### VIII. Test Discipline

- **Unit tests** are mandatory for every use case and domain rule, and MUST cover all money
  calculation, rounding, and ledger-balance paths including edge cases.
- **Integration tests** are mandatory for Room DAOs and transactions, every database migration,
  and the backup → encrypt → restore round trip (including wrong passphrase, corrupt file, and
  newer-schema rejection).
- **UI tests** (Compose testing) are mandatory for critical user flows: authentication/lock,
  recording a sale, recording a payment, viewing a customer ledger, and backup/restore.
- A bug fix touching financial data or migrations MUST include a regression test that fails
  without the fix.
- Tests MUST be deterministic: no real network, injected clocks and dispatchers, in-memory or
  test databases.

**Rationale**: Financial and data-migration errors are often silent until they cause damage;
automated tests are the primary defence.

### IX. Ease of Use for Daily Business Operations

- Frequent tasks (record sale, record payment, check a customer balance) MUST be reachable from
  the home screen and completable in a minimal number of steps.
- Destructive or irreversible actions (deleting a customer, restoring a backup) MUST require
  explicit confirmation that states the consequence.
- Error messages MUST be in plain language and tell the user what to do next.
- UI MUST meet Android accessibility basics: minimum 48dp touch targets, content descriptions,
  sufficient contrast, and support for system font scaling.
- All user-facing text MUST live in string resources; currency, number, and date formatting MUST
  be locale-aware so the app can be localized.

**Rationale**: The primary users are busy business owners; an accurate app that is hard to use
will not be used.

### X. Cost Control & Dependency Discipline

- The project MUST NOT introduce any paid service, paid API, paid SDK, or subscription without
  explicit written approval from the project owner, recorded in the relevant plan.
- Third-party libraries MUST be free, actively maintained, license-compatible, and justified in
  the plan. Prefer AndroidX/Jetpack and Kotlin first-party libraries.
- No advertising, analytics, or tracking SDKs may be included.

**Rationale**: The app is designed to run at zero recurring cost and to keep business data private.

## Technology & Platform Constraints

- **Language**: Kotlin (latest stable), with Kotlin coroutines and Flow.
- **UI**: Jetpack Compose + Material 3; Navigation Compose for in-app navigation.
- **Persistence**: Room (with KSP) as the only application database; DataStore for small
  preferences. No other database engines unless approved by amendment.
- **Background work**: WorkManager for all deferrable/scheduled work, including backups.
- **Backup**: Google Sign-In / Credential Manager + Google Drive API restricted to the scopes in
  Principle V.
- **Security**: Android Keystore, AndroidX Biometric, standard JCA crypto (AES-GCM, PBKDF2/Argon2).
- **Build**: Gradle Kotlin DSL with a version catalog; reproducible release builds with R8
  enabled; release signing configuration kept out of version control.
- **SDK levels**: `targetSdk` and `compileSdk` MUST track the latest stable Android API level
  (no more than one year behind Google Play's current target requirement, even for sideloaded
  distribution). `minSdk` is chosen in the implementation plan to balance reach and security, and
  MUST NOT be lowered without an amendment-level justification.
- **Distribution**: Signed release APK for manual installation; no dependency on Google Play
  services for any core (non-backup) feature.

## Development Workflow & Quality Gates

- Every feature follows the Spec Kit flow: specification → plan (with Constitution Check) →
  tasks → implementation. The Constitution Check MUST explicitly confirm Principles I–X.
- Any deviation from this constitution MUST be recorded in the plan's Complexity Tracking table
  with justification and the simpler alternative that was rejected; non-negotiable principles
  (I, II, III) cannot be waived without an amendment.
- Before a change is merged or released:
  - All unit, integration, and UI tests pass.
  - Lint and static analysis pass with no new errors.
  - Any schema change includes its migration, exported schema, and migration test.
  - Any change to financial logic includes updated unit tests.
- Before an APK is distributed:
  - `versionCode` is incremented and `versionName` updated.
  - The APK is signed with the official release key.
  - An upgrade install over the previous released APK is tested on a device or emulator with
    realistic data and verified to preserve that data.
  - A backup created by the previous release restores successfully in the new release.
- Release notes MUST mention any schema change and any change to backup format.

## Governance

- This constitution supersedes all other project practices and guidance. Where a plan, task, or
  code review conflicts with it, the constitution wins.
- Amendments are made by updating this file, including a Sync Impact Report, and require
  approval from the project owner. Amendments that affect existing data, the backup format, or
  signing MUST include a migration/compatibility plan.
- Versioning follows semantic versioning:
  - **MAJOR**: Removal or backward-incompatible redefinition of a principle.
  - **MINOR**: A new principle or section, or materially expanded guidance.
  - **PATCH**: Clarifications and wording fixes with no change in meaning.
- Every implementation plan and code review MUST verify compliance with this constitution.
  Runtime development guidance for AI agents lives in `CLAUDE.md` and MUST remain consistent
  with this document.
- Compliance is reviewed at each release; any violation found MUST be fixed or ratified by
  amendment before the next release.

**Version**: 1.0.0 | **Ratified**: 2026-11-07 | **Last Amended**: 2026-11-07
