package com.eggbusinessmanager.app.auth

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.app.MainActivity
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.testing.AppRobot
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end on the real encrypted database and real PBKDF2 (US1, US11), fully offline:
 * fresh install → signup → sign out → sign in with mobile → sign in with email → generic error for a
 * wrong password → lock and unlock → recover with the PIN → new password works.
 *
 * One test method on purpose: the app's singletons (database, key) live for the whole process, so
 * local data is wiped once, before the activity first starts.
 */
@RunWith(AndroidJUnit4::class)
class AuthFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun freshInstall() {
        AppRobot(compose).wipeLocalData() // fresh install, licensed with a test key for this emulator
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() = scenario.close()

    private fun s(@StringRes id: Int, vararg args: Any) = context.getString(id, *args)

    private fun waitFor(text: String, timeoutMs: Long = 60_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** Scrolls into view only when the node sits in a scrollable container (bottom-bar items do not). */
    private fun SemanticsNodeInteraction.scrollIfNeeded(): SemanticsNodeInteraction {
        val scrollable = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() &&
            runCatching { performScrollTo() }.isSuccess
        if (!scrollable) assertExists()
        return this
    }

    private fun type(label: String, value: String) =
        compose.onNode(hasText(label) and hasSetTextAction()).scrollIfNeeded().performTextInput(value)

    private fun click(text: String) = compose.onAllNodesWithText(text).onFirst().scrollIfNeeded().performClick()

    private fun clickButton(text: String) =
        compose.onNode(hasText(text) and hasClickAction() and !hasSetTextAction()).scrollIfNeeded().performClick()

    /** Opens the dropdown, tapping it again if the first tap was not delivered (busy emulator), then picks [option]. */
    private fun choose(fieldLabel: String, option: String) {
        repeat(3) {
            compose.onNodeWithText(fieldLabel).scrollIfNeeded().performClick()
            if (runCatching { waitFor(option, 5_000) }.isSuccess) {
                compose.onAllNodesWithText(option).onFirst().performClick()
                return
            }
        }
        waitFor(option, 5_000)
    }

    private fun signOut() {
        click(s(R.string.nav_more))
        click(s(R.string.more_sign_out))
        // Confirm in the dialog (the second "Sign out" on screen).
        waitFor(s(R.string.more_sign_out_confirm_title), 5_000)
        compose.onAllNodesWithText(s(R.string.more_sign_out))[1].performClick()
        waitFor(s(R.string.signin_welcome_back))
    }

    private fun signIn(identifier: String, password: String) {
        type(s(R.string.label_identifier), identifier)
        type(s(R.string.label_password), password)
        clickButton(s(R.string.signin_action))
    }

    @Test
    fun signupSignInLockAndRecover() {
        val password = "eggs2024"
        val newPassword = "fresh2025"

        // US1-1: no account → welcome with "Create account".
        waitFor(s(R.string.welcome_create_account))
        click(s(R.string.welcome_create_account))

        // Step 1: account details. Submitting empty shows inline validation first.
        waitFor(s(R.string.signup_account_heading))
        clickButton(s(R.string.action_next))
        waitFor(s(R.string.v_owner_name_required), 5_000)
        type(s(R.string.label_owner_name), "Ramesh Patil")
        type(s(R.string.label_mobile), "9876543210")
        type(s(R.string.field_optional, s(R.string.label_email)), "owner@example.com")
        type(s(R.string.label_password), password)
        type(s(R.string.label_confirm_password), password)
        clickButton(s(R.string.action_next))

        // Step 2: shop with city, state and PIN code (all required).
        waitFor(s(R.string.signup_shop_heading))
        type(s(R.string.label_shop_name), "Patil Egg Centre")
        type(s(R.string.label_city), "Nashik")
        choose(s(R.string.label_state), s(R.string.state_andhra_pradesh))
        type(s(R.string.label_pin_code), "422001")
        clickButton(s(R.string.action_next))

        // Step 3: recovery PIN + 3 security questions, then create.
        waitFor(s(R.string.signup_security_heading))
        type(s(R.string.label_recovery_pin), "482916")
        type(s(R.string.label_confirm_recovery_pin), "482916")
        choose(s(R.string.label_security_question, 1), s(R.string.sq_first_school))
        type(s(R.string.label_security_answer, 1), "Saraswati Vidyalaya")
        choose(s(R.string.label_security_question, 2), s(R.string.sq_birth_town))
        type(s(R.string.label_security_answer, 2), "Nashik")
        choose(s(R.string.label_security_question, 3), s(R.string.sq_favourite_dish))
        type(s(R.string.label_security_answer, 3), "Poha")
        clickButton(s(R.string.signup_create))

        // Lands on the dashboard; the shop shows under More.
        waitFor(s(R.string.nav_dashboard))
        click(s(R.string.nav_more))
        waitFor("Patil Egg Centre", 10_000)

        // Sign out → sign in with the mobile number (+91 format).
        signOut()
        signIn("+91 98765 43210", password)
        waitFor(s(R.string.nav_dashboard))

        // Sign out → a wrong password gets the single generic message (FR-005).
        signOut()
        signIn("owner@example.com", "wrong-pass1")
        waitFor(s(R.string.error_invalid_credentials))

        // The identifier is kept after a failure; only the password was cleared. Retry correctly.
        type(s(R.string.label_password), password)
        clickButton(s(R.string.signin_action))
        waitFor(s(R.string.nav_dashboard))

        // Lock now → overlay → unlock with the password (US1-8).
        click(s(R.string.nav_more))
        click(s(R.string.more_security))
        click(s(R.string.security_lock_now))
        waitFor(s(R.string.lock_title))
        type(s(R.string.label_password), password)
        clickButton(s(R.string.lock_unlock))
        compose.waitUntil(30_000) { compose.onAllNodesWithText(s(R.string.lock_title)).fetchSemanticsNodes().isEmpty() }

        // Forgot password → recovery PIN → new password; then review prompt (US11-3, FR-013).
        compose.onNodeWithContentDescription(s(R.string.action_back)).performClick()
        signOut()
        click(s(R.string.signin_forgot))
        click(s(R.string.recovery_with_pin))
        type(s(R.string.label_recovery_pin), "482916")
        type(s(R.string.label_new_password), newPassword)
        type(s(R.string.label_confirm_new_password), newPassword)
        clickButton(s(R.string.recovery_reset_action))
        waitFor(s(R.string.recovery_review_title))
        click(s(R.string.recovery_review_later))

        // The new password works and the old one does not.
        signOut()
        signIn("9876543210", password)
        waitFor(s(R.string.error_invalid_credentials))
        type(s(R.string.label_password), newPassword)
        clickButton(s(R.string.signin_action))
        waitFor(s(R.string.nav_dashboard))
    }
}
