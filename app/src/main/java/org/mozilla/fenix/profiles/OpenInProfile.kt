/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.navigation.NavController
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.SessionState
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components

// Profiles: helpers for the "Open in profile" main menu item.

/** Name shown for the profile of [contextId]; null means the Default profile. */
fun BrowserState.profileName(context: Context, contextId: String?): String =
    when (contextId) {
        null -> context.getString(R.string.profiles_default_name)
        else -> containers[contextId]?.name ?: context.getString(R.string.profiles_default_name)
    }

/** Whether the "Open in profile" menu item applies to [tab]: normal tab with a web URL. */
fun isOpenInProfileSupported(tab: SessionState?): Boolean =
    tab != null && !tab.content.private &&
        (tab.content.url.startsWith("http://") || tab.content.url.startsWith("https://"))

/**
 * Shows a dialog to pick a profile and opens the URL of [tabId] in a new selected tab of that profile.
 * The original tab is kept.
 */
fun showOpenInProfileDialog(
    context: Context,
    components: Components,
    tabId: String,
    navController: NavController,
) {
    val store = components.core.store
    val containers = store.state.containers.values.sortedBy { it.name.lowercase() }
    val currentContextId = store.state.findTab(tabId)?.contextId
    fun label(name: String, isCurrent: Boolean) =
        if (isCurrent) context.getString(R.string.profiles_current_suffix, name) else name
    val names = listOf(label(context.getString(R.string.profiles_default_name), currentContextId == null)) +
        containers.map { label(it.name, it.contextId == currentContextId) } +
        context.getString(R.string.profiles_manage)

    AlertDialog.Builder(context)
        .setTitle(R.string.profiles_open_in_profile_title)
        .setItems(names.toTypedArray()) { _, which ->
            when (which) {
                0 -> openInProfile(components, tabId, null)
                names.lastIndex -> navController.navigate(NavGraphDirections.actionGlobalProfilesSettingsFragment())
                else -> openInProfile(components, tabId, containers[which - 1].contextId)
            }
        }
        .show()
}

private fun openInProfile(components: Components, tabId: String, contextId: String?) {
    val store = components.core.store
    val tab = store.state.findTab(tabId) ?: return
    val newTabId = components.useCases.tabsUseCases.addTab(
        url = tab.content.url,
        selectTab = true,
        contextId = contextId,
    )
    // New tabs take the global desktop mode default; copy the one of the original tab.
    val newTab = store.state.findTab(newTabId)
    if (newTab != null && newTab.content.desktopMode != tab.content.desktopMode) {
        components.useCases.sessionUseCases.requestDesktopSite(tab.content.desktopMode, newTabId)
    }
}
