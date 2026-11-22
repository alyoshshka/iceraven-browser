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
import androidx.compose.ui.unit.dp
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
import org.mozilla.fenix.compose.list.SwitchListItem
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

        var askForLinks by remember { mutableStateOf(storage.askForExternalLinks) }
        var rules by remember(profiles) { mutableStateOf(storage.rules()) }
        var limited by remember(profiles) { mutableStateOf(storage.limitedProfiles()) }

        FirefoxTheme {
            ProfilesSettingsScreen(
                profiles = profiles,
                askForLinks = askForLinks,
                onAskForLinksChange = {
                    storage.askForExternalLinks = it
                    askForLinks = it
                },
                rules = rules,
                limited = limited,
                onSave = { existing, name, color, emoji, icon, limitSites, removedSites, proxy ->
                    // Adding a container with an existing contextId replaces it (see ContainerReducer).
                    val contextId = existing?.contextId ?: UUID.randomUUID().toString()
                    storage.setName(contextId, name)
                    ProfileProxy.set(requireContext(), contextId, proxy)
                    if (existing != null) {
                        removedSites.forEach { storage.removeRule(it) }
                        storage.setLimited(contextId, limitSites)
                        rules = storage.rules()
                        limited = storage.limitedProfiles()
                    }
                    store.dispatch(
                        ContainerAction.AddContainerAction(
                            ContainerState(
                                contextId = contextId,
                                name = name,
                                color = ProfileAppearance.apply(requireContext(), contextId, color, emoji),
                                icon = if (emoji != null) ContainerState.Icon.CIRCLE else icon,
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
        ProfileProxy.remove(requireContext(), container.contextId)
        ProfileAppearance.remove(requireContext(), container.contextId)
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
 * @param askForLinks Whether links from other apps ask which profile to open in.
 * @param rules Saved site rules, host to contextId (or [ProfileStorage.DEFAULT_ID]).
 * @param limited The profiles limited to their designated sites.
 * @param onSave Invoked to add (existing null) or update a profile.
 * @param onDelete Invoked after the user confirmed deleting a profile.
 */
@Composable
private fun ProfilesSettingsScreen(
    profiles: List<ContainerState>,
    askForLinks: Boolean,
    onAskForLinksChange: (Boolean) -> Unit,
    rules: Map<String, String>,
    limited: Set<String>,
    onSave: (
        existing: ContainerState?,
        name: String,
        color: Int,
        emoji: String?,
        icon: ContainerState.Icon,
        limited: Boolean,
        removedSites: Set<String>,
        proxy: ProfileProxySettings,
    ) -> Unit,
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

            item {
                SwitchListItem(
                    label = stringResource(R.string.profiles_ask_for_links),
                    checked = askForLinks,
                    maxLabelLines = 2,
                    onClick = onAskForLinksChange,
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
            sites = current.existing?.let { existing ->
                rules.filterValues { it == existing.contextId }.keys.sorted()
            }.orEmpty(),
            limited = current.existing?.let { it.contextId in limited } ?: false,
            proxy = current.existing?.let { ProfileProxy.get(it.contextId) } ?: ProfileProxySettings(),
            onDismiss = { dialog = null },
            onSave = { name, color, emoji, icon, limitSites, removedSites, proxy ->
                onSave(current.existing, name, color, emoji, icon, limitSites, removedSites, proxy)
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
        beforeIconPainter = painterResource(profile.icon.drawableRes()),
        beforeIconContent = {
            ProfileBadge(
                container = profile,
                contentDescription = ProfileAppearance.emojiOf(profile.contextId)
                    ?: ProfileAppearance.lookOf(profile.contextId)?.color?.let { hexOf(it) }
                    ?: stringResource(profile.color.nameRes()),
            )
        },
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
            askForLinks = true,
            onAskForLinksChange = {},
            rules = mapOf("example.com" to "2"),
            limited = emptySet(),
            profiles = listOf(
                ContainerState("1", "Home", ContainerState.Color.GREEN, ContainerState.Icon.CIRCLE),
                ContainerState("2", "Work", ContainerState.Color.BLUE, ContainerState.Icon.CIRCLE),
            ),
            onSave = { _, _, _, _, _, _, _, _ -> },
            onDelete = {},
        )
    }
}
