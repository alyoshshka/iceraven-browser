/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import mozilla.components.ui.icons.R as iconsR
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
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
import androidx.fragment.app.FragmentActivity
import androidx.navigation.findNavController
import mozilla.components.concept.engine.utils.ABOUT_HOME_URL
import mozilla.components.browser.state.selector.selectedTab
import org.mozilla.fenix.tabstray.redux.state.Page
import mozilla.components.browser.state.selector.findTab
import mozilla.components.concept.engine.HitResult
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.compose.base.button.TextButton
import mozilla.components.feature.prompts.R as promptsR
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.ext.components
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
 * Dialog to pick a profile: Default and each profile as radio rows with their icon (the current profile selected), a
 * "Manage profiles" text button and Cancel.
 *
 * @param containers The profiles to list, sorted by name.
 * @param currentContextId Profile to preselect, null for Default.
 * @param onProfileSelected Invoked with the context id of the chosen profile, null for Default.
 * @param onManageProfiles Invoked when "Manage profiles" is tapped.
 * @param onDismiss Invoked when the dialog is cancelled.
 * @param title Dialog title.
 */
@Composable
fun OpenInProfileDialog(
    containers: List<ContainerState>,
    currentContextId: String?,
    onProfileSelected: (contextId: String?) -> Unit,
    onManageProfiles: () -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.profiles_open_in_profile),
) {
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
                ProfileRadioRow(
                    label = stringResource(R.string.profiles_default_name),
                    icon = null,
                    selected = currentContextId == null,
                    onClick = { onProfileSelected(null) },
                )
                containers.forEach { container ->
                    ProfileRadioRow(
                        label = container.name,
                        icon = container,
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

/** A radio row with the profile icon (a blank slot for Default, so labels line up). */
@Composable
private fun ProfileRadioRow(
    label: String,
    icon: ContainerState?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                role = Role.RadioButton
            }
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            modifier = Modifier
                .padding(end = 8.dp)
                .clearAndSetSemantics {},
        )
        if (icon != null) {
            ProfileIcon(icon = icon.icon, color = icon.color.toComposeColor(), size = 20.dp)
        } else {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_globe_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = label,
            style = FirefoxTheme.typography.subtitle1,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * Profiles: hosts [OpenInProfileDialog] outside the menu sheet, so the menu can be dismissed first. Shown from the
 * activity's fragment manager. Three modes: open the URL of a tab, open a given URL, or open a new homepage tab, all in
 * the chosen profile.
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
        val tabId = requireArguments().getString(ARG_TAB_ID)
        val url = requireArguments().getString(ARG_URL)
        val components = requireContext().components
        return ComposeView(requireContext()).apply {
            setContent {
                FirefoxTheme {
                    val store = components.core.store
                    val containers = remember { store.state.containers.values.sortedBy { it.name.lowercase() } }
                    val currentContextId = remember {
                        (tabId?.let { store.state.findTab(it) } ?: store.state.selectedTab)?.contextId
                    }
                    OpenInProfileDialog(
                        containers = containers,
                        currentContextId = currentContextId,
                        title = stringResource(
                            if (url == null && tabId == null) {
                                R.string.profiles_new_tab_in_profile_title
                            } else {
                                R.string.profiles_open_in_profile
                            },
                        ),
                        onProfileSelected = { contextId ->
                            dismissAllowingStateLoss()
                            when {
                                tabId != null -> openInProfile(components, tabId, contextId)
                                url != null -> openUrlInProfile(components, url, contextId)
                                else -> openNewTabInProfile(requireContext(), components, contextId)
                            }
                            navigateToNewTab(requireActivity(), isHomepage = url == null && tabId == null)
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
        private const val ARG_URL = "url"

        /** Shows the dialog for [tabId] using [fragmentManager]. */
        fun show(fragmentManager: FragmentManager, tabId: String) =
            showWith(fragmentManager, Bundle().apply { putString(ARG_TAB_ID, tabId) })

        /** Shows the dialog to open [url] in a new tab of the chosen profile. */
        fun showForUrl(fragmentManager: FragmentManager, url: String) =
            showWith(fragmentManager, Bundle().apply { putString(ARG_URL, url) })

        /** Shows the dialog to open a new homepage tab in the chosen profile. */
        fun showForNewTab(fragmentManager: FragmentManager) = showWith(fragmentManager, Bundle())

        private fun showWith(fragmentManager: FragmentManager, args: Bundle) {
            OpenInProfileDialogFragment().apply { arguments = args }.show(fragmentManager, TAG)
        }
    }
}

/** The http(s) link a long-pressed element points to (text link or linked image), or null. */
fun HitResult.profileLinkUrl(): String? {
    val link = when (this) {
        is HitResult.UNKNOWN -> src
        is HitResult.IMAGE_SRC -> uri
        else -> return null
    }
    return link.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
}

/** The activity hosting this context, if any (unwraps context wrappers). */
fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}

private fun openInProfile(components: Components, tabId: String, contextId: String?) {
    val store = components.core.store
    val tab = store.state.findTab(tabId) ?: return
    openUrlInProfile(components, tab.content.url, contextId, desktopMode = tab.content.desktopMode)
}

/** Opens [url] in a new selected tab of [contextId] (null for Default). */
fun openUrlInProfile(
    components: Components,
    url: String,
    contextId: String?,
    desktopMode: Boolean = false,
) {
    val store = components.core.store
    val newTabId = components.useCases.tabsUseCases.addTab(
        url = url,
        selectTab = true,
        contextId = contextId,
    )
    // New tabs take the global desktop mode default; copy the one of the original tab.
    val newTab = store.state.findTab(newTabId)
    if (newTab != null && newTab.content.desktopMode != desktopMode) {
        components.useCases.sessionUseCases.requestDesktopSite(desktopMode, newTabId)
    }
}

/** Opens what a normal new tab shows (the custom homepage URL or the homepage) in a new tab of [contextId]. */
fun openNewTabInProfile(context: Context, components: Components, contextId: String?) {
    val settings = components.settings
    if (!settings.shouldUseDefaultHomepage) {
        components.useCases.tabsUseCases.addTab(
            url = settings.customHomepageUrl,
            selectTab = true,
            contextId = contextId,
        )
    } else {
        components.useCases.tabsUseCases.addTab(
            url = ABOUT_HOME_URL,
            title = context.getString(R.string.tab_tray_homepage_tab),
            selectTab = true,
            startLoading = true,
            contextId = contextId,
        )
    }
}

/**
 * After opening a tab from the tabs tray or the bookmarks list, leave that screen so the new tab shows. From the
 * browser nothing is needed.
 */
private fun navigateToNewTab(activity: FragmentActivity, isHomepage: Boolean) {
    val navController = runCatching { activity.findNavController(R.id.container) }.getOrNull() ?: return
    when (navController.currentDestination?.id) {
        R.id.tabManagementFragment -> {
            val target = if (isHomepage) R.id.homeFragment else R.id.browserFragment
            if (!navController.popBackStack(target, false)) navController.navigate(target)
        }
        R.id.bookmarkFragment ->
            navController.navigate(NavGraphDirections.actionGlobalTabManagementFragment(page = Page.NormalTabs))
        else -> Unit
    }
}
