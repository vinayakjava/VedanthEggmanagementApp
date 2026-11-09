---

description: "Task list for Egg Business Manager implementation"
---

# Tasks: Egg Business Manager

**Input**: Design documents from `/specs/001-egg-business-manager/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Tests are **mandatory** for this project. Constitution VIII requires unit, integration
and UI tests, and plan § Testing Strategy specifies them. Within each story, write the test tasks
first and make sure they fail before implementing.

**Organization**: Tasks are grouped by user story (US1–US12 from spec.md, in priority order) so each
story can be implemented, tested and demonstrated as an increment.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on incomplete tasks)
- **[Story]**: The user story the task belongs to (US1…US12)
- Every task names its exact file path(s)

## Path Conventions

Package root `com.eggbusinessmanager` (pending owner confirmation of the applicationId — T001).
Path aliases used below:

| Alias | Path |
|-------|------|
| `{app}` | `app/src/main/java/com/eggbusinessmanager/app` |
| `{appRes}` | `app/src/main/res` |
| `{appTest}` | `app/src/test/java/com/eggbusinessmanager/app` |
| `{appUiTest}` | `app/src/androidTest/java/com/eggbusinessmanager/app` |
| `{domain}` | `domain/src/main/kotlin/com/eggbusinessmanager/domain` |
| `{domainTest}` | `domain/src/test/kotlin/com/eggbusinessmanager/domain` |
| `{data}` | `data/src/main/java/com/eggbusinessmanager/data` |
| `{dataTest}` | `data/src/test/java/com/eggbusinessmanager/data` |
| `{dataIt}` | `data/src/androidTest/java/com/eggbusinessmanager/data` |
| `{testing}` | `core-testing/src/main/kotlin/com/eggbusinessmanager/testing` |

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Toolchain, modules, build configuration and quality gates

- [X] T001 Confirm the final applicationId with the owner (proposed `com.eggbusinessmanager.app`, research R18) and record it in `specs/001-egg-business-manager/research.md` § R18
- [X] T002 Install Android Studio (latest stable, JDK 17+) and the latest stable SDK platform; record the chosen compileSdk/targetSdk (≥ 36) in `specs/001-egg-business-manager/research.md` § R1
- [X] T003 Create the Gradle multi-module project: `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, Gradle wrapper, modules `:app`, `:domain`, `:data`, `:core-testing`
- [X] T004 Create the version catalog `gradle/libs.versions.toml` pinning the latest stable Kotlin, AGP, KSP, Compose BOM, Material 3 (+ adaptive), Navigation, Lifecycle, Hilt, Room, SQLCipher (16 KB-page compatible), DataStore, WorkManager, Biometric, AppCompat, Paging, Play services auth, OkHttp, kotlinx.serialization/coroutines, and the test libraries from research R17 — *Done (reconciled 2026-11-08): All libraries are pinned in the catalog as their features arrived (WorkManager, Biometric, Play services auth, OkHttp, espresso-intents); Paging and kotest were not needed (see T005, T122).*
- [X] T005 [P] Configure `domain/build.gradle.kts` as a pure Kotlin JVM library (JVM 17, no Android dependencies) with kotest-property, Truth and coroutines-test — *Done (reconciled 2026-11-08): Property-style tests use seeded `kotlin.random.Random` (random ledger histories in LedgerScenariosTest, random SQL-vs-domain checks in ReportRepositoryTest), so kotest-property was not added.*
- [X] T006 [P] Configure `data/build.gradle.kts` as an Android library (minSdk 31, KSP Room, Room Gradle plugin `schemaDirectory("$projectDir/schemas")`, Hilt, SQLCipher, OkHttp, serialization, WorkManager) — *Done (reconciled 2026-11-08): OkHttp, serialization and WorkManager added with Phases 9–10.*
- [X] T007 [P] Configure `app/build.gradle.kts` as the Android application (minSdk 31, compile/target from T002, Compose, Hilt, `versionCode = major*10000+minor*100+patch`, R8 for release, signing config read from `~/.gradle/gradle.properties` keys `EBM_STORE_FILE`, `EBM_STORE_PASSWORD`, `EBM_KEY_ALIAS`, `EBM_KEY_PASSWORD`, v2+v3 signing)
- [X] T008 [P] Configure `core-testing/build.gradle.kts` (depends on `:domain`; provides fakes and builders)
- [X] T009 [P] Add static analysis: detekt config `config/detekt/detekt.yml`, Android lint config `app/lint.xml` (treat new warnings as errors), Kover setup in the root `build.gradle.kts` with thresholds (domain ≥ 90% lines)
- [X] T010 [P] Create `scripts/check-no-destructive-migration.ps1` that fails when `fallbackToDestructiveMigration` appears anywhere under `data/` or `app/`
- [X] T011 [P] Create `.gitignore` covering build outputs, `local.properties`, keystores (`*.jks`, `*.keystore`), `.claude/settings.local.json`
- [X] T012 [P] Add R8 keep rules for Room, SQLCipher and kotlinx.serialization, and strip `android.util.Log` in release, in `app/proguard-rules.pro`
- [X] T013 Create the hardened manifest `app/src/main/AndroidManifest.xml`: permissions per plan § Permissions; `allowBackup=false`; `dataExtractionRules`/`fullBackupContent`; `networkSecurityConfig`; only `MainActivity` exported; `FileProvider` not exported; `<queries>` for WhatsApp and `ACTION_SEND`; `localeConfig`; WorkManager default initializer removed — *Done (reconciled 2026-11-08): WorkManager keeps its default initializer: no custom configuration is needed because the worker gets its dependencies through a Hilt EntryPoint.*
- [X] T014 [P] Create XML resources `{appRes}/xml/data_extraction_rules.xml`, `{appRes}/xml/backup_rules.xml` (exclude everything), `{appRes}/xml/network_security_config.xml` (no cleartext, system CAs only), `{appRes}/xml/file_paths.xml` (`cache/exports`), `{appRes}/xml/locales_config.xml` (en, hi)

**Checkpoint**: an empty app builds in debug and release, and lint, detekt and the migration check run

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Domain core, encrypted database (complete schema v1), security primitives, DI, theme,
navigation shell, localization and formatting. Every story depends on this phase.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Domain core (tests first)

- [X] T015 [P] Write unit tests for `Money` (add/subtract exact, overflow error, compare) and `QuantityMilli` parsing (≤ 3 decimals) in `{domainTest}/money/MoneyTest.kt` — *Done (reconciled 2026-11-08): QuantityMilli/QuantityParser covered in `TradeMathTest` and `TradeValidatorTest`.*
- [X] T016 [P] Write unit tests for `TradeMath.lineTotal` (HALF_UP to paise, 7.5 × 183.50 = 1376.25) and `TradeMath.pieces` (whole-egg rule, 0.05 tray rejected) in `{domainTest}/money/TradeMathTest.kt` — *In `{domainTest}/money/TradeMathTest.kt`.*
- [X] T017 [P] Write unit tests for `BalanceEffect.of` covering all 5 entry types and both opening directions (BR-001–BR-005) in `{domainTest}/ledger/BalanceEffectTest.kt`
- [X] T018 [P] Write unit tests for `LedgerCalculator` (balance, running lines ordered by date, then created time, then id; brought-forward; receivable/payable split, BR-007/011/012) in `{domainTest}/ledger/LedgerCalculatorTest.kt` — *Done (reconciled 2026-11-08): Brought-forward in `LoadReportUseCaseTest`; receivable/payable split in `ReportUseCasesTest`.*
- [ ] T019 [P] Write property-based tests (1,000 cases each): balance = Σ effects = spec formula; sums of line totals are exact; in `{domainTest}/ledger/LedgerPropertiesTest.kt`
- [X] T020 [P] Implement `Money`, `QuantityMilli` value classes in `{domain}/money/Money.kt` and `{domain}/money/QuantityMilli.kt` — *Done (reconciled 2026-11-08): `QuantityMilli`, `QuantityParser` and `TradeMath` added in Phase 4.*
- [X] T021 [P] Implement `TradeMath` in `{domain}/money/TradeMath.kt` — *In `{domain}/money/Quantity.kt` with `QuantityMilli` and `QuantityParser`.*
- [X] T022 [P] Implement domain enums and models (`EntryType`, `EntryStatus`, `PaymentMode`, `OpeningDirection`, `Balance`, `LedgerEntry`, `LedgerLine`, `Customer`, `CustomerSummary`, `MeasurementUnit`, `EggVariety`, `BusinessProfile`, `AppSettings`) in `{domain}/model/` — *Done (reconciled 2026-11-08): PaymentMode, catalog, settings and preference models added in their phases.*
- [X] T023 Implement `BalanceEffect` and `LedgerCalculator` in `{domain}/ledger/BalanceEffect.kt` and `{domain}/ledger/LedgerCalculator.kt` — *Done (reconciled 2026-11-08): Brought-forward computed in `ReportBuilder.statement` from `LedgerCalculator.runningLines`.*
- [X] T024 [P] Implement `DomainResult`, `DomainError`, `ValidationCode`, `Field` in `{domain}/error/`
- [X] T025 [P] Implement the cross-cutting ports `Clock`, `IdGenerator`, `TransactionRunner` in `{domain}/repository/Ports.kt`
- [X] T026 [P] Write tests for the shared field validators (mobile normalization `+91`/`0`/spaces, email, name lengths, remarks, dates: no future, not before 2000-01-01, amount ranges) in `{domainTest}/validation/FieldValidatorsTest.kt` — *Done (reconciled 2026-11-08): Date and amount rules in TradeValidator/PaymentValidator with tests.*
- [X] T027 Implement the shared field validators in `{domain}/validation/FieldValidators.kt` — *Done (reconciled 2026-11-08): `{domain}/validation/FieldValidators.kt` with FieldValidatorsTest.*
- [X] T028 Declare all repository and port interfaces from `contracts/domain-interfaces.md` in `{domain}/repository/` (one file per interface) — *Done (reconciled 2026-11-08): Repository and port interfaces in `{domain}/repository/` and next to each feature (backup, report, settings).*

### Test support

- [X] T029 [P] Create `FixedClock`, `SequentialIdGenerator`, `ImmediateTransactionRunner` and test-data builders in `{testing}/` — *Done (reconciled 2026-11-08): `FixedClock`, `SequentialIdGenerator`, `RecordingLogger` in `{testing}/TestDoubles.kt`; use cases take a `TransactionRunner` fake from the fakes; builders such as `SignupForms`.*
- [X] T030 [P] Create in-memory fake repositories (Account, Customer, Ledger, Catalog, Report, Settings, Backup) that use `LedgerCalculator` for derived values, in `{testing}/fakes/` — *Done (reconciled 2026-11-08): In-memory fakes in `{testing}/` (AuthFakes, CustomerFakes, LedgerFakes, ReportFakes, BackupFakes) computing balances with `LedgerCalculator`.*

### Encrypted database (complete schema v1)

- [X] T031 [P] Implement `KeystoreKeyWrapper` (AES-256-GCM Keystore keys `ebm_db_wrap`, `ebm_kek_wrap`; wrap/unwrap blobs) in `{data}/security/KeystoreKeyWrapper.kt`
- [X] T032 Implement `DatabaseKeyProvider` (generate a 32-byte key on first launch, store it wrapped in `noBackupFilesDir`) in `{data}/security/DatabaseKeyProvider.kt`
- [X] T033 [P] Create Room entities per data-model.md: `OwnerAccountEntity`, `SecurityAnswerEntity`, `BusinessProfileEntity` in `{data}/db/entity/AccountEntities.kt` — *Done (reconciled 2026-11-08): Account, security answer and business profile entities in `{data}/db/entity/`.*
- [X] T034 [P] Create `CustomerEntity` with indexes in `{data}/db/entity/CustomerEntity.kt`
- [X] T035 [P] Create `MeasurementUnitEntity` and `EggVarietyEntity` in `{data}/db/entity/CatalogEntities.kt` — *In `{data}/db/entity/CatalogEntities.kt`; seeds in SchemaCallback.*
- [X] T036 [P] Create `LedgerEntryEntity` with all columns and indexes from data-model.md in `{data}/db/entity/LedgerEntryEntity.kt` — *Done (reconciled 2026-11-08): variety_id/unit_id foreign keys added in Phase 4.*
- [X] T037 [P] Create `AuditLogEntity`, `ReferenceSequenceEntity`, `AppSettingEntity`, `BackupRecordEntity` in `{data}/db/entity/SupportEntities.kt` — *Done (reconciled 2026-11-08): BackupRecordEntity added in Phase 9.*
- [X] T038 [P] Create type converters (enums ↔ TEXT, `LocalDate` ↔ epoch day) in `{data}/db/converter/Converters.kt` — *Done (reconciled 2026-11-08): Not needed: enums are stored as TEXT names and dates as epoch days by explicit mapping in the entity mappers, so Room needs no TypeConverters.*
- [X] T039 Create `EggBusinessDatabase` (version 1, `exportSchema=true`, SQLCipher `SupportOpenHelperFactory`, `foreign_keys=ON`, WAL) in `{data}/db/EggBusinessDatabase.kt` — *Done (reconciled 2026-11-08): `EggBusinessDatabase` (exported schema, SQLCipher factory, foreign keys, WAL).*
- [X] T040 Create `SchemaCallback` with raw SQL for the CHECK constraints, the partial unique index `(root_entry_id) WHERE status='ACTIVE'`, and seed data (units Piece/Dozen/Tray/Box 210, varieties White(default)/Brown/Country, reference sequences, default settings) in `{data}/db/SchemaCallback.kt` — *Done (reconciled 2026-11-08): Unit and variety seeds added in Phase 4.*
- [X] T041 Implement `DatabaseBootstrapper` basics: open the key, downgrade guard (`user_version` > current ⇒ `UpdateRequired` state), pre-migration snapshot (keep the last 2), hook point for restore states (completed in US9), in `{data}/db/DatabaseBootstrapper.kt`
- [X] T042 Implement `RoomTransactionRunner` (`withTransaction`) in `{data}/db/RoomTransactionRunner.kt`
- [X] T043 Generate and commit the exported schema `data/schemas/com.eggbusinessmanager.data.db.EggBusinessDatabase/1.json` — *Done (reconciled 2026-11-08): `data/schemas/.../1.json` committed.*
- [X] T044 [P] Write integration tests on real SQLCipher: DB opens encrypted (file header not plaintext), seed data present, CHECK constraints reject invalid trade/payment/opening rows, partial unique index rejects two ACTIVE revisions, FK RESTRICT blocks customer delete with entries, in `{dataIt}/db/SchemaIntegrityTest.kt` — *Done (reconciled 2026-11-08): `{dataIt}/db/EncryptedDatabaseTest.kt` (encrypted header, seeds, constraints) plus trigger tests in the repository tests.*
- [ ] T045 [P] Write tests for the bootstrapper downgrade guard and pre-migration snapshot creation in `{dataIt}/db/DatabaseBootstrapperTest.kt`

### Security primitives

- [X] T046 [P] Write tests for `Pbkdf2SecretHasher` (600k iterations, random salt, constant-time verify, stored iterations honoured) in `{dataTest}/security/Pbkdf2SecretHasherTest.kt`
- [X] T047 [P] Implement `Pbkdf2SecretHasher` (runs on the Default dispatcher) in `{data}/security/Pbkdf2SecretHasher.kt`
- [X] T048 Implement `InMemorySessionManager` (Locked/Unlocked state, `touch()`, auto-lock timer from settings, background timeout via `ProcessLifecycleOwner`) in `{data}/security/InMemorySessionManager.kt` — *Done as `AppSessionManager` in :app (it follows the Android process lifecycle), bound to the domain `SessionManager` port.*

### App shell, DI, theme, localization

- [X] T049 [P] Create Hilt modules: `DispatcherModule` (`@IoDispatcher`, `@DefaultDispatcher`), `DatabaseModule`, `RepositoryModule`, `SecurityModule` in `{data}/di/` and `{app}/di/AppModule.kt` — *Done (reconciled 2026-11-08): Business, backup and settings bindings added in their phases (DataModule, AppModule).*
- [ ] T050 Create `EggBusinessApplication` (`@HiltAndroidApp`, WorkManager `Configuration.Provider` with `HiltWorkerFactory`, runs `DatabaseBootstrapper` before the first DB access) in `{app}/EggBusinessApplication.kt`
- [X] T051 Create `MainActivity` (single activity, edge-to-edge, recents protection: `setRecentsScreenshotEnabled(false)` on API 33+, `FLAG_SECURE` on 31–32) in `{app}/MainActivity.kt`
- [X] T052 [P] Create the Material 3 theme (light/dark, dynamic color on 31+, typography with font scaling, 48 dp touch targets) in `{app}/ui/theme/`
- [X] T053 [P] Create shared composables (`MoneyText` with balance direction colours, `AmountField`, `QuantityField`, `DateField` with no-future rule, `ConfirmDialog`, `EmptyState`, `ErrorBanner`, `LoadingOverlay`) in `{app}/ui/components/` — *Done (reconciled 2026-11-08): Shared composables in `{app}/ui/components/` (BalanceText, DateField with no-future rule, FormFields incl. amount/quantity fields and dropdowns, StateViews, confirm dialogs).*
- [ ] T054 [P] Implement `IndianFormatters` (en-IN / hi-IN-u-nu-latn money grouping, dates, quantity with remainder "10 trays 5 pieces" per BR-015) with unit tests in `{app}/ui/format/IndianFormatters.kt` and `{appTest}/ui/format/IndianFormattersTest.kt` — *Partial (2026-11-08): `IndianFormatters` money (lakh grouping) and date, with tests; quantity-with-remainder comes with US3.*
- [X] T055 [P] Create the base string resources for common UI, all validation messages and all error messages from the spec tables in English `{appRes}/values/strings.xml` and Hindi `{appRes}/values-hi/strings.xml` — *Done (reconciled 2026-11-08): Strings for every module in English and Hindi.*
- [X] T056 Implement `ErrorMessages` mapping `DomainError`/`ValidationCode` → string resources in `{app}/ui/ErrorMessages.kt`
- [X] T057 Create the navigation shell: type-safe route definitions per `contracts/navigation.md` in `{app}/navigation/Routes.kt`; `AppNavHost` with start-destination logic (restore pending / no account / account) in `{app}/navigation/AppNavHost.kt`; `MainScaffold` using `NavigationSuiteScaffold` with 5 top-level destinations (placeholder screens) in `{app}/navigation/MainScaffold.kt` — *Done (reconciled 2026-11-08): A confirmed restore is applied at startup by the bootstrapper before the root graph is chosen (Phase 9).*
- [X] T058 Create `LockOverlay` drawn above the `NavHost` when the session is Locked, and `InactivityTracker` (pointer-input modifier calling `SessionManager.touch()`), in `{app}/session/`

**Checkpoint**: the app launches into a placeholder Welcome screen on an encrypted database; domain
and schema tests pass

---

## Phase 3: User Story 1 - Create Account and Sign In Securely (Priority: P1) 🎯 MVP

**Goal**: Signup (mobile, optional email, password, shop details, 3 security questions), sign-in by
mobile or email, lockout, biometric unlock, auto-lock, language choice on Welcome

**Independent Test**: Fresh install → sign up → close → sign in with mobile, then email → 5 wrong
passwords ⇒ 30 s countdown (survives restart) → idle 6 min ⇒ lock overlay keeps form input

### Tests for User Story 1

- [X] T059 [P] [US1] Unit tests for `AccountValidator` (password rules incl. ≠ mobile, confirm match, 3 different questions, answer rules) in `{domainTest}/validation/AccountValidatorTest.kt`
- [X] T060 [P] [US1] Unit tests for `SignUpUseCase` (atomic account + profile + answers; signup refused when an account exists, FR-001) in `{domainTest}/usecase/auth/SignUpUseCaseTest.kt`
- [X] T061 [P] [US1] Unit tests for `SignInUseCase` (mobile or email case-insensitive, generic error, lockout 5 → 30 s doubling to 30 min max, reset on success, clock moving backwards keeps the lock) in `{domainTest}/usecase/auth/SignInUseCaseTest.kt`
- [X] T062 [P] [US1] Integration test for `RoomAccountRepository` (singleton constraint, unique mobile/email, lockout persistence across DB reopen) in `{dataIt}/repository/AccountRepositoryTest.kt`
- [X] T063 [P] [US1] Compose UI test: signup flow → dashboard, then sign-in with mobile and with email, in `{appUiTest}/auth/SignupSignInFlowTest.kt`
- [ ] T064 [P] [US1] Compose UI test: lockout countdown, and the lock overlay preserving entered form text after unlock, in `{appUiTest}/auth/LockBehaviourTest.kt` — *Partial (2026-11-08): Lock and unlock via the overlay are covered by AuthFlowTest; the lockout countdown and kept-form-text cases are covered by unit tests (SignInUseCaseTest, AppSessionManagerTest) but not yet by a UI test.*

### Implementation for User Story 1

> 2026-11-08: recovery uses a 6-digit recovery PIN **and** 3 security questions (spec FR-012, clarified). The PIN is hashed like the password, set at signup (SignupSecurity step) and changeable in Security settings.


- [X] T065 [P] [US1] Implement `AccountValidator` in `{domain}/validation/AccountValidator.kt`
- [X] T066 [P] [US1] Define the predefined security question keys (≥ 10, stable ids) in `{domain}/model/SecurityQuestions.kt` and their English/Hindi texts in `{appRes}/values/strings_security_questions.xml` and `{appRes}/values-hi/strings_security_questions.xml`
- [X] T067 [P] [US1] Create `AccountDao` and `BusinessProfileDao` in `{data}/db/dao/AccountDao.kt` and `{data}/db/dao/BusinessProfileDao.kt`
- [X] T068 [US1] Implement `RoomAccountRepository` and `RoomBusinessProfileRepository` with mappers in `{data}/repository/RoomAccountRepository.kt`, `{data}/repository/RoomBusinessProfileRepository.kt`, `{data}/mapper/AccountMappers.kt`
- [X] T069 [US1] Implement `SignUpUseCase`, `SignInUseCase`, `SignOutUseCase`, `ObserveSessionUseCase` in `{domain}/usecase/auth/`
- [X] T070 [US1] Implement the `WelcomeScreen` (create account / restore from backup / language picker; restore button navigates to the US9 route, disabled until US9 exists) and `WelcomeViewModel` in `{app}/feature/auth/welcome/` — *Done (reconciled 2026-11-08): "Restore from a backup" enabled in Phase 9; language picker tracked in T076.*
- [X] T071 [US1] Implement the three signup screens (`SignupAccountScreen`, `SignupShopScreen`, `SignupQuestionsScreen`) with a shared sub-graph-scoped `SignupViewModel` (`SavedStateHandle`) in `{app}/feature/auth/signup/`
- [X] T072 [US1] Implement `SignInScreen` + `SignInViewModel` (generic error, countdown, forgot-password link stub for US11) in `{app}/feature/auth/signin/`
- [X] T073 [US1] Implement `BiometricCryptoProvider` (Keystore key `ebm_bio`: auth-required, invalidated by enrollment; `KeyPermanentlyInvalidatedException` ⇒ disable with notice) in `{data}/security/BiometricCryptoProvider.kt`
- [X] T074 [US1] Implement `EnableBiometricUseCase`, `DisableBiometricUseCase`, `UnlockWithBiometricUseCase` in `{domain}/usecase/auth/` and the biometric prompt integration in `{app}/feature/auth/signin/BiometricPromptLauncher.kt`
- [X] T075 [US1] Wire `LockOverlay` unlock (password + biometric) to `SessionManager`, and the background/inactivity auto-lock using the `auto_lock_minutes` setting, in `{app}/session/LockOverlay.kt`
- [X] T076 [US1] Implement `ChangeLanguageUseCase` (persist `language` setting + `AppCompatDelegate.setApplicationLocales`) in `{domain}/usecase/settings/ChangeLanguageUseCase.kt` and `{data}/settings/LocaleApplier.kt` — *Done (2026-11-08): Language picker (phone language / English / हिन्दी) on Display & preferences via `AppCompatDelegate.setApplicationLocales`; AppCompat stores the choice on Android 12, the system on 13+; tested by `{appUiTest}/settings/LanguageSwitchTest.kt`. A phone setting, so not in backups.*
- [X] T077 [US1] Add US1 strings (welcome, signup, sign-in, lockout, biometric) in English and Hindi to `{appRes}/values/strings.xml` and `{appRes}/values-hi/strings.xml`

**Checkpoint**: the owner can create an account and get in and out securely

---

## Phase 4: User Story 2 - Manage and Find Customers (Priority: P1)

**Goal**: Add/edit/archive/un-archive/delete customers with an optional opening balance; search by
name or mobile; sort and filter the list

**Independent Test**: Add 3 customers (opening receivable, opening payable, none); search "ram" and
"9876"; edit one; archive one; delete an unused one

### Tests for User Story 2

- [X] T078 [P] [US2] Unit tests for `CustomerValidator` (name 2–80, duplicate-name warning, mobile unique, opening amount and date) in `{domainTest}/validation/CustomerValidatorTest.kt`
- [X] T079 [P] [US2] Unit tests for `CreateCustomerUseCase` (opening balance ⇒ OPENING_BALANCE entry with OB- reference), `UpdateCustomerUseCase` (opening change ⇒ revision + audit), `Archive`/`Unarchive`/`Delete` rules in `{domainTest}/usecase/customer/CustomerUseCasesTest.kt`
- [X] T080 [P] [US2] Integration tests for `CustomerDao` search (case-insensitive substring on name and mobile, archived excluded unless requested), sort by balance/last activity, filters To receive/To pay/Settled/Archived, in `{dataIt}/db/CustomerDaoTest.kt` — *Covered in `{dataIt}/repository/CustomerRepositoryTest.kt` through the Room repositories (search, filters, sorts, SQL = Kotlin balance, unique mobile, FK RESTRICT, triggers, one-active-revision index).*
- [X] T081 [P] [US2] Compose UI test: add customer with opening balance → appears with "To receive ₹1,500.00"; duplicate mobile blocked with "open existing" action; search empty state offers "Add '<text>'"; in `{appUiTest}/customers/CustomerFlowsTest.kt`

### Implementation for User Story 2

- [X] T082 [P] [US2] Implement `CustomerValidator` in `{domain}/validation/CustomerValidator.kt`
- [X] T083 [P] [US2] Create `CustomerDao` (search, summaries with balance aggregate and last activity, picker query by recent activity, `findByMobile`) in `{data}/db/dao/CustomerDao.kt`
- [X] T084 [P] [US2] Create `LedgerEntryDao` write primitives (insert, status update, sequence next value) and `AuditLogDao` in `{data}/db/dao/LedgerEntryDao.kt` and `{data}/db/dao/AuditLogDao.kt` — *`AuditLogDao` lives in `LedgerEntryDao.kt`; ledger_entry integrity triggers and the partial unique index are in `{data}/db/SchemaCallback.kt`.*
- [X] T085 [US2] Implement `RoomLedgerRepository.insert` (generic single-entry insert with reference sequence, `client_request_id`, `BalanceEffect`, audit — used for opening balances now and for all types later) in `{data}/repository/RoomLedgerRepository.kt` — *Done (reconciled 2026-11-08): Generic `insertEntry`/`reviseEntry`/`replaceEntry` with client_request_id via `EntryWriter` (Phase 5).*
- [X] T086 [US2] Implement `RoomCustomerRepository` and `RoomAuditRepository` with mappers in `{data}/repository/RoomCustomerRepository.kt`, `{data}/repository/RoomAuditRepository.kt`, `{data}/mapper/CustomerMappers.kt`
- [X] T087 [US2] Implement `SearchCustomersUseCase`, `GetCustomerDetailUseCase`, `CreateCustomerUseCase`, `UpdateCustomerUseCase`, `ArchiveCustomerUseCase`, `UnarchiveCustomerUseCase`, `DeleteCustomerUseCase` in `{domain}/usecase/customer/`
- [ ] T088 [US2] Implement `CustomersScreen` (search-as-you-type, sort, filter chips, include-archived toggle, `ListDetailPaneScaffold` on expanded width) + `CustomersViewModel` in `{app}/feature/customers/list/` — *Partial (2026-11-08): Search-as-you-type, filter chips (Active / To receive / To pay / Settled / Deactivated) and sort done; the Deactivated filter replaces the include-archived toggle; `ListDetailPaneScaffold` for wide screens not yet.*
- [X] T089 [US2] Implement `CustomerEditorScreen` (name, mobile, address, notes, opening balance amount/direction/date; `returnToPicker` result) + `CustomerEditorViewModel` in `{app}/feature/customers/editor/` — *Done (reconciled 2026-11-08): `returnToPicker` hands a new customer back to Sell/Buy and payment screens (Phases 4–5).*
- [X] T090 [US2] Implement `CustomerDetailScreen` (balance header, contact, quick-action placeholders for US3/US4, archive/delete with confirmation stating the outstanding amount) + `CustomerDetailViewModel` in `{app}/feature/customers/detail/` — *Done (reconciled 2026-11-08): Sell/Buy/Receive/Pay quick actions and Statement shortcut added in Phases 4, 5 and 8.*
- [X] T091 [US2] Add US2 strings in English and Hindi to `{appRes}/values/strings.xml` and `{appRes}/values-hi/strings.xml`

**Checkpoint**: customers can be managed and found; opening balances show correct balances

---

## Phase 5: User Story 3 - Record Egg Sales and Purchases (Priority: P1)

**Goal**: SELL/BUY entries with date, customer, variety, quantity, unit, price, live total and
pieces, last-price pre-fill, optional on-the-spot payment (atomic), inline customer creation,
idempotent save

**Independent Test**: SELL 10 Tray Brown @ ₹180 ⇒ ₹1,800.00 / 300 pcs, balance +₹1,800; BUY ⇒
balance decreases; SELL with ₹1,000 received now ⇒ S- and RP- lines, net +₹800

### Tests for User Story 3

- [X] T092 [P] [US3] Unit tests for `EntryValidator` trade rules (quantity > 0, ≤ 3 decimals, whole pieces, ≤ 10,000,000 pcs, price > 0 ≤ ₹10,00,000, total range, active customer/unit/variety, "amount now" ≤ total) in `{domainTest}/validation/EntryValidatorTradeTest.kt` — *As `{domainTest}/trade/TradeValidatorTest.kt` (`TradeValidator`); linked-payment rules come with payments.*
- [X] T093 [P] [US3] Unit tests for `RecordSaleUseCase`/`RecordPurchaseUseCase` (effects, references S-/B-, linked payment created atomically, piece-count snapshot) and `SuggestPriceUseCase` (per customer + type + variety + unit) in `{domainTest}/usecase/entry/RecordTradeUseCasesTest.kt` — *Done (2026-11-08): `{domainTest}/usecase/trade/PaidNowTest.kt` covers the on-the-spot payment (linked, up to the total, idempotent, edit/type change keep the link, delete together or alone).*
- [X] T094 [P] [US3] Integration tests: trade + linked payment in one transaction (inject failure after the first insert ⇒ nothing persisted); duplicate `client_request_id` ⇒ single row; sequences never reused; in `{dataIt}/repository/LedgerRepositoryTradeTest.kt` — *Done (2026-11-08): `{dataIt}/repository/PaidNowRepositoryTest.kt`: trade + payment in one transaction, injected failure after the first insert leaves nothing, duplicate request id = one pair.*
- [X] T095 [P] [US3] Compose UI test: record a sale with on-the-spot payment; double-tap Save creates one entry; inline new customer keeps typed values; in `{appUiTest}/entries/RecordTradeFlowTest.kt` — *Done (2026-11-08): `{appUiTest}/trade/PaidNowFlowTest.kt`: sale with ₹500 received now by UPI, linked entries open each other, delete both together.*

### Implementation for User Story 3

- [X] T096 [P] [US3] Implement `EntryValidator` (trade part) in `{domain}/validation/EntryValidator.kt` — *As `TradeValidator` in `{domain}/trade/Trade.kt`.*
- [X] T097 [P] [US3] Create `CatalogDao` (active units/varieties, defaults) in `{data}/db/dao/CatalogDao.kt` and `RoomCatalogRepository` read operations in `{data}/repository/RoomCatalogRepository.kt` — *Plus unit/variety management (FR-059, FR-027a) in `{data}/repository/RoomCatalogRepository.kt`.*
- [X] T098 [US3] Implement `RoomLedgerRepository.insertTradeWithPayment` and `lastPrice` query in `{data}/repository/RoomLedgerRepository.kt` and `{data}/db/dao/LedgerEntryDao.kt` — *Done (2026-11-08): `EntryWriter.recordLinked` + `RoomLedgerRepository.link` (linked_root_entry_id both ways, kept on revisions and type changes).*
- [X] T099 [US3] Implement `RecordSaleUseCase`, `RecordPurchaseUseCase`, `SuggestPriceUseCase` in `{domain}/usecase/entry/` — *As `RecordTradeUseCase` (SELL and BUY) and `SuggestPriceUseCase` in `{domain}/usecase/trade/`.*
- [X] T100 [US3] Implement `EntryEditorScreen` in trade mode (customer picker sorted by recent activity with inline add; variety, unit, quantity, price, live total and pieces; date; remarks; "amount received/paid now" + mode; Save disabled while saving; UUID per editor session) + `EntryEditorViewModel` in `{app}/feature/entries/editor/` — *As `TradeEditorScreen`/`TradeEditorViewModel` in `{app}/feature/trade/` (separate Sell eggs / Buy eggs titles).*
- [X] T101 [US3] Implement the post-save confirmation sheet (reference, "Record another"; "Share receipt" hidden until US7) in `{app}/feature/entries/editor/SaveConfirmationSheet.kt` — *Saved dialog with reference and "Record another".*
- [X] T102 [US3] Wire the Sell/Buy quick actions from `CustomerDetailScreen` and a temporary dashboard FAB to `EntryEditor` in `{app}/feature/customers/detail/CustomerDetailScreen.kt` and `{app}/navigation/MainScaffold.kt` — *Sell/Buy on customer detail and on the dashboard.*
- [X] T103 [US3] Add US3 strings in English and Hindi to `{appRes}/values/strings.xml` and `{appRes}/values-hi/strings.xml` — *In `strings_trades.xml` (English and Hindi).*

**Checkpoint**: daily trading can be recorded; balances move correctly

---

## Phase 6: User Story 4 - Record Payments and See Accurate Balances and Ledgers (Priority: P1)

**Goal**: RECEIVE PAYMENT / PAY CUSTOMER with mode and reference, advance notice, "Full amount",
customer ledger with running balance and per-type totals; identical balances everywhere

**Independent Test**: SELL 5,000 · BUY 2,000 · RECEIVE 1,500 · PAY 500 ⇒ "To receive ₹2,000.00",
running balances 5,000 / 3,000 / 1,500 / 2,000; advance scenario 3a

### Tests for User Story 4

- [X] T104 [P] [US4] Unit tests for `EntryValidator` payment rules and `PreviewPaymentEffectUseCase` (advance amount and resulting balance for both directions, settled customer advance) in `{domainTest}/usecase/entry/PaymentUseCasesTest.kt` — *As `{domainTest}/payment/PaymentRulesTest.kt` (`PaymentValidator`, `PaymentImpact`) and `{domainTest}/usecase/payment/LedgerScenariosTest.kt` (positive, negative, zero, advance, spec example, edits/deletes of historical entries, 40 random histories).*
- [ ] T105 [P] [US4] Integration tests: ledger running balance via SQL window function equals `LedgerCalculator` on 500 generated random entries incl. back-dated ones; per-type totals; balance query == customer list balance; in `{dataIt}/db/LedgerQueriesTest.kt` — *Partial (2026-11-08): `{dataIt}/repository/PaymentRepositoryTest.kt`: spec example, all balance directions, edit/delete recalculation, SQL list balance = LedgerCalculator, payment triggers; running balance is computed in `LedgerCalculator` (no SQL window query yet), so the 500-row SQL comparison is pending.*
- [X] T106 [P] [US4] Compose UI test: record payment with "Full amount" ⇒ Settled; advance notice shown and confirmed; ledger shows correct running balances; in `{appUiTest}/ledger/PaymentAndLedgerFlowTest.kt` — *As `{appUiTest}/payment/PaymentFlowsTest.kt`: advance confirmed, "Full amount" ⇒ Settled, totals, payments filter, correction.*

### Implementation for User Story 4

- [X] T107 [P] [US4] Extend `EntryValidator` with payment rules in `{domain}/validation/EntryValidator.kt` — *As `PaymentValidator` in `{domain}/payment/Payment.kt`.*
- [ ] T108 [P] [US4] Create ledger read queries (`ledgerLines` with `SUM() OVER`, per-type totals, balance by customer) in `{data}/db/dao/LedgerQueryDao.kt` — *Partial (2026-11-08): Per-type totals and running balance computed by `LedgerCalculator` from the entries flow; SQL window-function queries deferred until paging is needed.*
- [X] T109 [US4] Implement `RoomLedgerRepository.ledger` and `balance` flows in `{data}/repository/RoomLedgerRepository.kt` — *`observeEntries` + SQL balance aggregate; generic `insertEntry`/`reviseEntry`/`replaceEntry` shared by trades and payments.*
- [X] T110 [US4] Implement `RecordPaymentReceivedUseCase`, `RecordPaymentMadeUseCase`, `PreviewPaymentEffectUseCase`, `ObserveCustomerLedgerUseCase`, `ObserveCustomerBalanceUseCase` in `{domain}/usecase/entry/` and `{domain}/usecase/ledger/` — *As `RecordPaymentUseCase` (both directions), `EditPaymentUseCase`, `GetPaymentDraftUseCase`, `PreviewPaymentUseCase` in `{domain}/usecase/payment/`; all writes go through `EntryWriter`.*
- [X] T111 [US4] Extend `EntryEditorScreen`/`EntryEditorViewModel` with payment mode (amount, mode default Cash, reference, current balance, "Full amount", advance confirmation dialog) in `{app}/feature/entries/editor/` — *As `PaymentEditorScreen`/`PaymentEditorViewModel` in `{app}/feature/payment/`.*
- [ ] T112 [US4] Implement `LedgerScreen` (balance at top, per-type totals, lines with date, reference, type, qty/unit, debit/credit, running balance, remarks; paged) + `LedgerViewModel` in `{app}/feature/ledger/` — *Partial (2026-11-08): `CustomerHistoryScreen` shows closing balance, per-type totals, running balance per line, payment mode and an All/Payments filter; not paged yet.*
- [X] T113 [US4] Wire Receive/Pay quick actions and the "View ledger" action on `CustomerDetailScreen` in `{app}/feature/customers/detail/CustomerDetailScreen.kt` — *Receive/Pay on customer detail and dashboard; "Transactions" opens the ledger.*
- [X] T114 [US4] Add US4 strings in English and Hindi to `{appRes}/values/strings.xml` and `{appRes}/values-hi/strings.xml` — *In `strings_payments.xml` (English and Hindi).*

**Checkpoint**: MVP complete — accounts, customers, trades, payments and exact ledgers (US1–US4)

---

## Phase 7: User Story 5 - Review, Filter and Correct Transactions (Priority: P2)

**Goal**: History with filters, chips, sort and summary totals; entry detail; edit any field incl.
customer and type; void with linked-payment choice; revision history

**Independent Test**: 20 mixed entries → filter SELL + customer + last 7 days ⇒ correct rows and
totals; change customer A→C; change SELL→BUY; void; history shows every change

### Tests for User Story 5

- [ ] T115 [P] [US5] Unit tests for `EditEntryUseCase` (same root and reference, superseded original, audit before/after, customer change moves both balances, voided/superseded not editable) in `{domainTest}/usecase/entry/EditEntryUseCaseTest.kt`
- [ ] T116 [P] [US5] Unit tests for `ChangeEntryTypeUseCase` (new root and reference in the new series, `replaced_by_root_id`, trade↔payment field rules, linked-payment convert-or-void) and `PreviewEditEffectUseCase` in `{domainTest}/usecase/entry/ChangeEntryTypeUseCaseTest.kt`
- [ ] T117 [P] [US5] Unit tests for `VoidEntryUseCase` (excluded from balances, linked payment optional void, terminal state) in `{domainTest}/usecase/entry/VoidEntryUseCaseTest.kt`
- [ ] T118 [P] [US5] Property tests: random sequences of create/edit/type-change/customer-change/void keep Σ balances = Σ active effects, and exactly one ACTIVE row per live root, in `{domainTest}/ledger/RevisionPropertiesTest.kt`
- [ ] T119 [P] [US5] Integration tests: `EntryFilter` query combinations (dates, multi-type, multi-customer, variety, unit, mode, amount range, status, text on remarks/reference) and summary totals, plus atomic revise/changeType, in `{dataIt}/repository/LedgerRepositoryRevisionTest.kt` — *Partial (2026-11-08): Phase 6 (Dashboard and Transactions): `{dataIt}/repository/ReportRepositoryTest.kt` checks date range, multi-type, customer and name/mobile search (incl. LIKE escaping) against `TransactionFilter.matches` on random data; variety/unit/mode/amount/remarks filters pending.*
- [ ] T120 [P] [US5] Compose UI test: filter chips + clear all; edit customer with balance-effect preview; change type shows "Changed to B-…"; void shows the label, in `{appUiTest}/history/HistoryAndCorrectionsTest.kt`

### Implementation for User Story 5

- [ ] T121 [P] [US5] Define the `EntryFilter`/`EntrySort` models in `{domain}/model/EntryFilter.kt` — *Partial (2026-11-08): Phase 6 (Dashboard and Transactions): `TransactionFilter`/`TransactionSort` in `{domain}/report/Reports.kt` (dates, customer, types, name/mobile search, 5 sorts).*
- [ ] T122 [US5] Implement `RoomLedgerRepository.revise`, `changeType`, `void`, `entry`, `history`, `query` (Paging 3 `PagingSource` + summary aggregate) in `{data}/repository/RoomLedgerRepository.kt` and `{data}/db/dao/LedgerQueryDao.kt` — *Partial (2026-11-08): Phase 6 (Dashboard and Transactions): filtered query in `ReportDao.transactions`, totals in the domain; not paged yet.*
- [ ] T123 [US5] Implement `EditEntryUseCase`, `ChangeEntryTypeUseCase`, `PreviewEditEffectUseCase`, `VoidEntryUseCase`, `GetEntryDetailUseCase`, `GetEntryHistoryUseCase`, `QueryEntriesUseCase` in `{domain}/usecase/entry/` and `{domain}/usecase/history/`
- [ ] T124 [US5] Implement `HistoryScreen` (grouped by date, summary bar, filter bottom sheet, chips, sort, list-detail on expanded width) + `HistoryViewModel` in `{app}/feature/history/` — *Partial (2026-11-08): Phase 6 (Dashboard and Transactions): Transactions tab (`{app}/feature/transactions/`) with date range, customer, Sell/Buy/Receive/Pay multi-select, search, sort and filtered totals; no date grouping or list-detail yet.*
- [ ] T125 [US5] Implement `EntryDetailScreen` (all fields, status labels Revised/Voided/"Changed to", Edit, Void with reason and linked prompt) + `EntryDetailViewModel` in `{app}/feature/entries/detail/`
- [ ] T126 [US5] Extend `EntryEditorScreen` for edit mode (prefill from `editRootId`, type switcher, customer change, balance-effect preview dialog, reason field, linked-payment choice) in `{app}/feature/entries/editor/`
- [ ] T127 [US5] Implement `EntryRevisionHistoryScreen` (old → new values, time, reason) in `{app}/feature/entries/revisions/`
- [X] T128 [US5] Add US5 strings in English and Hindi to `{appRes}/values/strings.xml` and `{appRes}/values-hi/strings.xml` — *Transactions strings in `strings_reports.xml` (English and Hindi).*

**Checkpoint**: mistakes can be found and corrected with a full audit trail

---

## Phase 8: User Story 6 - See Business at a Glance on the Dashboard (Priority: P2)

**Goal**: Period figures, dues, top-5 receivables, quick actions, recent 10 entries, backup reminder,
midnight rollover, empty-state guide

**Independent Test**: Seeded data → every dashboard figure equals a hand calculation; change the
fixed clock past midnight ⇒ "Today" resets

### Tests for User Story 6

- [X] T129 [P] [US6] Unit tests for `ObserveDashboardUseCase` (Today/Week/Month/Custom periods, net cash, midnight ticker using `FixedClock`), `ObserveDuesUseCase` (receivable/payable not netted, counts, top 5) and `ObserveBackupReminderUseCase` (7-day rule) in `{domainTest}/usecase/dashboard/DashboardUseCasesTest.kt` — *Done (2026-11-08): `{domainTest}/usecase/report/ReportUseCasesTest.kt`: Today/Week/Month figures incl. payments and net cash, top 5, midnight rollover with the ticker, backup reminder 7-day rule (custom range not built).*
- [X] T130 [P] [US6] Integration tests for dashboard aggregate queries vs `LedgerCalculator` on generated data in `{dataIt}/db/DashboardQueriesTest.kt` — *As `{dataIt}/repository/ReportRepositoryTest.kt`: SQL dashboard = `DashboardCalculator` on random data, dues = customer balances, live updates.*
- [X] T131 [P] [US6] Compose UI test: empty-state guide on a new account; quick actions open the right editors; backup banner visible without a backup, in `{appUiTest}/dashboard/DashboardTest.kt` — *Done (2026-11-08): `{appUiTest}/reports/DashboardExtrasTest.kt`: backup banner and first-customer guide on a new account, top customers, period selector, Back up now; `DashboardAndTransactionsTest.kt` for figures.*

### Implementation for User Story 6

- [X] T132 [P] [US6] Create `ReportDao` dashboard and dues aggregate queries in `{data}/db/dao/ReportDao.kt` — *Customer counts, trade totals (all time and today), dues and recent queries.*
- [X] T133 [US6] Implement `RoomReportRepository.dashboard` and `dues` in `{data}/repository/RoomReportRepository.kt` — *`observeDashboard` combines the aggregates; `observeTransactions` for the Transactions tab.*
- [X] T134 [US6] Implement `ObserveDashboardUseCase`, `ObserveDuesUseCase`, `ObserveBackupReminderUseCase` in `{domain}/usecase/dashboard/` — *Done (2026-11-08): `ObserveDashboardUseCase(period)` with midnight ticker and top 5, `ObserveBackupReminderUseCase` in `{domain}/usecase/report/ReportUseCases.kt`.*
- [ ] T135 [US6] Implement `DashboardScreen` (period selector, stat cards in pieces and display unit, dues cards, top-5 list, quick actions Sell/Buy/Receive/Pay/Add customer, recent 10, backup banner) + `DashboardViewModel` in `{app}/feature/dashboard/`; replace the temporary FAB from T102 with the type-chooser FAB in `{app}/navigation/MainScaffold.kt` — *Partial (2026-11-08): period selector (Today/This week/This month), sales/purchases/received/paid/net cash, dues with top 5, quick actions incl. Add customer, recent 10, backup banner, first-customer guide; custom period and display-unit figures not built.*
- [X] T136 [US6] Add US6 strings in English and Hindi to `{appRes}/values/strings.xml` and `{appRes}/values-hi/strings.xml` — *In `strings_reports.xml` (English and Hindi).*

**Checkpoint**: the owner sees the business state on opening the app

---

## Phase 9: User Story 7 - Generate and Share Customer Statements (Priority: P2)

**Goal**: Statement for a date range (brought-forward, entries, running balance, totals, closing
meaning) as PDF/CSV/XLSX; WhatsApp and share sheet; balance reminder; PDF receipts (FR-042a)

**Independent Test**: A customer with entries before and inside the range ⇒ correct brought-forward
and closing balance; export each format; Share on WhatsApp opens WhatsApp; without WhatsApp the
chooser opens

### Tests for User Story 7

- [ ] T137 [P] [US7] Unit tests for `BuildStatementUseCase` (brought-forward per BR-012, debit/credit columns, empty period line, voided excluded) and `BuildReceiptUseCase` (content per FR-042a, voided refused, revised label) in `{domainTest}/usecase/document/StatementAndReceiptTest.kt` — *Partial (2026-11-08): statement tests done; `{domainTest}/receipt/BuildReceiptTest.kt` covers receipt content, revised label, voided/opening refused, linked payment.*
- [X] T138 [P] [US7] JVM tests for `CsvRenderer` (BOM, RFC 4180 escaping, CRLF, ISO dates, plain amounts, Hindi text) and `XlsxRenderer` (valid ZIP parts, numeric and date cells, frozen header) in `{dataTest}/document/CsvRendererTest.kt` and `{dataTest}/document/XlsxRendererTest.kt` — *As `{dataTest}/export/CsvReportWriterTest.kt` (BOM, CRLF, RFC 4180 round trip, ISO dates, plain amounts, Hindi, CSV-injection guard) and `XlsxReportWriterTest.kt` (package parts parse, typed number/date cells, styles, escaping, 1,500-row sheet).*
- [ ] T139 [P] [US7] Instrumented tests for `PdfRenderer` (pagination with page "x of y", shop header, Devanagari text renders, one-page receipt) in `{dataIt}/document/PdfRendererTest.kt` — *Partial (2026-11-08): Phase 7 (Reporting and export): `{appUiTest}/reports/ReportExportTest.kt` renders saved PDFs with PdfRenderer (A4, ink on every page, multi-page statements); receipt layout pending.*
- [ ] T140 [P] [US7] Instrumented tests for `IntentShareGateway` (WhatsApp package selection incl. w4b fallback, `jid` extra, chooser fallback, URI read grant) in `{dataIt}/share/IntentShareGatewayTest.kt`

### Implementation for User Story 7

- [X] T141 [P] [US7] Define the format-independent `DocumentModel` (header, sections, tables, totals, footer) in `{domain}/model/DocumentModel.kt` — *As `ReportDocument`/`Table`/`Cell` in `{domain}/export/ReportDocument.kt` (typed cells keep raw values for spreadsheets).*
- [X] T142 [P] [US7] Implement `CsvRenderer` in `{data}/document/csv/CsvRenderer.kt` — *As `CsvReportWriter` in `{data}/export/`.*
- [X] T143 [P] [US7] Implement the minimal `XlsxRenderer` (SpreadsheetML via `java.util.zip`) in `{data}/document/xlsx/XlsxRenderer.kt` — *As `XlsxReportWriter` in `{data}/export/` (no spreadsheet library).*
- [ ] T144 [P] [US7] Implement `PdfRenderer` (PdfDocument + StaticLayout table engine, A4 and receipt layouts, localized context) in `{data}/document/pdf/PdfRenderer.kt` and `{data}/document/pdf/PdfTableLayout.kt` — *Partial (2026-11-08): Phase 7 (Reporting and export): `PdfReportWriter` (A4, letterhead, summary box, repeated table header, zebra rows, word-wrapped cells, totals row, page x of y); receipt layout pending.*
- [ ] T145 [US7] Implement `DocumentRendererImpl` (format dispatch, file naming per contract, writes to `cacheDir/exports`) in `{data}/document/DocumentRendererImpl.kt` — *Partial (2026-11-08): Phase 7 (Reporting and export): `FileReportExporter` (format dispatch) and `ReportFileName`; files are written straight to the SAF document, no cache copy.*
- [ ] T146 [US7] Implement `IntentShareGateway` (FileProvider URIs, WhatsApp/w4b, chooser, SAF save, text share) in `{data}/share/IntentShareGateway.kt` — *Partial (2026-11-08): Phase 7 (Reporting and export): SAF save via `CreateDocument` + `ReportFileSaver`; WhatsApp/share pending.*
- [X] T147 [US7] Implement `RoomReportRepository.statement` (brought-forward + range lines) in `{data}/repository/RoomReportRepository.kt` — *Statement built from the customer ledger by `ReportBuilder.statement` (brought-forward + range lines).*
- [ ] T148 [US7] Implement `BuildStatementUseCase`, `BuildReceiptUseCase`, `ExportDocumentUseCase`, `ShareDocumentUseCase`, `ShareBalanceReminderUseCase` in `{domain}/usecase/document/` — *Partial (2026-11-08): `BuildReceiptUseCase` in `{domain}/receipt/Receipt.kt` added; balance reminder pending.*
- [ ] T149 [US7] Implement `StatementScreen` (range picker default this month, preview, export format menu, Share on WhatsApp, Save, Send balance reminder with editable text dialog) + `StatementViewModel` in `{app}/feature/statements/` — *Partial (2026-11-08): Phase 7 (Reporting and export): `ReportViewerScreen` (customer picker, date range defaulting to this month, preview, Save as PDF/Excel/CSV); WhatsApp share and balance reminder pending.*
- [X] T150 [US7] Implement `ReceiptPreviewScreen` and enable "Share receipt" on the save confirmation (T101) and on `EntryDetailScreen` in `{app}/feature/entries/receipt/`, `{app}/feature/entries/editor/SaveConfirmationSheet.kt`, `{app}/feature/entries/detail/EntryDetailScreen.kt` — *Done (2026-11-08): "Share receipt" on the save confirmation (trades and payments) and on the entry screen; `{app}/feature/receipt/` (ReceiptDocumentFactory, ShareReceipt); tested by `{appUiTest}/receipt/ReceiptShareTest.kt`.*
- [ ] T151 [US7] Add statement, receipt, reminder template and document label strings in English and Hindi to `{appRes}/values/strings_documents.xml` and `{appRes}/values-hi/strings_documents.xml` — *Partial (2026-11-08): receipt strings in `strings_receipts.xml` (English and Hindi); reminder template pending.*

**Checkpoint**: customers can receive accurate statements and receipts on WhatsApp

---

## Phase 10: User Story 8 - Daily and Monthly Reports with Export (Priority: P2)

**Goal**: Daily and monthly reports (by type, variety, payment mode; day-by-day; top 10; dues at
month end) and transaction-list export in PDF/CSV/XLSX; export file cleanup

**Independent Test**: Two months of seeded entries ⇒ daily and monthly figures equal hand
calculation; export all three formats; open in viewer apps

### Tests for User Story 8

- [ ] T152 [P] [US8] Unit tests for `BuildDailyReportUseCase` and `BuildMonthlyReportUseCase` (trading difference label, average price per piece by variety, payment mode breakdown, empty period) in `{domainTest}/usecase/document/ReportsTest.kt` — *Partial (2026-11-08): Phase 7 (Reporting and export): daily and monthly sales/purchase report tests (per day/month, per customer, every entry) in `LoadReportUseCaseTest.kt`; average price by variety and payment-mode breakdown pending.*
- [ ] T153 [P] [US8] Integration tests for report aggregate queries (daily, monthly day-by-day, top 10, dues as at month end) vs `LedgerCalculator` in `{dataIt}/db/ReportQueriesTest.kt`
- [X] T154 [P] [US8] Test `CleanupExportsUseCase`/`CleanupWorker` deletes export files older than 24 h only, in `{dataIt}/document/ExportCleanupTest.kt` — *Done (reconciled 2026-11-08): Shared export files are deleted after 24 h by `ReportSharer` each time it prepares a file (`MAX_AGE_MS`); saved reports go straight to the user's chosen SAF location, so nothing else needs cleaning.*

### Implementation for User Story 8

- [ ] T155 [P] [US8] Add daily/monthly/top-customer/dues-as-at queries to `{data}/db/dao/ReportDao.kt` and implement `RoomReportRepository.daily` and `monthly` in `{data}/repository/RoomReportRepository.kt` — *Partial (2026-11-08): Phase 7 (Reporting and export): reports reuse `ReportDao.transactions` and a new all-customer balances query; top-10 and dues-as-at-month-end pending.*
- [ ] T156 [US8] Implement `BuildDailyReportUseCase`, `BuildMonthlyReportUseCase`, `BuildEntryListExportUseCase`, `CleanupExportsUseCase` in `{domain}/usecase/document/` — *Partial (2026-11-08): Phase 7 (Reporting and export): daily/monthly building in `ReportBuilder.tradePeriods`; cleanup not needed (no cache files).*
- [X] T157 [US8] Implement `CleanupWorker` (daily unique periodic work + run at app start) in `{data}/document/CleanupWorker.kt` — *Done (reconciled 2026-11-08): No worker needed: cleanup runs inside `ReportSharer` (see T154).*
- [ ] T158 [US8] Implement `ReportsScreen` (pick a day or a month), `DailyReportScreen`, `MonthlyReportScreen` with export/share/save menus + ViewModels in `{app}/feature/reports/` — *Partial (2026-11-08): Phase 7 (Reporting and export): Reports tab lists nine reports; one viewer screen serves daily, monthly, outstanding, payment history and date-range transaction reports.*
- [ ] T159 [US8] Add an Export action to `HistoryScreen` that exports exactly the filtered set, in `{app}/feature/history/HistoryScreen.kt`
- [X] T160 [US8] Add report strings in English and Hindi to `{appRes}/values/strings_documents.xml` and `{appRes}/values-hi/strings_documents.xml` — *In `strings_exports.xml` (English and Hindi).*

**Checkpoint**: end-of-day and monthly reporting are available in all formats

---

## Phase 11: User Story 9 - Back Up and Restore Data Manually (Priority: P2)

**Goal**: Backup passphrase; encrypted backup to a device location (SAF) and to Google Drive;
restore from either with verification, summary, crash-safe swap and rollback; restore from Welcome on
a new device; password reset after restore

**Independent Test**: Back up locally and to Drive → change data → restore each ⇒ data equals the
backup point; wrong passphrase / corrupt / foreign / newer files ⇒ distinct messages; crash during
swap ⇒ previous data back

### Tests for User Story 9

- [X] T161 [P] [US9] JVM tests for `BackupHeaderCodec` (magic, length bounds, JSON fields, unknown formatVersion) in `{dataTest}/backup/format/BackupHeaderCodecTest.kt` — *As `{dataTest}/backup/format/BackupFileFormatTest.kt` (magic, length bounds, JSON fields, newer format, unknown fields/triggers, wrong passphrase, tampered header, NFKC passphrase).*
- [X] T162 [P] [US9] Instrumented round-trip tests for `SnapshotBackupCrypto`: backup → inspect ⇒ table-by-table equality; wrong passphrase ⇒ `WRONG_PASSPHRASE`; truncated or bit-flipped ⇒ `CORRUPT`; JPEG ⇒ `NOT_A_BACKUP`; snapshot with `user_version` + 1 ⇒ `NEWER_VERSION`; no plaintext temp files ever exist; in `{dataIt}/backup/SnapshotBackupCryptoTest.kt` — *As `{dataIt}/backup/BackupRestoreTest.kt`: backup → restore round trip, wrong passphrase, damaged/truncated/foreign files, newer schema.*
- [X] T163 [P] [US9] Instrumented tests for the restore state machine in `DatabaseBootstrapper`: success path; crash injected in SWAPPING and in VERIFYING ⇒ rolled back on the next start; `backup_record` rows carried over; older-schema backup migrates; in `{dataIt}/backup/RestoreStateMachineTest.kt` — *In `BackupRestoreTest.kt`: crash injected in SWAPPING and VERIFYING ⇒ rolled back on the next start; failed verification ⇒ rollback; history carried over.*
- [X] T164 [P] [US9] JVM tests for `DriveClient` with MockWebServer (resumable upload + resume, list with appProperties parsing, download, delete, error mapping 401/403 quota/429/5xx) in `{dataTest}/backup/drive/DriveClientTest.kt` — *As `{dataTest}/backup/drive/DriveClientTest.kt` with MockWebServer: chunked resumable upload, resume after a hung connection, paged list, download, 401/403 quota/404/offline, retry with backoff.*
- [X] T165 [P] [US9] Unit tests for `SetBackupPassphraseUseCase` (rules, ≠ login password), `StageRestoreUseCase`/`ConfirmRestoreUseCase` (no data change before confirm), and restore-then-reset password (`must_change_password`) in `{domainTest}/usecase/backup/ManualBackupRestoreTest.kt` — *As `{domainTest}/backup/BackupUseCasesTest.kt`: passphrase rules incl. ≠ login password, staging changes nothing, safety backup before restore, failure stops the restore.*
- [ ] T166 [P] [US9] Compose UI test: passphrase setup acknowledgement; backup to device (SAF via `Intents` stub); restore flow shows summary before replace; in `{appUiTest}/backup/ManualBackupRestoreFlowTest.kt` — *Partial (2026-11-08): Phase 9: `{appUiTest}/backup/BackupFlowTest.kt` (passphrase, backup to device via stubbed SAF, wrong passphrase, preview needs acknowledgement); the confirmed restore restarts the app, so the swap is covered in the data tests.*

### Implementation for User Story 9

- [X] T167 [P] [US9] Implement `BackupHeader` + `BackupHeaderCodec` per `contracts/backup-format.md` in `{data}/backup/format/` — *`BackupFileFormat` in `{data}/backup/format/`.*
- [X] T168 [P] [US9] Implement `BackupKeyStore` (derive KEK with PBKDF2, store wrapped with `ebm_kek_wrap`, salt and iterations in DataStore; verify and change passphrase) in `{data}/backup/crypto/BackupKeyStore.kt` — *`BackupKeyStore` (KEK from PBKDF2 600k, wrapped with Keystore alias `ebm_kek_wrap`, salt and iterations in no-backup storage).*
- [X] T169 [US9] Implement `SnapshotBackupCrypto` (`ATTACH … KEY` + `sqlcipher_export` in a read transaction, SHA-256, AES-GCM wrap with header AAD, write; inspect/validate in contract order) in `{data}/backup/SnapshotBackupCrypto.kt` — *`SnapshotBackupEngine` (`ATTACH … KEY` + `sqlcipher_export` on a separate connection, SHA-256, AES-GCM wrap with header AAD; validation in contract order; re-key into staging).*
- [X] T170 [US9] Implement `ProcessRestartRestoreCoordinator` (stage: re-key into `staging.db`, read summary; commit: write state STAGED, cancel backup work, restart the process; discard) in `{data}/backup/ProcessRestartRestoreCoordinator.kt` — *Staging, confirmation and process restart in `SnapshotBackupEngine` + `BackupScreen`.*
- [X] T171 [US9] Complete the restore states in `DatabaseBootstrapper` (SWAPPING/VERIFYING/COMMITTED/ROLLED_BACK, safety copy kept until first sign-in, `backup_record` carry-over, FAILED/INTERRUPTED records) in `{data}/db/DatabaseBootstrapper.kt` — *`RestoreCoordinator` run by `DatabaseBootstrapper` before Room opens: SWAPPING/VERIFYING, safety copy, history carry-over, rollback, one-time outcome.*
- [X] T172 [P] [US9] Implement `SafDeviceDestination` (write to a `CREATE_DOCUMENT` URI, read from an `OPEN_DOCUMENT` URI, low-storage and permission errors) in `{data}/backup/destination/saf/SafDeviceDestination.kt` — *`SafDeviceBackupStore` (CREATE_DOCUMENT/OPEN_DOCUMENT URIs, location errors).*
- [ ] T173 [P] [US9] Implement `GoogleAuthorizer` (AuthorizationClient with `drive.appdata`, interactive `PendingIntent` result, silent token for workers, `AUTH_REQUIRED` mapping) in `{data}/backup/destination/drive/GoogleAuthorizer.kt` — *Partial (2026-11-08): Phase 9: `GoogleDriveAuthorizer` (AuthorizationClient, `drive.appdata`, interactive consent PendingIntent, silent token, AUTH_REQUIRED); not yet exercised against a real Google account — needs the Google Cloud OAuth client.*
- [ ] T174 [US9] Implement `DriveClient` (OkHttp + kotlinx.serialization: resumable upload 8 MiB chunks, list, download to `cacheDir/restore`, delete) and `DriveAppDataDestination` in `{data}/backup/destination/drive/` — *Partial (2026-11-08): Phase 9: `DriveClient` (OkHttp: resumable 8 MiB chunks with resume, list, download, delete) and `GoogleDriveBackupStore`; tested with a mock Drive only.*
- [ ] T175 [US9] Implement `BackupRecordDao`, `BackupRepositoryImpl` and the DataStore `BackupStatusStore` in `{data}/db/dao/BackupRecordDao.kt`, `{data}/backup/BackupRepositoryImpl.kt`, `{data}/backup/BackupStatusStore.kt` — *Partial (2026-11-08): Phase 9: `BackupRecordDao` + `RoomBackupHistory`; status is derived from the history table (no separate DataStore).*
- [X] T176 [US9] Implement `RunBackupJob` (snapshot → encrypt → destination → record; never touches the live DB beyond a read transaction; shared restore mutex) in `{domain}/usecase/backup/RunBackupJob.kt` and `ManualBackupWorker` (unique one-time, expedited, `setForeground` dataSync) in `{data}/backup/worker/ManualBackupWorker.kt` — *Done (reconciled 2026-11-08): Automatic backup added in Phase 10 (`RunAutomaticBackupUseCase`); manual and automatic share `BackupLock`.*
- [X] T177 [US9] Implement `SetBackupPassphraseUseCase`, `BackupToDeviceUseCase`, `BackupToDriveUseCase`, `LinkDriveUseCase`, `ListDriveBackupsUseCase`, `StageRestoreUseCase`, `ConfirmRestoreUseCase`, `CancelRestoreUseCase` in `{domain}/usecase/backup/` — *In `{domain}/backup/BackupUseCases.kt`.*
- [ ] T178 [US9] Implement `BackupCenterScreen` (status card, Back up to device, Back up to Google Drive with link flow and offline message, Restore) + `BackupCenterViewModel` and `BackupPassphraseScreen` (create with acknowledgement) in `{app}/feature/backup/` — *Partial (2026-11-08): Phase 9: `BackupScreen` (last successful backup, passphrase, Google Drive connect, destination, Back up now, restore from device/Drive/safety backup, history with Download).*
- [ ] T179 [US9] Implement the restore flow screens `RestoreSourceScreen` (device file / Drive list with date, size, device, app version), `RestorePassphraseScreen`, `RestoreSummaryScreen` (confirm "Replace all current data"), `RestoreProgressScreen` in `{app}/feature/backup/restore/` — *Partial (2026-11-08): Phase 9: restore steps as dialogs in `BackupScreen` (pick, passphrase, preview with explicit acknowledgement).*
- [X] T180 [US9] Enable "Restore from backup" on `WelcomeScreen`, and after a restore offer "Set a new password" based on the proven passphrase (US9-9), in `{app}/feature/auth/welcome/WelcomeScreen.kt` and `{app}/feature/auth/signin/SignInScreen.kt` — *Done (2026-11-08): "Restore from a backup" on Welcome; after a restore the sign-in screen reports it and offers "Set a new password" (`SetPasswordAfterRestoreUseCase` with the in-memory `RestoreProof`, one use); tested by `{domainTest}/backup/BackupExtrasTest.kt` and `{appUiTest}/backup/PasswordAfterRestoreTest.kt`.*
- [X] T181 [US9] Add backup/restore strings and all restore failure messages in English and Hindi to `{appRes}/values/strings_backup.xml` and `{appRes}/values-hi/strings_backup.xml` — *In `strings_backup.xml` (English and Hindi), one message per failure.*

**Checkpoint**: data is protected by manual backups and can move to a new phone

---

## Phase 12: User Story 10 - Scheduled Automatic Google Drive Backup and Backup History (Priority: P3)

**Goal**: Daily/weekly automatic Drive backups with constraints and retries; retention of 10
automatic backups; failure/miss notifications; history and status; disconnect Drive

**Independent Test**: Enable daily + Wi-Fi only → force-run ⇒ new Drive file and history row; disable
Wi-Fi ⇒ waits; revoke access ⇒ "Google account disconnected", notification after 3 failures

### Tests for User Story 10

- [X] T182 [P] [US10] `work-testing` tests for `AutoBackupWorker` scheduling (unique periodic work UPDATE policy, interval + flex window, UNMETERED vs CONNECTED, charging/battery/storage constraints, backoff) in `{dataIt}/backup/worker/AutoBackupSchedulingTest.kt` — *As `{appUiTest}/backup/AutoBackupSchedulingTest.kt` on real WorkManager: unique one-time work chained per slot (daily/weekly/monthly, not periodic: periodic work cannot express monthly or a time of day), REPLACE on change, KEEP at app start, CONNECTED vs UNMETERED, battery/storage not low, charging, cancel; worker run via work-testing.*
- [X] T183 [P] [US10] Tests for the failure/miss counter and notification at 3, and for `CheckMissedBackupsUseCase` at app start, in `{dataIt}/backup/worker/BackupFailureNotificationTest.kt` — *Done (2026-11-08): `CheckMissedBackupsUseCase` at app start notifies after 3 slots without a successful automatic backup since it was switched on (`enabledSince`), plus the final-failure notification; tested in `BackupExtrasTest.kt` and `AutoBackupTest.kt`.*
- [X] T184 [P] [US10] JVM test: retention deletes only AUTOMATIC files beyond the newest 10 and never MANUAL ones, in `{dataTest}/backup/drive/DriveRetentionTest.kt` — *As retention tests in `{domainTest}/backup/AutoBackupTest.kt` and `{dataIt}/backup/AutomaticBackupRestoreTest.kt` (keep last N automatic, configurable 5/10/20/30; manual never deleted).*
- [ ] T185 [P] [US10] Compose UI test: auto-backup settings persist and reschedule; history list shows status, trigger, size and failure reason, in `{appUiTest}/backup/AutoBackupAndHistoryTest.kt` — *Partial (2026-11-08): Phase 10: settings persistence tested in `AutomaticBackupRestoreTest`; scheduling in `AutoBackupSchedulingTest`; history UI from Phase 9.*

### Implementation for User Story 10

- [X] T186 [US10] Implement `AutoBackupWorker` (HiltWorker; calls `RunBackupJob` with AUTOMATIC; retention; failure counting; `setForeground`) in `{data}/backup/worker/AutoBackupWorker.kt` — *As `AutoBackupWorker` (EntryPoint, opens the DB via the bootstrapper, `RunAutomaticBackupUseCase`, retry with exponential backoff, reschedules the next slot).*
- [X] T187 [US10] Implement `AutoBackupScheduler` (enqueue/update/cancel unique periodic work from settings; reschedule on settings change and at app start) in `{data}/backup/worker/AutoBackupScheduler.kt` — *As `WorkManagerAutoBackupScheduler` + `ScheduleNextAutoBackupUseCase` (settings change and app start).*
- [X] T188 [P] [US10] Implement `BackupNotifier` (channel "Backups", failure/missed notification with an explicit `PendingIntent` to BackupCenter, POST_NOTIFICATIONS permission check) in `{data}/backup/BackupNotifier.kt` — *As `AndroidBackupNotifier` (channel "Backup status", POST_NOTIFICATIONS requested on enable).*
- [ ] T189 [US10] Implement `ConfigureAutoBackupUseCase`, `UnlinkDriveUseCase` (turns auto-backup off, leaves Drive files), `DeleteDriveBackupUseCase`, `ObserveBackupHistoryUseCase`, `ObserveBackupStatusUseCase`, `CheckMissedBackupsUseCase` in `{domain}/usecase/backup/` — *Partial (2026-11-08): `DeleteDriveBackupUseCase` and `CheckMissedBackupsUseCase` added; retention also clears download links; unlinking Drive is done by disconnecting on the Backup screen.*
- [X] T190 [US10] Implement `AutoBackupSettingsScreen` (enable, Daily/Weekly, time window, Wi-Fi only, charging only; request POST_NOTIFICATIONS on enable) + ViewModel in `{app}/feature/backup/auto/` — *As `AutoBackupSection` on the Backup & restore screen (enable, daily/weekly/monthly with day, preferred time, retention, Wi-Fi only, charging only, next run "around …", battery hint).*
- [X] T191 [US10] Implement `BackupHistoryScreen` (all attempts, delete individual Drive backups) + ViewModel, and extend the status card with next scheduled run and linked account, in `{app}/feature/backup/history/` and `{app}/feature/backup/BackupCenterScreen.kt` — *Done (2026-11-08): History shows every attempt; Drive backups have Download and Delete (with confirmation) and show "Deleted from Google Drive" afterwards; status card shows last success, last automatic failure and next run.*
- [X] T192 [US10] Add US10 strings in English and Hindi to `{appRes}/values/strings_backup.xml` and `{appRes}/values-hi/strings_backup.xml` — *In `strings_backup.xml` (English and Hindi).*

**Checkpoint**: backups happen automatically with visible history

---

## Phase 13: User Story 11 - Change Password and Recover Access (Priority: P3)

**Goal**: Change password; change login mobile/email; forgot-password recovery with 3 security
answers; update security questions; shared lockout; help text when everything is lost

**Independent Test**: Change password ⇒ old rejected; recover with differently-cased answers ⇒
success; one wrong answer ⇒ generic message; 5 failures ⇒ lockout

### Tests for User Story 11

- [X] T193 [P] [US11] Unit tests for `ChangePasswordUseCase`, `ChangeLoginIdentifiersUseCase`, `RecoverPasswordUseCase` (all 3 answers, normalization NFKC/case/space, generic error, shared lockout counter), `UpdateSecurityQuestionsUseCase` in `{domainTest}/usecase/auth/PasswordManagementTest.kt` — *Done (reconciled 2026-11-08): Changing the sign-in mobile/email is covered by `UpdateOwnerProfileUseCase` tests (Phase 11).*
- [ ] T194 [P] [US11] Compose UI test: forgot password → answers → new password → prompt to review questions; change password flow, in `{appUiTest}/auth/RecoveryFlowTest.kt` — *Partial (2026-11-08): Forgot password → recovery PIN → new password → review prompt is covered by AuthFlowTest; the questions path and the change-password screen are covered only by unit tests so far.*

### Implementation for User Story 11

- [X] T195 [US11] Implement `SecurityAnswerNormalizer` in `{domain}/validation/SecurityAnswerNormalizer.kt`
- [X] T196 [US11] Implement `ChangePasswordUseCase`, `ChangeLoginIdentifiersUseCase`, `RecoverPasswordUseCase`, `UpdateSecurityQuestionsUseCase` in `{domain}/usecase/auth/` — *Done (reconciled 2026-11-08): Login identifier change (FR-010) built as `UpdateOwnerProfileUseCase` with password confirmation (Phase 11).*
- [X] T197 [US11] Implement `ForgotPasswordScreen`, `SetNewPasswordScreen` (incl. "lost everything" help text pointing to restore) + ViewModels in `{app}/feature/auth/recovery/`, and enable the forgot-password link in `{app}/feature/auth/signin/SignInScreen.kt`
- [X] T198 [US11] Implement `SecuritySettingsScreen` with `ChangePasswordScreen`, `ChangeIdentifiersScreen`, `EditSecurityQuestionsScreen`, biometric toggle, and a link to the backup passphrase change (US12) in `{app}/feature/settings/security/` — *Done (reconciled 2026-11-08): Login identifiers are changed on the Owner profile screen (Phase 11); the passphrase is set on Backup & restore.*
- [X] T199 [US11] Add US11 strings in English and Hindi to `{appRes}/values/strings.xml` and `{appRes}/values-hi/strings.xml`

**Checkpoint**: the owner can keep credentials current and regain access offline

---

## Phase 14: User Story 12 - Business Profile, Measurement Units and App Settings (Priority: P3)

**Goal**: Edit business profile; manage units (add, rename, resize with notice, deactivate; Piece
fixed) and varieties (add, rename, default, deactivate; at least one active); defaults; auto-lock,
theme, language; backup passphrase change; About

**Independent Test**: Change shop name ⇒ appears on a new PDF; add Crate 210, sell 1 crate, resize to
180 ⇒ old entry still 210 pcs; cannot deactivate the last variety

### Tests for User Story 12

- [X] T200 [P] [US12] Unit tests for `ManageUnitsUseCase` (unique names, 1–10,000 pieces, Piece immutable, resize never alters past entries, used units deactivate instead of delete) and `ManageVarietiesUseCase` (exactly one default, last active protected, rename propagates) in `{domainTest}/usecase/settings/CatalogManagementTest.kt` — *Covered by `{domainTest}/usecase/catalog/CatalogUseCasesTest.kt` (Phase 4).*
- [X] T201 [P] [US12] Integration test: resizing a unit leaves stored `pieces_per_unit_snapshot`/`pieces` untouched and audits the resize, in `{dataIt}/repository/CatalogRepositoryTest.kt` — *Covered by `{dataIt}/repository/TradeRepositoryTest.kt` resize test (Phase 4).*
- [ ] T202 [P] [US12] Unit tests for `ChangeBackupPassphraseUseCase` (current passphrase required; old backups still need the old one) in `{domainTest}/usecase/backup/ChangeBackupPassphraseTest.kt` — *Partial (2026-11-08): Phase 11: the passphrase can be set again from Backup & restore (`SetBackupPassphraseUseCase`, ≠ login password); old backups keep their own salt, so they still need the old passphrase.*
- [X] T203 [P] [US12] Compose UI test: profile edit, unit add/resize notice, variety default change, theme and language switch without restart, in `{appUiTest}/settings/SettingsFlowsTest.kt` — *Done (2026-11-08): `{appUiTest}/CompleteAppTest.kt` (profiles, formats, default unit, auto-lock, About) and `{appUiTest}/settings/LanguageSwitchTest.kt` (Hindi and back without restart).*

### Implementation for User Story 12

- [X] T204 [US12] Implement `RoomCatalogRepository` write operations (upsert/resize/deactivate units; upsert/default/deactivate varieties with invariants and audit) in `{data}/repository/RoomCatalogRepository.kt` — *Phase 4 (`RoomCatalogRepository`).*
- [X] T205 [US12] Implement `RoomSettingsRepository` (typed `app_setting`, unknown keys ignored) in `{data}/repository/RoomSettingsRepository.kt` — *`RoomSettingsRepository` also implements `AppPreferencesRepository` (currency symbol, grouping, date style, default unit, auto-lock); unknown values fall back to defaults.*
- [ ] T206 [US12] Implement `UpdateBusinessProfileUseCase`, `ManageUnitsUseCase`, `ManageVarietiesUseCase`, `UpdateAppSettingsUseCase`, `ChangeBackupPassphraseUseCase` in `{domain}/usecase/settings/` and `{domain}/usecase/backup/` — *Partial (2026-11-08): Phase 11: `UpdateShopProfileUseCase`, `UpdateOwnerProfileUseCase` (password required to change the sign-in mobile/email), `UpdatePreferencesUseCase`, `SetAutoLockMinutesUseCase`; catalog use cases from Phase 4.*
- [X] T207 [US12] Implement `MoreScreen` (settings hub) and `BusinessProfileScreen` in `{app}/feature/settings/` — *More hub with Shop profile, Owner profile, Security, Units, Varieties, Backup & restore, Display & preferences, About and Sign out; `ShopProfileScreen`/`OwnerProfileScreen` in `{app}/feature/settings/profile/`.*
- [X] T208 [P] [US12] Implement `UnitsSettingsScreen` and `VarietiesSettingsScreen` + ViewModels in `{app}/feature/settings/catalog/` — *Phase 4 (`CatalogScreens.kt`).*
- [ ] T209 [P] [US12] Implement `AppPreferencesScreen` (default Buy/Sell units, default variety, display unit, auto-lock timeout, theme, language) and `AboutScreen` (version name/code) in `{app}/feature/settings/preferences/` — *Partial (2026-11-08): theme, language, ₹/Rs., grouping, date format and default unit on `PreferencesScreen`; auto-lock on Security; `AboutScreen`. A separate display unit for totals is not built.*
- [ ] T210 [US12] Implement the backup passphrase change screen (states that old backups need the old passphrase) in `{app}/feature/backup/BackupPassphraseScreen.kt` — *Partial (2026-11-08): Phase 11: the passphrase dialog on Backup & restore sets a new passphrase; its text says old backups cannot be opened without the passphrase they were made with.*
- [X] T211 [US12] Add US12 strings in English and Hindi to `{appRes}/values/strings.xml` and `{appRes}/values-hi/strings.xml` — *In `strings_settings.xml` (English and Hindi).*

**Checkpoint**: all user stories are functional

---

## Phase 15: Polish & Cross-Cutting Concerns

**Purpose**: Hardening, performance, accessibility, release readiness

- [ ] T212 [P] Create a debug-only seed tool (2,000 customers / 100,000 entries) and fault-injection flags (crash during save, SWAPPING, VERIFYING) in `app/src/debug/java/com/eggbusinessmanager/app/debug/DebugMenu.kt`
- [ ] T213 Create the `:benchmark` Macrobenchmark module measuring customer search, dashboard open, monthly report (SC-005) and cold start in `benchmark/src/main/java/com/eggbusinessmanager/benchmark/`
- [ ] T214 Optimize any query that misses the SC-005 targets (index review with `EXPLAIN QUERY PLAN`) in `{data}/db/dao/` and record the results in `specs/001-egg-business-manager/quickstart.md` § 17
- [ ] T215 [P] Accessibility pass: content descriptions, 48 dp targets, 200% font scale, TalkBack order for money values, in `{app}/ui/components/` and the feature screens
- [ ] T216 [P] Adaptive layout pass on compact, medium and expanded widths and a foldable posture (rail/drawer, list-detail), in `{app}/navigation/MainScaffold.kt` and `{app}/feature/customers/list/CustomersScreen.kt`
- [ ] T217 [P] Hindi translation review of all string files `{appRes}/values-hi/*.xml` and document layouts in Hindi
- [ ] T218 [P] Security review against plan § Security Architecture: manifest exported components, no secrets or PII in logs (grep), R8 log stripping verified in the release APK, `apksigner verify --print-certs` output, in `specs/001-egg-business-manager/checklists/security-review.md`
- [ ] T219 Write the release checklist (version bump, signing, schema JSON committed, migration tests, upgrade-install N-1 → N with seeded data, restore of the previous release's backup, release notes for schema/backup-format changes) in `docs/RELEASE_CHECKLIST.md` — *Partial (2026-11-08): `docs/RELEASE.md` (version, signing, validation checklist and results, upgrade check with `scripts/release_check.py`, keystore backup).*
- [ ] T220 [P] Write `README.md` (build prerequisites, module overview, Google Cloud OAuth setup for Drive, keystore handling warning) — *Partial (2026-11-08): `docs/RELEASE.md` covers building, signing and the Google Cloud OAuth set-up; `docs/USER_GUIDE.md` covers every feature.*
- [ ] T221 Run every scenario in `specs/001-egg-business-manager/quickstart.md` (1–18) on API 31, latest API and a physical mid-range phone; record the results in `specs/001-egg-business-manager/checklists/validation-results.md`
- [ ] T222 Run all quality gates: `:domain:test`, `:app:testDebugUnitTest`, `:data:connectedDebugAndroidTest`, `:app:connectedDebugAndroidTest`, `koverVerify`, `lint`, `detekt`, `scripts/check-no-destructive-migration.ps1`, `:app:assembleRelease`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies; T001 (applicationId) must be decided before T003.
- **Foundational (Phase 2)**: depends on Setup. It **blocks all user stories**. The complete schema
  v1 is created here, so later stories add DAOs and queries but no tables.
- **User stories (Phases 3–14)**: depend on Foundational.
- **Polish (Phase 15)**: depends on all stories that are in scope for the release.

### User Story Dependencies

| Story | Depends on | Reason |
|-------|------------|--------|
| US1 Auth | Foundational | — |
| US2 Customers | US1 | screens sit behind sign-in |
| US3 Trades | US2 | needs customers; uses the generic ledger insert from T085 |
| US4 Payments & ledger | US2 (US3 for on-the-spot payment display) | |
| US5 History & corrections | US3, US4 | edits/voids existing entries |
| US6 Dashboard | US3, US4 | aggregates entries; backup banner reads backup status (shows "never" until US9) |
| US7 Statements & receipts | US4 | ledger data; adds the shared document/share engine |
| US8 Reports | US7 | reuses renderers and share gateway |
| US9 Manual backup/restore | US1 (account in backup), Foundational DB | independent of US3–US8 data features |
| US10 Auto backup | US9 | reuses RunBackupJob, Drive destination |
| US11 Password management | US1 | |
| US12 Settings & catalog | US3 (units/varieties in use) | catalog writes and preferences |

### Within Each User Story

- Write the tests first and confirm they fail.
- Order: domain validators/models → DAOs → repositories → use cases → ViewModels/screens → strings.
- Strings for a story are added in the same story so Hindi never lags behind.

### Parallel Opportunities

- Setup: T005–T012 and T014 in parallel after T003/T004.
- Foundational: domain tests T015–T019 in parallel; entities T033–T038 in parallel; theme,
  components and formatters (T052–T055) in parallel.
- After US2: US11 and US9 can proceed in parallel with US3/US4 (different packages).
- After US4: US5, US6 and US7 can proceed in parallel; US8 follows US7.
- Within each story, all tasks marked [P] in its tests block run together.

---

## Parallel Example: User Story 3

```text
# Tests together:
T092 EntryValidatorTradeTest.kt   T093 RecordTradeUseCasesTest.kt
T094 LedgerRepositoryTradeTest.kt T095 RecordTradeFlowTest.kt
# Then in parallel:
T096 EntryValidator.kt            T097 CatalogDao.kt + RoomCatalogRepository (read)
# Then sequentially:
T098 → T099 → T100 → T101 → T102 → T103
```

## Parallel Example: User Story 9

```text
# Tests together: T161, T162, T163, T164, T165, T166
# Then in parallel: T167 header codec, T168 key store, T172 SAF destination, T173 Google authorizer
# Then: T169 → T170 → T171, T174 → T175 → T176 → T177 → T178 → T179 → T180 → T181
```

---

## Implementation Strategy

### MVP first (US1–US4)

1. Phase 1 Setup → Phase 2 Foundational.
2. US1 → US2 → US3 → US4. **STOP and VALIDATE** with quickstart scenarios 1–5 and 8.
3. The MVP is a secure offline ledger for daily trading. Because it has no backup yet, install it
   on a test phone only — not for real business use.

### Recommended first real-use release (MVP + protection)

4. Add **US9** (manual backup/restore) **before** the owner relies on the app with real data
   (Constitution V: the phone holds the only copy).
5. Add US5 (corrections) and US6 (dashboard).

### Incremental delivery

6. US7 → US8 (documents and sharing).
7. US10 → US11 → US12.
8. Phase 15 polish, full quickstart validation, signed release APK.

Each increment is a signed APK installed over the previous one. Schema v1 must be final before the
**first APK given to the owner for real data**. After that, every schema change follows the
migration policy (data-model.md) with migration tests.

---

## Notes

- [P] = different files and no dependency on incomplete tasks.
- Story labels map every task to spec.md for traceability.
- Commit after each task or logical group. Never commit keystores or `local.properties`.
- Constitution gates apply to every merge: tests green, lint/detekt clean, no destructive migration,
  financial changes with unit tests, schema changes with migration + exported schema + test.
