// Column weights (relative widths) are layout values; naming each would obscure them.
@file:Suppress("MagicNumber")

package com.eggbusinessmanager.app.feature.receipt

import android.content.Context
import androidx.annotation.StringRes
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.payment.modeLabel
import com.eggbusinessmanager.app.feature.reports.Catalog
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.app.ui.text.ReferenceLabels
import com.eggbusinessmanager.domain.export.Cell
import com.eggbusinessmanager.domain.export.Column
import com.eggbusinessmanager.domain.export.ExportFormat
import com.eggbusinessmanager.domain.export.ReportDocument
import com.eggbusinessmanager.domain.export.ReportFileName
import com.eggbusinessmanager.domain.export.Table
import com.eggbusinessmanager.domain.ledger.Balance
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.money.QuantityParser
import com.eggbusinessmanager.domain.receipt.Receipt
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Receipt title by entry type (FR-042a). */
@StringRes
fun receiptTitle(type: EntryType): Int = when (type) {
    EntryType.SELL -> R.string.receipt_title_sell
    EntryType.BUY -> R.string.receipt_title_buy
    EntryType.PAYMENT_RECEIVED -> R.string.receipt_title_received
    EntryType.PAYMENT_MADE -> R.string.receipt_title_made
    EntryType.OPENING_BALANCE -> throw IllegalArgumentException("Opening balances have no receipt")
}

/**
 * A one-page receipt (FR-042a) as a [ReportDocument], in the app's language, so it is drawn by the
 * same PDF writer as statements: shop letterhead with phone, title and receipt number (marked
 * "Revised" after a correction), date and customer, the amount and both balances in the summary box,
 * then the trade or payment details, any on-the-spot payment and remarks.
 */
class ReceiptDocumentFactory @Inject constructor(@ApplicationContext private val context: Context) {

    private val locale get() = context.resources.configuration.locales[0]
    private fun s(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    fun build(receipt: Receipt, catalog: Catalog, now: LocalDateTime): ReportDocument {
        val entry = receipt.entry
        val number = s(R.string.receipt_no, entry.reference) + if (receipt.revised) " · " + s(R.string.receipt_revised) else ""
        val mobile = receipt.customer.mobile?.takeIf { it.isNotBlank() }
        val customer = if (mobile != null) {
            s(R.string.doc_customer_mobile, receipt.customer.name, mobile)
        } else {
            s(R.string.doc_customer, receipt.customer.name)
        }
        return ReportDocument(
            title = s(receiptTitle(entry.type)),
            business = business(receipt),
            details = listOf(number, s(R.string.receipt_date, IndianFormatters.date(entry.date, locale)), customer),
            summary = listOf(
                s(if (entry.trade != null) R.string.doc_total else R.string.doc_col_amount) to amount(entry.amount),
                s(R.string.receipt_balance_after) to balance(receipt.balanceAfter),
                s(R.string.receipt_balance_now) to balance(receipt.currentBalance),
            ),
            tables = listOf(
                Table(
                    heading = null,
                    columns = listOf(Column(s(R.string.receipt_col_item), 1f), Column(s(R.string.doc_col_details), 2f)),
                    rows = rows(receipt, catalog).map { (label, value) -> listOf(Cell.Text(label), Cell.Text(value)) },
                ),
            ),
            generatedAt = s(R.string.doc_generated, DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).format(now)),
            footnote = s(R.string.receipt_thanks),
        )
    }

    /** E.g. "Sale-receipt_S-000001_Ramesh-Traders.pdf". */
    fun fileName(receipt: Receipt): String =
        ReportFileName.of(listOf(s(receiptTitle(receipt.entry.type)), receipt.entry.reference, receipt.customer.name), ExportFormat.PDF)

    private fun business(receipt: Receipt): List<String> {
        val profile = receipt.shop ?: return emptyList()
        val place = listOfNotNull(
            profile.address?.takeIf { it.isNotBlank() },
            profile.city,
            "${s(ReferenceLabels.state(profile.state))} ${profile.pinCode}",
        ).joinToString(", ")
        return listOfNotNull(profile.shopName, place, receipt.shopPhone?.let { s(R.string.receipt_shop_phone, it) })
    }

    private fun rows(receipt: Receipt, catalog: Catalog): List<Pair<String, String>> {
        val entry = receipt.entry
        val rows = mutableListOf<Pair<String, String>>()
        entry.trade?.let { line ->
            val unit = catalog.units[line.unitId]?.name.orEmpty()
            rows += s(R.string.doc_col_variety) to catalog.varieties[line.varietyId]?.name.orEmpty()
            rows += s(R.string.doc_col_quantity) to "${QuantityParser.toInput(line.quantity)} $unit"
            rows += s(R.string.receipt_pieces) to IndianFormatters.group(line.pieces)
            rows += s(R.string.receipt_rate, unit) to IndianFormatters.money(line.pricePerUnit)
            rows += s(R.string.doc_total) to IndianFormatters.money(entry.amount)
        }
        entry.payment?.let { payment ->
            rows += s(R.string.doc_col_amount) to IndianFormatters.money(entry.amount)
            rows += s(R.string.doc_col_mode) to s(modeLabel(payment.mode))
            payment.reference?.let { rows += s(R.string.doc_col_payment_ref) to it }
        }
        receipt.linked?.let { rows += linkedRow(entry, it) }
        entry.remarks?.let { rows += s(R.string.receipt_remarks) to it }
        return rows
    }

    /** The on-the-spot payment on a trade's receipt, or the trade on its payment's receipt (FR-023). */
    private fun linkedRow(entry: LedgerEntry, linked: LedgerEntry): Pair<String, String> {
        val label = if (entry.trade != null) {
            if (entry.type == EntryType.SELL) R.string.receipt_received_now else R.string.receipt_paid_now
        } else {
            R.string.receipt_against
        }
        val mode = linked.payment?.mode?.let { s(modeLabel(it)) }
        return s(label) to listOfNotNull(IndianFormatters.money(linked.amount), mode, linked.reference).joinToString(" · ")
    }

    private fun amount(m: Money) = Cell.Amount(m, IndianFormatters.money(m))

    private fun balance(m: Money): Cell = Cell.Amount(
        m,
        when (val b = Balance.of(m)) {
            is Balance.ToReceive -> s(R.string.balance_to_receive, IndianFormatters.money(b.amount))
            is Balance.ToPay -> s(R.string.balance_to_pay, IndianFormatters.money(b.amount))
            Balance.Settled -> s(R.string.balance_settled)
        },
    )
}
