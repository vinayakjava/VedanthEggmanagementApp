# Contract: Exported Documents and Sharing

All documents are generated from a format-independent `DocumentModel` (title, period, shop
header, sections, tables, totals, footer). PDF, CSV and XLSX renderers consume the same model, so
all formats contain the same figures. Text uses the selected app language (FR-061a). Enum codes
are translated, and user-entered text is shown as entered.

## Common rules

| Rule | PDF | CSV | XLSX |
|------|-----|-----|------|
| Amounts | `₹1,23,456.78` (Indian grouping) | `123456.78` (no symbol, no grouping, `.` decimal) | numeric cell, format `#,##,##0.00` |
| Dates | `07 Nov 2026` / `07 नव॰ 2026` | ISO `2026-11-07` | date cell `dd-mm-yyyy` |
| Quantity | `10 Tray (300 pcs)` | columns `quantity`, `unit`, `pieces` | numeric cells |
| Encoding | embedded system fonts (Devanagari supported) | UTF-8 with BOM, RFC 4180, CRLF | UTF-8 XML |
| Header block | shop name, address, phone, GST (if set), title, period, "Generated on <date time>" | first rows: title, period, generated; blank row; table header | sheet 1 header rows + table; frozen header |
| Pages | A4 portrait, page "x of y", footer note | n/a | one sheet per table |
| Voided / superseded | excluded (BR-009) | excluded | excluded |

File names: `<DocType>_<subject>_<period>.<ext>`, e.g. `Statement_Ramesh-Traders_2026-11.pdf`,
`DailyReport_2026-11-07.xlsx`, `Receipt_S-000123.pdf`, `Transactions_2026-11-01_2026-11-07.csv`.

## Customer statement (FR-038, US7)

Columns: Date · Reference · Description (type + variety) · Quantity · Rate · Debit (increases what
the customer owes: SELL, PAY CUSTOMER, opening owed) · Credit (BUY, RECEIVE PAYMENT, opening shop-owes) · Balance.
Rows: "Balance brought forward" first, then entries, then totals per type and the closing balance
with its meaning text. An empty period shows "No transactions in this period".

## Daily report (US8-1) and monthly report (US8-2)

Sections: Summary (bought/sold pieces and per unit; purchase/sales amounts; trading difference;
received/paid by payment mode; net cash; counts by type) · By variety (qty, amount, average
price/piece for SELL and BUY) · Entries list (daily) or day-by-day table (monthly) · Top 10
customers by sales and by purchases (monthly) · Receivable/payable as at period end (monthly).
In CSV, each section is a block separated by one blank row and preceded by a section title row.
In XLSX, each section is its own sheet.

## Transaction list (US5-7)

Columns: Date, Reference, Type, Customer, Mobile, Variety, Quantity, Unit, Pieces, Rate, Amount,
Payment mode, Payment reference, Status, Remarks. Rows are exactly the filtered set in the chosen
sort order, followed by a totals row per type.

## Receipt (FR-042a)

PDF only, one page (A5 width, auto height ≤ A4). Content is exactly as listed in FR-042a. Title by
type: Sale Receipt / Purchase Receipt / Payment Received Receipt / Payment Made Receipt. Voided
entries cannot produce a receipt; revised entries carry the "Revised" label.

## Sharing

| Action | Mechanism |
|--------|-----------|
| Share (any app) | `ACTION_SEND`, MIME by format (`application/pdf`, `text/csv`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`), `EXTRA_STREAM` = FileProvider URI from `cacheDir/exports/`, `FLAG_GRANT_READ_URI_PERMISSION`, `Intent.createChooser` |
| Share on WhatsApp | same intent with `setPackage("com.whatsapp")`, or `com.whatsapp.w4b` if only Business is installed; when the customer has a mobile number, extra `jid = "91<mobile>@s.whatsapp.net"` (best effort). If WhatsApp is absent, use the generic chooser |
| Save to device | `ACTION_CREATE_DOCUMENT` (SAF) with the suggested file name; bytes copied from the rendered file |
| Balance reminder | `ACTION_SEND` `text/plain`, localized template: "Namaste <name>, your balance with <shop> as on <date> is <amount> (<meaning>). – <owner>" — editable in an in-app dialog before sharing |
| Cleanup | `cacheDir/exports/*` older than 24 h deleted at app start and by a daily `CleanupWorker` |

`AndroidManifest.xml` declares `<queries>` for packages `com.whatsapp` and `com.whatsapp.w4b`, plus
an `ACTION_SEND` intent query, so availability can be detected under package-visibility rules.
