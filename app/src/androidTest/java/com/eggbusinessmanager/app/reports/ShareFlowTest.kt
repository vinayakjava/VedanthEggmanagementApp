package com.eggbusinessmanager.app.reports

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eggbusinessmanager.app.MainActivity
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.customers.list.ADD_CUSTOMER_TAG
import com.eggbusinessmanager.app.feature.reports.REPORT_PREVIEW_TAG
import com.eggbusinessmanager.app.feature.reports.SHARE_PDF_TAG
import com.eggbusinessmanager.app.feature.reports.SHARE_TEXT_TAG
import com.eggbusinessmanager.app.testing.AppRobot
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sharing from the app (Phase 8): from a customer's page to the statement, then Share PDF and Share as
 * text open the Android Sharesheet. The Sharesheet itself is system UI, so the test intercepts the
 * chooser intent and checks exactly what any target app (WhatsApp, Gmail, Bluetooth, Quick Share…)
 * would receive — including that the attached PDF is readable through the granted URI.
 */
@RunWith(AndroidJUnit4::class)
class ShareFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()
    private val robot = AppRobot(compose)
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun freshInstall() {
        robot.wipeLocalData()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        Intents.init()
        // Stand in for the system Sharesheet so the test stays in the app.
        Intents.intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After
    fun close() {
        Intents.release()
        scenario.close()
    }

    private fun s(id: Int, vararg args: Any) = robot.s(id, *args)

    private fun lastChooser(): Intent {
        compose.waitUntil(10_000) { Intents.getIntents().any { it.action == Intent.ACTION_CHOOSER } }
        return Intents.getIntents().last { it.action == Intent.ACTION_CHOOSER }
    }

    @Suppress("DEPRECATION")
    private fun Intent.target(): Intent = getParcelableExtra(Intent.EXTRA_INTENT)!!

    @Test
    fun statementIsSharedAsPdfWithSummaryAndAsText() = with(robot) {
        signUp()
        click(s(R.string.nav_customers))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(ADD_CUSTOMER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ADD_CUSTOMER_TAG).performClick()
        waitFor(s(R.string.editor_add_title))
        type(s(R.string.label_customer_name), "Ramesh Traders")
        type(s(R.string.label_opening_amount), "1000")
        advance(s(R.string.action_save), s(R.string.detail_statement), 15_000)

        // Customer page → Statement opens with the customer already chosen.
        click(s(R.string.detail_statement))
        compose.waitUntil(15_000) {
            val closing = hasText(s(R.string.doc_closing)) and hasAnyAncestor(hasTestTag(REPORT_PREVIEW_TAG))
            compose.onAllNodes(closing).fetchSemanticsNodes().isNotEmpty()
        }

        // Share PDF → Sharesheet with the PDF, a read grant and the readable summary.
        compose.onNodeWithTag(SHARE_PDF_TAG).performClick()
        val pdf = lastChooser().target()
        assertThat(pdf.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(pdf.type).isEqualTo("application/pdf")
        assertThat(pdf.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        @Suppress("DEPRECATION")
        val uri = pdf.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertThat(uri.toString()).startsWith("content://com.eggbusinessmanager.app.fileprovider/")
        assertThat(uri.lastPathSegment).startsWith("Customer-statement_Ramesh-Traders_")
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        assertThat(resolver.openInputStream(uri)!!.use { it.readBytes() }.copyOfRange(0, 5).decodeToString()).isEqualTo("%PDF-")
        val text = pdf.getStringExtra(Intent.EXTRA_TEXT)!!
        assertThat(text).contains("Ramesh Traders")
        assertThat(text).contains(s(R.string.doc_closing) + ": " + s(R.string.balance_to_receive, "₹1,000.00"))
        assertThat(text).endsWith(s(R.string.share_pdf_attached))
        assertThat(pdf.getStringExtra(Intent.EXTRA_SUBJECT)).startsWith(s(R.string.rk_statement) + " – Ramesh Traders")

        // Share as text → plain text only.
        compose.onNodeWithTag(SHARE_TEXT_TAG).performClick()
        compose.waitUntil(10_000) { Intents.getIntents().count { it.action == Intent.ACTION_CHOOSER } == 2 }
        val plain = lastChooser().target()
        assertThat(plain.type).isEqualTo("text/plain")
        assertThat(plain.hasExtra(Intent.EXTRA_STREAM)).isFalse()
        assertThat(plain.getStringExtra(Intent.EXTRA_TEXT)).contains(s(R.string.balance_to_receive, "₹1,000.00"))
    }
}
