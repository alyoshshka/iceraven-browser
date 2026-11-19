/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.compose.base.button.TextButton
import mozilla.components.feature.prompts.R as promptsR
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.IntentReceiverActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Profiles: translucent activity shown over the app that opened a link, asking which profile to open it in.
 * IntentReceiverActivity finishes immediately, so it starts this one when [ProfileLinkIntentProcessor] asks for it.
 * The choice re-enters IntentReceiverActivity with our profile extras, which open the link in a new tab.
 */
class ProfileChooserActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.dataString
        val host = url?.let { runCatching { it.toUri().host }.getOrNull() }
        if (url == null || host.isNullOrEmpty()) {
            finish()
            return
        }
        val storage = ProfileStorage(this)

        setContent {
            FirefoxTheme {
                val containers by components.core.store.observeAsComposableState { it.containers }
                val profiles = remember(containers) {
                    containers.orEmpty().values.sortedBy { it.name.lowercase() }
                }
                ProfileChooserDialog(
                    host = host,
                    profiles = profiles,
                    onChosen = { contextId, always ->
                        if (always) storage.setRule(host, contextId)
                        openLink(url, contextId)
                        finish()
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    private fun openLink(url: String, contextId: String?) {
        val open = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            setClass(this@ProfileChooserActivity, IntentReceiverActivity::class.java)
            putExtra(EXTRA_PROFILE_SKIP_ASK, true)
            if (contextId != null) {
                putExtra(EXTRA_PROFILE_CONTEXT_ID, contextId)
                putExtra(EXTRA_PROFILE_DESKTOP, false)
                putExtra(EXTRA_PROFILE_NEW_TAB, true)
            }
        }
        startActivity(open)
    }

    companion object {
        /** Intent that shows the chooser for the http(s) link in [source]. */
        fun createIntent(context: Context, source: Intent): Intent =
            Intent(context, ProfileChooserActivity::class.java).apply { data = source.data }
    }
}

/**
 * "Open link in" dialog: Default and each profile as radio rows, an "Always open <host> in this profile" checkbox and
 * Cancel / Open buttons.
 *
 * @param host Host of the link, used in the checkbox label.
 * @param profiles The profiles to list, sorted by name.
 * @param onChosen Invoked with the chosen contextId (null for Default) and whether to remember it for [host].
 * @param onDismiss Invoked when the dialog is cancelled.
 */
@Composable
fun ProfileChooserDialog(
    host: String,
    profiles: List<ContainerState>,
    onChosen: (contextId: String?, always: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var always by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.profiles_open_link_in),
                style = FirefoxTheme.typography.headline5,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ChooserRow(
                    label = stringResource(R.string.profiles_default_name),
                    dotColor = null,
                    selected = selected == null,
                    onClick = { selected = null },
                )
                profiles.forEach { profile ->
                    ChooserRow(
                        label = profile.name,
                        dotColor = profile.color,
                        selected = selected == profile.contextId,
                        onClick = { selected = profile.contextId },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Checkbox) { always = !always }
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = always, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text(
                        text = stringResource(R.string.profiles_always_open_in_profile, host),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = FirefoxTheme.typography.body2,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                text = stringResource(R.string.profiles_open_link_confirm),
                onClick = { onChosen(selected, always) },
            )
        },
        dismissButton = {
            TextButton(
                text = stringResource(promptsR.string.mozac_feature_prompts_cancel),
                onClick = onDismiss,
            )
        },
    )
}

@Composable
private fun ChooserRow(
    label: String,
    dotColor: ContainerState.Color?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        if (dotColor != null) {
            ProfileDot(color = dotColor.toComposeColor())
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = FirefoxTheme.typography.subtitle1,
        )
    }
}
