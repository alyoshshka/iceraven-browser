/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.state.ContainerState
import org.mozilla.fenix.R
import org.mozilla.fenix.theme.FirefoxTheme

// Profiles: the colored dot that marks a profile everywhere (tabs, tab strip, downloads, settings).

/** Diameter of the profile dot shown next to text. */
val ProfileDotSize: Dp = 8.dp

/** Profiles: draws a small filled circle in [color]. Decorative, hidden from accessibility. */
@Composable
fun ProfileDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = ProfileDotSize,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(color = color, shape = CircleShape)
            .clearAndSetSemantics {},
    )
}

/** Profiles: draws the [icon] of a profile tinted with [color]. Decorative unless [contentDescription] is set. */
@Composable
fun ProfileIcon(
    icon: ContainerState.Icon,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = ProfileIconSize,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(icon.drawableRes()),
        contentDescription = contentDescription,
        tint = color,
        modifier = modifier.size(size),
    )
}

/** Default size of [ProfileIcon]. */
val ProfileIconSize: Dp = 24.dp

/** Drawable of this container icon (the Firefox container icons). */
@DrawableRes
fun ContainerState.Icon.drawableRes(): Int = when (this) {
    ContainerState.Icon.FINGERPRINT -> R.drawable.ic_profile_fingerprint
    ContainerState.Icon.BRIEFCASE -> R.drawable.ic_profile_briefcase
    ContainerState.Icon.DOLLAR -> R.drawable.ic_profile_dollar
    ContainerState.Icon.CART -> R.drawable.ic_profile_cart
    ContainerState.Icon.CIRCLE -> R.drawable.ic_profile_circle
    ContainerState.Icon.GIFT -> R.drawable.ic_profile_gift
    ContainerState.Icon.VACATION -> R.drawable.ic_profile_vacation
    ContainerState.Icon.FOOD -> R.drawable.ic_profile_food
    ContainerState.Icon.FRUIT -> R.drawable.ic_profile_fruit
    ContainerState.Icon.PET -> R.drawable.ic_profile_pet
    ContainerState.Icon.TREE -> R.drawable.ic_profile_tree
    ContainerState.Icon.CHILL -> R.drawable.ic_profile_chill
    ContainerState.Icon.FENCE -> R.drawable.ic_profile_fence
}

/** String resource with the (spoken) name of this icon. */
@StringRes
fun ContainerState.Icon.nameRes(): Int = when (this) {
    ContainerState.Icon.FINGERPRINT -> R.string.profiles_icon_fingerprint
    ContainerState.Icon.BRIEFCASE -> R.string.profiles_icon_briefcase
    ContainerState.Icon.DOLLAR -> R.string.profiles_icon_dollar
    ContainerState.Icon.CART -> R.string.profiles_icon_cart
    ContainerState.Icon.CIRCLE -> R.string.profiles_icon_circle
    ContainerState.Icon.GIFT -> R.string.profiles_icon_gift
    ContainerState.Icon.VACATION -> R.string.profiles_icon_vacation
    ContainerState.Icon.FOOD -> R.string.profiles_icon_food
    ContainerState.Icon.FRUIT -> R.string.profiles_icon_fruit
    ContainerState.Icon.PET -> R.string.profiles_icon_pet
    ContainerState.Icon.TREE -> R.string.profiles_icon_tree
    ContainerState.Icon.CHILL -> R.string.profiles_icon_chill
    ContainerState.Icon.FENCE -> R.string.profiles_icon_fence
}

/** The icons a user can pick for a profile, in display order. */
val ProfileIconPalette: List<ContainerState.Icon> = ContainerState.Icon.values().toList()

// Profiles: ARGB values of the Firefox container colors (there is no Acorn equivalent).
private const val BLUE = 0xFF37ADFF
private const val TURQUOISE = 0xFF00C79A
private const val GREEN = 0xFF51CD00
private const val YELLOW = 0xFFFFCB00
private const val ORANGE = 0xFFFF9F00
private const val RED = 0xFFFF613D
private const val PINK = 0xFFFF4BDA
private const val PURPLE = 0xFFAF51F5
private const val TOOLBAR = 0xFF7C7C7D

/** Returns the ARGB color used to draw this container color. */
fun ContainerState.Color.toArgb(): Int = when (this) {
    ContainerState.Color.BLUE -> BLUE
    ContainerState.Color.TURQUOISE -> TURQUOISE
    ContainerState.Color.GREEN -> GREEN
    ContainerState.Color.YELLOW -> YELLOW
    ContainerState.Color.ORANGE -> ORANGE
    ContainerState.Color.RED -> RED
    ContainerState.Color.PINK -> PINK
    ContainerState.Color.PURPLE -> PURPLE
    ContainerState.Color.TOOLBAR -> TOOLBAR
}.toInt()

/** Returns the Compose color used to draw this container color. */
fun ContainerState.Color.toComposeColor(): Color = Color(toArgb())

/** String resource with the (spoken) name of this color. */
@StringRes
fun ContainerState.Color.nameRes(): Int = when (this) {
    ContainerState.Color.BLUE -> R.string.profiles_color_blue
    ContainerState.Color.TURQUOISE -> R.string.profiles_color_turquoise
    ContainerState.Color.GREEN -> R.string.profiles_color_green
    ContainerState.Color.YELLOW -> R.string.profiles_color_yellow
    ContainerState.Color.ORANGE -> R.string.profiles_color_orange
    ContainerState.Color.RED -> R.string.profiles_color_red
    ContainerState.Color.PINK -> R.string.profiles_color_pink
    ContainerState.Color.PURPLE -> R.string.profiles_color_purple
    ContainerState.Color.TOOLBAR -> R.string.profiles_color_gray
}

/** The colors a user can pick for a profile, in display order. */
val ProfileColorPalette: List<ContainerState.Color> =
    ContainerState.Color.values().filter { it != ContainerState.Color.TOOLBAR }

/** The first palette color not used by [existing] profiles (the first one if all are used). */
fun firstUnusedColor(existing: Collection<ContainerState>): ContainerState.Color {
    val used = existing.map { it.color }.toSet()
    return ProfileColorPalette.firstOrNull { it !in used } ?: ProfileColorPalette.first()
}

@Composable
@PreviewLightDark
private fun ProfileDotPreview() {
    FirefoxTheme {
        ProfileDot(color = ContainerState.Color.BLUE.toComposeColor())
    }
}
