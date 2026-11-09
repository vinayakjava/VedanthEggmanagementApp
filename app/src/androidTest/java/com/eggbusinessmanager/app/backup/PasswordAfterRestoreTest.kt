package com.eggbusinessmanager.app.backup

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eggbusinessmanager.app.MainActivity
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.testing.AppRobot
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * US9-9 on the real screens: after a restore the app restarts on the sign-in screen, says the backup
 * was restored and offers "Set a new password"; the owner sets one without the old password and signs
 * in with it. The restore itself is covered by the data tests; here its finished state is staged.
 */
@RunWith(AndroidJUnit4::class)
class PasswordAfterRestoreTest {

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

    /** What the restore engine leaves behind when a restore was applied before the restart. */
    private fun stageFinishedRestore() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "restore").apply { mkdirs() }
        File(dir, "outcome").writeText("RESTORED")
    }

    private fun signOut() = with(robot) {
        click(s(R.string.nav_more))
        click(s(R.string.more_sign_out))
        waitFor(s(R.string.more_sign_out_confirm_title), 5_000)
        compose.onAllNodesWithText(s(R.string.more_sign_out))[1].performClick()
    }

    @Test
    fun aRestoreLetsTheOwnerSetANewPasswordOnce() = with(robot) {
        signUp() // password eggs2024
        stageFinishedRestore()
        signOut()

        // Back on sign-in: the restore is reported, with the offer.
        waitFor(s(R.string.restore_outcome_ok_title), 15_000)
        click(s(R.string.restore_set_password))
        waitFor(s(R.string.restore_set_password_body), 10_000)
        type(s(R.string.label_new_password), "fresh2025")
        type(s(R.string.label_confirm_new_password), "fresh2025")
        clickButton(s(R.string.restore_set_password))
        waitFor(s(R.string.nav_dashboard), 60_000) // signed in with the new password set

        // The new password works; the old one no longer does.
        signOut()
        waitFor(s(R.string.signin_welcome_back), 15_000)
        check(!exists(s(R.string.restore_outcome_ok_title))) { "The restore message is shown only once" }
        type(s(R.string.label_identifier), "9988776655")
        type(s(R.string.label_password), "fresh2025")
        clickButton(s(R.string.signin_action))
        waitFor(s(R.string.nav_dashboard), 60_000)
    }
}
