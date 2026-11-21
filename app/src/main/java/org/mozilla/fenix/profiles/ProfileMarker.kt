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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
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

/**
 * Profiles: draws [emoji] in a square of [size], as a replacement of the profile icon. Decorative unless
 * [contentDescription] is set.
 */
@Composable
fun ProfileEmoji(
    emoji: String,
    modifier: Modifier = Modifier,
    size: Dp = ProfileIconSize,
    contentDescription: String? = null,
) {
    val fontSize = with(LocalDensity.current) { (size * EMOJI_FRACTION).toSp() }
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier.clearAndSetSemantics {}
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = emoji,
            fontSize = fontSize,
            lineHeight = fontSize,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private const val EMOJI_FRACTION = 0.8f

/** [name] with [emoji] in front, for menus that can only show an icon resource (the emoji can't be drawn there). */
fun withEmoji(emoji: String?, name: String): String = if (emoji == null) name else "$emoji $name"

/** The color to draw [container]: its custom color, else the one of its container color. */
@Composable
fun profileColor(container: ContainerState): Color {
    val looks by ProfileAppearance.looks.collectAsState()
    return looks[container.contextId]?.color?.let { Color(it) } ?: container.color.toComposeColor()
}

/**
 * Profiles: the one place that draws a profile's mark: its emoji if it has one, else its icon tinted with its color
 * (custom color first). [icon] null draws a dot instead; [color] is the fallback when the profile has no custom color.
 * [contextId] null (or unknown) draws [icon] in [color] as given.
 */
@Composable
fun ProfileBadge(
    contextId: String?,
    icon: ContainerState.Icon?,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = ProfileIconSize,
    contentDescription: String? = null,
) {
    val looks by ProfileAppearance.looks.collectAsState()
    val look = contextId?.let { looks[it] }
    val emoji = look?.emoji
    val tint = look?.color?.let { Color(it) } ?: color
    when {
        emoji != null -> ProfileEmoji(emoji, modifier, size, contentDescription)
        icon == null -> ProfileDot(color = tint, modifier = modifier)
        else -> ProfileIcon(icon, tint, modifier, size, contentDescription)
    }
}

/** Profiles: [ProfileBadge] of [container]. */
@Composable
fun ProfileBadge(
    container: ContainerState,
    modifier: Modifier = Modifier,
    size: Dp = ProfileIconSize,
    contentDescription: String? = null,
) = ProfileBadge(
    contextId = container.contextId,
    icon = container.icon,
    color = container.color.toComposeColor(),
    modifier = modifier,
    size = size,
    contentDescription = contentDescription,
)

/**
 * Profiles: the small profile mark shown on tab cards, the tab strip and download rows (a dot if the icon is
 * unknown). [profileId] lets a custom color or emoji of that profile take over.
 */
@Composable
fun ProfileTabIcon(
    icon: ContainerState.Icon?,
    color: Color,
    modifier: Modifier = Modifier,
    profileId: String? = null,
) {
    ProfileBadge(
        contextId = profileId,
        icon = icon,
        color = color,
        modifier = modifier,
        size = ProfileTabIconSize,
    )
}

/** Size of [ProfileTabIcon]. */
val ProfileTabIconSize: Dp = 14.dp

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

/** A color a user can pick for a profile. */
data class ProfileSwatch(val argb: Int, @StringRes val nameRes: Int)

/** The color presets of the editor: the eight Firefox colors first, then more that read on light and dark. */
val ProfileSwatches: List<ProfileSwatch> =
    ContainerState.Color.values().filter { it != ContainerState.Color.TOOLBAR }
        .map { ProfileSwatch(it.toArgb(), it.nameRes()) } + listOf(
        ProfileSwatch(0xFFD63F5C.toInt(), R.string.profiles_color_crimson),
        ProfileSwatch(0xFFF06292.toInt(), R.string.profiles_color_rose),
        ProfileSwatch(0xFFC2399E.toInt(), R.string.profiles_color_magenta),
        ProfileSwatch(0xFF7E57C2.toInt(), R.string.profiles_color_violet),
        ProfileSwatch(0xFF5C6BC0.toInt(), R.string.profiles_color_indigo),
        ProfileSwatch(0xFF4FC3F7.toInt(), R.string.profiles_color_sky),
        ProfileSwatch(0xFF26C6DA.toInt(), R.string.profiles_color_cyan),
        ProfileSwatch(0xFF1E9E8F.toInt(), R.string.profiles_color_teal),
        ProfileSwatch(0xFF4CD39A.toInt(), R.string.profiles_color_mint),
        ProfileSwatch(0xFF9CCC3C.toInt(), R.string.profiles_color_lime),
        ProfileSwatch(0xFF8A9A3B.toInt(), R.string.profiles_color_olive),
        ProfileSwatch(0xFFF5A623.toInt(), R.string.profiles_color_amber),
        ProfileSwatch(0xFFD2622A.toInt(), R.string.profiles_color_rust),
        ProfileSwatch(0xFFA47551.toInt(), R.string.profiles_color_brown),
        ProfileSwatch(0xFF78909C.toInt(), R.string.profiles_color_slate),
        ProfileSwatch(0xFF9E9E9E.toInt(), R.string.profiles_color_gray),
    )

/** "#RRGGBB" of [argb] (alpha ignored). */
fun hexOf(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/** The colors of the container (enum) palette, in display order. */
val ProfileColorPalette: List<ContainerState.Color> =
    ContainerState.Color.values().filter { it != ContainerState.Color.TOOLBAR }

/** The first swatch color not used by [existing] profiles (the first one if all are used), as ARGB. */
fun firstUnusedColor(existing: Collection<ContainerState>): Int {
    val used = existing.map { ProfileAppearance.colorOf(it) }.toSet()
    return (ProfileSwatches.firstOrNull { it.argb !in used } ?: ProfileSwatches.first()).argb
}

@Composable
@PreviewLightDark
private fun ProfileDotPreview() {
    FirefoxTheme {
        ProfileDot(color = ContainerState.Color.BLUE.toComposeColor())
    }
}
