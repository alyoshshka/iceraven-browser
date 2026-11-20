/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.downloads.listscreen.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import mozilla.components.compose.base.SelectableChip
import mozilla.components.compose.base.button.TextButton
import mozilla.components.compose.base.menu.DropdownMenu
import mozilla.components.compose.base.menu.MenuItem
import mozilla.components.compose.base.text.Text as MenuText
import mozilla.components.feature.prompts.R as promptsR
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.RadioButtonListItem
import org.mozilla.fenix.downloads.listscreen.store.FileItem
import org.mozilla.fenix.downloads.listscreen.store.ProfileFilter
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.profiles.ProfileFileResult
import org.mozilla.fenix.profiles.ProfileStorage
import org.mozilla.fenix.profiles.drawableRes
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Profiles: the profile filter, a chip for the start of the [Filters] row. Tapping it opens a dropdown with
 * "All profiles", Default, each profile that owns a listed file and (if such files exist) "Device downloads".
 *
 * @param selected The profile filter currently applied.
 * @param profiles Files whose profile (id and name) is offered as an option.
 * @param showDevice Whether "Device downloads" is offered.
 * @param onSelected Invoked with the chosen filter.
 */
@Composable
internal fun ProfileFilterChip(
    selected: ProfileFilter,
    profiles: List<FileItem>,
    showDevice: Boolean,
    onSelected: (ProfileFilter) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val allLabel = stringResource(R.string.profiles_files_all_profiles)
    val defaultLabel = stringResource(R.string.profiles_files_default)
    val deviceLabel = stringResource(R.string.profiles_files_device_downloads)

    val options = buildList {
        add(ProfileFilter.All to allLabel)
        add(ProfileFilter.Profile(ProfileStorage.DEFAULT_ID) to defaultLabel)
        profiles.forEach { item ->
            val id = item.profileId ?: return@forEach
            add(ProfileFilter.Profile(id) to (item.profileName ?: id))
        }
        if (showDevice) add(ProfileFilter.Device to deviceLabel)
    }
    val label = options.firstOrNull { it.first == selected }?.second ?: allLabel
    val containers = LocalContext.current.applicationContext.components.core.store.state.containers
    val iconOf: (ProfileFilter) -> Int = { filter ->
        when (filter) {
            ProfileFilter.All -> iconsR.drawable.mozac_ic_tab_24
            ProfileFilter.Device -> iconsR.drawable.mozac_ic_download_24
            is ProfileFilter.Profile -> containers[filter.id]?.icon?.drawableRes() ?: iconsR.drawable.mozac_ic_globe_24
        }
    }

    // Chip and DropdownMenu in a common parent so the menu position is calculated correctly.
    Box {
        SelectableChip(
            text = label,
            selected = selected != ProfileFilter.All,
            modifier = Modifier.height(FilterChipHeight),
            onClick = { expanded = true },
        )

        DropdownMenu(
            expanded = expanded,
            menuItems = options.map { (filter, text) ->
                MenuItem.IconItem(
                    text = MenuText.String(text),
                    drawableRes = iconOf(filter),
                    level = MenuItem.FixedItem.Level.Default,
                    onClick = { onSelected(filter) },
                )
            },
            onDismissRequest = { expanded = false },
        )
    }
}

/**
 * Profiles: chooser dialog listing Default, every existing profile and "Device downloads" as radio rows.
 * Tapping a row chooses it and closes the dialog.
 *
 * @param excludedIds Profile ids not offered (the current location).
 * @param excludeDevice Whether "Device downloads" is not offered.
 * @param onChoose Invoked with the chosen profile id, or null for Device downloads.
 */
@Composable
internal fun ProfileTargetDialog(
    title: String,
    excludedIds: Set<String>,
    excludeDevice: Boolean,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val store = LocalContext.current.applicationContext.components.core.store
    val profiles = remember(store.state.containers) {
        store.state.containers.values.sortedBy { it.name.lowercase() }.filter { it.contextId !in excludedIds }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = FirefoxTheme.typography.headline5,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (ProfileStorage.DEFAULT_ID !in excludedIds) {
                    RadioButtonListItem(
                        label = stringResource(R.string.profiles_files_default),
                        selected = false,
                        onClick = { onChoose(ProfileStorage.DEFAULT_ID) },
                    )
                }
                profiles.forEach { container ->
                    RadioButtonListItem(
                        label = container.name,
                        selected = false,
                        onClick = { onChoose(container.contextId) },
                    )
                }
                if (!excludeDevice) {
                    RadioButtonListItem(
                        label = stringResource(R.string.profiles_files_device_downloads),
                        selected = false,
                        onClick = { onChoose(null) },
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

/** Profiles: the snackbar message for the outcome of a move, export or import. */
internal fun profileFileResultMessage(context: Context, result: ProfileFileResult): String {
    val res = context.resources
    return when (result) {
        is ProfileFileResult.Moved -> {
            val failed = result.total - result.moved - result.unchanged
            when {
                failed > 0 && result.moved == 0 -> context.getString(R.string.profiles_files_move_failed)
                failed > 0 -> res.getQuantityString(
                    R.plurals.profiles_files_moved_partial, result.total, result.moved, result.total,
                )
                result.moved == 0 -> context.getString(R.string.profiles_files_already_there)
                result.unchanged > 0 -> context.getString(
                    R.string.profiles_files_moved_already_there,
                    res.getQuantityString(R.plurals.profiles_files_moved, result.moved, result.moved),
                    result.unchanged,
                )
                else -> res.getQuantityString(R.plurals.profiles_files_moved, result.moved, result.moved)
            }
        }
        is ProfileFileResult.Exported ->
            context.getString(if (result.success) R.string.profiles_files_exported else R.string.profiles_files_export_failed)
        is ProfileFileResult.Imported -> when {
            result.imported == 0 -> context.getString(R.string.profiles_files_import_failed)
            result.imported == result.total ->
                res.getQuantityString(R.plurals.profiles_files_imported_count, result.imported, result.imported)
            else -> res.getQuantityString(
                R.plurals.profiles_files_imported_partial, result.total, result.imported, result.total,
            )
        }
    }
}
