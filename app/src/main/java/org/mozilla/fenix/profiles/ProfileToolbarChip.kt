/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.Context
import androidx.appcompat.content.res.AppCompatResources
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.compose.browser.toolbar.concept.Action
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction
import org.mozilla.fenix.R

// Profiles: the profile indicator in the toolbar (icon in the profile color and the profile name).
private const val MAX_LABEL = 14

/** The profile of [tab] to show in the toolbar: normal tabs of a profile only, null for Default and private tabs. */
fun BrowserState.toolbarProfileOf(tab: TabSessionState?): ContainerState? =
    tab?.takeIf { !it.content.private }?.contextId?.let { containers[it] }

/**
 * Builds the toolbar action for [profile]: its icon tinted with its color (custom color first) and its name; with an
 * emoji the emoji goes in front of the name and replaces the icon. [onClick] is used for
 * tap and long-press.
 */
fun profileToolbarAction(
    context: Context,
    profile: ContainerState?,
    onClick: BrowserToolbarInteraction,
): Action {
    val emoji = profile?.let { ProfileAppearance.emojiOf(it.contextId) }
    val icon = profile?.icon ?: ContainerState.Icon.CIRCLE
    val color = profile?.let { ProfileAppearance.colorOf(it) }
    // The emoji replaces the icon: it is part of the label and no icon is drawn.
    val drawable = if (emoji != null) null else AppCompatResources.getDrawable(context, icon.drawableRes())?.mutate()
    return Action.ActionButton(
        drawable = drawable,
        shouldTint = false,
        contentDescription = context.getString(
            R.string.profiles_chip_description,
            profile?.name ?: context.getString(R.string.profiles_default_name),
        ),
        onClick = onClick,
        onLongClick = onClick,
        tint = color,
        label = profile?.name?.let { withEmoji(emoji, if (it.length > MAX_LABEL) it.take(MAX_LABEL - 1) + "…" else it) },
    )
}
