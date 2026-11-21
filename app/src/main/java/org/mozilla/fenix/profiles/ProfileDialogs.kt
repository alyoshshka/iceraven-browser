/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.compose.base.button.TextButton
import mozilla.components.feature.prompts.R as promptsR
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import kotlinx.coroutines.launch
import org.mozilla.fenix.compose.list.IconListItem
import org.mozilla.fenix.compose.list.RadioButtonListItem
import org.mozilla.fenix.compose.list.SwitchListItem
import org.mozilla.fenix.theme.FirefoxTheme

private val ColorCircleSize = 40.dp
private const val ICONS_PER_ROW = 5

/**
 * Profiles: dialog to add a profile ([existing] null) or to rename, recolor and re-icon [existing]. When editing it
 * also shows the profile's designated sites and the "Limit to designated sites" switch.
 *
 * @param existing The profile being edited, or null when adding.
 * @param defaultColor ARGB color preselected when adding (the first color no other profile uses).
 * @param sites Hosts that always open in the profile being edited.
 * @param limited Whether the profile being edited is limited to its designated sites.
 * @param onDismiss Invoked when the dialog is cancelled.
 * @param proxy The proxy of the profile being edited.
 * @param onSave Invoked with the trimmed, non-empty name, the chosen ARGB color, the emoji (null to use the icon),
 * the chosen icon, the limit switch, the sites the user removed from the list and the chosen proxy.
 */
@Composable
fun ProfileEditDialog(
    existing: ContainerState?,
    defaultColor: Int,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        color: Int,
        emoji: String?,
        icon: ContainerState.Icon,
        limited: Boolean,
        removedSites: Set<String>,
        proxy: ProfileProxySettings,
    ) -> Unit,
    sites: List<String> = emptyList(),
    limited: Boolean = false,
    proxy: ProfileProxySettings = ProfileProxySettings(),
) {
    // TextFieldValue so the cursor starts at the end of the prefilled name.
    var nameValue by remember {
        val initial = existing?.name ?: ""
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val name = nameValue.text
    var color by remember { mutableStateOf(existing?.let { ProfileAppearance.colorOf(it) } ?: defaultColor) }
    var icon by remember { mutableStateOf(existing?.icon ?: ContainerState.Icon.CIRCLE) }
    var emoji by remember { mutableStateOf(existing?.let { ProfileAppearance.emojiOf(it.contextId) }) }
    var limitSites by remember { mutableStateOf(limited) }
    var removedSites by remember { mutableStateOf(emptySet<String>()) }
    var nameError by remember { mutableStateOf(false) }
    var proxyType by remember { mutableStateOf(proxy.type) }
    var proxyHost by remember { mutableStateOf(proxy.host) }
    var proxyPort by remember { mutableStateOf(if (proxy.port > 0) proxy.port.toString() else "") }
    var proxyPortError by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(if (existing == null) R.string.profiles_add else R.string.profiles_edit),
                style = FirefoxTheme.typography.headline5,
            )
        },
        text = {
            // Scrollable so that the buttons stay reachable with the on-screen keyboard.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = nameValue,
                    onValueChange = {
                        nameValue = it
                        nameError = false
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    textStyle = FirefoxTheme.typography.body1,
                    label = {
                        Text(
                            text = stringResource(R.string.profiles_name_label),
                            style = FirefoxTheme.typography.caption,
                        )
                    },
                    isError = nameError,
                    supportingText = if (nameError) {
                        {
                            Text(
                                text = stringResource(R.string.profiles_name_error),
                                style = FirefoxTheme.typography.caption,
                            )
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
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

                ProfileProxySection(
                    type = proxyType,
                    host = proxyHost,
                    port = proxyPort,
                    portError = proxyPortError,
                    onTypeChange = { proxyType = it },
                    onHostChange = { proxyHost = it },
                    onPortChange = {
                        proxyPort = it.filter(Char::isDigit).take(5)
                        proxyPortError = false
                    },
                )

                if (existing != null) {
                    ProfileSitesSection(
                        sites = sites.filter { it !in removedSites },
                        limited = limitSites,
                        onLimitedChange = { limitSites = it },
                        onRemoveSite = { removedSites = removedSites + it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                text = stringResource(promptsR.string.mozac_feature_prompt_save_confirmation),
                onClick = {
                    val trimmed = name.trim()
                    val portNumber = proxyPort.toIntOrNull()
                    val portValid = portNumber != null && portNumber in 1..65535
                    if (trimmed.isEmpty()) nameError = true
                    if (proxyType != ProxyType.NONE && !portValid) proxyPortError = true
                    if (trimmed.isNotEmpty() && !proxyPortError) {
                        val chosen = if (proxyType == ProxyType.NONE) {
                            ProfileProxySettings()
                        } else {
                            ProfileProxySettings(
                                proxyType,
                                proxyHost.trim().ifEmpty { ProfileProxy.DEFAULT_HOST },
                                portNumber ?: 0,
                            )
                        }
                        onSave(trimmed, color, emoji?.takeIf { it.isNotBlank() }, icon, limitSites, removedSites, chosen)
                    }
                },
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
internal fun ProfileIconPicker(
    selected: ContainerState.Icon,
    color: Color,
    onSelected: (ContainerState.Icon) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProfileIconPalette.chunked(ICONS_PER_ROW).forEach { rowIcons ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowIcons.forEach { option ->
                    val isSelected = option == selected
                    Box(
                        modifier = Modifier
                            .size(ColorCircleSize)
                            .clip(CircleShape)
                            .then(
                                if (isSelected) {
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                } else {
                                    Modifier
                                },
                            )
                            .clickable(role = Role.RadioButton, onClick = { onSelected(option) })
                            .semantics {
                                contentDescription = ""
                                this.selected = isSelected
                                role = Role.RadioButton
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        ProfileIcon(
                            icon = option,
                            color = if (isSelected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                            contentDescription = stringResource(option.nameRes()),
                        )
                    }
                }
            }
        }
    }
}

/** Proxy choice (none, SOCKS5, HTTP) with host, port, the fail-closed note and a reachability check. */
@Composable
private fun ProfileProxySection(
    type: ProxyType,
    host: String,
    port: String,
    portError: Boolean,
    onTypeChange: (ProxyType) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var checkResult by remember { mutableStateOf<Boolean?>(null) }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = stringResource(R.string.profiles_proxy_title),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = FirefoxTheme.typography.caption,
    )

    listOf(
        ProxyType.NONE to R.string.profiles_proxy_none,
        ProxyType.SOCKS5 to R.string.profiles_proxy_socks5,
        ProxyType.HTTP to R.string.profiles_proxy_http,
    ).forEach { (option, label) ->
        RadioButtonListItem(
            label = stringResource(label),
            selected = type == option,
            onClick = {
                onTypeChange(option)
                checkResult = null
            },
        )
    }

    if (type == ProxyType.NONE) return

    if (type == ProxyType.HTTP) {
        Text(
            text = stringResource(R.string.profiles_proxy_http_warning),
            color = MaterialTheme.colorScheme.error,
            style = FirefoxTheme.typography.body2,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }

    OutlinedTextField(
        value = host,
        onValueChange = {
            onHostChange(it)
            checkResult = null
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        textStyle = FirefoxTheme.typography.body1,
        label = {
            Text(text = stringResource(R.string.profiles_proxy_host_label), style = FirefoxTheme.typography.caption)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )

    OutlinedTextField(
        value = port,
        onValueChange = {
            onPortChange(it)
            checkResult = null
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        textStyle = FirefoxTheme.typography.body1,
        label = {
            Text(text = stringResource(R.string.profiles_proxy_port_label), style = FirefoxTheme.typography.caption)
        },
        isError = portError,
        supportingText = if (portError) {
            { Text(text = stringResource(R.string.profiles_proxy_port_error), style = FirefoxTheme.typography.caption) }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )

    Text(
        text = stringResource(R.string.profiles_proxy_fail_closed),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = FirefoxTheme.typography.body2,
        modifier = Modifier.padding(top = 8.dp),
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            text = stringResource(R.string.profiles_proxy_check),
            onClick = {
                val number = port.toIntOrNull()
                if (number == null || number !in 1..65535) {
                    checkResult = false
                } else {
                    val target = host.trim().ifEmpty { ProfileProxy.DEFAULT_HOST }
                    scope.launch { checkResult = ProfileProxy.checkReachable(target, number) }
                }
            },
        )
        checkResult?.let { reachable ->
            Text(
                text = stringResource(
                    if (reachable) R.string.profiles_proxy_reachable else R.string.profiles_proxy_unreachable,
                ),
                style = FirefoxTheme.typography.body2,
            )
        }
    }
}

/** Designated sites and the limit switch of the profile being edited. */
@Composable
private fun ProfileSitesSection(
    sites: List<String>,
    limited: Boolean,
    onLimitedChange: (Boolean) -> Unit,
    onRemoveSite: (String) -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))

    SwitchListItem(
        label = stringResource(R.string.profiles_limit_sites),
        maxLabelLines = 3,
        description = stringResource(R.string.profiles_limit_sites_description),
        maxDescriptionLines = 2,
        checked = limited,
        onClick = onLimitedChange,
    )

    Text(
        text = stringResource(R.string.profiles_sites_title),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = FirefoxTheme.typography.caption,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )

    if (sites.isEmpty()) {
        Text(
            text = stringResource(R.string.profiles_sites_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = FirefoxTheme.typography.body2,
        )
    }
    sites.forEach { host ->
        IconListItem(
            label = host,
            beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_globe_24),
            afterIconPainter = painterResource(iconsR.drawable.mozac_ic_delete_24),
            afterIconDescription = stringResource(R.string.profiles_site_rule_remove, host),
            onAfterIconClick = { onRemoveSite(host) },
        )
    }
}

/**
 * Profiles: confirmation before deleting [profileName] with its tabs, logins and files.
 */
@Composable
fun DeleteProfileDialog(
    profileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.profiles_delete_confirm_title, profileName),
                style = FirefoxTheme.typography.headline5,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.profiles_delete_confirm_message),
                style = FirefoxTheme.typography.body2,
            )
        },
        confirmButton = {
            TextButton(
                text = stringResource(R.string.bookmark_menu_delete_button),
                onClick = onConfirm,
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
@PreviewLightDark
private fun ProfileEditDialogPreview() {
    FirefoxTheme {
        Surface {
            ProfileEditDialog(
                existing = null,
                defaultColor = ProfileSwatches.first().argb,
                onDismiss = {},
                onSave = { _, _, _, _, _, _, _ -> },
            )
        }
    }
}

@Composable
@PreviewLightDark
private fun DeleteProfileDialogPreview() {
    FirefoxTheme {
        Surface {
            DeleteProfileDialog(profileName = "Work", onConfirm = {}, onDismiss = {})
        }
    }
}
