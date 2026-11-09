package com.eggbusinessmanager.app.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eggbusinessmanager.app.BuildConfig
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.components.BrandMark
import com.eggbusinessmanager.app.ui.components.FormScreen

const val APP_VERSION_TAG = "app_version"

/** About: name, version and build, what the app does, and how data is handled (Phase 11). */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    FormScreen(title = stringResource(R.string.about_title), onBack = onBack) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BrandMark(size = 96.dp)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(APP_VERSION_TAG),
            )
            Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        Developer()
        Info(stringResource(R.string.about_privacy_title), stringResource(R.string.about_privacy_body))
        Info(stringResource(R.string.about_security_title), stringResource(R.string.about_security_body))
        Info(stringResource(R.string.about_libraries_title), stringResource(R.string.about_libraries_body))
    }
}

/** Who made the app and how to reach them; tapping the number opens the dialer (no call permission needed). */
@Composable
private fun Developer() {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().testTag(DEVELOPER_TAG)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.about_developer_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.about_developer_name), style = MaterialTheme.typography.bodyLarge)
            val phone = stringResource(R.string.about_developer_phone)
            Text(
                stringResource(R.string.about_developer_contact, phone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .wrapContentHeight()
                    .clickable(role = Role.Button) {
                        val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it == '+' || it.isDigit() }))
                        try {
                            context.startActivity(dial)
                        } catch (ignored: ActivityNotFoundException) {
                            // No phone app (e.g. a tablet without calling): the number stays visible to copy.
                        }
                    },
            )
        }
    }
}

const val DEVELOPER_TAG = "about_developer"

@Composable
private fun Info(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
