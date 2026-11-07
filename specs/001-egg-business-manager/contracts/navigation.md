# Contract: Navigation Graph and Screens

Type-safe Navigation Compose routes (`@Serializable`). Arguments are ids only, never whole objects.
The lock screen is **not** a route: it is an overlay drawn above the `NavHost` whenever
`SessionManager.state == Locked`, so the back stack and `SavedStateHandle`-backed form state
survive locking (US1-8).

```mermaid
flowchart TD
    Start{{App start: DatabaseBootstrapper}} -->|restore pending| RestoreProgress
    Start -->|no account| Welcome
    Start -->|account exists| SignIn

    subgraph AuthGraph[auth graph]
        Welcome --> LanguagePick[Language choice]
        Welcome --> SignupAccount[Signup: mobile, email, password]
        SignupAccount --> SignupShop[Signup: shop details]
        SignupShop --> SignupQuestions[Signup: recovery PIN + 3 security questions]
        Welcome --> RestoreSource[Restore: choose device file / Google Drive]
        SignIn --> ForgotPassword[Recover: choose PIN or questions]
        ForgotPassword --> RecoverWithPin[Recovery PIN + new password]
        ForgotPassword --> RecoverWithQuestions[3 answers + new password]
        RestoreSource --> RestorePassphrase --> RestoreSummary --> RestoreProgress
    end

    SignupQuestions --> Main
    SignIn --> Main
    RecoverWithPin --> Main
    RecoverWithQuestions --> Main
    RestoreProgress -->|restart| SignIn

    subgraph Main[main graph - NavigationSuiteScaffold]
        Dashboard
        Customers
        History[Transactions]
        Reports
        More
    end

    Dashboard --> EntryEditor
    Dashboard --> CustomerEditor
    Customers --> CustomerDetail
    CustomerDetail --> Ledger
    CustomerDetail --> EntryEditor
    CustomerDetail --> Statement
    CustomerDetail --> CustomerEditor
    History --> EntryDetail
    Ledger --> EntryDetail
    EntryDetail --> EntryEditor
    EntryDetail --> EntryRevisionHistory
    EntryDetail --> ReceiptPreview
    Reports --> DailyReport
    Reports --> MonthlyReport
    More --> BusinessProfile
    More --> UnitsSettings
    More --> VarietiesSettings
    More --> SecuritySettings
    More --> BackupCenter
    More --> AppPreferences
    More --> About
    BackupCenter --> BackupHistory
    BackupCenter --> AutoBackupSettings
    BackupCenter --> RestoreSource
    SecuritySettings --> ChangePassword
    SecuritySettings --> ChangeIdentifiers
    SecuritySettings --> EditSecurityQuestions
    SecuritySettings --> ChangeRecoveryPin
    SecuritySettings --> BackupPassphrase
```

## Routes

| Route | Args | Screen / notes |
|-------|------|----------------|
| `Welcome` | — | create account / restore / language |
| `SignupAccount`, `SignupShop`, `SignupQuestions` | — | one shared `SignupViewModel` scoped to the signup sub-graph; the account is created only at the last step (atomic) |
| `SignIn` | — | identifier + password, biometric button, lockout countdown |
| `ForgotPassword`, `RecoverWithPin`, `RecoverWithQuestions` | — | US11; each recovery screen takes the secret and the new password together, so verification and reset are one atomic step |
| `RestoreSource`, `RestorePassphrase`, `RestoreSummary`, `RestoreProgress` | `source: DEVICE\|DRIVE`, `driveFileId?` | US9; reachable from Welcome and BackupCenter |
| `Dashboard` | — | top-level |
| `Customers` | `filter?` | top-level; `ListDetailPaneScaffold` with `CustomerDetail` on expanded width |
| `CustomerDetail` | `customerId` | balance header, quick actions, recent entries |
| `CustomerEditor` | `customerId?`, `returnToPicker: Boolean` | add/edit; inline creation from entry editor returns the new id via `SavedStateHandle` result |
| `Ledger` | `customerId` | full ledger with running balance |
| `EntryEditor` | `type`, `customerId?`, `editRootId?` | one editor for all 4 types; type switchable when editing (FR-033b) |
| `EntryDetail` | `rootId` | detail, receipt, edit, void, revisions |
| `EntryRevisionHistory` | `rootId` | audit trail |
| `ReceiptPreview` | `rootId` | PDF preview + share |
| `History` | — | top-level; filter sheet, chips, export |
| `Statement` | `customerId`, `from?`, `to?` | preview + export + WhatsApp |
| `Reports`, `DailyReport(date)`, `MonthlyReport(yearMonth)` | | |
| `More` and settings routes | — | see diagram |

## Top-level destinations and adaptive layout

| Width class | Container | Extras |
|-------------|-----------|--------|
| Compact (< 600 dp) | bottom navigation bar (5 items) | FAB "New entry" on Dashboard/History/Customers opens a type chooser (Sell / Buy / Receive / Pay) |
| Medium (600–840 dp) | navigation rail | FAB in rail |
| Expanded (≥ 840 dp) | permanent navigation drawer | list–detail two-pane for Customers and History |

Back behaviour: from any top-level destination except Dashboard, back returns to Dashboard; back on
Dashboard exits. Editors with unsaved changes ask "Discard changes?" before leaving.

Deep links: none exported (no exported components except the launcher activity). Notification
taps for backup failures open `BackupCenter` through an explicit `PendingIntent` to `MainActivity`.
