package com.eggbusinessmanager.app.trade

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.app.MainActivity
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.customers.list.ADD_CUSTOMER_TAG
import com.eggbusinessmanager.app.feature.trade.CUSTOMER_FIELD_TAG
import com.eggbusinessmanager.app.feature.trade.TOTAL_TAG
import com.eggbusinessmanager.app.testing.AppRobot
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Egg sales and purchases end to end on the real encrypted database (US3, US5-4/5, US12-3):
 * sell from a customer with a live total, buy from the dashboard adding the supplier inline, edit
 * with history, delete (void), and resize a unit.
 */
@RunWith(AndroidJUnit4::class)
class TradeFlowsTest {

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

    /** The live total (FR-021) shows [text]. */
    private fun totalShows(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text) and androidx.compose.ui.test.hasTestTag(TOTAL_TAG)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun sellBuyEditDeleteAndResize() = with(robot) {
        signUp()

        // A customer to sell to.
        click(s(R.string.nav_customers))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(ADD_CUSTOMER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ADD_CUSTOMER_TAG).performClick()
        waitFor(s(R.string.editor_add_title))
        type(s(R.string.label_customer_name), "Ramesh Traders")
        clickButton(s(R.string.action_save))
        waitFor(s(R.string.customer_sell), 15_000)

        // US3-1/2: 10 trays at ₹180 = ₹1,800.00 and 300 eggs, live; saved as S-000001.
        click(s(R.string.customer_sell))
        waitFor(s(R.string.trade_sell_title))
        waitFor("Ramesh Traders", 5_000) // customer pre-selected
        choose(s(R.string.label_unit), s(R.string.unit_option, "Tray", 30))
        type(s(R.string.label_quantity), "10")
        type(s(R.string.label_price_per_unit, "Tray"), "180")
        totalShows("₹1,800.00")
        assertThat(exists(s(R.string.trade_pieces, "300"))).isTrue()
        clickButton(s(R.string.action_save))
        waitFor(s(R.string.trade_saved_title, "S-000001"), 15_000)
        click(s(R.string.trade_done))
        waitFor(s(R.string.balance_to_receive, "₹1,800.00"), 10_000)

        // US3-3 + US3-9: buy from the dashboard, adding the supplier without leaving the Buy screen.
        back()
        click(s(R.string.nav_dashboard))
        waitFor(s(R.string.dashboard_quick_actions), 10_000)
        clickButton(s(R.string.trade_buy_title))
        waitFor(s(R.string.trade_choose_customer))
        choose(s(R.string.label_unit), s(R.string.unit_option, "Box", 210))
        type(s(R.string.label_quantity), "2")
        compose.onNodeWithTag(CUSTOMER_FIELD_TAG).scrollIfNeeded().performClick()
        click(s(R.string.trade_add_new_customer))
        waitFor(s(R.string.editor_add_title))
        type(s(R.string.label_customer_name), "Farm Fresh")
        clickButton(s(R.string.action_save))
        waitFor("Farm Fresh", 15_000)
        // The quantity typed before adding the customer is still there.
        assertThat(compose.onAllNodes(hasText("2") and hasSetTextAction()).fetchSemanticsNodes()).isNotEmpty()
        type(s(R.string.label_price_per_unit, "Box"), "1000")
        totalShows("₹2,000.00")
        assertThat(exists(s(R.string.trade_pieces, "420"))).isTrue()
        clickButton(s(R.string.action_save))
        waitFor(s(R.string.trade_saved_title, "B-000001"), 15_000)
        click(s(R.string.trade_done))

        // US5-4: edit the sale; the balance follows and the history keeps the original.
        click(s(R.string.nav_customers))
        waitFor("Ramesh Traders", 10_000)
        click("Ramesh Traders")
        click(s(R.string.detail_transactions))
        waitFor(s(R.string.entry_type_sell), 10_000)
        click(s(R.string.entry_type_sell))
        waitFor("S-000001", 10_000)
        click(s(R.string.entry_edit))
        waitFor(s(R.string.trade_edit_title, s(R.string.entry_type_sell)), 10_000)
        compose.onNode(hasText("10") and hasSetTextAction()).performTextClearance()
        type(s(R.string.label_quantity), "12")
        totalShows("₹2,160.00")
        clickButton(s(R.string.action_save))
        waitFor(s(R.string.entry_history), 15_000)
        assertThat(exists(s(R.string.entry_quantity_line, "12", "Tray", "360"))).isTrue()
        assertThat(compose.onAllNodesWithText(s(R.string.entry_revised_badge), substring = true).fetchSemanticsNodes()).isNotEmpty()

        // US5-5: delete with confirmation; the entry stays, labelled, and the balance drops to settled.
        click(s(R.string.entry_delete))
        waitFor(s(R.string.entry_delete_title, "S-000001"), 5_000)
        compose.onAllNodesWithText(s(R.string.entry_delete)).let { it[it.fetchSemanticsNodes().size - 1].performClick() }
        waitFor(s(R.string.entry_deleted_badge), 10_000)
        assertThat(exists(s(R.string.entry_cannot_change))).isTrue()
        back()
        waitFor(s(R.string.history_deleted_heading), 10_000)
        waitFor(s(R.string.balance_settled), 10_000)

        // US12-3: units are configurable; resizing shows the warning and only affects new entries.
        click(s(R.string.nav_more))
        click(s(R.string.more_units))
        waitFor(s(R.string.units_size, 210), 10_000)
        compose.onAllNodesWithText(s(R.string.units_edit)).let { it[it.fetchSemanticsNodes().size - 1].performClick() }
        waitFor(s(R.string.units_resize_warning), 5_000)
        compose.onNode(hasText("210") and hasSetTextAction()).performTextClearance()
        type(s(R.string.label_unit_pieces), "180")
        compose.onAllNodesWithText(s(R.string.action_save)).let { it[it.fetchSemanticsNodes().size - 1].performClick() }
        waitFor(s(R.string.units_size, 180), 10_000)
    }
}
