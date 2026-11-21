/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import mozilla.components.browser.state.state.CustomTabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.theme.FirefoxTheme

/** Height of the slim bar of an app window (the window already leaves room for the status bar). */
private val AppBarHeight = 30.dp

/**
 * Profiles: the slim top bar of an app window (a home screen shortcut opened as an app): the profile badge, the page
 * title and a menu. It takes the color of the profile of [sessionId] (a neutral color for the Default profile).
 */
@Composable
@Suppress("LongParameterList", "LongMethod")
fun ProfileAppBar(
    store: BrowserStore,
    sessionId: String,
    fallbackLabel: String?,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onToggleDesktop: () -> Unit,
    onShare: () -> Unit,
    onOpenInRaven: () -> Unit,
) {
    val tab: CustomTabSessionState? by store.observeAsComposableState { state ->
        state.customTabs.firstOrNull { it.id == sessionId }
    }
    val containers by store.observeAsComposableState { it.containers }
    val current = tab ?: return
    // The bar is hidden while the page is fullscreen (video).
    if (current.content.fullScreen) return

    FirefoxTheme {
        val container = current.contextId?.let { containers[it] }
        val background = container?.let { profileColor(it) } ?: MaterialTheme.colorScheme.surfaceVariant
        val foreground = if (container != null) readableOn(background) else MaterialTheme.colorScheme.onSurfaceVariant
        var menuOpen by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .height(AppBarHeight)
                .padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (container != null) {
                ProfileBadge(
                    container = container,
                    size = 16.dp,
                    contentDescription = stringResource(R.string.profiles_chip_description, container.name),
                )
            }
            Text(
                text = current.content.title.ifBlank {
                    fallbackLabel ?: current.content.url.toUri().host.orEmpty().removePrefix("www.")
                },
                color = foreground,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            val menuDescription = stringResource(R.string.profiles_app_menu)
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier
                    .size(AppBarHeight)
                    .semantics { contentDescription = menuDescription },
            ) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_ellipsis_vertical_24),
                    contentDescription = null,
                    tint = foreground,
                    modifier = Modifier.size(20.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    AppMenuItem(stringResource(R.string.profiles_app_back), current.content.canGoBack) {
                        menuOpen = false
                        onBack()
                    }
                    AppMenuItem(stringResource(R.string.profiles_app_forward), current.content.canGoForward) {
                        menuOpen = false
                        onForward()
                    }
                    AppMenuItem(stringResource(R.string.profiles_app_reload)) {
                        menuOpen = false
                        onReload()
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profiles_app_desktop_site)) },
                        trailingIcon = { Checkbox(checked = current.content.desktopMode, onCheckedChange = null) },
                        onClick = {
                            menuOpen = false
                            onToggleDesktop()
                        },
                    )
                    AppMenuItem(stringResource(R.string.profiles_app_share)) {
                        menuOpen = false
                        onShare()
                    }
                    AppMenuItem(stringResource(R.string.profiles_app_open_in_raven)) {
                        menuOpen = false
                        onOpenInRaven()
                    }
                }
            }
        }
    }
}

@Composable
private fun AppMenuItem(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, enabled = enabled, onClick = onClick)
}

/** Black or white, whichever reads better on [background]. */
private fun readableOn(background: Color): Color = if (background.luminance() > 0.5f) Color.Black else Color.White
