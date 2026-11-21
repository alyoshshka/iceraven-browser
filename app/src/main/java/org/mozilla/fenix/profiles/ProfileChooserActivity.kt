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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import mozilla.components.browser.state.action.ContainerAction
import mozilla.components.compose.base.button.FilledButton
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.compose.list.IconListItem
import java.util.UUID
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
                    url = url,
                    host = host,
                    profiles = profiles,
                    defaultColor = firstUnusedColor(profiles),
                    onChosen = { contextId, always ->
                        if (always) storage.setRule(host, contextId)
                        openLink(url, contextId)
                        finish()
                    },
                    onCreate = { name, color, emoji, icon, always ->
                        val contextId = UUID.randomUUID().toString()
                        storage.setName(contextId, name)
                        val containerColor = ProfileAppearance.apply(this, contextId, color, emoji)
                        components.core.store.dispatch(
                            ContainerAction.AddContainerAction(
                                ContainerState(
                                    contextId = contextId,
                                    name = name,
                                    color = containerColor,
                                    icon = if (emoji != null) ContainerState.Icon.CIRCLE else icon,
                                ),
                            ),
                        )
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
 * "Open link in" dialog, modelled on the ask-for-container extension: the link at the top, an "Always open <host> in
 * the profile I choose" checkbox, one-tap rows for Default and each profile (icon in the profile color), a search field
 * once there are many profiles, and "New profile" to create one inline and open the link in it.
 *
 * @param url The link being opened, shown under the title.
 * @param host Host of the link, used in the checkbox label and saved as the rule.
 * @param profiles The profiles to list, sorted by name.
 * @param defaultColor Color preselected for a new profile.
 * @param onChosen Invoked with the chosen contextId (null for Default) and whether to remember it for [host].
 * @param onCreate Invoked with a new profile's name, ARGB color, emoji (or null) and icon, and whether to remember it for [host].
 * @param onDismiss Invoked when the dialog is cancelled.
 */
@Composable
fun ProfileChooserDialog(
    url: String,
    host: String,
    profiles: List<ContainerState>,
    defaultColor: Int,
    onChosen: (contextId: String?, always: Boolean) -> Unit,
    onCreate: (name: String, color: Int, emoji: String?, icon: ContainerState.Icon, always: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var always by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val defaultName = stringResource(R.string.profiles_default_name)
    val needle = query.trim()
    val showDefault = needle.isEmpty() || defaultName.contains(needle, ignoreCase = true)
    val shown = if (needle.isEmpty()) profiles else profiles.filter { it.name.contains(needle, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = stringResource(R.string.profiles_open_link_in),
                    style = FirefoxTheme.typography.headline5,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = url,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = FirefoxTheme.typography.caption,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Checkbox) { always = !always },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = always, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text(
                        text = stringResource(R.string.profiles_always_open_in_chosen, host),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = FirefoxTheme.typography.body2,
                    )
                }

                if (profiles.size >= SEARCH_THRESHOLD) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        textStyle = FirefoxTheme.typography.body1,
                        placeholder = {
                            Text(
                                text = stringResource(R.string.profiles_search),
                                style = FirefoxTheme.typography.body1,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(iconsR.drawable.mozac_ic_search_24),
                                contentDescription = null,
                            )
                        },
                        singleLine = true,
                    )
                }

                if (showDefault) {
                    ChooserRow(
                        label = defaultName,
                        painter = painterResource(iconsR.drawable.mozac_ic_globe_24),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { onChosen(null, always) },
                    )
                }
                shown.forEach { profile ->
                    ChooserRow(
                        label = profile.name,
                        painter = painterResource(profile.icon.drawableRes()),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        leading = { ProfileBadge(container = profile) },
                        onClick = { onChosen(profile.contextId, always) },
                    )
                }
                if (!showDefault && shown.isEmpty()) {
                    Text(
                        text = stringResource(R.string.profiles_search_no_match),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = FirefoxTheme.typography.body2,
                    )
                }

                if (creating) {
                    NewProfileForm(
                        initialName = needle.takeIf { !showDefault && shown.isEmpty() }.orEmpty(),
                        defaultColor = defaultColor,
                        onCreate = { name, color, emoji, icon -> onCreate(name, color, emoji, icon, always) },
                    )
                } else {
                    ChooserRow(
                        label = stringResource(R.string.profiles_new_profile),
                        painter = painterResource(iconsR.drawable.mozac_ic_plus_24),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        showChevron = false,
                        onClick = { creating = true },
                    )
                }
            }
        },
        confirmButton = {},
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
    painter: Painter,
    tint: Color,
    onClick: () -> Unit,
    showChevron: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    IconListItem(
        label = label,
        onClick = onClick,
        beforeIconPainter = painter,
        beforeIconTint = tint,
        beforeIconContent = leading,
        afterIconPainter = if (showChevron) painterResource(iconsR.drawable.mozac_ic_chevron_right_24) else null,
        contentPaddingListItem = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    )
}

/** Inline "New profile" form of the chooser: name, color, icon and "Create and open". */
@Composable
private fun NewProfileForm(
    initialName: String,
    defaultColor: Int,
    onCreate: (name: String, color: Int, emoji: String?, icon: ContainerState.Icon) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(defaultColor) }
    var icon by remember { mutableStateOf(ContainerState.Icon.CIRCLE) }
    var emoji by remember { mutableStateOf<String?>(null) }
    var nameError by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    fun submit() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) nameError = true else onCreate(trimmed, color, emoji?.takeIf { it.isNotBlank() }, icon)
    }

    Column(modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                nameError = false
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            textStyle = FirefoxTheme.typography.body1,
            label = { Text(text = stringResource(R.string.profiles_name_label), style = FirefoxTheme.typography.caption) },
            isError = nameError,
            supportingText = if (nameError) {
                { Text(text = stringResource(R.string.profiles_name_error), style = FirefoxTheme.typography.caption) }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            // Done on the keyboard creates the profile: the button can sit under the keyboard on small screens.
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Spacer(modifier = Modifier.height(16.dp))
        ProfileAppearancePicker(
            color = color,
            onColorChange = { color = it },
            icon = icon,
            onIconChange = { icon = it },
            emoji = emoji,
            onEmojiChange = { emoji = it },
        )
        Spacer(modifier = Modifier.height(16.dp))
        FilledButton(
            text = stringResource(R.string.profiles_create_and_open),
            modifier = Modifier.fillMaxWidth(),
            onClick = { submit() },
        )
    }
}

/** Number of profiles from which the chooser shows a search field. */
private const val SEARCH_THRESHOLD = 5
