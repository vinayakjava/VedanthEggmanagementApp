package com.eggbusinessmanager.app.reports

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.app.MainActivity
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.customers.list.ADD_CUSTOMER_TAG
import com.eggbusinessmanager.app.feature.reports.REPORT_PREVIEW_TAG
import com.eggbusinessmanager.app.testing.AppRobot
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reports end to end (Phase 7): the statement asks for a customer, then previews the real figures with
 * the export bar; outstanding receivables list the same customer. (The system "save as" picker itself is
 * outside the app; saving is covered by [ReportExportTest].)
 */
@RunWith(AndroidJUnit4::class)
class ReportsFlowTest {

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

    private fun previewShows(text: String) = compose.waitUntil(10_000) {
        val inPreview = hasText(text, substring = true) and hasAnyAncestor(hasTestTag(REPORT_PREVIEW_TAG))
        compose.onAllNodes(inPreview).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun statementAndReceivablesShowTheBook() = with(robot) {
        signUp()

        // A customer who owes ₹1,000 from the paper book, then a ₹1,800 sale.
        click(s(R.string.nav_customers))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(ADD_CUSTOMER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ADD_CUSTOMER_TAG).performClick()
        waitFor(s(R.string.editor_add_title))
        type(s(R.string.label_customer_name), "Ramesh Traders")
        type(s(R.string.label_opening_amount), "1000")
        advance(s(R.string.action_save), s(R.string.customer_sell), 15_000)
        click(s(R.string.customer_sell))
        waitFor(s(R.string.trade_sell_title))
        choose(s(R.string.label_unit), s(R.string.unit_option, "Tray", 30))
        type(s(R.string.label_quantity), "10")
        type(s(R.string.label_price_per_unit, "Tray"), "180")
        advance(s(R.string.action_save), s(R.string.trade_saved_title, "S-000001"), 15_000)
        click(s(R.string.trade_done))
        compose.onNodeWithContentDescription(s(R.string.action_back)).performClick()

        // Customer statement: asks for a customer, then shows opening, entries and closing balance.
        click(s(R.string.nav_reports))
        waitFor(s(R.string.rk_statement), 10_000)
        click(s(R.string.rk_statement))
        waitFor(s(R.string.rv_choose_customer), 10_000)
        click(s(R.string.rv_customer))
        waitFor("Ramesh Traders", 10_000)
        click("Ramesh Traders")
        previewShows(s(R.string.doc_customer, "Ramesh Traders"))
        previewShows(s(R.string.doc_closing))
        previewShows(s(R.string.balance_to_receive, "₹2,800.00"))
        previewShows("S-000001")
        waitFor(s(R.string.rv_export_pdf))
        waitFor(s(R.string.rv_export_xlsx))
        waitFor(s(R.string.rv_export_csv))

        // Outstanding receivables: the same customer and total.
        compose.onNodeWithContentDescription(s(R.string.action_back)).performClick()
        waitFor(s(R.string.rk_receivables), 10_000)
        click(s(R.string.rk_receivables))
        previewShows("Ramesh Traders")
        previewShows("₹2,800.00")
    }
}
