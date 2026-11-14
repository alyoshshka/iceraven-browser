/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.downloads.listscreen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mozilla.components.compose.base.SelectableChip
import org.mozilla.fenix.R
import org.mozilla.fenix.downloads.listscreen.store.FileItem
import org.mozilla.fenix.downloads.listscreen.store.ProfileFilter
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.profiles.ProfileStorage
import org.mozilla.fenix.theme.FirefoxTheme

/** Profiles: chips All / Default / each profile with files / Device (only if such files exist). Each profile that owns a listed file. */
@Composable
internal fun ProfileFilters(
    selected: ProfileFilter,
    profiles: List<FileItem>,
    showDevice: Boolean,
    modifier: Modifier = Modifier,
    onSelected: (ProfileFilter) -> Unit,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(FirefoxTheme.layout.space.static100),
        contentPadding = PaddingValues(horizontal = FirefoxTheme.layout.space.static200),
    ) {
        item {
            SelectableChip(
                text = stringResource(R.string.profiles_files_all),
                selected = selected == ProfileFilter.All,
                modifier = Modifier.height(36.dp),
                onClick = { onSelected(ProfileFilter.All) },
            )
        }
        item {
            SelectableChip(
                text = stringResource(R.string.profiles_files_default),
                selected = selected == ProfileFilter.Profile(ProfileStorage.DEFAULT_ID),
                modifier = Modifier.height(36.dp),
                onClick = { onSelected(ProfileFilter.Profile(ProfileStorage.DEFAULT_ID)) },
            )
        }
        items(items = profiles, key = { it.profileId ?: "" }) { item ->
            val id = item.profileId ?: return@items
            SelectableChip(
                text = item.profileName ?: id,
                selected = selected == ProfileFilter.Profile(id),
                modifier = Modifier.height(36.dp),
                onClick = { onSelected(ProfileFilter.Profile(id)) },
            )
        }
        if (showDevice) {
            item {
                SelectableChip(
                    text = stringResource(R.string.profiles_files_device),
                    selected = selected == ProfileFilter.Device,
                    modifier = Modifier.height(36.dp),
                    onClick = { onSelected(ProfileFilter.Device) },
                )
            }
        }
    }
}

/**
 * Profiles: simple chooser dialog listing Default, every existing profile and "Device Downloads".
 *
 * @param excludedIds Profile ids not offered (the current location).
 * @param excludeDevice Whether "Device Downloads" is not offered.
 * @param onChoose Invoked with the chosen profile id, or null for Device Downloads.
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
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (ProfileStorage.DEFAULT_ID !in excludedIds) {
                    TextButton(
                        onClick = { onChoose(ProfileStorage.DEFAULT_ID) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.profiles_files_default), textAlign = TextAlign.Start)
                    }
                }
                profiles.forEach { container ->
                    TextButton(onClick = { onChoose(container.contextId) }, modifier = Modifier.fillMaxWidth()) {
                        Text(container.name, textAlign = TextAlign.Start)
                    }
                }
                if (!excludeDevice) {
                    TextButton(onClick = { onChoose(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.profiles_files_device_downloads), textAlign = TextAlign.Start)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.profiles_cancel)) } },
    )
}
