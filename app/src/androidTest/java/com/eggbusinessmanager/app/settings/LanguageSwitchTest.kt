package com.eggbusinessmanager.app.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eggbusinessmanager.app.MainActivity
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.testing.AppRobot
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** In-app language (FR-061a): switching to Hindi and back takes effect at once, without restarting the app. */
@RunWith(AndroidJUnit4::class)
class LanguageSwitchTest {

    @get:Rule
    val compose = createEmptyComposeRule()
    private val robot = AppRobot(compose)
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun freshInstall() {
        robot.wipeLocalData()
        setLanguage("")
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        scenario.close()
        setLanguage("")
    }

    private fun setLanguage(tag: String) = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    @Test
    fun hindiAndBackFromDisplayAndPreferences() = with(robot) {
        signUp()
        click(s(R.string.nav_more))
        click(s(R.string.more_preferences))
        waitFor(s(R.string.pref_language), 10_000)

        click("हिन्दी")
        waitFor("भाषा", 15_000) // the screen itself is now in Hindi
        assertThat(AppCompatDelegate.getApplicationLocales().toLanguageTags()).isEqualTo("hi")
        waitFor("दिखावट और पसंद")

        click("English")
        waitFor("Language", 15_000)
        assertThat(AppCompatDelegate.getApplicationLocales().toLanguageTags()).isEqualTo("en")
    }
}
