/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.findNavController
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.compose.base.button.TextButton
import mozilla.components.feature.prompts.R as promptsR
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.IconListItem
import org.mozilla.fenix.compose.list.SwitchListItem
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.theme.FirefoxTheme

// Profiles: the dialog opened from the toolbar profile chip.

/**
 * Actions for the profile of a tab: header with the profile icon and name, "Open in another profile", the "Always
 * open <site> in this profile" switch and "Manage profiles".
 *
 * @param profile The tab's profile.
 * @param host Host of the tab's page, or null if it has none (no always-open switch then).
 * @param alwaysOpen Whether [host] is set to always open in [profile].
 * @param onAlwaysOpenChanged Invoked when the switch is toggled.
 * @param proxy The profile's proxy, if it has one.
 * @param proxyReachable Whether the proxy accepts connections (null while checking).
 * @param keepAlive Whether the tab is kept running in the background.
 * @param onKeepAliveChanged Invoked when the "Keep running in background" switch is toggled.
 * @param onOpenInAnotherProfile Invoked when "Open in another profile" is tapped.
 * @param onManageProfiles Invoked when "Manage profiles" is tapped.
 * @param onDismiss Invoked when the dialog is closed.
 */
@Composable
fun ProfileActionsDialog(
    profile: ContainerState,
    host: String?,
    alwaysOpen: Boolean,
    onAlwaysOpenChanged: (Boolean) -> Unit,
    keepAlive: Boolean,
    onKeepAliveChanged: (Boolean) -> Unit,
    onOpenInAnotherProfile: (() -> Unit)?,
    onManageProfiles: () -> Unit,
    onDismiss: () -> Unit,
    proxy: ProfileProxySettings = ProfileProxySettings(),
    proxyReachable: Boolean? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileIcon(icon = profile.icon, color = profile.color.toComposeColor())
                Text(
                    text = profile.name,
                    style = FirefoxTheme.typography.headline5,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        },
        text = {
            Column {
                if (proxy.isActive) {
                    val status = stringResource(
                        when (proxyReachable) {
                            true -> R.string.profiles_proxy_status_reachable
                            false -> R.string.profiles_proxy_status_unreachable
                            null -> R.string.profiles_proxy_checking
                        },
                    )
                    Text(
                        text = stringResource(
                            R.string.profiles_proxy_status,
                            if (proxy.type == ProxyType.SOCKS5) "SOCKS5" else "HTTP",
                            proxy.host,
                            proxy.port,
                            status,
                        ),
                        style = FirefoxTheme.typography.body2,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (onOpenInAnotherProfile != null) {
                    IconListItem(
                        label = stringResource(R.string.profiles_open_in_another_profile),
                        beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_external_link_24),
                        onClick = onOpenInAnotherProfile,
                    )
                }
                if (host != null) {
                    SwitchListItem(
                        label = stringResource(R.string.profiles_always_open_in_profile, host),
                        checked = alwaysOpen,
                        maxLabelLines = 3,
                        showSwitchAfter = true,
                        onClick = onAlwaysOpenChanged,
                    )
                }
                SwitchListItem(
                    label = stringResource(R.string.profiles_keep_alive),
                    description = stringResource(R.string.profiles_keep_alive_description),
                    checked = keepAlive,
                    maxLabelLines = 2,
                    showSwitchAfter = true,
                    onClick = onKeepAliveChanged,
                )
                IconListItem(
                    label = stringResource(R.string.profiles_manage),
                    beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_settings_24),
                    onClick = onManageProfiles,
                )
            }
        },
        confirmButton = {
            TextButton(
                text = stringResource(R.string.profiles_close),
                onClick = onDismiss,
            )
        },
    )
}

/** Profiles: hosts [ProfileActionsDialog] for a tab. Shown from the activity's fragment manager. */
class ProfileActionsDialogFragment : DialogFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Translucent_NoTitleBar)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val tabId = requireArguments().getString(ARG_TAB_ID).orEmpty()
        val components = requireContext().components
        val storage = ProfileStorage(requireContext())
        return ComposeView(requireContext()).apply {
            setContent {
                FirefoxTheme {
                    val state = components.core.store.state
                    val tab = remember { state.findTab(tabId) }
                    val profile = remember { tab?.contextId?.let { state.containers[it] } }
                    if (tab == null || profile == null) {
                        LaunchedEffect(Unit) { dismissAllowingStateLoss() }
                        return@FirefoxTheme
                    }
                    val url = tab.content.url
                    val host = remember {
                        if (isOpenInProfileSupported(tab)) {
                            runCatching { url.toUri().host }.getOrNull()?.lowercase()
                        } else {
                            null
                        }
                    }
                    var alwaysOpen by remember {
                        mutableStateOf(host != null && storage.ruleFor(host) == profile.contextId)
                    }
                    var keepAlive by remember { mutableStateOf(ProfileKeepAlive.isKept(tab.id)) }
                    val proxy = remember { ProfileProxy.get(profile.contextId) }
                    var proxyReachable by remember { mutableStateOf<Boolean?>(null) }
                    LaunchedEffect(proxy) {
                        if (proxy.isActive) proxyReachable = ProfileProxy.checkReachable(proxy.host, proxy.port)
                    }
                    ProfileActionsDialog(
                        proxy = proxy,
                        proxyReachable = proxyReachable,
                        profile = profile,
                        host = host,
                        alwaysOpen = alwaysOpen,
                        onAlwaysOpenChanged = { enabled ->
                            if (host != null) {
                                if (enabled) storage.setRule(host, profile.contextId) else storage.removeRule(host)
                                alwaysOpen = enabled
                            }
                        },
                        keepAlive = keepAlive,
                        onKeepAliveChanged = { enabled ->
                            ProfileKeepAlive.setKept(requireContext(), tab.id, enabled)
                            keepAlive = enabled
                        },
                        onOpenInAnotherProfile = if (isOpenInProfileSupported(tab)) {
                            {
                                dismissAllowingStateLoss()
                                OpenInProfileDialogFragment.show(parentFragmentManager, tabId)
                            }
                        } else {
                            null
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
        private const val TAG = "ProfileActionsDialog"
        private const val ARG_TAB_ID = "tab_id"

        /** Shows the dialog for [tabId] using [fragmentManager]. */
        fun show(fragmentManager: FragmentManager, tabId: String) {
            ProfileActionsDialogFragment().apply {
                arguments = Bundle().apply { putString(ARG_TAB_ID, tabId) }
            }.show(fragmentManager, TAG)
        }
    }
}
