# EggVyapar — User Guide

Version 1.0.0 · Android 12 or newer · English and हिन्दी

EggVyapar is a digital account book (khata) for an egg shop. It records eggs you sell and
buy, money you receive and pay, and keeps every customer's balance up to date. Everything is stored
**only on your phone**, encrypted. Nothing is sent to any server; the only time the app uses the
internet is when **you** choose to keep an encrypted backup in your own Google Drive.

---

## 1. Installing

1. Copy `EggVyapar-<version>.apk` (for example `EggVyapar-1.0.3.apk`) to the phone (USB cable, Google Drive, Quick Share, WhatsApp
   "document", etc.).
2. Open the file from the phone's **Files** app. If Android asks, allow **Install unknown apps** for
   the app you opened it from (Settings → Apps → Special app access → Install unknown apps).
3. Tap **Install**, then **Open**. Play Protect may say the app is unknown because it is not from the
   Play Store; choose **Install anyway**.

> If a test version of the app was installed on the phone before, uninstall it first. Test versions
> are signed with a different key, so Android will not install 1.0.0 over them.

## 2. Activate your lifetime license (once)

The first time EggVyapar opens it shows **Activate EggVyapar**:

1. Tap **Share** (or **Copy**) and send the **Device code** shown (like `7K2Q-M9XP-4RTD-H8WN`) to the
   seller — Vinayak Metagoli, +91 7848971006.
2. You receive a **license key** starting with `EV1-`. Copy it, tap **Paste from clipboard** (or paste
   into the box) and tap **Activate**.

That is all: the license never expires, needs no renewal and no internet, and the app does not ask
again. **More → License** shows *Activated · Lifetime License*. A key works only on the phone it was made
for; on a new phone (or after reinstalling) send the new Device code for a key. Your data is never
deleted by the license check.

## 3. First start — create your account

1. Tap **Create account**.
2. Enter the **owner name**, **mobile number** (this is what you sign in with), optional **email**, and
   a **password**: 8–64 characters with at least one letter and one number, and not your mobile number.
3. Enter the **shop details**: shop name, address (optional), city, state and PIN code. These print on
   statements and receipts.
4. Choose a 6-digit **recovery PIN** and answer **3 security questions**. You need one of them if you
   ever forget your password — write the PIN down somewhere safe.
5. You land on the **Dashboard**.

Already have a backup from another phone? Tap **Restore from a backup** on the first screen instead
(see section 10).

## 4. Signing in, locking and security

- **Sign in** with your mobile number (or email) and password. After several wrong tries the app makes
  you wait before trying again.
- **Fingerprint / face unlock**: More → Security → turn on biometric unlock.
- **Auto-lock**: More → Security → Auto-lock after 1, 5, 15 or 30 minutes in the background.
  **Lock now** locks immediately.
- **Forgot password**: on the sign-in screen tap **Forgot password**, then use the recovery PIN or the
  security answers to set a new password.
- More → Security also lets you **change the password**, **change the recovery PIN** and
  **change the security questions**.
- **Sign out**: More → Sign out. Your data stays on the phone; you need the password to get back in.

## 5. The main screens

The bar at the bottom has five tabs:

| Tab | What it shows |
|---|---|
| **Dashboard** | Quick buttons (Sell, Buy, Receive, Pay); today's sales and purchases; how much customers owe you (receivables) and how much you owe (payables); all-time totals; recent transactions |
| **Customers** | Everyone you deal with — buyers and suppliers — with their balances |
| **Transactions** | Every sale, purchase and payment, with filters and totals |
| **Reports** | Statements and business reports to view, save or share |
| **More** | Shop and owner profile, security, units, varieties, backup, display settings, About, sign out |

### Balances in plain words

- **To receive ₹X** — the customer owes you.
- **To pay ₹X** — you owe the customer.
- **Settled** — nothing is due either way.

Balances are always calculated from the entries, so they are correct even after you edit or delete
an old entry.

## 6. Customers

- **Add**: Customers → **+**. Name is required; mobile, address and notes are optional.
- **Opening balance**: if the customer already had a balance in your paper book, enter the amount and
  choose whether **the customer owes you** or **you owe the customer**, with the date.
- The app warns if another customer has the same mobile number (and offers to open them) or a similar name.
- **Search** by name or mobile; filter **Active / To receive / To pay / Settled / Deactivated**; sort
  by name, balance or last activity.
- A customer's page shows the balance, contact details, quick buttons **Sell / Buy / Receive / Pay**,
  **Transaction history** and **Statement**.
- **Deactivate** a customer you no longer deal with (hidden from pickers, history kept). A customer
  with no entries can be deleted.

## 7. Selling and buying eggs

Dashboard → **Sell** (or **Buy**), or from a customer's page.

1. Choose the **customer** (search, or **Add new customer** without leaving the screen — what you
   typed is kept).
2. Check the **date** (today by default; future dates are not allowed).
3. Choose the **variety** (White, Brown, Country…) and the **unit** (Tray of 30, Box, Dozen, Piece…).
4. Enter the **quantity** and **price per unit**. The app fills in the last price you used for this
   customer, variety and unit. The **total** and the number of **eggs** update as you type.
5. **Amount received now** (Sell) / **Amount paid now** (Buy) — optional. If the customer pays part
   or all of it on the spot, enter it here and pick the payment mode (Cash, UPI, Bank transfer,
   Cheque…). **Received in full** fills in the whole total. The app saves the sale and a separate
   linked payment, so the history shows both lines; only the rest stays on the balance. This amount
   cannot be more than the total — record extra money with **Receive payment**.
6. Add **remarks** if you like and tap **Save**.

The confirmation shows the reference number (Sales `S-000001`, Purchases `B-000001`, Payments
received `RP-…`, Payments made `PC-…`). Choose **Record another** (keeps customer, date, variety and
unit), **Share receipt**, or **Done**. Tapping Save twice never creates two entries.

## 8. Payments

Dashboard → **Receive** (money from a customer) or **Pay** (money to a customer/supplier).

- Choose the customer; the screen shows what is currently due (**Full amount**).
- Enter the amount, the **payment mode** and an optional **reference no.** (UPI transaction id,
  cheque number…).
- Paying **more** than is due creates an **advance**: the app asks you to confirm, and the balance
  turns the other way. Later sales use the advance up automatically.

## 9. Correcting and deleting entries

Open an entry from **Transactions** or a customer's **Transaction history**.

- **Edit** — any field can change: amount, quantity, price, date, customer, even Sell ↔ Buy or
  Receive ↔ Pay. Before saving, the app shows how each customer's balance will change. A reason is
  optional. The original stays in the entry's **history**, marked **Revised**. Changing the type gives
  the entry a new reference in the new series ("Changed to B-000004").
- **Delete** — the entry stays visible, marked **Deleted**, and no longer counts anywhere. If the entry
  was saved together with an on-the-spot payment, the app offers to delete that payment too.
- **Share receipt** — sends a one-page PDF receipt (see section 11).
- A sale and its on-the-spot payment link to each other: "Paid on the spot: RP-…" / "Taken with S-…".

## 10. Backup and restore (More → Backup & restore)

Backups contain everything: shop and owner profile, customers, all entries, units, varieties and
settings. They are **encrypted with a backup passphrase** that you choose (different from the login
password).

> **If you forget the backup passphrase, the backups cannot be opened by anyone — including you.**
> Write it down and keep it somewhere safe, away from the phone.

**Set up**

1. **Set backup passphrase**.
2. Choose where to save: **This phone** (pick a folder, e.g. Documents or a memory card) or
   **Google Drive** (tap **Connect Google Drive** and choose your account; the app can only see its own
   hidden folder in your Drive, nothing else).

**Back up now** makes a backup immediately. **Last successful backup** shows when the last one worked.
The **history** lists past backups; Drive backups can be downloaded to the phone.

**Automatic backup** (to Google Drive):

- Turn on **Back up automatically to Google Drive**.
- **How often**: daily, weekly or monthly, with a **preferred time**. Android runs the backup around
  that time when the phone allows it — it may be later if the phone is off, in battery saver or offline.
- **Only on Wi-Fi** / **Only while charging** (optional).
- **Automatic backups to keep**: older automatic backups are removed from Drive; backups you make
  yourself are never removed.
- If automatic backups keep failing, the app shows a notification (allow notifications when asked).
  The screen shows the last failure and the next scheduled run.

**Restore**

1. Backup & restore → **Restore** → choose **from this phone**, **from Google Drive**, or a
   **safety backup**. (On a new phone: **Restore from a backup** on the first screen.)
2. Enter the backup's passphrase.
3. Check the preview (shop, number of customers and entries, date of the backup) and confirm that you
   understand the data on this phone will be **replaced**.
4. Before replacing anything, the app makes a **safety backup** of the current data. If anything goes
   wrong during the restore, it rolls back automatically. The app restarts with the restored data;
   sign in with the password that was in use when the backup was made.

## 11. Reports, statements, receipts and sharing

**Reports tab**

| Report | Shows |
|---|---|
| Customer statement | Opening balance, every entry with debit/credit, running and closing balance |
| Daily sales / Daily purchases | Per day, per customer, and every entry |
| Monthly sales / Monthly purchases | Per month and per customer |
| Outstanding receivables / payables | Who owes you / whom you owe, largest first |
| Payment history | Every payment received and made |
| Transaction report | All sales, purchases and payments in a date range |

Choose the customer (where needed) and the dates (this month by default), then:

- **Save as PDF / Excel / CSV** — choose the folder; files open in any PDF viewer, Excel, Google Sheets.
- **Share PDF** — opens Android's share menu (WhatsApp, Gmail, Bluetooth, Quick Share…) with the PDF and
  a short summary as the message. **Share as text** sends only the summary.
- A customer's page has a **Statement** shortcut.

**Receipts** — after saving a sale, purchase or payment, or from the entry screen, tap
**Share receipt**. It is a one-page PDF with your shop name, address and phone, the receipt number and
date, the customer, the items or payment details, any amount paid on the spot, remarks, the balance
after this entry and the current balance. Corrected entries are marked **Revised**; deleted entries
have no receipt.

Shared files are made only when you ask and are removed from the app's temporary storage within a day.

## 12. Settings (More)

| Setting | What you can change |
|---|---|
| **Shop profile** | Shop name, address, city, state, PIN code (used on statements and receipts) |
| **Owner profile** | Owner name; mobile number and email (the sign-in details — your current password is required to change them) |
| **Security** | Password, recovery PIN, security questions, fingerprint unlock, auto-lock, lock now |
| **Measurement units** | Add units and how many eggs each holds (Piece = 1 cannot change). Changing a unit's size only affects new entries; old entries keep the size they were saved with |
| **Egg varieties** | Add, rename, deactivate varieties; choose the default variety for new entries |
| **Backup & restore** | See section 10 |
| **Display & preferences** | **Language** (phone language, English, हिन्दी — changes immediately); **Theme** (system, light, dark); **Currency symbol** (₹ or Rs.); **Number grouping** (Indian 1,00,000 or international 100,000); **Date format** (8 Nov 2026, 08/11/2026, 08-11-2026, 2026-11-08); **Default unit** for Sell and Buy |
| **About** | App version and build, privacy and security information, libraries used |
| **Sign out** | See section 4 |

## 13. Privacy and security in short

- All data is in an encrypted database on the phone (SQLCipher, key protected by the Android Keystore).
- Password, PIN and security answers are stored only as strong one-way hashes.
- Backups are encrypted with your passphrase before they leave the app.
- The app is excluded from Android's own cloud backup, so your data never goes anywhere you did not choose.

## 14. Updating to a new version

Install the new APK over the old one the same way as in section 1 — **do not uninstall first**.
Your account, customers, entries and settings stay as they are. Updates must come from the same
publisher (the same signing key); Android refuses an APK signed with a different key. Making a
backup before updating is always a good idea.

## 15. Getting help

| Problem | What to do |
|---|---|
| Forgot password | Sign-in screen → Forgot password → recovery PIN or security answers |
| Forgot backup passphrase | Make a new backup with a new passphrase now; old backups stay locked |
| "App not installed" when updating | The APK is signed with a different key (e.g. a test build) — back up, uninstall, install, restore |
| Google Drive won't connect | Check the internet connection and that the right Google account is chosen; try again |
| Automatic backup did not run on time | Android delays background work in battery saver or without network; it runs when allowed |
