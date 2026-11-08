package com.eggbusinessmanager.app.receipt

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eggbusinessmanager.app.MainActivity
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.customers.list.ADD_CUSTOMER_TAG
import com.eggbusinessmanager.app.feature.trade.TOTAL_TAG
import com.eggbusinessmanager.app.testing.AppRobot
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-042a: "Share receipt" from the save confirmation and from the entry screen opens the Sharesheet
 * with a one-page PDF receipt only (no text), readable through the granted URI.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptShareTest {

    @get:Rule
    val compose = createEmptyComposeRule()
    private val robot = AppRobot(compose)
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun freshInstall() {
        robot.wipeLocalData()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        Intents.init()
        // Stand in for the system Sharesheet so the test stays in the app.
        Intents.intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After
    fun close() {
        Intents.release()
        scenario.close()
    }

    private fun s(id: Int, vararg args: Any) = robot.s(id, *args)

    private fun choosers() = Intents.getIntents().filter { it.action == Intent.ACTION_CHOOSER }

    /** The PDF sent by chooser number [count]: checked to be a PDF only, granted, and one page. */
    @Suppress("DEPRECATION")
    private fun sharedReceipt(count: Int, namePrefix: String) {
        compose.waitUntil(15_000) { choosers().size >= count }
        val send = choosers()[count - 1].getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("application/pdf")
        assertThat(send.hasExtra(Intent.EXTRA_TEXT)).isFalse() // receipts are PDF only
        assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertThat(uri.lastPathSegment).startsWith(namePrefix)
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val pages = resolver.openFileDescriptor(uri, "r")!!.use { fd -> PdfRenderer(fd).use { it.pageCount } }
        assertThat(pages).isEqualTo(1)
    }

    @Test
    fun receiptsAreSharedFromTheSaveConfirmationAndTheEntryScreen() = with(robot) {
        signUp()
        click(s(R.string.nav_customers))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(ADD_CUSTOMER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ADD_CUSTOMER_TAG).performClick()
        waitFor(s(R.string.editor_add_title))
        type(s(R.string.label_customer_name), "Ramesh Traders")
        advance(s(R.string.action_save), s(R.string.customer_sell), 15_000)

        // A sale with ₹500 received now; share its receipt from the confirmation.
        click(s(R.string.customer_sell))
        waitFor(s(R.string.trade_sell_title))
        choose(s(R.string.label_unit), s(R.string.unit_option, "Tray", 30))
        type(s(R.string.label_quantity), "10")
        type(s(R.string.label_price_per_unit, "Tray"), "180")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("₹1,800.00") and hasTestTag(TOTAL_TAG)).fetchSemanticsNodes().isNotEmpty() }
        type(s(R.string.field_optional, s(R.string.trade_received_now)), "500")
        advance(s(R.string.action_save), s(R.string.trade_saved_title, "S-000001"), 15_000)
        click(s(R.string.receipt_share))
        sharedReceipt(1, "Sale-receipt_S-000001_Ramesh-Traders")
        click(s(R.string.trade_done))

        // From the entry screen: the payment taken with the sale has its own receipt.
        click(s(R.string.detail_transactions))
        waitFor(s(R.string.entry_type_payment_received), 10_000)
        click(s(R.string.entry_type_payment_received))
        waitFor(s(R.string.entry_linked_trade, "S-000001"), 10_000)
        click(s(R.string.receipt_share))
        sharedReceipt(2, "Payment-receipt_RP-000001_Ramesh-Traders")
    }
}
