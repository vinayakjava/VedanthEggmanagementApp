package com.eggbusinessmanager.app.reports

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.app.MainActivity
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.customers.list.ADD_CUSTOMER_TAG
import com.eggbusinessmanager.app.feature.dashboard.DashboardTags
import com.eggbusinessmanager.app.feature.transactions.TX_SEARCH_TAG
import com.eggbusinessmanager.app.testing.AppRobot
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dashboard and Transactions end to end (Phase 6): figures start at zero, follow real entries, and the
 * Transactions filters (type selection, search by mobile and name, reset) change the list and its totals.
 */
@RunWith(AndroidJUnit4::class)
class DashboardAndTransactionsTest {

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

    private fun back() = compose.onNodeWithContentDescription(s(R.string.action_back)).performClick()

    /** Waits until the dashboard card tagged [tag] shows [text]. */
    private fun cardShows(tag: String, text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().isNotEmpty()
    }

    private fun addCustomer(name: String, mobile: String, opening: String, shopOwes: Boolean = false) = with(robot) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(ADD_CUSTOMER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ADD_CUSTOMER_TAG).performClick()
        waitFor(s(R.string.editor_add_title))
        type(s(R.string.label_customer_name), name)
        type(s(R.string.field_optional, s(R.string.label_mobile)), mobile)
        if (shopOwes) click(s(R.string.opening_shop_owes))
        type(s(R.string.label_opening_amount), opening)
        advance(s(R.string.action_save), s(R.string.customer_sell), 15_000)
    }

    @Test
    fun dashboardFollowsEntriesAndTransactionsFilter() = with(robot) {
        signUp()

        // A fresh book: zeros and no recent transactions.
        waitFor(s(R.string.dash_no_recent), 10_000)
        cardShows(DashboardTags.CUSTOMERS, "0")
        cardShows(DashboardTags.TODAY_SALES, "₹0.00")

        // Ramesh owes ₹1,000, buys 10 trays at ₹180 and pays ₹500.
        click(s(R.string.nav_customers))
        addCustomer("Ramesh Traders", "9876543210", "1000")
        click(s(R.string.customer_sell))
        waitFor(s(R.string.trade_sell_title))
        choose(s(R.string.label_unit), s(R.string.unit_option, "Tray", 30))
        type(s(R.string.label_quantity), "10")
        type(s(R.string.label_price_per_unit, "Tray"), "180")
        advance(s(R.string.action_save), s(R.string.trade_saved_title, "S-000001"), 15_000)
        click(s(R.string.trade_done))
        click(s(R.string.customer_receive))
        waitFor(s(R.string.label_payment_mode))
        type(s(R.string.label_amount), "500")
        advance(s(R.string.action_save), s(R.string.trade_saved_title, "RP-000001"), 15_000)
        click(s(R.string.trade_done))
        waitFor(s(R.string.balance_to_receive, "₹2,300.00"), 10_000)
        back()

        // The shop owes Farm Fresh ₹1,000 from the paper book.
        addCustomer("Farm Fresh", "9123456789", "1000", shopOwes = true)
        back()

        // Every dashboard figure is computed from those entries.
        click(s(R.string.nav_dashboard))
        cardShows(DashboardTags.CUSTOMERS, "2")
        cardShows(DashboardTags.TODAY_SALES, "₹1,800.00")
        cardShows(DashboardTags.TODAY_SALES, s(R.string.dash_trade_detail, "1", "300"))
        cardShows(DashboardTags.TOTAL_SALES, "₹1,800.00")
        cardShows(DashboardTags.TODAY_PURCHASES, "₹0.00")
        cardShows(DashboardTags.RECEIVABLES, "₹2,300.00")
        cardShows(DashboardTags.PAYABLES, "₹1,000.00")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("RP-000001", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        // Transactions: both entries (opening balances are not transactions), then filters.
        click(s(R.string.dash_see_all))
        waitFor(s(R.string.tx_count, "2"), 10_000)
        click(s(R.string.tx_type_sell)) // Sell off: only the payment remains
        waitFor(s(R.string.tx_count, "1"), 10_000)
        click(s(R.string.tx_type_sell))
        waitFor(s(R.string.tx_count, "2"), 10_000)

        compose.onNodeWithTag(TX_SEARCH_TAG).performTextInput("43210") // Ramesh's mobile
        waitFor(s(R.string.tx_count, "2"), 10_000)
        compose.onNodeWithTag(TX_SEARCH_TAG).performTextClearance()
        compose.onNodeWithTag(TX_SEARCH_TAG).performTextInput("farm") // Farm Fresh has no transactions
        waitFor(s(R.string.tx_empty_title), 10_000)
        click(s(R.string.tx_reset))
        waitFor(s(R.string.tx_count, "2"), 10_000)
    }
}
