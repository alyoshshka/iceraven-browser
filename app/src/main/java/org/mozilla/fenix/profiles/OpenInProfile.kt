/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.findNavController
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.compose.base.button.TextButton
import mozilla.components.feature.prompts.R as promptsR
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.compose.list.RadioButtonListItem
import org.mozilla.fenix.theme.FirefoxTheme

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
 * Dialog to pick the profile to open the URL of a tab in: Default and each profile as radio rows (the tab's current
 * profile selected), a "Manage profiles" text button and Cancel.
 *
 * @param containers The profiles to list, sorted by name.
 * @param currentContextId Profile of the tab, null for Default.
 * @param onProfileSelected Invoked with the context id of the chosen profile, null for Default.
 * @param onManageProfiles Invoked when "Manage profiles" is tapped.
 * @param onDismiss Invoked when the dialog is cancelled.
 */
@Composable
fun OpenInProfileDialog(
    containers: List<ContainerState>,
    currentContextId: String?,
    onProfileSelected: (contextId: String?) -> Unit,
    onManageProfiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.profiles_open_in_profile),
                style = FirefoxTheme.typography.headline5,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                RadioButtonListItem(
                    label = stringResource(R.string.profiles_default_name),
                    selected = currentContextId == null,
                    onClick = { onProfileSelected(null) },
                )
                containers.forEach { container ->
                    RadioButtonListItem(
                        label = container.name,
                        selected = container.contextId == currentContextId,
                        onClick = { onProfileSelected(container.contextId) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                text = stringResource(R.string.profiles_manage),
                onClick = onManageProfiles,
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

/**
 * Profiles: hosts [OpenInProfileDialog] outside the main menu sheet, so the menu can be dismissed first.
 * Shown with [show] from the activity's fragment manager.
 */
class OpenInProfileDialogFragment : DialogFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent host window; the visible dialog is the Compose AlertDialog.
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Translucent_NoTitleBar)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val tabId = requireArguments().getString(ARG_TAB_ID).orEmpty()
        val components = requireContext().components
        return ComposeView(requireContext()).apply {
            setContent {
                FirefoxTheme {
                    val store = components.core.store
                    val containers = remember { store.state.containers.values.sortedBy { it.name.lowercase() } }
                    val currentContextId = remember { store.state.findTab(tabId)?.contextId }
                    OpenInProfileDialog(
                        containers = containers,
                        currentContextId = currentContextId,
                        onProfileSelected = { contextId ->
                            dismissAllowingStateLoss()
                            openInProfile(components, tabId, contextId)
                        },
                        onManageProfiles = {
                            dismissAllowingStateLoss()
                            requireActivity().findNavController(R.id.container)
                                .navigate(NavGraphDirections.actionGlobalProfilesSettingsFragment())
                        },
                        onDismiss = { dismissAllowingStateLoss() },
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "OpenInProfileDialog"
        private const val ARG_TAB_ID = "tab_id"

        /** Shows the dialog for [tabId] using [fragmentManager]. */
        fun show(fragmentManager: FragmentManager, tabId: String) {
            OpenInProfileDialogFragment().apply {
                arguments = Bundle().apply { putString(ARG_TAB_ID, tabId) }
            }.show(fragmentManager, TAG)
        }
    }
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
