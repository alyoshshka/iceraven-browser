/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import android.graphics.Color as AndroidColor
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.compose.base.button.TextButton
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import mozilla.components.compose.base.SelectableChip
import org.mozilla.fenix.theme.FirefoxTheme

private val SwatchSize = 36.dp
private const val SWATCHES_PER_ROW = 5
private val SquareHeight = 160.dp
private val HueHeight = 28.dp
private val SuggestedEmoji = listOf(
    "🦊", "🏠", "💼", "🛒", "🎮", "🎓", "✈️", "💰", "🎵", "🔒", "🧪", "❤️", "⭐", "🐱", "🌲",
)

/**
 * Profiles: the appearance part of the profile editor and of the link chooser's new-profile form: a color
 * (presets or a custom one), then an icon or an emoji.
 *
 * @param color ARGB color of the profile.
 * @param emoji The profile's emoji; null selects the Icon mode, an empty string the Emoji mode without a choice yet.
 */
@Composable
internal fun ProfileAppearancePicker(
    color: Int,
    onColorChange: (Int) -> Unit,
    icon: ContainerState.Icon,
    onIconChange: (ContainerState.Icon) -> Unit,
    emoji: String?,
    onEmojiChange: (String?) -> Unit,
) {
    var lastEmoji by remember { mutableStateOf(emoji.orEmpty()) }
    val composeColor = Color(color)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.profiles_color_label),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = FirefoxTheme.typography.caption,
            modifier = Modifier.weight(1f),
        )
        if (!emoji.isNullOrEmpty()) {
            ProfileEmoji(emoji = emoji, size = 28.dp)
        } else {
            ProfileIcon(icon = icon, color = composeColor, size = 28.dp)
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    ProfileColorPicker(selected = color, onSelected = onColorChange)
    Spacer(modifier = Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectableChip(
            text = stringResource(R.string.profiles_icon_mode_icon),
            selected = emoji == null,
            onClick = {
                if (emoji != null) lastEmoji = emoji
                onEmojiChange(null)
            },
        )
        SelectableChip(
            text = stringResource(R.string.profiles_icon_mode_emoji),
            selected = emoji != null,
            onClick = { if (emoji == null) onEmojiChange(lastEmoji) },
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    if (emoji == null) {
        ProfileIconPicker(selected = icon, color = composeColor, onSelected = onIconChange)
    } else {
        ProfileEmojiPicker(emoji = emoji, onEmojiChange = onEmojiChange)
    }
}

/** The 24 presets, then a "Custom color" swatch that opens [CustomColorDialog]. */
@Composable
internal fun ProfileColorPicker(
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    var showCustom by remember { mutableStateOf(false) }
    val isCustom = ProfileSwatches.none { it.argb == selected }
    val customName = stringResource(R.string.profiles_color_custom)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (0..ProfileSwatches.size).chunked(SWATCHES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { index ->
                    val swatch = ProfileSwatches.getOrNull(index)
                    if (swatch != null) {
                        ColorSwatch(
                            color = Color(swatch.argb),
                            name = stringResource(swatch.nameRes),
                            isSelected = swatch.argb == selected,
                            onClick = { onSelected(swatch.argb) },
                        )
                    } else {
                        // The custom swatch shows the current custom color (a rainbow until one is chosen).
                        ColorSwatch(
                            color = if (isCustom) Color(selected) else null,
                            name = if (isCustom) "$customName ${hexOf(selected)}" else customName,
                            isSelected = isCustom,
                            onClick = { showCustom = true },
                        )
                    }
                }
            }
        }
    }
    if (showCustom) {
        CustomColorDialog(
            initial = selected,
            onSelect = {
                onSelected(it)
                showCustom = false
            },
            onDismiss = { showCustom = false },
        )
    }
}

@Composable
private fun ColorSwatch(
    color: Color?,
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val rainbow = remember { Brush.sweepGradient(HueColors + HueColors.first()) }
    Box(
        modifier = Modifier
            .size(SwatchSize)
            .clip(CircleShape)
            .then(if (color != null) Modifier.background(color) else Modifier.background(rainbow))
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = name
                this.selected = isSelected
                role = Role.RadioButton
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected && color != null) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_checkmark_24),
                contentDescription = null,
                tint = if (isLight(color)) Color.Black else Color.White,
            )
        } else if (color == null) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_plus_24),
                contentDescription = null,
                tint = Color.White,
            )
        }
    }
}

private fun isLight(color: Color): Boolean = color.luminance() > 0.45f

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private val HueColors = (0..6).map { Color.hsv(it * 60f, 1f, 1f) }

/** Emoji mode: a field (type, or pick from the keyboard) and a row of suggestions. */
@Composable
private fun ProfileEmojiPicker(
    emoji: String,
    onEmojiChange: (String?) -> Unit,
) {
    Column {
        OutlinedTextField(
            value = emoji,
            onValueChange = { typed -> onEmojiChange(lastEmoji(typed) ?: if (typed.isEmpty()) "" else emoji) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = FirefoxTheme.typography.body1,
            label = { Text(stringResource(R.string.profiles_emoji_field), style = FirefoxTheme.typography.caption) },
            supportingText = {
                Text(stringResource(R.string.profiles_emoji_hint), style = FirefoxTheme.typography.caption)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SuggestedEmoji.forEach { option ->
                val isSelected = option == emoji
                val description = stringResource(R.string.profiles_emoji_suggestion, option)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .then(
                            if (isSelected) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable(role = Role.RadioButton, onClick = { onEmojiChange(option) })
                        .semantics {
                            contentDescription = description
                            this.selected = isSelected
                            role = Role.RadioButton
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ProfileEmoji(emoji = option, size = 28.dp)
                }
            }
        }
    }
}

/** The last emoji in [typed] (so typing a new one replaces the old), or null if it holds none. */
internal fun lastEmoji(typed: String): String? {
    val breaker = BreakIterator.getCharacterInstance()
    breaker.setText(typed)
    var end = breaker.last()
    var start = breaker.previous()
    while (start != BreakIterator.DONE) {
        val cluster = typed.substring(start, end)
        if (isEmoji(cluster)) return cluster
        end = start
        start = breaker.previous()
    }
    return null
}

private fun isEmoji(cluster: String): Boolean =
    cluster.codePoints().anyMatch { it >= 0x80 && UCharacter.hasBinaryProperty(it, UProperty.EMOJI) }

/** Dialog with a hue slider, a saturation/brightness square, a live preview and a #RRGGBB field. */
@Composable
private fun CustomColorDialog(
    initial: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = remember {
        FloatArray(3).also { AndroidColor.colorToHSV(initial, it) }
    }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    // TextFieldValue (not String): a String-backed field loses keystrokes typed faster than it recomposes.
    var hexField by remember { mutableStateOf(hexOf(initial).let { TextFieldValue(it, TextRange(it.length)) }) }
    val hex = hexField.text
    val current = Color.hsv(hue, sat, value)
    val parsed = parseHex(hex)

    fun fromHsv(h: Float = hue, s: Float = sat, v: Float = value) {
        hue = h
        sat = s
        value = v
        hexOf(Color.hsv(h, s, v).toArgbInt()).let { hexField = TextFieldValue(it, TextRange(it.length)) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.profiles_color_custom_title),
                style = FirefoxTheme.typography.headline5,
            )
        },
        text = {
            val squareDescription = stringResource(R.string.profiles_color_custom_square)
            val hueDescription = stringResource(R.string.profiles_color_custom_hue)
            Column {
                // Saturation (x) and brightness (y) of the current hue.
                var squareSize by remember { mutableStateOf(IntSize.Zero) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SquareHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .onSizeChanged { squareSize = it }
                        .semantics { contentDescription = squareDescription }
                        .dragPosition { p ->
                            fromHsv(
                                s = (p.x / squareSize.width).coerceIn(0f, 1f),
                                v = 1f - (p.y / squareSize.height).coerceIn(0f, 1f),
                            )
                        },
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        val center = Offset(sat * size.width, (1f - value) * size.height)
                        drawCircle(Color.White, radius = 11.dp.toPx(), center = center, style = Stroke(3.dp.toPx()))
                        drawCircle(Color.Black, radius = 13.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                var hueSize by remember { mutableStateOf(IntSize.Zero) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HueHeight)
                        .clip(RoundedCornerShape(14.dp))
                        .onSizeChanged { hueSize = it }
                        .semantics { contentDescription = hueDescription }
                        .dragPosition { p -> fromHsv(h = (p.x / hueSize.width).coerceIn(0f, 1f) * 359.99f) },
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        drawRect(Brush.horizontalGradient(HueColors))
                        val x = hue / 359.99f * size.width
                        drawCircle(Color.White, radius = 11.dp.toPx(), center = Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
                        drawCircle(Color.Black, radius = 13.dp.toPx(), center = Offset(x, size.height / 2), style = Stroke(1.dp.toPx()))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileBadge(
                        contextId = null,
                        icon = ContainerState.Icon.CIRCLE,
                        color = current,
                        size = 40.dp,
                        contentDescription = stringResource(R.string.profiles_color_preview),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hexField,
                        onValueChange = { typed ->
                            hexField = typed
                            parseHex(typed.text)?.let {
                                val hsv = FloatArray(3).also { arr -> AndroidColor.colorToHSV(it, arr) }
                                hue = hsv[0]
                                sat = hsv[1]
                                value = hsv[2]
                            }
                        },
                        modifier = Modifier.weight(1f),
                        textStyle = FirefoxTheme.typography.body1,
                        label = {
                            Text(
                                stringResource(R.string.profiles_color_custom_hex),
                                style = FirefoxTheme.typography.caption,
                            )
                        },
                        isError = parsed == null,
                        supportingText = if (parsed == null) {
                            {
                                Text(
                                    stringResource(R.string.profiles_color_custom_hex_error),
                                    style = FirefoxTheme.typography.caption,
                                )
                            }
                        } else {
                            null
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                text = stringResource(R.string.profiles_color_custom_select),
                onClick = { onSelect(parsed ?: current.toArgbInt()) },
            )
        },
        dismissButton = {
            TextButton(
                text = stringResource(mozilla.components.feature.prompts.R.string.mozac_feature_prompts_cancel),
                onClick = onDismiss,
            )
        },
    )
}

private fun Color.toArgbInt(): Int = toArgb()

/** Parses "#RRGGBB" (the # is optional) to an opaque ARGB color; null if it isn't one. */
internal fun parseHex(text: String): Int? {
    val digits = text.removePrefix("#")
    if (digits.length != 6 || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return 0xFF000000.toInt() or digits.toInt(16)
}

/** Calls [onPosition] for the touch-down and every move of a drag, with the position inside the element. */
private fun Modifier.dragPosition(onPosition: (Offset) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            onPosition(down.position)
            down.consume()
            drag(down.id) {
                onPosition(it.position)
                it.consume()
            }
        }
    }
