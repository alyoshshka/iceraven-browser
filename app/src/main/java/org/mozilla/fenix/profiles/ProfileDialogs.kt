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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.compose.base.button.TextButton
import mozilla.components.feature.prompts.R as promptsR
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.IconListItem
import org.mozilla.fenix.compose.list.SwitchListItem
import org.mozilla.fenix.theme.FirefoxTheme

private val ColorCircleSize = 40.dp
private const val COLORS_PER_ROW = 4
private const val ICONS_PER_ROW = 5

/**
 * Profiles: dialog to add a profile ([existing] null) or to rename, recolor and re-icon [existing]. When editing it
 * also shows the profile's designated sites and the "Limit to designated sites" switch.
 *
 * @param existing The profile being edited, or null when adding.
 * @param defaultColor Color preselected when adding (the first color no other profile uses).
 * @param sites Hosts that always open in the profile being edited.
 * @param limited Whether the profile being edited is limited to its designated sites.
 * @param onDismiss Invoked when the dialog is cancelled.
 * @param onSave Invoked with the trimmed, non-empty name, the chosen color and icon, the limit switch and the sites
 * the user removed from the list.
 */
@Composable
fun ProfileEditDialog(
    existing: ContainerState?,
    defaultColor: ContainerState.Color,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        color: ContainerState.Color,
        icon: ContainerState.Icon,
        limited: Boolean,
        removedSites: Set<String>,
    ) -> Unit,
    sites: List<String> = emptyList(),
    limited: Boolean = false,
) {
    // TextFieldValue so the cursor starts at the end of the prefilled name.
    var nameValue by remember {
        val initial = existing?.name ?: ""
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val name = nameValue.text
    var color by remember { mutableStateOf(existing?.color ?: defaultColor) }
    var icon by remember { mutableStateOf(existing?.icon ?: ContainerState.Icon.CIRCLE) }
    var limitSites by remember { mutableStateOf(limited) }
    var removedSites by remember { mutableStateOf(emptySet<String>()) }
    var nameError by remember { mutableStateOf(false) }
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

                Text(
                    text = stringResource(R.string.profiles_color_label),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = FirefoxTheme.typography.caption,
                )

                Spacer(modifier = Modifier.height(8.dp))

                ProfileColorPicker(selected = color, onSelected = { color = it })

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.profiles_icon_label),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = FirefoxTheme.typography.caption,
                )

                Spacer(modifier = Modifier.height(8.dp))

                ProfileIconPicker(selected = icon, color = color.toComposeColor(), onSelected = { icon = it })

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
                    if (trimmed.isEmpty()) nameError = true else onSave(trimmed, color, icon, limitSites, removedSites)
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
internal fun ProfileColorPicker(
    selected: ContainerState.Color,
    onSelected: (ContainerState.Color) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProfileColorPalette.chunked(COLORS_PER_ROW).forEach { rowColors ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowColors.forEach { option ->
                    ProfileColorCircle(
                        color = option.toComposeColor(),
                        name = stringResource(option.nameRes()),
                        isSelected = option == selected,
                        onClick = { onSelected(option) },
                    )
                }
            }
        }
    }
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

@Composable
private fun ProfileColorCircle(
    color: Color,
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(ColorCircleSize)
            .clip(CircleShape)
            .background(color)
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = name
                this.selected = isSelected
                role = Role.RadioButton
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_checkmark_24),
                contentDescription = null,
                // Profile palette colors are all light enough for a dark check.
                tint = Color.Black,
            )
        }
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
                defaultColor = ContainerState.Color.BLUE,
                onDismiss = {},
                onSave = { _, _, _, _, _ -> },
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
