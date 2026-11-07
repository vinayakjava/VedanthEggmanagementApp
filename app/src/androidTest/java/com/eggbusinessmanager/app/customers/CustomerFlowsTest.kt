package com.eggbusinessmanager.app.customers

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import com.eggbusinessmanager.app.feature.customers.list.SEARCH_TAG
import com.eggbusinessmanager.app.testing.AppRobot
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Customer management end to end on the real encrypted database (US2): add with an opening balance,
 * balance display, duplicate mobile with "open existing", search by name and mobile, the empty search
 * offer, delete of an unused customer, delete refused for a customer with entries, deactivate with
 * the outstanding warning, activate, edit of the opening balance, and the transaction history.
 */
@RunWith(AndroidJUnit4::class)
class CustomerFlowsTest {

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

    /** The list screen is back; its search box may hold text, so wait for the box itself. */
    private fun waitForList() = compose.waitUntil(10_000) { compose.onAllNodesWithTag(SEARCH_TAG).fetchSemanticsNodes().isNotEmpty() }

    private fun search(text: String) {
        compose.onNodeWithTag(SEARCH_TAG).performTextClearance()
        if (text.isNotEmpty()) compose.onNodeWithTag(SEARCH_TAG).performTextInput(text)
    }

    private fun addCustomer(name: String, mobile: String = "", opening: String = "", shopOwes: Boolean = false): Unit = with(robot) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(ADD_CUSTOMER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ADD_CUSTOMER_TAG).performClick()
        waitFor(s(R.string.editor_add_title))
        type(s(R.string.label_customer_name), name)
        if (mobile.isNotEmpty()) type(s(R.string.field_optional, s(R.string.label_mobile)), mobile)
        if (shopOwes) click(s(R.string.opening_shop_owes))
        if (opening.isNotEmpty()) type(s(R.string.label_opening_amount), opening)
        clickButton(s(R.string.action_save))
        Unit
    }

    @Test
    fun manageAndFindCustomers() = with(robot) {
        signUp()
        click(s(R.string.nav_customers))
        waitFor(s(R.string.customers_empty_title), 10_000)

        // US2-2: opening balance "Customer owes me" ₹1,500 → "To receive ₹1,500.00".
        addCustomer("Ramesh Traders", mobile = "9876543210", opening = "1500")
        waitFor(s(R.string.balance_to_receive, "₹1,500.00"), 15_000)
        back()
        waitFor("Ramesh Traders", 10_000)
        assertThat(exists(s(R.string.balance_to_receive, "₹1,500.00"))).isTrue()

        // The same customer can be a supplier too: a second customer we owe money to.
        addCustomer("Shyam Poultry", mobile = "9123456789", opening = "2,000.50", shopOwes = true)
        waitFor(s(R.string.balance_to_pay, "₹2,000.50"), 15_000)
        back()

        // US2-3: a duplicate mobile is blocked with the owner's name and an "open" action.
        addCustomer("Someone Else", mobile = "98765 43210")
        waitFor(s(R.string.error_duplicate_mobile, "Ramesh Traders"), 15_000)
        click(s(R.string.action_open_customer, "Ramesh Traders"))
        waitFor(s(R.string.customer_current_balance), 10_000)
        back()

        // US2-4: search by part of the name (any case) and by part of the mobile.
        waitForList()
        search("TRADER")
        waitGone("Shyam Poultry")
        assertThat(exists("Ramesh Traders")).isTrue()
        search("91234")
        waitGone("Ramesh Traders")
        assertThat(exists("Shyam Poultry")).isTrue()

        // US2-5: no match offers to add the typed text, which pre-fills the editor.
        search("Gopal")
        waitFor(s(R.string.customers_none_found), 10_000)
        click(s(R.string.customers_add_typed, "Gopal"))
        waitFor(s(R.string.editor_add_title))
        clickButton(s(R.string.action_save))
        waitFor(s(R.string.balance_settled), 15_000)

        // US2-7: a customer with no entries can be deleted.
        click(s(R.string.detail_delete))
        waitFor(s(R.string.delete_title, "Gopal"), 5_000)
        compose.onAllNodesWithText(s(R.string.detail_delete))[1].performClick()
        waitForList()
        search("")
        waitGone("Gopal")

        // US2-6/8: delete is refused for a customer with entries; deactivate states the outstanding amount.
        click("Ramesh Traders")
        waitFor(s(R.string.customer_current_balance), 10_000)
        click(s(R.string.detail_delete))
        waitFor(s(R.string.delete_blocked_title), 5_000)
        compose.onAllNodesWithText(s(R.string.detail_deactivate)).let { it[it.fetchSemanticsNodes().size - 1].performClick() }
        waitFor(s(R.string.deactivate_title, "Ramesh Traders"), 5_000)
        val outstanding = s(R.string.deactivate_outstanding, s(R.string.balance_to_receive, "₹1,500.00"))
        assertThat(compose.onAllNodesWithText(outstanding, substring = true).fetchSemanticsNodes()).isNotEmpty()
        compose.onAllNodesWithText(s(R.string.detail_deactivate)).let { it[it.fetchSemanticsNodes().size - 1].performClick() }
        waitFor(s(R.string.detail_activate), 10_000)
        back()
        waitGone("Ramesh Traders")
        click(s(R.string.filter_deactivated))
        waitFor("Ramesh Traders", 10_000)

        // Activate again, then edit the opening balance: the balance follows (FR-019).
        click("Ramesh Traders")
        click(s(R.string.detail_activate))
        waitFor(s(R.string.detail_deactivate), 10_000)
        click(s(R.string.detail_edit))
        waitFor(s(R.string.editor_edit_title))
        compose.onNode(hasText("1500") and hasSetTextAction()).performTextClearance()
        type(s(R.string.label_opening_amount), "2000")
        clickButton(s(R.string.action_save))
        waitFor(s(R.string.balance_to_receive, "₹2,000.00"), 15_000)

        // Transaction history: the opening balance entry with its reference and running balance.
        click(s(R.string.detail_transactions))
        waitFor(s(R.string.entry_type_opening), 10_000)
        assertThat(compose.onAllNodesWithText("OB-000001", substring = true).fetchSemanticsNodes()).hasSize(1)
        assertThat(exists(s(R.string.history_balance_after, s(R.string.balance_to_receive, "₹2,000.00")))).isTrue()
    }
}
