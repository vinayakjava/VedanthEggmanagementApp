package com.eggbusinessmanager.app.backup

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eggbusinessmanager.app.MainActivity
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.backup.BACKUP_NOW_TAG
import com.eggbusinessmanager.app.feature.backup.RESTORE_CONFIRM_TAG
import com.eggbusinessmanager.app.testing.AppRobot
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Manual backup and the restore preview end to end (Phase 9). Android's "Save as" and "Open" pickers are
 * system UI, so the test answers them with a file in the app's cache. Confirming the restore restarts the
 * app, which an instrumented test cannot survive; the swap itself is covered by the data module's
 * BackupRestoreTest. Here the test checks that nothing can be replaced without the explicit acknowledgement.
 */
@RunWith(AndroidJUnit4::class)
class BackupFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()
    private val robot = AppRobot(compose)
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val target = File(context.cacheDir, "picked/EggBusiness_test.ebmbackup")

    @Before
    fun freshInstall() {
        robot.wipeLocalData()
        listOf("backup", "safety-backups", "restore").forEach { File(context.noBackupFilesDir, it).deleteRecursively() }
        target.parentFile?.mkdirs()
        target.delete()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        Intents.init()
        val picked = Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(target)))
        Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(picked)
        Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(picked)
    }

    @After
    fun close() {
        Intents.release()
        scenario.close()
    }

    private fun s(id: Int, vararg args: Any) = robot.s(id, *args)

    @Test
    fun setPassphraseBackUpToThePhoneAndPreviewARestore() = with(robot) {
        signUp()
        click(s(R.string.nav_more))
        waitFor(s(R.string.more_backup), 10_000)
        click(s(R.string.more_backup))
        waitFor(s(R.string.backup_last_none), 10_000)
        compose.onNodeWithTag(BACKUP_NOW_TAG).performScrollTo().assertIsNotEnabled() // no passphrase yet

        // Passphrase: the login password is refused, then a good one is saved (slow key derivation).
        click(s(R.string.backup_passphrase_set))
        waitFor(s(R.string.backup_passphrase_body), 5_000)
        type(s(R.string.label_backup_passphrase), "eggs2024")
        type(s(R.string.label_backup_passphrase_confirm), "eggs2024")
        click(s(R.string.action_save))
        waitFor(s(R.string.v_pp_same), 30_000)
        clearAndType(s(R.string.label_backup_passphrase), "Ramesh-backup-26")
        clearAndType(s(R.string.label_backup_passphrase_confirm), "Ramesh-backup-26")
        click(s(R.string.action_save))
        waitFor(s(R.string.backup_pp_saved), 60_000)

        // Back up to the phone: the picked file receives an encrypted backup and the status updates.
        compose.onNodeWithTag(BACKUP_NOW_TAG).performScrollTo().assertIsEnabled().performClick()
        val saved = s(R.string.backup_done, "").trim()
        compose.waitUntil(60_000) { compose.onAllNodes(hasText(saved, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertThat(target.length()).isGreaterThan(1_000L)
        assertThat(target.readBytes().copyOf(6).decodeToString()).isEqualTo("EBMBAK")
        assertThat(String(target.readBytes(), Charsets.ISO_8859_1)).doesNotContain("Patil Egg Centre")
        waitGone(s(R.string.backup_last_none))

        // Restore from that file: a wrong passphrase is refused…
        click(s(R.string.restore_from_device))
        waitFor(s(R.string.restore_passphrase_title), 10_000)
        type(s(R.string.label_backup_passphrase), "wrong-passphrase")
        click(s(R.string.restore_check))
        waitFor(s(R.string.bf_wrong_passphrase), 60_000)
        // …the right one shows what is in the backup next to the phone's data.
        clearAndType(s(R.string.label_backup_passphrase), "Ramesh-backup-26")
        click(s(R.string.restore_check))
        waitFor(s(R.string.restore_preview_title), 60_000)
        waitFor(s(R.string.restore_shop, "Patil Egg Centre"))
        assertThat(exists(s(R.string.restore_warning))).isTrue()

        // Replacing needs the explicit acknowledgement.
        compose.onNodeWithTag(RESTORE_CONFIRM_TAG).assertIsNotEnabled()
        click(s(R.string.restore_understand))
        compose.onNodeWithTag(RESTORE_CONFIRM_TAG).assertIsEnabled()
        click(s(R.string.action_cancel))
        waitGone(s(R.string.restore_preview_title))
    }
}
