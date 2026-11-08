package com.eggbusinessmanager.app.reports

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.app.MainActivity
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.dashboard.DashboardTags
import com.eggbusinessmanager.app.testing.AppRobot
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * US6-1/2/5/6 end to end: a new account shows the backup reminder and the first-customer guide; the
 * guide adds a customer, who then appears among the top customers; the period selector switches the
 * figures; "Back up now" opens Backup & restore.
 */
@RunWith(AndroidJUnit4::class)
class DashboardExtrasTest {

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

    private fun inCard(tag: String, text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun reminderGuidePeriodsAndTopCustomers() = with(robot) {
        signUp()

        // A brand-new account: never backed up, no customers yet.
        inCard(DashboardTags.BACKUP_BANNER, s(R.string.dash_backup_title))
        waitFor(s(R.string.dash_first_title), 10_000)

        // The guide adds the first customer, who owes ₹1,000 from the paper book.
        clickButton(s(R.string.customers_add))
        waitFor(s(R.string.editor_add_title), 10_000)
        type(s(R.string.label_customer_name), "Ramesh Traders")
        type(s(R.string.label_opening_amount), "1000")
        advance(s(R.string.action_save), s(R.string.customer_sell), 15_000)
        compose.onNodeWithContentDescription(s(R.string.action_back)).performClick()
        click(s(R.string.nav_dashboard))
        inCard(DashboardTags.TOP_CUSTOMERS, "Ramesh Traders")
        inCard(DashboardTags.TOP_CUSTOMERS, "₹1,000.00")
        check(!exists(s(R.string.dash_first_title))) { "The guide should go once there is a customer" }

        // The period selector: this month's net cash is shown (nothing received or paid yet).
        click(s(R.string.dash_month))
        inCard(DashboardTags.NET_CASH, s(R.string.dash_cash_detail, "₹0.00", "₹0.00"))

        // Top customer opens their page; "Back up now" opens Backup & restore.
        compose.onNode(hasText("Ramesh Traders") and hasAnyAncestor(hasTestTag(DashboardTags.TOP_CUSTOMERS)))
            .performScrollTo().performClick()
        waitFor(s(R.string.detail_transactions), 10_000) // only on the customer's page
        compose.onNodeWithContentDescription(s(R.string.action_back)).performClick()
        click(s(R.string.dash_backup_now))
        waitFor(s(R.string.backup_title), 10_000)
    }
}
