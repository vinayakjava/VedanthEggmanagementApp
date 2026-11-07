# Quickstart & Validation Guide: Egg Business Manager

This guide shows how to build, test and validate the app end to end once implementation exists.
It references the [data model](./data-model.md) and [contracts](./contracts/) rather than repeating
them.

## Prerequisites

| Need | Detail |
|------|--------|
| Android Studio | latest stable; includes JBR 17+ (this machine currently has JDK 11 only, which is **not** enough for AGP) |
| Android SDK | platform = latest stable API (R1); build-tools; emulator images API 31 and latest |
| Devices | one emulator API 31 (min), one emulator latest API, at least one physical mid-range phone (SC-005), one tablet/foldable profile (adaptive layout) |
| Google Cloud (Drive only) | free project; OAuth consent screen; Android OAuth clients for debug and release SHA-1 + package name (R11) |
| Release signing | keystore stored outside the repository; values in `~/.gradle/gradle.properties`: `EBM_STORE_FILE`, `EBM_STORE_PASSWORD`, `EBM_KEY_ALIAS`, `EBM_KEY_PASSWORD` |

## Build and test commands (repository root)

```powershell
./gradlew :domain:test                         # pure JVM unit tests (money, ledger, validators, use cases)
./gradlew :app:testDebugUnitTest               # ViewModel unit tests
./gradlew :data:connectedDebugAndroidTest      # Room DAOs, migrations, SQLCipher, crypto, backup round trip
./gradlew :app:connectedDebugAndroidTest       # Compose UI tests of critical flows
./gradlew koverHtmlReport                      # coverage (domain ≥ 90 %, money/ledger 100 % branches)
./gradlew lint detekt                          # static analysis gate
./gradlew :app:assembleRelease                 # signed, R8-minified APK
./scripts/check-no-destructive-migration.ps1   # fails if fallbackToDestructiveMigration appears
```

## Validation scenarios

Run on an emulator with the debug build unless stated. "✔" lists the expected outcome.

1. **Offline first run (US1, SC-001, SC-004)**: Airplane mode on → install → choose Hindi → sign up
   with mobile only → set 3 questions. ✔ Dashboard reached in < 3 min; all text is Hindi; no network
   prompt anywhere.
2. **Balance formula (US4, SC-003)**: For customer A: SELL 10 Tray @ ₹180 (₹1,800.00), BUY 5 Tray @
   ₹170 (₹850.00), RECEIVE ₹500, PAY ₹100. ✔ Balance = 1800 − 850 − 500 + 100 = **To receive ₹550.00**
   on the list, detail, ledger, dashboard, statement and both reports.
3. **Fractional quantity and rounding (FR-021)**: SELL 7.5 Tray @ ₹183.50. ✔ Total ₹1,376.25 and 225
   pcs. SELL 0.05 Tray. ✔ Rejected: "Quantity must equal a whole number of eggs".
4. **Advance (FR-030)**: Settled customer B → RECEIVE ₹5,000. ✔ Advance notice, then To pay ₹5,000.
   Then SELL ₹3,000. ✔ To pay ₹2,000.
5. **On-the-spot payment atomicity (FR-023)**: SELL ₹1,800 with ₹1,000 received now. ✔ Two ledger
   lines (S- and RP-), net +₹800. Kill the app during save (debug fault-injection flag). ✔ After restart
   both lines exist or neither does.
6. **Edit / type change / customer change (FR-033–033b)**: Change S-000001's customer A→C. ✔ A −1,800,
   C +1,800, history shows both. Change its type to BUY. ✔ New B- reference; the old row shows
   "Changed to B-…". Void an entry. ✔ Excluded everywhere; still visible as Voided.
7. **Unit resize (US12-3)**: Box 210 → 180. ✔ Older Box entries still show 210 pcs per box.
8. **Lock and lockout (US1-6/8, FR-007)**: Five wrong passwords. ✔ 30 s countdown that survives an app
   restart. Leave mid-entry for 6 min. ✔ Lock overlay; after unlock the typed values are still there;
   the recents thumbnail is hidden.
9. **Recovery (US11)**: Forgot password → answer with different capitalisation and spaces. ✔ Accepted.
   One wrong answer. ✔ "One or more answers are incorrect".
10. **Exports (US7/US8, FR-042a)**: Generate a statement, daily report, monthly report, filtered
    transaction list and receipt in each available format. ✔ Figures equal scenario 2; Hindi renders
    correctly in PDF and in Excel/Sheets (CSV opened with BOM); Share on WhatsApp opens WhatsApp;
    without WhatsApp the chooser opens.
11. **Device backup and restore (US9, SC-006)**: Set passphrase → back up to Downloads via SAF → add
    entries → restore the file. ✔ Summary shown before the change; after restart, data equals the
    backup point (record counts and balances match a pre-backup DB dump).
12. **Restore failures (FR-052)**: Wrong passphrase; a truncated file; a renamed JPEG; a backup made
    by a test build with schema v+1. ✔ The four distinct messages; current data unchanged.
13. **Restore crash safety (R10, SC-007)**: Debug flag to crash in SWAPPING and in VERIFYING. ✔ On
    the next start the previous data is back and the history shows a failed restore.
14. **Drive manual + automatic (US10)**: Link Google account → Back up now. ✔ The file appears in the
    restore list with date/size/device. Enable daily automatic backup, Wi-Fi only; force-run with
    `adb shell cmd jobscheduler run -f <pkg> <jobId>` (or WorkManager test helpers). ✔ A history row
    is added. Disable Wi-Fi. ✔ The work waits. Revoke access at myaccount.google.com. ✔ Failure reason
    "Google account disconnected"; notification after 3 failures.
15. **New-device restore (US9-8/9)**: Fresh emulator → Welcome → Restore from Drive. ✔ Sign-in with
    the backed-up credentials works; "forgot password" right after restore allows a new password
    because the passphrase was proven.
16. **APK upgrade (Constitution III, SC-008)**: Install release N-1 APK, create realistic data
    (seed script), install release N APK over it with `adb install -r`. ✔ All data, settings and
    backup configuration intact; a backup made by N-1 restores in N.
17. **Performance (SC-005)**: Debug menu "seed 2,000 customers / 100,000 entries" on the physical
    mid-range phone. ✔ Search < 1 s; dashboard < 2 s; monthly report < 5 s (measured with Macrobenchmark
    or logged timings).
18. **Adaptive layout**: Resize the emulator to tablet and foldable. ✔ Rail/drawer navigation;
    two-pane Customers and History; no clipped content at 200 % font scale.
