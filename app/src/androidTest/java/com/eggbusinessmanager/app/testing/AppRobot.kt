package com.eggbusinessmanager.app.testing

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.data.license.FileLicenseStore
import com.eggbusinessmanager.data.license.KeystoreDeviceIdentity
import com.eggbusinessmanager.domain.license.StoredLicense
import com.eggbusinessmanager.testing.TestLicenseKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant

/** Shared helpers for end-to-end UI tests on the real app (encrypted DB, real PBKDF2). */
class AppRobot(private val compose: ComposeTestRule) {

    val context: Context = ApplicationProvider.getApplicationContext()

    fun s(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    /**
     * Wipes local data so the next activity launch is a fresh install. Call before launching. Unless
     * [activated] is false, the phone is also licensed with a key for its own Device code signed by the
     * test key (accepted by debug builds only), so tests start at the Welcome screen as before.
     */
    fun wipeLocalData(activated: Boolean = true) {
        listOf("", "-wal", "-shm").forEach { File(context.getDatabasePath("egg_business.db").path + it).delete() }
        File(context.noBackupFilesDir, "keys").deleteRecursively()
        File(context.noBackupFilesDir, "datastore").deleteRecursively()
        File(context.noBackupFilesDir, FileLicenseStore.DIR).deleteRecursively()
        if (activated) activateThisPhone()
    }

    /** Saves a test-signed lifetime license for this emulator's Keystore identity. */
    fun activateThisPhone() = runBlocking {
        val hash = KeystoreDeviceIdentity(Dispatchers.IO).deviceHash()
        FileLicenseStore(context, Dispatchers.IO).save(StoredLicense(TestLicenseKeys.keyFor(hash), Instant.now()))
    }

    /** Waits for [text]; on timeout the failure lists what was on screen instead, to make failures readable. */
    fun waitFor(text: String, timeoutMs: Long = 60_000) {
        try {
            compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("Waited for \"$text\". On screen: ${screenTexts()}", e)
        }
    }

    /** Every text currently on screen, in tree order. */
    fun screenTexts(): String = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .mapNotNull { node ->
            val c = node.config
            listOfNotNull(
                c.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString(),
                c.getOrElseNullable(SemanticsProperties.EditableText) { null }?.let { "[typed: $it]" },
                c.getOrElseNullable(SemanticsProperties.Error) { null }?.let { "[error: $it]" },
            ).joinToString(" ").ifEmpty { null }
        }
        .filter { it.isNotBlank() }
        .joinToString(" | ")

    fun waitGone(text: String, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    fun exists(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Scrolls into view when anything on screen scrolls; otherwise just checks the node exists (same as AuthFlowTest). */
    fun SemanticsNodeInteraction.scrollIfNeeded(): SemanticsNodeInteraction {
        val scrolled = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() &&
            runCatching { performScrollTo() }.isSuccess
        if (!scrolled) assertExists()
        return this
    }

    fun type(label: String, value: String) =
        compose.onNode(hasText(label) and hasSetTextAction()).scrollIfNeeded().performTextInput(value)

    /** Replaces what is in the field labelled [label]. */
    fun clearAndType(label: String, value: String) {
        val field = compose.onNode(hasText(label) and hasSetTextAction()).scrollIfNeeded()
        field.performTextClearance()
        field.performTextInput(value)
    }

    fun click(text: String) = compose.onAllNodesWithText(text).onFirst().scrollIfNeeded().performClick()


    fun clickButton(text: String) =
        compose.onNode(hasText(text) and hasClickAction() and !hasSetTextAction()).scrollIfNeeded().performClick()

    /**
     * Opens a dropdown and picks [option]. On a busy emulator a tap right after typing is sometimes not
     * delivered (the keyboard is still animating), so the field is tapped again, after hiding the keyboard,
     * until the menu shows the option.
     */
    fun choose(fieldLabel: String, option: String) {
        retrying(until = { exists(option) }, stillThere = { exists(fieldLabel) }) {
            compose.onNodeWithText(fieldLabel).scrollIfNeeded().performClick()
        }
        compose.onAllNodesWithText(option).onFirst().performClick()
    }

    /**
     * Taps the button [text] and waits for [next] to appear, tapping again (after hiding the keyboard) if
     * the tap was not delivered. Safe for navigation and submit buttons: a tap that did land moves to the
     * next screen, so the button is never pressed twice.
     */
    fun advance(text: String, next: String, timeoutMs: Long = 15_000) {
        retrying(until = { exists(next) }, stillThere = { buttonShown(text) }) { clickButton(text) }
        waitFor(next, timeoutMs)
    }

    private fun buttonShown(text: String) =
        compose.onAllNodes(hasText(text) and hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()

    /**
     * Runs [action] until [until] holds. Taps again only while the control is [stillThere]: if it has gone,
     * the earlier tap worked and the app is just slow (e.g. hashing the password), so the caller waits.
     */
    private fun retrying(until: () -> Boolean, stillThere: () -> Boolean, attempts: Int = 3, action: () -> Unit) {
        var done = false
        var attempt = 0
        while (!done && attempt < attempts) {
            if (attempt > 0 && stillThere()) {
                Espresso.closeSoftKeyboard()
                compose.waitForIdle()
            }
            done = if (attempt > 0 && (until() || !stillThere())) {
                true
            } else {
                action()
                runCatching { compose.waitUntil(RETRY_WAIT_MS) { until() } }.isSuccess || !stillThere()
            }
            attempt++
        }
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
        advance(s(R.string.action_next), s(R.string.signup_shop_heading))
        type(s(R.string.label_shop_name), "Patil Egg Centre")
        type(s(R.string.label_city), "Nashik")
        choose(s(R.string.label_state), s(R.string.state_andhra_pradesh))
        type(s(R.string.label_pin_code), "422001")
        advance(s(R.string.action_next), s(R.string.signup_security_heading))
        type(s(R.string.label_recovery_pin), "482916")
        type(s(R.string.label_confirm_recovery_pin), "482916")
        choose(s(R.string.label_security_question, 1), s(R.string.sq_first_school))
        type(s(R.string.label_security_answer, 1), "Saraswati")
        choose(s(R.string.label_security_question, 2), s(R.string.sq_birth_town))
        type(s(R.string.label_security_answer, 2), "Nashik")
        choose(s(R.string.label_security_question, 3), s(R.string.sq_favourite_dish))
        type(s(R.string.label_security_answer, 3), "Poha")
        advance(s(R.string.signup_create), s(R.string.nav_dashboard), timeoutMs = 60_000)
    }

    private companion object {
        const val RETRY_WAIT_MS = 5_000L
    }
}
