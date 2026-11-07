package com.eggbusinessmanager.app.testing

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.eggbusinessmanager.app.R
import java.io.File

/** Shared helpers for end-to-end UI tests on the real app (encrypted DB, real PBKDF2). */
class AppRobot(private val compose: ComposeTestRule) {

    val context: Context = ApplicationProvider.getApplicationContext()

    fun s(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    /** Wipes local data so the next activity launch is a fresh install. Call before launching. */
    fun wipeLocalData() {
        listOf("", "-wal", "-shm").forEach { File(context.getDatabasePath("egg_business.db").path + it).delete() }
        File(context.noBackupFilesDir, "keys").deleteRecursively()
        File(context.noBackupFilesDir, "datastore").deleteRecursively()
    }

    fun waitFor(text: String, timeoutMs: Long = 60_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    fun waitGone(text: String, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    fun exists(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /**
     * Tries to scroll the node into view. Floating buttons and bottom-bar items cannot be scrolled to;
     * that failure is ignored, and the following action reports a genuinely missing node.
     */
    fun SemanticsNodeInteraction.scrollIfNeeded(): SemanticsNodeInteraction {
        runCatching { performScrollTo() }
        return this
    }

    fun type(label: String, value: String) =
        compose.onNode(hasText(label) and hasSetTextAction()).scrollIfNeeded().performTextInput(value)

    fun click(text: String) = compose.onAllNodesWithText(text).onFirst().scrollIfNeeded().performClick()

    fun clickButton(text: String) =
        compose.onNode(hasText(text) and hasClickAction() and !hasSetTextAction()).scrollIfNeeded().performClick()

    fun choose(fieldLabel: String, option: String) {
        compose.onNodeWithText(fieldLabel).scrollIfNeeded().performClick()
        waitFor(option, 5_000)
        compose.onAllNodesWithText(option).onFirst().performClick()
    }

    /** Creates the owner account through the real signup screens and waits for the dashboard. */
    fun signUp(password: String = "eggs2024") {
        waitFor(s(R.string.welcome_create_account))
        click(s(R.string.welcome_create_account))
        waitFor(s(R.string.signup_account_heading))
        type(s(R.string.label_owner_name), "Ramesh Patil")
        type(s(R.string.label_mobile), "9988776655")
        type(s(R.string.label_password), password)
        type(s(R.string.label_confirm_password), password)
        clickButton(s(R.string.action_next))
        waitFor(s(R.string.signup_shop_heading))
        type(s(R.string.label_shop_name), "Patil Egg Centre")
        type(s(R.string.label_city), "Nashik")
        choose(s(R.string.label_state), s(R.string.state_andhra_pradesh))
        type(s(R.string.label_pin_code), "422001")
        clickButton(s(R.string.action_next))
        waitFor(s(R.string.signup_security_heading))
        type(s(R.string.label_recovery_pin), "482916")
        type(s(R.string.label_confirm_recovery_pin), "482916")
        choose(s(R.string.label_security_question, 1), s(R.string.sq_first_school))
        type(s(R.string.label_security_answer, 1), "Saraswati")
        choose(s(R.string.label_security_question, 2), s(R.string.sq_birth_town))
        type(s(R.string.label_security_answer, 2), "Nashik")
        choose(s(R.string.label_security_question, 3), s(R.string.sq_favourite_dish))
        type(s(R.string.label_security_answer, 3), "Poha")
        clickButton(s(R.string.signup_create))
        waitFor(s(R.string.nav_dashboard))
    }
}
