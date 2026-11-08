package com.eggbusinessmanager.app.payment

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.app.MainActivity
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.customers.list.ADD_CUSTOMER_TAG
import com.eggbusinessmanager.app.testing.AppRobot
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Payments and the customer ledger end to end (US4): receive more than owed (advance confirmed), pay
 * the advance back with "Full amount", see totals and the payments-only history, then correct a
 * payment and watch the balance follow.
 */
@RunWith(AndroidJUnit4::class)
class PaymentFlowsTest {

    @get:Rule
    val compose = createEmptyComposeRule()
    private val robot = AppRobot(compose)
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun freshInstall() {
        robot.wipeLocalData()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() = scenario.close()

    private fun s(id: Int, vararg args: Any) = robot.s(id, *args)

    @Test
    fun advanceFullAmountLedgerAndCorrection() = with(robot) {
        signUp()

        // A customer who already owes ₹2,000 from the paper book.
        click(s(R.string.nav_customers))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(ADD_CUSTOMER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ADD_CUSTOMER_TAG).performClick()
        waitFor(s(R.string.editor_add_title))
        type(s(R.string.label_customer_name), "Ramesh Traders")
        type(s(R.string.label_opening_amount), "2000")
        clickButton(s(R.string.action_save))
        waitFor(s(R.string.balance_to_receive, "₹2,000.00"), 15_000)

        // US4-2: receive ₹2,500; the screen shows the ₹500 advance and asks before saving.
        click(s(R.string.customer_receive))
        waitFor(s(R.string.label_payment_mode))
        waitFor(s(R.string.payment_full_amount, "₹2,000.00"), 5_000)
        type(s(R.string.label_amount), "2500")
        waitFor(s(R.string.payment_advance), 5_000)
        assertThat(exists("₹500.00")).isTrue()
        choose(s(R.string.label_payment_mode), s(R.string.payment_mode_upi))
        clickButton(s(R.string.action_save))
        waitFor(s(R.string.payment_advance_title), 10_000)
        click(s(R.string.payment_advance_confirm))
        waitFor(s(R.string.trade_saved_title, "RP-000001"), 15_000)
        click(s(R.string.trade_done))
        waitFor(s(R.string.balance_to_pay, "₹500.00"), 10_000)

        // US4-4: pay the advance back with "Full amount"; the customer is settled.
        click(s(R.string.customer_pay))
        waitFor(s(R.string.label_payment_mode))
        waitFor(s(R.string.payment_full_amount, "₹500.00"), 5_000)
        click(s(R.string.payment_full_amount, "₹500.00"))
        clickButton(s(R.string.action_save))
        waitFor(s(R.string.trade_saved_title, "PC-000001"), 15_000)
        click(s(R.string.trade_done))
        waitFor(s(R.string.balance_settled), 10_000)

        // US4-5: the ledger shows per-type totals and the closing balance; "Payments" lists only payments.
        click(s(R.string.detail_transactions))
        waitFor(s(R.string.ledger_closing), 10_000)
        assertThat(exists(s(R.string.ledger_received))).isTrue()
        assertThat(exists("₹2,500.00")).isTrue()
        click(s(R.string.ledger_filter_payments))
        // The opening-balance entry (OB-…) leaves the list; its total line stays in the card above.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("OB-", substring = true).fetchSemanticsNodes().isEmpty() }
        assertThat(exists(s(R.string.entry_type_payment_received))).isTrue()
        assertThat(exists(s(R.string.entry_type_payment_made))).isTrue()

        // Correct the receipt to ₹2,000: the balance is recalculated from the entries.
        click(s(R.string.entry_type_payment_received))
        waitFor("RP-000001", 10_000)
        click(s(R.string.entry_edit))
        waitFor(s(R.string.payment_edit_title), 10_000)
        compose.onNode(hasText("2500") and hasSetTextAction()).performTextClearance()
        type(s(R.string.label_amount), "2000")
        clickButton(s(R.string.action_save))
        waitFor(s(R.string.entry_history), 15_000)
        assertThat(exists(s(R.string.payment_mode_upi))).isTrue()
        compose.onNode(hasText(s(R.string.action_back)) or androidx.compose.ui.test.hasContentDescription(s(R.string.action_back)))
            .performClick()
        waitFor(s(R.string.ledger_closing), 10_000)
        waitFor(s(R.string.balance_to_receive, "₹500.00"), 10_000)
    }
}
