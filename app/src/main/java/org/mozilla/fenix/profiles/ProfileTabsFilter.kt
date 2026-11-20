/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mozilla.components.compose.base.SelectableChip
import mozilla.components.compose.base.menu.DropdownMenu
import mozilla.components.compose.base.menu.MenuItem
import mozilla.components.compose.base.text.Text as MenuText
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Profiles: the profile filter of the tab list, same look as the one of the Downloads screen. Tapping the chip opens a
 * dropdown with "All profiles" and each profile that owns an open tab.
 *
 * @param profileIds Profiles owning open tabs (Default as [ProfileStorage.DEFAULT_ID]).
 * @param selected The profile the tabs are filtered to, null for all.
 * @param onSelected Invoked with the chosen profile id, null for all.
 */
@Composable
fun ProfileTabsFilterChip(
    profileIds: List<String>,
    selected: String?,
    onSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val containers = LocalContext.current.applicationContext.components.core.store.state.containers
    var expanded by remember { mutableStateOf(false) }
    val allLabel = stringResource(R.string.profiles_tabs_filter_all)
    val defaultLabel = stringResource(R.string.profiles_default_name)

    val options: List<Pair<String?, String>> = buildList {
        add(null to allLabel)
        profileIds.map { id ->
            id to if (id == ProfileStorage.DEFAULT_ID) defaultLabel else containers[id]?.name ?: id
        }.sortedBy { it.second.lowercase() }.forEach { add(it) }
    }
    val label = options.firstOrNull { it.first == selected }?.second ?: allLabel

    // Chip and DropdownMenu in a common parent so the menu position is calculated correctly.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FirefoxTheme.layout.space.static200, vertical = FirefoxTheme.layout.space.static100),
    ) {
        SelectableChip(
            text = label,
            selected = selected != null,
            modifier = Modifier.height(CHIP_HEIGHT.dp),
            onClick = { expanded = true },
        )

        DropdownMenu(
            expanded = expanded,
            menuItems = options.map { (id, text) ->
                MenuItem.TextItem(
                    text = MenuText.String(text),
                    level = MenuItem.FixedItem.Level.Default,
                    onClick = { onSelected(id) },
                )
            },
            onDismissRequest = { expanded = false },
        )
    }
}

private const val CHIP_HEIGHT = 32
