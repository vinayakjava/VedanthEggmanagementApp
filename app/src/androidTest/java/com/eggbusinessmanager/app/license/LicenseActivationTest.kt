package com.eggbusinessmanager.app.license

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.app.MainActivity
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.license.DEVICE_CODE_TAG
import com.eggbusinessmanager.app.feature.license.LICENSE_KEY_FIELD_TAG
import com.eggbusinessmanager.app.testing.AppRobot
import com.eggbusinessmanager.domain.license.LicenseFormat
import com.eggbusinessmanager.testing.TestLicenseKeys
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lifetime license activation on the real screens: the Activation screen comes first, shows this
 * phone's Device code, refuses a key for another phone, accepts the key made for this one, and never
 * asks again; Settings → License shows Activated · Lifetime License.
 */
@RunWith(AndroidJUnit4::class)
class LicenseActivationTest {

    @get:Rule
    val compose = createEmptyComposeRule()
    private val robot = AppRobot(compose)
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun freshInstallWithoutLicense() {
        robot.wipeLocalData(activated = false)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        scenario?.close()
    }

    private fun s(id: Int, vararg args: Any) = robot.s(id, *args)

    private fun codeText(): String? = compose.onNodeWithTag(DEVICE_CODE_TAG).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun enterKey(key: String) {
        val field = compose.onNodeWithTag(LICENSE_KEY_FIELD_TAG)
        field.performTextClearance()
        field.performTextInput(key)
        robot.clickButton(s(R.string.license_activate_action))
    }

    @Test
    fun activationComesFirstAndIsAskedOnlyOnce() = with(robot) {
        waitFor(s(R.string.license_activate_title), 30_000)
        compose.waitUntil(10_000) { codeText()?.isNotBlank() == true }
        val code = checkNotNull(codeText())
        val hash = checkNotNull(LicenseFormat.parseDeviceCode(code)) { "Not a device code: $code" }

        // A genuine key, but for another phone.
        val other = hash.copyOf().also { it[0] = (it[0] + 1).toByte() }
        enterKey(TestLicenseKeys.keyFor(other))
        waitFor(s(R.string.license_error_other_device), 10_000)

        // The key made for this phone's Device code activates it; the app continues to the Welcome screen.
        enterKey(TestLicenseKeys.keyFor(hash, maxDevices = 2))
        waitFor(s(R.string.welcome_create_account), 30_000)

        // Settings → License.
        signUp()
        click(s(R.string.nav_more))
        click(s(R.string.license_title))
        waitFor(s(R.string.license_status_activated), 10_000)
        waitFor(s(R.string.license_lifetime))
        waitFor(s(R.string.license_info_line, s(R.string.license_devices_label), "2"))
        waitFor(s(R.string.license_info_line, s(R.string.license_device_code_label), code))

        // A restart does not ask again.
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // Still signed in in this process, so the app opens on the Dashboard: no Activation screen.
        waitFor(s(R.string.nav_dashboard), 30_000)
        assertThat(exists(s(R.string.license_activate_title))).isFalse()
    }
}
