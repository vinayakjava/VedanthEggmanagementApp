package com.eggbusinessmanager.app

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.app.feature.customers.list.ADD_CUSTOMER_TAG
import com.eggbusinessmanager.app.feature.reports.REPORT_PREVIEW_TAG
import com.eggbusinessmanager.app.feature.settings.APP_VERSION_TAG
import com.eggbusinessmanager.app.feature.trade.TOTAL_TAG
import com.eggbusinessmanager.app.testing.AppRobot
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.domain.settings.AppPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The complete application, end to end on a fresh install (Phase 11): sign up, edit the shop and owner
 * profiles, change display preferences (currency, grouping, date format, default unit), add a customer,
 * sell with the default unit, receive a payment, read the statement in the chosen formats, set auto-lock,
 * check About, sign out and sign back in to find everything still there.
 */
@RunWith(AndroidJUnit4::class)
class CompleteAppTest {

    @get:Rule
    val compose = createEmptyComposeRule()
    private val robot = AppRobot(compose)
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun freshInstall() {
        robot.wipeLocalData()
        IndianFormatters.preferences = AppPreferences()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        scenario.close()
        IndianFormatters.preferences = AppPreferences()
    }

    private fun s(id: Int, vararg args: Any) = robot.s(id, *args)

    private fun back() = compose.onNodeWithContentDescription(s(R.string.action_back)).performClick()

    private fun shows(text: String, timeoutMs: Long = 10_000) = compose.waitUntil(timeoutMs) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun everyAreaOfTheAppWorksTogether() = with(robot) {
        signUp()

        // Shop and owner profile.
        click(s(R.string.nav_more))
        click(s(R.string.more_shop_profile))
        waitFor(s(R.string.label_shop_name), 10_000)
        clearAndType(s(R.string.label_shop_name), "Patil Eggs and Sons")
        clickButton(s(R.string.action_save))
        waitFor("Patil Eggs and Sons", 15_000) // back on More, in the header
        click(s(R.string.more_owner_profile))
        waitFor(s(R.string.owner_password_note), 10_000)
        clearAndType(s(R.string.label_owner_name), "Sunil Patil")
        advance(s(R.string.action_save), s(R.string.more_preferences), 15_000)

        // Display preferences: Rs., international grouping, ISO dates, Tray as the default unit.
        click(s(R.string.more_preferences))
        waitFor(s(R.string.pref_currency), 10_000)
        click("Rs.")
        shows(s(R.string.pref_example, "Rs.1,23,456.78"))
        click(s(R.string.pref_grouping_international))
        shows(s(R.string.pref_example, "Rs.123,456.78"))
        click(LocalDate.now().toString()) // the ISO option shows today's date
        choose(s(R.string.pref_default_unit), s(R.string.unit_option, "Tray", 30))
        compose.waitUntil(15_000) { IndianFormatters.preferences.defaultUnitId != null }
        back()

        // A customer, a sale with the default unit, and a payment — all shown in the chosen formats.
        click(s(R.string.nav_customers))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(ADD_CUSTOMER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ADD_CUSTOMER_TAG).performClick()
        waitFor(s(R.string.editor_add_title))
        type(s(R.string.label_customer_name), "Ramesh Traders")
        type(s(R.string.label_opening_amount), "1000")
        advance(s(R.string.action_save), s(R.string.balance_to_receive, "Rs.1,000.00"), 15_000)
        click(s(R.string.customer_sell))
        waitFor(s(R.string.trade_sell_title))
        waitFor(s(R.string.label_price_per_unit, "Tray"), 10_000) // Tray pre-selected
        type(s(R.string.label_quantity), "10")
        type(s(R.string.label_price_per_unit, "Tray"), "180")
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Rs.1,800.00") and hasTestTag(TOTAL_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        advance(s(R.string.action_save), s(R.string.trade_saved_title, "S-000001"), 15_000)
        click(s(R.string.trade_done))
        click(s(R.string.customer_receive))
        waitFor(s(R.string.label_payment_mode))
        type(s(R.string.label_amount), "500")
        advance(s(R.string.action_save), s(R.string.trade_saved_title, "RP-000001"), 15_000)
        click(s(R.string.trade_done))
        waitFor(s(R.string.balance_to_receive, "Rs.2,300.00"), 10_000)

        // The statement uses the same formats (ISO dates, Rs.).
        click(s(R.string.detail_statement))
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText(s(R.string.balance_to_receive, "Rs.2,300.00")) and hasAnyAncestor(hasTestTag(REPORT_PREVIEW_TAG)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(compose.onAllNodes(hasText(LocalDate.now().toString(), substring = true)).fetchSemanticsNodes()).isNotEmpty()
        assertThat(exists("Patil Eggs and Sons")).isTrue() // letterhead uses the edited shop name
        back()
        back()

        // Security: auto-lock; About: version.
        click(s(R.string.nav_more))
        click(s(R.string.more_security))
        choose(s(R.string.security_auto_lock), s(R.string.security_auto_lock_option, 15))
        waitFor(s(R.string.security_auto_lock_option, 15))
        back()
        click(s(R.string.more_about))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(APP_VERSION_TAG).fetchSemanticsNodes().isNotEmpty() }
        shows(s(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        shows("Vinayak Metagoli")
        shows(s(R.string.about_developer_contact, "+91 7848971006"))
        back()

        // Sign out and back in: everything is still there.
        click(s(R.string.more_sign_out))
        waitFor(s(R.string.more_sign_out_confirm_title), 5_000)
        compose.onAllNodesWithText(s(R.string.more_sign_out))[1].performClick()
        waitFor(s(R.string.signin_welcome_back), 15_000)
        type(s(R.string.label_identifier), "9988776655")
        type(s(R.string.label_password), "eggs2024")
        clickButton(s(R.string.signin_action))
        waitFor(s(R.string.nav_dashboard), 60_000)
        click(s(R.string.nav_customers))
        waitFor("Ramesh Traders", 10_000)
        shows("Rs.2,300.00")
    }
}
