package com.eggbusinessmanager.app.trade

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.app.MainActivity
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.customers.list.ADD_CUSTOMER_TAG
import com.eggbusinessmanager.app.feature.trade.SAVED_PAID_TAG
import com.eggbusinessmanager.app.feature.trade.TOTAL_TAG
import com.eggbusinessmanager.app.testing.AppRobot
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-023 end to end: a sale with part of the money received on the spot saves the sale and a linked
 * payment, the balance shows only the rest, the two entries open each other, and deleting the sale
 * can take its payment along.
 */
@RunWith(AndroidJUnit4::class)
class PaidNowFlowTest {

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
    fun saleWithMoneyReceivedNow() = with(robot) {
        signUp()
        click(s(R.string.nav_customers))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(ADD_CUSTOMER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ADD_CUSTOMER_TAG).performClick()
        waitFor(s(R.string.editor_add_title))
        type(s(R.string.label_customer_name), "Ramesh Traders")
        advance(s(R.string.action_save), s(R.string.customer_sell), 15_000)

        // 10 trays at ₹180 = ₹1,800; ₹500 received now by UPI.
        click(s(R.string.customer_sell))
        waitFor(s(R.string.trade_sell_title))
        choose(s(R.string.label_unit), s(R.string.unit_option, "Tray", 30))
        type(s(R.string.label_quantity), "10")
        type(s(R.string.label_price_per_unit, "Tray"), "180")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("₹1,800.00") and hasTestTag(TOTAL_TAG)).fetchSemanticsNodes().isNotEmpty() }
        type(s(R.string.field_optional, s(R.string.trade_received_now)), "500")
        waitFor(s(R.string.trade_rest_to_receive, "₹1,300.00"), 5_000)
        choose(s(R.string.label_payment_mode), s(R.string.payment_mode_upi))
        advance(s(R.string.action_save), s(R.string.trade_saved_title, "S-000001"), 15_000)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(s(R.string.trade_saved_paid, "₹500.00", "RP-000001")) and hasTestTag(SAVED_PAID_TAG))
                .fetchSemanticsNodes().isNotEmpty()
        }
        click(s(R.string.trade_done))
        waitFor(s(R.string.balance_to_receive, "₹1,300.00"), 10_000)

        // Both lines are in the history; the sale opens its payment and the payment opens the sale.
        click(s(R.string.detail_transactions))
        waitFor(s(R.string.entry_type_payment_received), 10_000)
        click(s(R.string.entry_type_sell))
        waitFor(s(R.string.entry_linked_payment, "RP-000001"), 10_000)
        click(s(R.string.entry_linked_payment, "RP-000001"))
        waitFor(s(R.string.entry_linked_trade, "S-000001"), 10_000)
        click(s(R.string.entry_linked_trade, "S-000001"))
        waitFor(s(R.string.entry_linked_payment, "RP-000001"), 10_000)

        // Deleting the sale offers to delete its payment too (ticked by default): the balance is settled.
        click(s(R.string.entry_delete))
        waitFor(s(R.string.entry_delete_linked_too, "RP-000001"), 5_000)
        compose.onAllNodesWithText(s(R.string.entry_delete)).let { it[it.fetchSemanticsNodes().size - 1].performClick() }
        waitFor(s(R.string.entry_deleted_badge), 10_000)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(s(R.string.entry_deleted_badge), substring = true)).fetchSemanticsNodes().size >= 2
        }
    }
}
