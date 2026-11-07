# Feature Specification: Egg Business Manager

**Feature Branch**: `001-egg-business-manager`

**Created**: 2026-11-07

**Status**: Draft

**Input**: User description: "Create a complete functional specification for Egg Business Manager — an
offline Android app for a small egg trading shop owner who buys eggs from and sells eggs to
customers/suppliers. Includes signup/login, password change and recovery, customer management and
search, Buy/Sell transactions with configurable units, customer ledgers, receive/pay payments,
automatic balances, dashboard, transaction history with filters, customer statements, daily and
monthly reports, PDF/CSV/Excel export, sharing (WhatsApp and others), local and Google Drive backup
(manual and scheduled), restore, backup history, business profile and settings. All business data
remains on the device."

## Overview

Egg Business Manager is a single-owner, offline business record book for an egg trading shop. The
owner records what they **buy** from and **sell** to each party, records **payments** in both
directions, and always knows who owes whom. Every party (supplier or buyer) is a **Customer**; the
same customer may both sell eggs to the shop and buy eggs from it.

### Balance convention (applies everywhere in this spec)

```
Customer Balance = Opening Balance
                 + Total Sales            (SELL)
                 − Total Purchases        (BUY)
                 − Payments Received      (RECEIVE PAYMENT)
                 + Payments Made          (PAY CUSTOMER)
```

| Balance | Meaning | Displayed as |
|---------|---------|--------------|
| > 0 | Customer owes the shop | "To receive ₹X" (receivable) |
| < 0 | Shop owes the customer | "To pay ₹X" (payable, shown as absolute value) |
| = 0 | Settled | "Settled" |

The Opening Balance term exists only so the owner can carry forward dues from a paper book when a
customer is first added. When it is zero the formula is exactly the one requested:
`Total Sales − Total Purchases − Payments Received + Payments Made`. Voided entries never count.

## Clarifications

### Session 2026-11-07

- Q: Should sale and purchase totals be rounded to the nearest whole rupee, or kept exact to the
  paisa? → A: Exact to the paisa; no whole-rupee rounding or round-off amounts anywhere.
- Q: Which languages should screens and shared documents be available in for the first release?
  → A: English and Hindi, switchable in Settings.
- Q: When the owner taps "Share receipt" for a single sale, purchase or payment, what is sent? → A: A
  PDF receipt only, with shop header.
- Q: Should each customer be tagged as Buyer, Supplier or Both? → A: No tags; every customer
  appears in every picker, list and filter.
- Q: When correcting a saved entry, which details may be changed? → A: Every field, including entry
  type and customer (with full revision history).
- Owner design review (confirmed): a customer can be both buyer and supplier (one netted balance);
  egg varieties are supported and must stay extensible; opening balances are supported; advance
  payments are supported (FR-030); entries are editable with automatic balance recalculation
  (FR-033); multiple measurement units are supported; multi-device use is out of scope for V1.

### Session 2026-11-08

- Q: Password recovery — a recovery PIN or security questions? → A: Both. The owner sets a 6-digit
  recovery PIN and 3 security questions at signup and can recover with either one (FR-012).
- Q: Should the shop address capture city, state and PIN code? → A: Yes; city, state and a valid
  6-digit PIN code are required at signup, and the street address stays optional (FR-002).
- Q: Customer "archive" or "activate/deactivate"? → A: Same behaviour; the owner-facing wording is
  "Deactivate" / "Activate". A deactivated customer is what this spec calls archived.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Create Account and Sign In Securely (Priority: P1)

On first launch the owner creates a local account with their mobile number, an optional email, a
password, and basic shop details. Afterwards they sign in with mobile number or email plus
password, and can optionally unlock with fingerprint/face. The app locks itself when left idle.

**Why this priority**: Nothing else can be used until an account exists, and the app holds
sensitive financial data that must be protected from anyone who picks up the phone.

**Independent Test**: Install fresh, complete signup, close app, reopen, sign in with mobile
number, sign out, sign in with email; enter a wrong password repeatedly and observe lockout.

**Acceptance Scenarios**:

1. **Given** a fresh install with no account, **When** the app opens, **Then** the owner sees a
   welcome screen offering "Create account" and "Restore from backup", and no business screen is
   reachable.
2. **Given** the signup form, **When** the owner enters a valid mobile number, a password meeting
   the rules, matching confirmation, shop name, owner name, city, state and PIN code, and submits,
   **Then** the account is
   created after the owner sets up a 6-digit recovery PIN and 3 security questions for password
   recovery (see FR-012), and
   the owner lands on the dashboard.
3. **Given** the signup form, **When** the owner leaves email empty, **Then** signup still succeeds.
4. **Given** an existing account, **When** the owner enters the registered mobile number or the
   registered email (case-insensitive) with the correct password, **Then** they are signed in.
5. **Given** an existing account, **When** the identifier or password is wrong, **Then** a single
   generic message "Mobile/email or password is incorrect" is shown (it does not reveal which part
   was wrong) and the failed-attempt counter increases.
6. **Given** 5 consecutive failed attempts, **When** the owner tries again, **Then** sign-in is
   blocked for 30 seconds with a visible countdown; each further 5 failures doubles the wait (60 s,
   120 s, …) up to a maximum of 30 minutes. A successful sign-in resets the counter.
7. **Given** biometric unlock is enabled in settings, **When** the app is reopened, **Then** the
   owner may unlock with biometrics or fall back to password.
8. **Given** the owner is signed in, **When** the app stays in background or unused longer than the
   auto-lock timeout (default 5 minutes), **Then** re-opening it requires unlocking, and any
   unsaved form input is retained after unlock.
9. **Given** an account already exists on the device, **When** anyone opens the app, **Then**
   signup is not offered (one account per installation).

---

### User Story 2 - Manage and Find Customers (Priority: P1)

The owner adds the people and businesses they trade with — name, mobile number, address, notes,
and an optional opening balance carried over from paper records — and finds them quickly by typing
part of a name or number.

**Why this priority**: Every transaction and payment belongs to a customer; without customers no
financial record can be made.

**Independent Test**: Add 3 customers (one with an opening receivable, one with an opening payable,
one with none), search by partial name and partial mobile, edit one, archive one.

**Acceptance Scenarios**:

1. **Given** the customer list, **When** the owner adds a customer with a name only, **Then** the
   customer is saved with balance "Settled" and appears in the list.
2. **Given** the add-customer form, **When** the owner enters an opening balance of ₹1,500 marked
   "Customer owes me", **Then** the customer's balance shows "To receive ₹1,500.00" and the ledger
   begins with an "Opening balance" entry dated on the chosen opening date.
3. **Given** a mobile number already used by another customer (active or archived), **When** the
   owner saves, **Then** saving is blocked with "This mobile number belongs to <Name>" and an option
   to open that customer.
4. **Given** customers exist, **When** the owner types "ram" in search, **Then** every customer whose
   name contains "ram" (case-insensitive, anywhere in the name) is listed; **When** the owner types
   "9876", **Then** every customer whose mobile contains "9876" is listed.
5. **Given** search returns no match, **When** results are shown, **Then** the screen says "No
   customers found" and offers "Add '<typed text>' as new customer".
6. **Given** a customer with transactions, **When** the owner chooses delete, **Then** hard delete
   is not allowed; the owner is offered "Archive" instead. Archived customers are hidden from
   pickers and the default list but remain in reports, ledgers, history and backups, and can be
   restored (un-archived).
7. **Given** a customer with no ledger entries at all, **When** the owner deletes and confirms,
   **Then** the customer is permanently removed.
8. **Given** a customer with a non-zero balance, **When** the owner archives, **Then** a warning
   states the outstanding amount and requires confirmation.
9. **Given** the customer list, **When** the owner sorts or filters, **Then** they can sort by name,
   balance (highest receivable / highest payable) or last activity, and filter to "To receive",
   "To pay", "Settled", or "Archived".

---

### User Story 3 - Record Egg Sales and Purchases (Priority: P1)

The owner records each SELL (eggs given to a customer) and BUY (eggs taken from a customer) with
date, customer, egg variety (white, brown, country …), quantity, unit (tray, box, dozen, piece …),
price per unit, automatically computed
total, optional amount paid on the spot, and remarks.

**Why this priority**: Recording trades is the core daily activity of the business.

**Independent Test**: Record a sale of 10 trays at ₹180/tray to a customer and verify the total
₹1,800.00, the customer's balance rising by ₹1,800.00, and the entry appearing in the ledger.
Record a purchase and verify the balance moving the opposite way.

**Acceptance Scenarios**:

1. **Given** the Sell screen, **When** the owner picks a customer, variety "Brown", enters quantity
   10, unit "Tray",
   price per unit 180, **Then** the total shows ₹1,800.00 live as values are typed, and the
   equivalent pieces (300) are shown.
2. **Given** a valid sale, **When** the owner saves, **Then** a SELL entry with a unique reference
   number (e.g. S-000001) is created, the customer's balance increases by the total, and a
   confirmation offers "Share receipt" (a one-page PDF, FR-042a, shared via WhatsApp or the share
   sheet) and "Record another".
3. **Given** a valid purchase of ₹2,000, **When** saved, **Then** a BUY entry (e.g. B-000001) is
   created and the customer's balance decreases by ₹2,000.00 (i.e. the shop owes more).
4. **Given** the Sell screen, **When** the owner also enters "Amount received now" ₹1,000 for a
   ₹1,800 sale, **Then** saving creates the SELL entry and a linked RECEIVE PAYMENT entry of ₹1,000
   together (both or neither), and the balance increases by net ₹800. The same applies to "Amount
   paid now" on the Buy screen, which creates a linked PAY CUSTOMER entry.
5. **Given** the date field, **When** the screen opens, **Then** it defaults to today; the owner may
   pick an earlier date but not a future date.
6. **Given** a customer has a default price history, **When** the owner selects that customer and
   variety and unit, **Then** the price field is pre-filled with the last price used for that
   customer, variety, unit and
   transaction type (editable).
7. **Given** any required field is missing or invalid, **When** the owner taps Save, **Then** the
   entry is not saved and each invalid field shows its specific message (see Validation Rules).
8. **Given** the owner taps Save twice quickly, **When** the save completes, **Then** exactly one
   entry is created.
9. **Given** the customer picker, **When** the needed customer does not exist, **Then** the owner can
   add a new customer inline without losing the transaction already typed.

---

### User Story 4 - Record Payments and See Accurate Balances and Ledgers (Priority: P1)

The owner records money received from a customer (RECEIVE PAYMENT) and money paid to a customer
(PAY CUSTOMER), with amount, date, payment mode and remarks. Each customer has a ledger listing
every entry in date order with a running balance, and the current balance is always calculated
automatically.

**Why this priority**: Knowing exactly who owes what is the primary reason the owner keeps records.

**Independent Test**: For one customer record: opening ₹0, SELL ₹5,000, BUY ₹2,000, RECEIVE ₹1,500,
PAY ₹500. Verify balance = 5,000 − 2,000 − 1,500 + 500 = ₹2,000.00 "To receive", and the ledger
running balance after each line is 5,000 / 3,000 / 1,500 / 2,000.

**Acceptance Scenarios**:

1. **Given** a customer with balance "To receive ₹2,000", **When** the owner records RECEIVE PAYMENT
   ₹2,000 in Cash, **Then** the balance becomes "Settled".
2. **Given** a customer with balance "To receive ₹2,000", **When** the owner records RECEIVE PAYMENT
   ₹2,500, **Then** the app shows "₹500 will be held as an advance from the customer; the shop will
   owe the customer ₹500" and, on confirmation, saves it; the balance becomes "To pay ₹500".
3. **Given** a customer with balance "To pay ₹3,000", **When** the owner records PAY CUSTOMER
   ₹3,000, **Then** the balance becomes "Settled". Paying more shows the equivalent advance notice.
3a. **Given** a settled customer, **When** the owner records RECEIVE PAYMENT ₹5,000 as an advance
   and later records a SELL of ₹3,000 to that customer, **Then** the balance is "To pay ₹5,000"
   after the payment and "To pay ₹2,000" after the sale. Likewise, a PAY CUSTOMER advance of ₹4,000
   to a settled supplier followed by a BUY of ₹4,000 leaves the balance "Settled".
4. **Given** the payment screen, **When** opened from a customer, **Then** it shows the current
   balance and offers a one-tap "Full amount" that fills the outstanding amount for the relevant
   direction.
5. **Given** a customer ledger, **When** opened, **Then** it lists all non-voided entries sorted by
   transaction date then by time recorded, each showing date, reference, type, quantity/unit (for
   trades), debit/credit amount, running balance and remarks, with totals for each of the four
   types and the current balance at the top.
6. **Given** a back-dated entry is added, **When** the ledger is viewed, **Then** the entry appears
   in its date position and all later running balances reflect it.
7. **Given** any combination of entries, **When** the balance is displayed anywhere (customer list,
   ledger, dashboard, statement, reports), **Then** it equals the balance formula exactly, to the
   paisa, in every place.
8. **Given** payment modes, **When** recording a payment, **Then** the owner selects one of Cash,
   UPI, Bank transfer, Cheque, Other (default Cash) and may enter an optional reference (e.g. UPI
   transaction id or cheque number).

---

### User Story 5 - Review, Filter and Correct Transactions (Priority: P2)

The owner browses all trades and payments, narrows them with filters, and corrects mistakes
without destroying the audit trail.

**Why this priority**: Mistakes are inevitable; the owner must be able to find and fix them while
keeping the records trustworthy.

**Independent Test**: Create 20 mixed entries over several dates, filter by type SELL + customer +
last 7 days, verify only matching entries and correct totals; edit one entry and void another and
check balances and audit history.

**Acceptance Scenarios**:

1. **Given** the history screen, **When** opened, **Then** all entries are listed newest first,
   grouped by date, with a summary bar showing count and total amount per type for the visible set.
2. **Given** the filter panel, **When** the owner applies any combination of: date range (Today,
   Yesterday, This week, This month, Last month, Custom), type (SELL, BUY, RECEIVE PAYMENT, PAY
   CUSTOMER — multi-select), customer (multi-select), egg variety (multi-select), unit, payment
   mode, amount range (min/max), status (Active, Voided), and text search over remarks and
   reference number, **Then** only entries
   matching all chosen filters are shown and summary totals reflect only those entries.
3. **Given** active filters, **When** shown, **Then** each filter appears as a removable chip and
   "Clear all" resets them; sorting is available by date (newest/oldest) and amount (high/low).
4. **Given** an entry, **When** the owner edits it and saves, **Then** the original is marked
   "Revised", a corrected entry replaces it in balances, both are linked, and the entry's history
   shows old value, new value, date/time of change and the optional reason. Any field may be changed,
   including type and customer (FR-033 – FR-033b). The reference number is kept unless the type
   changes.
5. **Given** an entry, **When** the owner voids it with confirmation (reason optional), **Then** it
   remains visible with a "Voided" label, is excluded from all balances, totals and reports, and
   cannot be edited further.
6. **Given** a sale that was saved with a linked "amount received now" payment, **When** the sale is
   voided, **Then** the owner is asked whether to also void the linked payment (default: yes).
7. **Given** a filtered list, **When** the owner chooses Export, **Then** exactly the filtered
   entries are exported in the chosen format (US8).
8. **Given** a SELL of ₹1,000 recorded against customer X by mistake, **When** the owner changes the
   customer to Y, **Then** X's balance decreases by ₹1,000, Y's increases by ₹1,000, and both happen
   together.
9. **Given** a SELL S-000010 that should have been a BUY, **When** the owner changes the type,
   **Then** a BUY with a new B- reference replaces it in balances, S-000010 shows "Changed to
   B-…", and the customer's balance moves by twice the total in the shop-owes direction.

---

### User Story 6 - See Business at a Glance on the Dashboard (Priority: P2)

On opening the app the owner sees key figures for today and the month, total dues in both
directions, recent activity, and one-tap buttons for the most common actions.

**Why this priority**: Gives daily situational awareness and the fastest path to frequent tasks.

**Independent Test**: With known seeded data, open the dashboard and compare each figure against a
hand calculation.

**Acceptance Scenarios**:

1. **Given** the dashboard, **When** shown, **Then** it displays for the selected period (Today
   default; This week; This month; Custom): eggs sold (pieces and in the owner's preferred display
   unit), sales amount, eggs bought, purchase amount, payments received, payments made, and net
   cash movement (received − made).
2. **Given** the dashboard, **When** shown, **Then** it displays — independent of period — total
   receivable (sum of all positive customer balances), total payable (sum of absolute values of all
   negative balances), number of customers with dues in each direction, and the top 5 customers by
   receivable amount.
3. **Given** the dashboard, **When** shown, **Then** quick actions Sell, Buy, Receive Payment, Pay
   Customer and Add Customer are reachable in one tap, and the last 10 entries are listed.
4. **Given** the date changes at midnight while the app is open, **When** the dashboard refreshes,
   **Then** "Today" figures reflect the new day.
5. **Given** no successful backup in the last 7 days (or never), **When** the dashboard is shown,
   **Then** a non-blocking banner reminds the owner to back up, with a "Back up now" action.
6. **Given** a brand-new account with no data, **When** the dashboard is shown, **Then** it shows
   zero values and a short guide: "Add your first customer".

---

### User Story 7 - Generate and Share Customer Statements (Priority: P2)

The owner produces a statement for one customer over a date range and sends it — for example via
WhatsApp — so the customer can verify their account.

**Why this priority**: Sharing clear statements reduces disputes and speeds up collection.

**Independent Test**: For a customer with entries before and within a date range, generate a
statement, verify opening balance, entries, running balance and closing balance, export to PDF and
share it.

**Acceptance Scenarios**:

1. **Given** a customer, **When** the owner generates a statement for a date range (default: this
   month), **Then** it contains the shop's name, address and phone; customer name, mobile and
   address; the period; the balance brought forward at the start of the period; every non-voided
   entry in the period with date, reference, description (including egg variety for trades),
   quantity/unit, amount and running balance;
   period totals per type; and the closing balance with its plain-language meaning ("Amount due to
   <Shop>" / "Amount payable by <Shop>" / "Settled").
2. **Given** a statement, **When** the owner exports, **Then** they can save or share it as PDF, CSV
   or Excel.
3. **Given** the customer has a mobile number and WhatsApp is installed, **When** the owner chooses
   "Share on WhatsApp", **Then** WhatsApp opens with the statement attached, addressed to that
   customer's chat where WhatsApp supports it; otherwise the owner picks the chat.
4. **Given** WhatsApp is not installed, **When** the owner shares, **Then** the standard share
   sheet of installed apps is shown and no error occurs.
5. **Given** a customer with a balance, **When** the owner chooses "Send balance reminder", **Then**
   a pre-written, editable text message stating the current balance is shared via WhatsApp or the
   share sheet.
6. **Given** a period with no entries, **When** a statement is generated, **Then** it still shows
   the brought-forward and closing balance and the line "No transactions in this period".

---

### User Story 8 - Daily and Monthly Reports with Export (Priority: P2)

The owner views a report for any single day or any month, and exports reports, statements and
transaction lists as PDF, CSV or Excel, then shares them via any installed app.

**Why this priority**: Supports end-of-day reconciliation, monthly review and record keeping for
accountants.

**Independent Test**: Seed entries across two months, open the daily report for a known date and
the monthly report for a known month, compare every figure with hand calculation, export each in
all three formats and open them in viewer apps.

**Acceptance Scenarios**:

1. **Given** the daily report for a date, **When** shown, **Then** it contains: eggs bought and sold
   (in pieces and per unit used, broken down by egg variety with quantity, amount and average price
   per piece for each), purchase amount, sales amount, trading difference (sales −
   purchases), payments received and made broken down by payment mode, net cash movement, number of
   entries per type, and the list of all entries of that day.
2. **Given** the monthly report for a month, **When** shown, **Then** it contains the same totals
   for the month, a day-by-day table (one row per day with activity), top 10 customers by sales and
   by purchases, and total receivable and payable as at the month's last day.
3. **Given** a report, statement or filtered transaction list, **When** exported, **Then** PDF is a
   printable document with shop header, title, period, generation date/time and page numbers; CSV
   uses one header row and one row per entry; Excel contains the same data with numeric cells for
   amounts and quantities and date cells for dates.
4. **Given** an exported file, **When** the owner chooses Share, **Then** the device share sheet
   (including WhatsApp, email and file apps if installed) is offered; **When** the owner chooses Save,
   **Then** they pick a location on the device.
5. **Given** a period with no activity, **When** a report is shown or exported, **Then** totals are
   zero and the report says "No activity in this period".
6. **Given** voided or revised-original entries, **When** reports are calculated, **Then** they are
   excluded.

---

### User Story 9 - Back Up and Restore Data Manually (Priority: P2)

The owner creates an encrypted backup on demand — either to a file on the device/storage of their
choice or to their own Google Drive — and can restore from either source, including onto a new
phone.

**Why this priority**: All data lives only on the phone; without backup, a lost or broken phone means
losing the business's entire financial history.

**Independent Test**: Create data, back up locally and to Drive, change data, restore each backup
and verify data matches the backup point exactly; install on a second device and restore from
Drive during first launch.

**Acceptance Scenarios**:

1. **Given** no backup passphrase is set, **When** the owner starts their first backup, **Then** they
   must create a backup passphrase (with confirmation) and acknowledge "Without this passphrase your
   backups cannot be restored. It cannot be recovered."
2. **Given** a passphrase is set, **When** the owner chooses "Back up to device", **Then** they choose
   a save location, an encrypted backup file named with shop name and date/time is written, and the
   result (success with size, or failure with reason) is recorded in backup history.
3. **Given** the owner has not linked Google Drive, **When** they choose "Back up to Google Drive",
   **Then** they are asked to sign in to Google and grant access limited to the app's own backup
   files; declining leaves all other app functions unaffected.
4. **Given** Drive is linked and the device is online, **When** the owner taps "Back up now",
   **Then** progress is shown and on completion the last-backup time updates.
5. **Given** the device is offline, **When** the owner taps "Back up to Google Drive", **Then** the
   app says "No internet connection. Your data is safe on this phone; try again when online," and
   records a failed attempt.
6. **Given** the restore screen, **When** the owner picks a local file or a Drive backup from a list
   (showing date/time, size, device name and app version), enters the backup passphrase, **Then**
   the backup is verified and a summary (backup date, number of customers, number of entries, shop
   name) is shown before anything is changed.
7. **Given** a verified backup summary, **When** the owner confirms "Replace all current data with
   this backup", **Then** a safety copy of current data is kept, the backup data replaces current
   data completely, and the owner is taken to the sign-in screen; **When** the restore fails at any
   point, **Then** the current data is put back unchanged and the owner is told restore failed.
8. **Given** a fresh install, **When** the owner chooses "Restore from backup" on the welcome screen,
   **Then** they can restore from a local file or Drive, and afterwards sign in with the account
   credentials contained in the backup.
9. **Given** the owner restores on a new device and has forgotten the login password contained in
   the backup, **When** restore succeeds, **Then** because they proved ownership with the backup
   passphrase, they may set a new login password immediately.

---

### User Story 10 - Scheduled Automatic Google Drive Backup and Backup History (Priority: P3)

The owner turns on automatic Drive backups (daily or weekly) and can see the full history and current
status of all backups.

**Why this priority**: Removes reliance on the owner remembering to back up; history gives confidence
that protection is working.

**Independent Test**: Enable daily backup with "Wi-Fi only", leave the device on Wi-Fi past the
schedule, verify a new Drive backup and a history entry; disable Wi-Fi and verify the backup waits
rather than failing permanently.

**Acceptance Scenarios**:

1. **Given** backup settings, **When** the owner enables automatic backup, **Then** they choose
   frequency (Daily default, Weekly), preferred time window (default night), "Wi-Fi only" (default
   on) and "Only while charging" (default off).
2. **Given** automatic backup is enabled, **When** the scheduled time arrives and conditions are met,
   **Then** a backup runs in the background without the app being open and without interrupting the
   owner, and the history records the result.
3. **Given** conditions are not met (offline, not on Wi-Fi), **When** the scheduled time passes,
   **Then** the backup runs as soon as conditions are met, with automatic retries for temporary
   failures.
4. **Given** automatic backups have failed or not run for 3 consecutive scheduled periods, **When**
   this is detected, **Then** the owner receives a notification and an in-app warning with the
   reason (e.g. "Google account disconnected", "Drive storage full").
5. **Given** backup history, **When** opened, **Then** it lists each backup attempt with date/time,
   destination (Device / Google Drive), trigger (Manual / Automatic), status (Success / Failed / In
   progress), size, and failure reason when failed; the current status card shows last successful
   backup, next scheduled backup and Drive account linked.
6. **Given** Drive backups accumulate, **When** a new automatic backup succeeds, **Then** only the 10
   most recent automatic Drive backups are kept and older automatic ones are deleted from Drive;
   manual Drive backups are kept until the owner deletes them.
7. **Given** the owner disconnects Google Drive, **When** confirmed, **Then** automatic backup is
   turned off, and existing backups on Drive are left untouched.

---

### User Story 11 - Change Password and Recover Access (Priority: P3)

The owner changes their password when they want to and can regain access on the device if they
forget it, without any internet service.

**Why this priority**: Needed for good security hygiene and to avoid permanent lock-out, but used
rarely.

**Independent Test**: Change password and sign in with the new one; then simulate a forgotten
password and complete recovery once with the recovery PIN and once by answering the security
questions; verify old password no longer
works.

**Acceptance Scenarios**:

1. **Given** the owner is signed in, **When** they change password by entering the current password,
   a new password meeting the rules and confirmation, **Then** the new password works immediately
   and the old one is rejected.
2. **Given** the current password entered is wrong, **When** submitting, **Then** the change is
   rejected and counts toward lockout.
3. **Given** the sign-in screen, **When** the owner taps "Forgot password", chooses either the
   recovery PIN or the security questions, and enters the correct PIN or all 3 answers correctly
   (FR-012), **Then** they set a new password and are signed in; all business data remains intact.
   If any answer is wrong, the app shows "One or more answers are incorrect" without saying which;
   a wrong PIN shows "The recovery PIN is incorrect".
4. **Given** recovery attempts fail 5 times, **When** trying again, **Then** the same progressive
   lockout as sign-in applies.
5. **Given** the owner remembers neither the password, the recovery PIN nor the security answers, **When** they seek help in
   the app, **Then** the app explains that the only remaining option is reinstalling and restoring
   from a backup using the backup passphrase, and that data not backed up cannot be recovered.

---

### User Story 12 - Business Profile, Measurement Units and App Settings (Priority: P3)

The owner maintains shop details shown on statements and reports, configures the egg measurement
units they use, and adjusts app preferences.

**Why this priority**: Defaults work out of the box; customization improves accuracy and branding
but is not needed on day one.

**Independent Test**: Change shop name and verify it on a new PDF; add a unit "Crate = 210 pieces",
use it in a sale, change its size to 180 and verify the earlier sale still shows 210 pieces.

**Acceptance Scenarios**:

1. **Given** a new account, **When** units are viewed, **Then** these defaults exist: Piece (1),
   Dozen (12), Tray (30), Box (210 pieces — editable); Piece cannot be edited or removed.
2. **Given** units settings, **When** the owner adds a unit with a unique name and a whole-number
   number of pieces ≥ 1, **Then** it becomes available in Buy and Sell screens.
3. **Given** a unit used in past entries, **When** the owner changes its piece count, **Then** past
   entries keep the piece count they were recorded with, and only new entries use the new value; the
   owner is told this before confirming.
4. **Given** a unit used in past entries, **When** the owner removes it, **Then** it is deactivated
   (hidden from new entries) rather than deleted, and past entries still show it.
5. **Given** settings, **When** the owner sets default unit for Buy and Sell and preferred display
   unit for dashboard and reports, **Then** those defaults apply to new entries and summaries.
6. **Given** business profile, **When** the owner edits shop name, owner name, shop address, shop
   phone, optional tax/GST number and optional statement footer note, **Then** the changes appear on
   all subsequently generated statements and reports.
7. **Given** settings, **When** viewed, **Then** the owner can also manage: login mobile/email
   (requires current password), biometric unlock on/off, auto-lock timeout (1, 5, 15, 30 minutes),
   theme (System, Light, Dark), language (English, हिन्दी), backup settings, backup passphrase change, security questions, egg varieties, and
   "About" (app version).
8. **Given** egg variety settings, **When** viewed, **Then** White, Brown and Country (Desi) exist by
   default with White as default; the owner can add, rename, set a default and deactivate
   varieties, but cannot deactivate the last active one.
9. **Given** the owner changes the backup passphrase, **When** confirmed with the current passphrase,
   **Then** future backups use the new passphrase and the app clearly states that older backups still
   need the old passphrase.

---

### Edge Cases

- **Back-dated entries**: An entry dated in the past is placed in date order; running balances,
  statements and historical reports for affected dates change accordingly.
- **Device clock**: Future dates are rejected using the device's current date. Dates before
  2000-01-01 are rejected. If the device's time zone changes, entries keep the calendar date they
  were recorded with.
- **Same customer buys and sells**: Both directions net into one balance; the ledger shows both.
- **Balance flips sign**: An overpayment or a large BUY may flip a receivable into a payable; this is
  allowed (with warning for payments) and displayed correctly.
- **Fractional quantities**: 2.5 trays (75 pieces) is valid; 1.5 dozen (18 pieces) is valid; 0.1 tray
  (3 pieces) is valid; 0.05 tray (1.5 pieces) is rejected because the equivalent must be a whole
  number of eggs.
- **Rounding**: Total = quantity × price, rounded once to 2 decimal places (half up). Totals of many
  entries are sums of already-rounded line totals, so sums always match the lines shown.
- **Very large values**: Amounts up to ₹99,99,99,999.99 per entry are accepted; balances and report
  sums must never overflow or lose precision at any data volume in scope.
- **Unit size changes / removal**: Never alters historical entries (US12).
- **Selling more than bought**: Allowed without any warning, because stock is not tracked (FR-026).
- **Variety renamed or deactivated**: Renaming shows the new name on all past and future entries;
  deactivating hides it from new entries only; history, filters and reports still include it.
- **Same customer, different varieties**: Price pre-fill (FR-024) is kept separately per variety.
- **Archived customer**: Cannot be chosen for new entries; existing entries stay; un-archiving
  restores normal use. A search option "Include archived" shows them.
- **Duplicate taps / app killed mid-save**: An entry is either fully saved (including any linked
  payment) or not saved at all; no duplicates, no half records.
- **Low device storage**: If storage is too low to save data, export a file, or create a backup, the
  operation fails with a clear message and no existing data is damaged.
- **Backup while editing**: A backup taken while the owner is working captures a consistent snapshot;
  entries saved after the snapshot started appear in the next backup.
- **Restore while automatic backup is due**: Automatic backup does not run during a restore.
- **Restore of a backup from a newer app version**: Rejected with "This backup was made by a newer
  version of the app. Please update the app first."
- **Restore of a backup from an older app version**: Accepted; data is upgraded to the current
  format with nothing lost.
- **Wrong file chosen for restore**: Rejected with "This file is not an Egg Business Manager backup."
- **Google account revoked/changed**: Automatic backup fails with reason "Google account disconnected"
  and prompts reconnection; local data unaffected.
- **Drive storage full**: Backup fails with reason "Google Drive storage is full".
- **Customer without mobile**: WhatsApp-addressed sharing falls back to the share sheet.
- **App update via new APK**: All data, settings, and backup configuration survive the upgrade.
- **Uninstall**: Uninstalling deletes local data; the backup reminder (US6) mitigates this.
- **Empty states**: Every list, report and statement has a defined empty state message.

## Requirements *(mandatory)*

### Functional Requirements

#### Account, Authentication and Security

- **FR-001**: The system MUST offer signup only when no account exists on the device, and support
  exactly one owner account per installation.
- **FR-002**: Signup MUST collect: mobile number (required), email (optional), password and
  confirmation (required), shop name (required), owner name (required), shop street address
  (optional), city (required), state or union territory (required, chosen from a list) and PIN code
  (required).
- **FR-003**: Users MUST be able to sign in with either the registered mobile number or the registered
  email, plus password.
- **FR-004**: The system MUST never store or display the password in readable form, and MUST never
  show it in exports, reports, logs or backups in readable form.
- **FR-005**: The system MUST show a generic error for failed sign-in that does not reveal whether the
  identifier or the password was wrong.
- **FR-006**: The system MUST apply progressive lockout after repeated failed sign-in, password-change
  or recovery attempts (5 failures → 30 s, doubling per further 5 failures, max 30 min), persisting
  across app restarts.
- **FR-007**: The system MUST lock automatically after the configured inactivity/background timeout
  (default 5 minutes) and MUST hide app content in the recent-apps preview while locked.
- **FR-008**: The system MUST support optional biometric unlock, always with password fallback;
  biometric unlock MUST be disabled automatically if the device's enrolled biometrics change.
- **FR-009**: Users MUST be able to change the password after confirming the current password.
- **FR-010**: Users MUST be able to change the login mobile number or email after confirming the
  current password.
- **FR-011**: Users MUST be able to sign out; signing out keeps all data on the device.
- **FR-012**: The system MUST provide fully offline password recovery with two methods, both set up
  at signup, either of which is sufficient: (a) a 6-digit recovery PIN with confirmation; (b) 3
  security questions — the owner selects 3 different questions from a predefined list of at least 10
  and answers each. The PIN and answers MUST be stored only in protected (non-readable) form. Answers
  are compared case-insensitively after trimming and collapsing spaces, and recovery by questions
  MUST require all 3 answers to be correct without revealing which answer was wrong. Both methods
  share the progressive lockout of FR-006.
- **FR-013**: After a successful password recovery, the system MUST require setting a new password and
  MUST prompt the owner to review or change their recovery PIN and security questions. Users MUST be
  able to change their recovery PIN, and their security questions and answers, at any time from
  settings after confirming the current
  password.

#### Customers

- **FR-014**: Users MUST be able to create, view, edit, archive, un-archive and (only if they have no
  ledger entries) delete customers.
- **FR-015**: A customer MUST have a name (required) and MAY have mobile number, address, notes, and
  an opening balance with direction ("Customer owes me" / "I owe customer") and opening date.
  Customers have no buyer/supplier type or tag; every active customer is available in both the Buy
  and Sell customer pickers, which list customers by most recent activity first, then by name.
- **FR-016**: Mobile numbers MUST be unique across all customers, including archived ones.
- **FR-017**: Users MUST be able to search customers by any part of the name (case-insensitive) or any
  part of the mobile number, with results updating as they type.
- **FR-018**: The customer list MUST show each customer's current balance with direction and support
  sort by name, balance and last activity, and filters To receive / To pay / Settled / Archived.
- **FR-019**: Changing a customer's opening balance after creation MUST be recorded as a revision in
  the customer's history (old value, new value, date/time).

#### Trades (Buy and Sell)

- **FR-020**: Users MUST be able to record SELL and BUY entries with: date (required, default today),
  customer (required), egg variety (required, default variety pre-selected), quantity (required), unit (required), price per unit (required), total amount
  (computed, read-only), remarks (optional).
- **FR-021**: The system MUST compute total = quantity × price per unit, rounded to 2 decimals (half
  up, only when quantity × price has more than 2 decimals), display it live, and show the
  equivalent number of pieces. Totals MUST NOT be rounded to whole rupees and no round-off amount
  exists.
- **FR-022**: Each entry MUST record the unit's piece count at the time of entry so later unit changes
  never alter it.
- **FR-023**: The Sell screen MUST allow an optional "Amount received now" and the Buy screen an
  optional "Amount paid now" with payment mode; the trade and the linked payment MUST be saved
  together atomically.
- **FR-024**: The system MUST pre-fill price per unit with the last price used for the same customer,
  variety,
  unit and entry type, if any.
- **FR-025**: Each entry MUST receive a unique, never-reused, human-readable reference number per type
  (S-, B-, RP-, PC- followed by a 6-digit sequence).
- **FR-026**: The system MUST NOT track stock on hand: no stock balance, stock adjustments or stock
  warnings. Sales MUST never be blocked or warned based on quantities bought. Reports show eggs
  bought and sold only as period totals.
- **FR-027**: The system MUST support configurable egg varieties. Each SELL and BUY entry MUST have
  exactly one variety (required; defaults to the owner's default variety). Varieties MUST be usable
  as a filter in history and as a breakdown in reports and statements.
- **FR-027a**: The system MUST provide default varieties White, Brown and Country (Desi), with White
  as default, and let the owner add, rename, set default and deactivate varieties. At least one active
  variety MUST always exist. A variety used in past entries can be deactivated (hidden from new
  entries) but not deleted; renaming a variety updates its name everywhere.

#### Payments, Balances and Ledger

- **FR-028**: Users MUST be able to record RECEIVE PAYMENT and PAY CUSTOMER entries with: date
  (required, default today), customer (required), amount (required), payment mode (Cash, UPI, Bank
  transfer, Cheque, Other; default Cash), payment reference (optional), remarks (optional).
- **FR-029**: The system MUST calculate each customer's balance automatically using the balance
  formula in the Overview, counting only active (non-voided, non-superseded) entries.
- **FR-030**: The system MUST support advance payments: a RECEIVE PAYMENT or PAY CUSTOMER may be
  recorded when nothing is owed in that direction, or for more than is owed. Before saving, the app
  MUST show the advance amount and the resulting balance (e.g. "₹500 will be held as an advance from
  the customer; the shop will owe the customer ₹500") and require confirmation. An advance is not a
  separate record type: it is the part of the balance carried in the opposite direction, and later
  trades automatically use it up through the running balance (Assumptions: Running account).
- **FR-031**: The system MUST provide a per-customer ledger with running balance, ordered by entry date
  and then by time recorded, with per-type totals and current balance.
- **FR-032**: The balance shown for a customer MUST be identical in every screen, export and report.
- **FR-033**: Users MUST be able to edit any field of an active entry, including entry type, date,
  customer, variety, quantity, unit, price, amount, payment mode, payment reference and remarks. The
  system MUST preserve the original as a superseded revision linked to the corrected one and record
  old values, new values, date/time and optional reason. Before saving, the edit screen MUST show the
  balance effect on every affected customer.
- **FR-033a**: Changing the customer of an entry MUST update both the previous and the new
  customer's balances and ledgers together; both ledgers show the change in the entry's history.
- **FR-033b**: Changing the entry type: (i) the reference number is kept if the type is unchanged;
  otherwise the corrected entry receives a new reference number in the new type's series and the
  original shows "Changed to <new reference>"; (ii) changing between trade types (SELL ↔ BUY) keeps
  variety, quantity, unit and price; (iii) changing a trade to a payment keeps the total as the
  payment amount and requires a payment mode, dropping variety, quantity, unit and price; (iv)
  changing a payment to a trade requires variety, quantity, unit and price, with the total computed
  as usual; (v) if a trade with a linked on-the-spot payment changes type or customer, the owner MUST
  choose to either convert the linked payment accordingly (e.g. RECEIVE PAYMENT → PAY CUSTOMER) or
  void it, and both changes are saved together.
- **FR-034**: Users MUST be able to void an entry with confirmation and optional reason; voided entries
  remain visible and labelled, are excluded from all calculations, and cannot be edited or un-voided.
- **FR-035**: Entries MUST NOT be permanently deleted by any user action.

#### Dashboard, History, Statements and Reports

- **FR-036**: The dashboard MUST show the period figures, overall dues, top receivable customers, quick
  actions, recent entries and backup reminder defined in US6.
- **FR-037**: The transaction history MUST support the filters, sorting, chips, summary totals and
  export defined in US5.
- **FR-038**: Users MUST be able to generate a customer statement for any date range with the content
  defined in US7, including the balance brought forward.
- **FR-039**: Users MUST be able to view a daily report for any date and a monthly report for any month
  with the content defined in US8.
- **FR-040**: Users MUST be able to export statements, daily reports, monthly reports and filtered
  transaction lists as PDF, CSV and Excel (.xlsx).
- **FR-041**: Users MUST be able to share any exported file through the device share sheet, with a
  direct "Share on WhatsApp" option when WhatsApp is installed, and to save it to a location they
  choose.
- **FR-042**: Users MUST be able to share a balance reminder text message to a customer.
- **FR-042a**: Users MUST be able to share a PDF receipt for any single active SELL, BUY, RECEIVE
  PAYMENT or PAY CUSTOMER entry — from the save confirmation and from the entry's detail screen. The
  receipt MUST fit one page and contain: shop name, address, phone and optional GST number; receipt
  title by type (e.g. "Sale Receipt", "Payment Receipt"); reference number; entry date and
  generation date/time; customer name and mobile; for trades the variety, quantity with unit,
  equivalent pieces, price per unit and total; for payments the amount, payment mode and payment
  reference; any linked on-the-spot payment; remarks; the customer's balance after this entry and
  current balance with plain-language direction; and the optional statement footer. Receipts for
  voided entries MUST NOT be shareable; receipts for revised entries show the corrected values and
  the label "Revised". Receipts are PDF only (no text or image receipt).
- **FR-043**: Exported files MUST be created only when the owner asks; temporary share files MUST be
  removed from the app's temporary storage after sharing completes or within 24 hours.

#### Backup and Restore

- **FR-044**: Every backup MUST be encrypted with a key derived from an owner-chosen backup passphrase
  that is separate from the login password; the passphrase MUST be set before the first backup.
- **FR-045**: A backup MUST contain all business data (account, business profile, customers, units,
  all entries including voided and revised ones, audit history, settings) and identify its format
  version, app version, data version, creation date/time and device name, and include an integrity
  check.
- **FR-046**: Users MUST be able to create a manual backup to a device location they choose.
- **FR-047**: Users MUST be able to link a Google account and create a manual backup to Google Drive,
  with access limited to files the app creates; Drive linking MUST be optional.
- **FR-048**: Users MUST be able to enable, configure (Daily/Weekly, time window, Wi-Fi only, while
  charging) and disable automatic Google Drive backups that run in the background with automatic
  retries.
- **FR-049**: The system MUST keep the 10 most recent automatic Drive backups and delete older automatic
  ones; manual backups MUST be retained until the owner deletes them.
- **FR-050**: Users MUST be able to restore from a local backup file or from a list of their Drive
  backups, after entering the backup passphrase, with verification and a summary shown before any
  data changes.
- **FR-051**: Restore MUST replace all current data (no merge), MUST keep a safety copy of current data
  until the restore is confirmed complete, and MUST return to the previous data unchanged if the
  restore fails.
- **FR-052**: Restore MUST reject backups that fail decryption, fail the integrity check, are not app
  backups, or were made by a newer data version, each with a distinct plain-language message.
- **FR-053**: Restore MUST be available from the welcome screen of a fresh install.
- **FR-054**: The system MUST record every backup and restore attempt in a backup history with date/time,
  destination/source, trigger, status, size and failure reason; and show a status card with last
  successful backup, next scheduled backup and linked Drive account.
- **FR-055**: The system MUST notify the owner when automatic backups have failed or not run for 3
  consecutive scheduled periods, and show the dashboard reminder when no successful backup exists in
  the last 7 days.
- **FR-056**: A failed backup MUST never modify or damage local data.
- **FR-057**: Users MUST be able to change the backup passphrase (with the current one) and delete
  individual Drive backups from the history.

#### Profile, Units and Settings

- **FR-058**: Users MUST be able to edit the business profile (shop name, owner name, address, phone,
  optional tax/GST number, optional statement footer); changes apply to subsequently generated
  documents.
- **FR-059**: The system MUST provide default units Piece (1), Dozen (12), Tray (30), Box (210) and let
  the owner add, rename, change size of, and deactivate units except Piece.
- **FR-060**: Users MUST be able to set default units for Buy and Sell and a preferred display unit
  for summaries.
- **FR-061**: Users MUST be able to set auto-lock timeout, biometric unlock and theme.
- **FR-061a**: The app MUST be fully available in English and Hindi. The language is chosen on the
  welcome screen (default: the device language if Hindi, otherwise English) and can be changed in
  Settings at any time without restarting or losing data. All screens, messages, notifications,
  statements, reports (PDF, CSV and Excel headings), receipts and balance reminders MUST use the
  selected language. User-entered data (names, remarks, variety and unit names) is shown exactly as
  entered. Amounts and dates use Indian formats in both languages, and numbers use standard digits
  (0–9).

#### Offline Operation and Data Protection

- **FR-062**: Every feature except Google Drive backup/restore and Google account linking MUST work with
  no internet connection.
- **FR-063**: All business data MUST be stored only on the device (plus the owner's own encrypted
  backups); no data may be sent to any server, analytics or tracking service.
- **FR-064**: Installing a newer version of the app over an existing one MUST preserve all data and
  settings.
- **FR-065**: Saving any entry, edit, void, customer change, or setting MUST be all-or-nothing.

### Validation Rules

| Field | Rule | Error message |
|-------|------|---------------|
| Mobile number (account & customer) | 10 digits starting with 6–9 after removing spaces, dashes and an optional +91/0 prefix; stored in normalized 10-digit form | "Enter a valid 10-digit mobile number" |
| Email | Optional; standard email format; max 254 characters; stored lower-case | "Enter a valid email address" |
| Password | 8–64 characters, at least one letter and one digit; must not equal the mobile number | "Password must be 8–64 characters with at least one letter and one number" |
| Confirm password / passphrase | Must match exactly | "Passwords do not match" |
| Backup passphrase | 8–128 characters; must differ from the login password | "Passphrase must be at least 8 characters and different from your login password" |
| Shop name, owner name | Required, 2–80 characters after trimming | "Enter the shop name" / "Enter the owner name" |
| Address | Optional, max 250 characters | "Address is too long (max 250)" |
| City | Required, 2–60 characters after trimming | "Enter the city" |
| State | Required; one of the Indian states or union territories | "Select a state" |
| PIN code | Required; 6 digits, first digit 1–9 | "Enter a valid 6-digit PIN code" |
| Recovery PIN | Exactly 6 digits; not all the same digit; not an ascending or descending run (123456, 987654); confirmation must match | "Choose a 6-digit PIN that is not easy to guess" / "PINs do not match" |
| Customer name | Required, 2–80 characters after trimming; duplicates allowed with a warning "A customer with this name already exists" | "Enter the customer's name" |
| Notes / remarks | Optional, max 500 characters | "Too long (max 500 characters)" |
| Opening balance | Optional; 0.00–99,99,99,999.99, max 2 decimals, with direction | "Enter a valid amount" |
| Transaction date | Required; not after today; not before 2000-01-01 | "Date cannot be in the future" / "Enter a valid date" |
| Customer (entry) | Required; must be an active customer | "Select a customer" |
| Quantity | Required; > 0; max 3 decimals; quantity × unit piece count must be a whole number ≥ 1; max 10,000,000 pieces per entry | "Enter a quantity greater than 0" / "Quantity must equal a whole number of eggs" |
| Unit | Required; active unit | "Select a unit" |
| Price per unit | Required; > 0; max 2 decimals; max ₹10,00,000.00 | "Enter a price greater than 0" |
| Total amount | Computed; must be ≥ ₹0.01 and ≤ ₹99,99,99,999.99 | "Total is outside the allowed range" |
| Payment amount / amount paid now | Required for payments; > 0; max 2 decimals; ≤ ₹99,99,99,999.99; "amount received/paid now" must not exceed the trade total | "Enter an amount greater than 0" / "Cannot exceed the total of this entry" |
| Payment reference | Optional, max 50 characters | "Reference is too long" |
| Unit name | Required, 1–20 characters, unique (case-insensitive) | "A unit with this name already exists" |
| Egg variety (entry) | Required; must be an active variety | "Select an egg variety" |
| Egg variety name | Required, 1–30 characters, unique (case-insensitive) | "A variety with this name already exists" |
| Security questions | Exactly 3, all different | "Choose 3 different questions" |
| Security answers | Required, 2–50 characters after trimming; the 3 answers must not all be identical; an answer must not equal the password | "Enter an answer of at least 2 characters" |
| Unit piece count | Whole number 1–10,000 | "Enter a whole number of eggs between 1 and 10,000" |
| Date range (filters, statements, reports) | Start ≤ end; end not after today | "Start date must be before end date" |
| Amount range filter | Min ≤ max; non-negative | "Minimum must be less than maximum" |

All text inputs are trimmed of leading/trailing whitespace before validation. Validation messages
appear next to the field and the first invalid field receives focus.

### Business Rules

- **BR-001**: SELL increases the customer's balance (customer owes the shop more) by the entry total.
- **BR-002**: BUY decreases the customer's balance (shop owes the customer more) by the entry total.
- **BR-003**: RECEIVE PAYMENT decreases the customer's balance by the payment amount.
- **BR-004**: PAY CUSTOMER increases the customer's balance by the payment amount.
- **BR-005**: Opening balance "Customer owes me" adds to the balance; "I owe customer" subtracts.
- **BR-006**: Balance > 0 means the customer owes the shop; < 0 means the shop owes the customer; = 0
  means settled.
- **BR-007**: Total receivable = sum of all positive customer balances; total payable = sum of the
  absolute values of all negative balances. They are never netted against each other on the dashboard.
- **BR-008**: All money is exact to the paisa; no rounding occurs except line-total rounding (FR-021).
  Sums are always sums of displayed line amounts.
- **BR-009**: Voided entries and superseded revisions are excluded from every balance, total, report
  and statement, but are always retained and included in backups.
- **BR-010**: Trading difference = sales amount − purchase amount for the period; it is labelled
  "Trading difference" and is not presented as profit.
- **BR-011**: Ledger order is by entry date, then by time recorded. Running balance is the cumulative
  balance after each line.
- **BR-012**: Statement brought-forward balance = balance from all active entries dated before the
  statement start date (including opening balance if its date is before the start).
- **BR-013**: Payment-on-the-spot (FR-023) creates a separate payment entry linked to the trade, dated
  the same day, so the ledger shows both lines.
- **BR-014**: Reference numbers are assigned on first save, never reused, and kept across edits that
  do not change the entry type; a type change assigns a new number in the new type's series (FR-033b).
  Voiding or changing type never releases a number.
- **BR-015**: A unit's piece count is captured per entry at save time; quantities in reports are
  summed in pieces and converted to the display unit for presentation (showing remainder, e.g.
  "10 trays 5 pieces").
- **BR-016**: Dashboard "Today" and daily reports use the device's local calendar date.

### Error Scenarios

| Scenario | Expected behaviour |
|----------|--------------------|
| Save fails due to low storage | Message "Not enough storage to save. Free up space and try again." Entered values stay on screen; nothing partial saved. |
| App closed or crashes during save | On reopen, the entry is either fully present or absent; no duplicate or partial records. |
| Unexpected internal error on any screen | Plain-language message "Something went wrong. Your saved data is safe." with option to retry; no data loss. |
| Sign-in locked out | Countdown shown; "Forgot password" remains available (subject to its own lockout). |
| Biometric not available / changed | Falls back to password; biometric toggle turned off with a note. |
| Google sign-in cancelled or fails | "Google Drive was not linked." All local features continue normally. |
| Drive backup: no internet | Manual: message and failed history entry. Automatic: waits and retries. |
| Drive backup: storage full / account revoked / quota error | Failed history entry with specific reason; notification after repeated automatic failures. |
| Local backup: location unavailable or permission revoked | "Could not save the backup to this location. Choose another location." |
| Restore: wrong passphrase | "Incorrect passphrase." Data unchanged. Counts toward lockout. |
| Restore: corrupt or tampered file | "This backup is damaged and cannot be restored." Data unchanged. |
| Restore: newer data version | "This backup was made by a newer version of the app. Please update the app first." |
| Restore: not an app backup | "This file is not an Egg Business Manager backup." |
| Restore interrupted mid-way | Previous data automatically put back; message "Restore did not complete. Your previous data has been kept." |
| Export fails (storage/permission) | "Could not create the file." No partial file left behind. |
| Share target not available | Standard share sheet shown; if no app can receive the file, "No app available to share this file." |
| Unit change affecting past entries | Pre-confirmation note that past entries are unaffected. |
| Attempt to delete customer with entries | Delete disabled; Archive offered. |
| App upgrade with data format change | Data upgraded automatically with nothing lost; if upgrading fails, the app does not discard data and shows "Update could not be completed. Please contact support; your data has not been deleted." |

### Key Entities *(include if feature involves data)*

- **Owner Account**: The single user of the installation — mobile number, optional email, protected
  password credential, protected recovery PIN, 3 security questions with protected answers, lockout
  state, biometric preference.
- **Business Profile**: Shop name, owner name, address, city, state, PIN code, phone, optional tax/GST number, statement
  footer. One per installation.
- **Customer**: A party the shop trades with in either direction — name, mobile, address, notes,
  archived flag, opening balance (amount, direction, date), created/updated times. Has many entries.
- **Measurement Unit**: Name, piece count, active flag, system flag (Piece). Referenced by trades.
- **Egg Variety**: Name (e.g. White, Brown, Country), active flag, default flag. Each trade entry
  references exactly one variety.
- **Trade Entry (SELL / BUY)**: Reference number, type, date, customer, egg variety, quantity, unit,
  piece count
  captured at save, price per unit, total amount, remarks, status (Active / Superseded / Voided),
  optional link to an on-the-spot payment, created/updated times.
- **Payment Entry (RECEIVE PAYMENT / PAY CUSTOMER)**: Reference number, type, date, customer, amount,
  payment mode, payment reference, remarks, status, optional link to the originating trade.
- **Entry Revision / Audit Record**: For edits, voids and opening-balance changes — which record,
  old values, new values, action, reason, date/time.
- **Customer Ledger (derived)**: The ordered set of a customer's active entries with running balance;
  always recomputable from entries.
- **Backup Record**: Date/time, destination or source (Device / Google Drive), trigger (Manual /
  Automatic / Restore), status, size, failure reason, backup identifier.
- **Backup Settings**: Passphrase verifier, Drive linked account, automatic schedule (frequency, time
  window, Wi-Fi only, while charging), retention.
- **App Settings**: Language, default Buy/Sell units, default egg variety, display unit, auto-lock timeout, theme, biometric toggle.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A first-time owner completes signup and reaches the dashboard in under 3 minutes.
- **SC-002**: The owner records a typical sale or purchase (existing customer) in under 30 seconds and
  a payment in under 20 seconds.
- **SC-003**: For any test data set, every customer balance, ledger running balance, statement,
  dashboard figure and report total matches an independent hand/spreadsheet calculation exactly (0
  paisa difference) in 100% of test cases.
- **SC-004**: 100% of features other than Google Drive backup/restore work with the device in airplane
  mode.
- **SC-005**: With 2,000 customers and 100,000 entries, customer search results appear within 1 second
  of typing, the dashboard opens within 2 seconds, and a monthly report is ready within 5 seconds on a
  mid-range phone.
- **SC-006**: Restoring a backup reproduces the backed-up data with 100% fidelity (every customer,
  entry, revision and setting), verified by comparing record counts and balances before and after.
- **SC-007**: In fault-injection testing (power loss, app kill, network loss during save, backup or
  restore), 0 cases result in lost, duplicated or partially saved records.
- **SC-008**: Upgrading the app from any previously released version preserves 100% of data and
  settings.
- **SC-009**: Without the password (or the recovery PIN, or all security answers) and backup passphrase, no customer or financial
  data can be read from the app's backups or viewed in the app.
- **SC-010**: 90% of first-time users can record a sale, record a payment and share a customer
  statement on WhatsApp without help on their first attempt.
- **SC-011**: When automatic backup is enabled and the device meets conditions at least once a day, a
  successful Drive backup exists that is no older than 48 hours on 95% of days.

## Assumptions

- **Users and devices**: One shop owner on one device in this release (V1). No staff accounts, roles,
  multi-device use or sync. Restoring a backup onto another phone (US9) is a one-time move of the
  data, not sync; if two phones are used anyway, their data is independent and a restore replaces,
  never merges. Multi-device support may be considered in a later version.
- **Region and currency**: The shop operates in India; currency is Indian Rupee (₹) with 2 decimals
  and Indian digit grouping (e.g. ₹1,00,000.00); mobile numbers are Indian 10-digit numbers. Interface
  languages are English and Hindi (FR-061a), with all text ready for further translation.
- **Customers and suppliers**: Represented by a single "Customer" concept; a party may both buy from
  and sell to the shop, and its balance nets both directions.
- **Opening balances**: Added to support migrating from paper records; zero by default so the requested
  formula holds unchanged for new customers.
- **Running account**: Payments are applied to the customer's overall balance, not to individual sale
  or purchase bills.
- **Box size**: Defaults to 210 pieces (7 trays) and is editable, since box sizes vary by region.
- **No taxes**: GST/tax calculation and invoicing are out of scope; the optional GST number is shown on
  documents for reference only.
- **Sharing**: WhatsApp and other apps are used only through the phone's standard sharing; no messaging
  service or API is integrated, so no messages are sent automatically.
- **Password recovery**: No SMS/email OTP is possible because no backend or paid service is allowed
  (Constitution I, X); recovery is therefore fully on-device via a recovery PIN or security
  questions (owner's choice, 2026-11-08). The owner accepts that security questions are weaker than a recovery key; lockout
  (FR-006) and the requirement to answer all 3 limit guessing. Owners are advised at setup to choose
  answers that people close to them cannot guess.
- **Stock**: The app does not track stock on hand (owner's choice). Quantities are recorded on trades
  and summarized in reports as eggs bought and sold, but no stock balance, breakage adjustments or
  stock warnings exist.
- **Egg varieties**: The shop trades a few varieties with different prices (owner's choice). Each
  trade entry is for exactly one variety; mixed sales are recorded as separate entries. Varieties
  are owner-managed data with no fixed list, and the record design MUST stay extensible so a later
  version can add multi-line bills (several varieties in one entry) and per-variety attributes
  without losing or re-entering existing data.
- **Backup passphrase**: Separate from the login password so backups remain restorable even after a
  password reset, and so a leaked backup file alone is not readable.
- **Restore semantics**: Restore replaces data; merging two data sets is out of scope.
- **Google Drive**: Owner uses their own free Google account; Drive storage used counts toward their
  quota. No other cloud provider in this release.
- **Out of scope for this release**: Stock/inventory tracking, multi-line bills (several varieties in
  one entry), multiple shops, staff/permissions, invoicing with tax, bill-wise
  payment allocation, expense tracking, egg price market feeds, SMS sending, cloud sync, web or iOS
  versions, and data import from other apps.
- **Distribution**: The app is installed by sideloading an APK and updated by installing newer APKs.
