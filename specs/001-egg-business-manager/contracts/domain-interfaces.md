# Contract: Domain Interfaces (Repositories and Use Cases)

The `:domain` module (pure Kotlin, no Android or Room imports) owns these interfaces. `:data`
implements the repositories, and `:app` ViewModels call **use cases only**, never repositories
directly. Signatures are given in Kotlin to remove ambiguity; bodies are not specified here.

## Core types

```kotlin
@JvmInline value class Money(val paise: Long)            // exact; plus/minus via Math.*Exact
@JvmInline value class QuantityMilli(val value: Long)     // 1.5 → 1500
@JvmInline value class CustomerId(val value: Long)
@JvmInline value class EntryRootId(val value: Long)

enum class EntryType { OPENING_BALANCE, SELL, BUY, PAYMENT_RECEIVED, PAYMENT_MADE }
enum class EntryStatus { ACTIVE, SUPERSEDED, VOIDED }
enum class PaymentMode { CASH, UPI, BANK_TRANSFER, CHEQUE, OTHER }
enum class OpeningDirection { CUSTOMER_OWES, SHOP_OWES }

sealed interface Balance { data class ToReceive(val m: Money); data class ToPay(val m: Money); data object Settled }

/** The ONLY place where entry sign rules live (BR-001..BR-005). */
object BalanceEffect { fun of(type: EntryType, amount: Money, opening: OpeningDirection? = null): Money }

/** The ONLY place where line totals are computed and rounded (FR-021). */
object TradeMath {
    fun lineTotal(quantity: QuantityMilli, pricePerUnit: Money): Money            // HALF_UP to paise
    fun pieces(quantity: QuantityMilli, piecesPerUnit: Int): Long?                  // null if not whole
}

/** Pure recomputation used by tests and statements; must equal SQL results. */
object LedgerCalculator {
    fun balance(entries: List<LedgerEntry>): Money
    fun runningLines(entries: List<LedgerEntry>): List<LedgerLine>                   // BR-011 order
    fun broughtForward(entries: List<LedgerEntry>, start: LocalDate): Money          // BR-012
}

sealed interface DomainResult<out T> { data class Ok<T>(val value: T); data class Err(val error: DomainError) }
sealed interface DomainError {
    data class Validation(val fieldErrors: Map<Field, ValidationCode>) : DomainError
    data object DuplicateMobile : DomainError
    data class Locked(val until: Instant) : DomainError
    data object InvalidCredentials : DomainError
    data object NotFound : DomainError
    data object EntryNotEditable : DomainError          // voided / superseded
    data object LowStorage : DomainError
    data class Backup(val code: BackupFailure) : DomainError
    data object Unexpected : DomainError
}
```

Cross-cutting abstractions: `interface TransactionRunner { suspend fun <T> inTransaction(block: suspend () -> T): T }`,
`interface Clock { fun now(): Instant; fun today(): LocalDate }`, and `interface IdGenerator { fun uuid(): String }`.

## Repositories

| Repository | Responsibility | Key operations |
|------------|----------------|----------------|
| `AccountRepository` | owner account, credentials, lockout, security answers | `exists(): Flow<Boolean>`, `create(SignupData, hashedSecrets)`, `findCredentialFor(identifier)`, `recordFailure(at)`, `resetFailures()`, `updatePassword(hash)`, `updateIdentifiers(mobile, email)`, `securityAnswers()`, `replaceSecurityAnswers(list)` |
| `BusinessProfileRepository` | shop details | `observe(): Flow<BusinessProfile>`, `update(profile)` |
| `CustomerRepository` | customers + balances | `search(query, filter, sort, includeArchived): Flow<List<CustomerSummary>>`, `get(id): Flow<CustomerDetail>`, `create(draft)`, `update(id, draft)`, `setArchived(id, Boolean)`, `deleteIfUnused(id)`, `findByMobile(mobile)`, `recentForPicker(limit)` |
| `LedgerRepository` | **all** money-moving writes, ledger reads | `insert(EntryDraft, clientRequestId): EntryRootId`, `insertTradeWithPayment(trade, payment, ids)`, `revise(root, EntryDraft, reason)`, `changeType(root, EntryDraft, linkedAction, reason)`, `void(root, reason, voidLinked)`, `entry(root): Flow<EntryDetail>`, `history(root): Flow<List<Revision>>`, `ledger(customer): Flow<CustomerLedger>`, `balance(customer): Flow<Money>`, `lastPrice(customer, type, variety, unit): Money?`, `query(filter: EntryFilter, sort): Flow<PagedEntries>` |
| `CatalogRepository` | units and egg varieties | `units(activeOnly)`, `upsertUnit`, `resizeUnit`, `deactivateUnit`, `varieties(activeOnly)`, `upsertVariety`, `setDefaultVariety`, `deactivateVariety` |
| `ReportRepository` | read-only aggregates | `dashboard(period): Flow<DashboardStats>`, `dues(): Flow<DuesSummary>`, `daily(date): DailyReport`, `monthly(yearMonth): MonthlyReport`, `statement(customer, range): Statement` |
| `SettingsRepository` | typed `app_setting` access | `observe(): Flow<AppSettings>`, `update(transform)` |
| `AuditRepository` | append-only audit rows | `append(AuditEvent)` — called **inside** the same transaction by the other repositories |
| `BackupRepository` | backup and restore orchestration state | `history(): Flow<List<BackupRecord>>`, `status(): Flow<BackupStatus>`, `startRecord(...)`, `finishRecord(...)` |
| `BackupCrypto` (port) | passphrase/KEK and file format | `setPassphrase`, `verifyPassphrase`, `changePassphrase`, `writeBackup(sink, metadata)`, `inspect(source, passphrase): BackupInspection` |
| `BackupDestination` (port) | `DeviceBackupDestination` (SAF), `DriveBackupDestination` | `write(name, stream)`, `list()`, `read(id)`, `delete(id)` |
| `RestoreCoordinator` (port) | staging + process-restart restore (R10) | `stage(source, passphrase): BackupSummary`, `commit()`, `discard()` |
| `DocumentRenderer` (port) | PDF/CSV/XLSX | `render(document: DocumentModel, format, locale): ExportedFile` |
| `ShareGateway` (port) | share intents | `share(file, target: ShareTarget)`, `shareText(text, target)`, `isWhatsAppInstalled()` |
| `SecretHasher` (port) | PBKDF2 hashing | `hash(secret): HashedSecret`, `verify(secret, hashed): Boolean` |
| `SessionManager` (port) | lock state | `state: StateFlow<SessionState>`, `unlock()`, `lock()`, `touch()` |

**Write rule**: every method that changes a `ledger_entry` runs inside `TransactionRunner.inTransaction`
and writes its `audit_log` rows in the same transaction (Constitution II).

## Use cases (by feature)

Each is a class with a single `suspend operator fun invoke(...)` (or a `Flow` for observers). Each
validates its input with the domain validators and returns `DomainResult`.

| Area | Use case | Spec |
|------|----------|------|
| Auth | `SignUpUseCase` (account + profile + recovery PIN + 3 security answers, atomic) | US1, FR-001/002/012 |
| | `SignInUseCase` (mobile or email; generic error; lockout) | FR-003/005/006 |
| | `UnlockWithBiometricUseCase`, `EnableBiometricUseCase`, `DisableBiometricUseCase` | FR-008 |
| | `ChangePasswordUseCase`, `ChangeLoginIdentifiersUseCase` | FR-009/010 |
| | `RecoverWithPinUseCase`, `RecoverWithSecurityAnswersUseCase` (verify → set new password) | FR-012/013, US11 |
| | `ChangeRecoveryPinUseCase`, `UnlockWithPasswordUseCase` | FR-013, FR-007 |
| | `UpdateSecurityQuestionsUseCase`, `SignOutUseCase`, `ObserveSessionUseCase` | FR-011/013 |
| Customers | `SearchCustomersUseCase`, `GetCustomerDetailUseCase` | FR-017/018 |
| | `CreateCustomerUseCase` (optional opening balance → OPENING_BALANCE entry) | FR-014/015/016 |
| | `UpdateCustomerUseCase` (opening change → revision + audit) | FR-019 |
| | `ArchiveCustomerUseCase`, `UnarchiveCustomerUseCase`, `DeleteCustomerUseCase` | US2-6/7/8 |
| Entries | `RecordSaleUseCase`, `RecordPurchaseUseCase` (optional on-the-spot payment, atomic) | FR-020–025, FR-023 |
| | `RecordPaymentReceivedUseCase`, `RecordPaymentMadeUseCase` | FR-028 |
| | `PreviewPaymentEffectUseCase` (advance / resulting balance message) | FR-030 |
| | `SuggestPriceUseCase` | FR-024 |
| | `EditEntryUseCase` (any field incl. customer; same type) | FR-033/033a |
| | `ChangeEntryTypeUseCase` (new root/reference, linked-payment choice) | FR-033b |
| | `PreviewEditEffectUseCase` (balance effect per affected customer) | FR-033 |
| | `VoidEntryUseCase` (optionally void linked) | FR-034, US5-6 |
| | `GetEntryDetailUseCase`, `GetEntryHistoryUseCase` | US5-4 |
| Ledger | `ObserveCustomerLedgerUseCase`, `ObserveCustomerBalanceUseCase` | FR-029/031/032 |
| History | `QueryEntriesUseCase` (EntryFilter: dates, types, customers, varieties, units, modes, amount range, status, text; sort) | FR-037 |
| Dashboard | `ObserveDashboardUseCase` (period + midnight ticker), `ObserveDuesUseCase`, `ObserveBackupReminderUseCase` | FR-036, US6 |
| Documents | `BuildStatementUseCase`, `BuildDailyReportUseCase`, `BuildMonthlyReportUseCase`, `BuildReceiptUseCase`, `BuildEntryListExportUseCase` | FR-038–040, FR-042a |
| | `ExportDocumentUseCase` (format → file), `ShareDocumentUseCase`, `ShareBalanceReminderUseCase`, `CleanupExportsUseCase` | FR-041–043 |
| Backup | `SetBackupPassphraseUseCase`, `ChangeBackupPassphraseUseCase` | FR-044/057 |
| | `BackupToDeviceUseCase`, `BackupToDriveUseCase` (enqueue work) | FR-046/047 |
| | `LinkDriveUseCase`, `UnlinkDriveUseCase` | FR-047, US10-7 |
| | `ConfigureAutoBackupUseCase` | FR-048 |
| | `ListDriveBackupsUseCase`, `DeleteDriveBackupUseCase` | FR-050/057 |
| | `StageRestoreUseCase`, `ConfirmRestoreUseCase`, `CancelRestoreUseCase` | FR-050–053 |
| | `ObserveBackupHistoryUseCase`, `ObserveBackupStatusUseCase`, `CheckMissedBackupsUseCase` | FR-054/055 |
| | `RunBackupJob` (called by the worker: snapshot → encrypt → upload → retention → record) | FR-048/049/056 |
| Settings | `UpdateBusinessProfileUseCase`, `ManageUnitsUseCase`, `ManageVarietiesUseCase`, `UpdateAppSettingsUseCase`, `ChangeLanguageUseCase` | FR-058–061a |

## Error-to-message mapping

`DomainError` values map to localized string resources in `:app` (`ErrorMessages.kt`). Messages are
exactly those in the spec's Validation Rules and Error Scenarios tables. Domain code never holds
user-facing text.
