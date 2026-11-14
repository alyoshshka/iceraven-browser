/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import mozilla.components.browser.state.action.ContainerAction
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.compose.base.button.IconButton
import mozilla.components.compose.base.menu.DropdownMenu
import mozilla.components.compose.base.menu.MenuItem
import mozilla.components.compose.base.text.Text as AcText
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.IconListItem
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.theme.FirefoxTheme
import java.util.UUID

/** Profiles: lists, adds, edits and deletes profiles (containers). */
class ProfilesSettingsFragment : Fragment(), SystemInsetsPaddedFragment {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = content {
        val store = requireComponents.core.store
        val containers by store.observeAsComposableState { it.containers }
        val profiles = remember(containers) {
            containers.orEmpty().values.sortedBy { it.name.lowercase() }
        }
        val storage = remember { ProfileStorage(requireContext()) }

        // Keep the names cache (used by the file picker and Downloads) in sync with the store.
        LaunchedEffect(profiles) {
            val known = storage.knownProfiles()
            profiles.forEach { if (known[it.contextId] != it.name) storage.setName(it.contextId, it.name) }
        }

        FirefoxTheme {
            ProfilesSettingsScreen(
                profiles = profiles,
                onSave = { existing, name, color ->
                    // Adding a container with an existing contextId replaces it (see ContainerReducer).
                    val contextId = existing?.contextId ?: UUID.randomUUID().toString()
                    storage.setName(contextId, name)
                    store.dispatch(
                        ContainerAction.AddContainerAction(
                            ContainerState(
                                contextId = contextId,
                                name = name,
                                color = color,
                                icon = existing?.icon ?: ContainerState.Icon.CIRCLE,
                            ),
                        ),
                    )
                },
                onDelete = ::deleteProfile,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(R.string.profiles_title))
    }

    private fun deleteProfile(container: ContainerState) {
        val components = requireComponents
        val store = components.core.store
        val tabIds = store.state.tabs.filter { it.contextId == container.contextId }.map { it.id }
        if (tabIds.isNotEmpty()) {
            components.useCases.tabsUseCases.removeTabs(tabIds)
        }
        components.core.engine.clearDataForContext(container.contextId)
        store.dispatch(ContainerAction.RemoveContainerAction(container.contextId))
        ProfileStorage(requireContext()).deleteProfileFiles(container.contextId)
    }
}

private sealed interface ProfileDialog {
    data class Edit(val existing: ContainerState?) : ProfileDialog
    data class Delete(val profile: ContainerState) : ProfileDialog
}

/**
 * Profiles settings screen: an "Add profile" row followed by one row per profile.
 *
 * @param profiles The profiles to show, already sorted.
 * @param onSave Invoked to add (existing null) or update a profile.
 * @param onDelete Invoked after the user confirmed deleting a profile.
 */
@Composable
private fun ProfilesSettingsScreen(
    profiles: List<ContainerState>,
    onSave: (existing: ContainerState?, name: String, color: ContainerState.Color) -> Unit,
    onDelete: (ContainerState) -> Unit,
) {
    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }

    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                IconListItem(
                    label = stringResource(R.string.profiles_add),
                    beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_plus_24),
                    onClick = { dialog = ProfileDialog.Edit(null) },
                )
            }

            if (profiles.isEmpty()) {
                item { ProfilesEmptyState() }
            }

            items(profiles, key = { it.contextId }) { profile ->
                ProfileRow(
                    profile = profile,
                    onEdit = { dialog = ProfileDialog.Edit(profile) },
                    onDelete = { dialog = ProfileDialog.Delete(profile) },
                )
            }
        }
    }

    when (val current = dialog) {
        is ProfileDialog.Edit -> ProfileEditDialog(
            existing = current.existing,
            defaultColor = firstUnusedColor(profiles),
            onDismiss = { dialog = null },
            onSave = { name, color ->
                onSave(current.existing, name, color)
                dialog = null
            },
        )

        is ProfileDialog.Delete -> DeleteProfileDialog(
            profileName = current.profile.name,
            onConfirm = {
                onDelete(current.profile)
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        null -> Unit
    }
}

@Composable
private fun ProfileRow(
    profile: ContainerState,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    IconListItem(
        label = profile.name,
        beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_notification_dot_fill_24),
        beforeIconDescription = stringResource(profile.color.nameRes()),
        beforeIconTint = profile.color.toComposeColor(),
        onClick = onEdit,
        afterListAction = {
            IconButton(
                onClick = { menuExpanded = true },
                contentDescription = stringResource(R.string.content_description_menu),
            ) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_ellipsis_vertical_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )

                DropdownMenu(
                    menuItems = listOf(
                        MenuItem.TextItem(
                            text = AcText.Resource(R.string.profiles_edit),
                            onClick = onEdit,
                        ),
                        MenuItem.TextItem(
                            text = AcText.Resource(R.string.bookmark_menu_delete_button),
                            onClick = onDelete,
                            level = MenuItem.FixedItem.Level.Critical,
                        ),
                    ),
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                )
            }
        },
    )
}

@Composable
private fun ProfilesEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(all = FirefoxTheme.layout.space.static200),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.profiles_empty_title),
            color = MaterialTheme.colorScheme.onSurface,
            style = FirefoxTheme.typography.headline6,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(FirefoxTheme.layout.space.static100))

        Text(
            text = stringResource(R.string.profiles_empty_message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = FirefoxTheme.typography.body2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
@PreviewLightDark
private fun ProfilesSettingsScreenPreview() {
    FirefoxTheme {
        ProfilesSettingsScreen(
            profiles = listOf(
                ContainerState("1", "Home", ContainerState.Color.GREEN, ContainerState.Icon.CIRCLE),
                ContainerState("2", "Work", ContainerState.Color.BLUE, ContainerState.Icon.CIRCLE),
            ),
            onSave = { _, _, _ -> },
            onDelete = {},
        )
    }
}
