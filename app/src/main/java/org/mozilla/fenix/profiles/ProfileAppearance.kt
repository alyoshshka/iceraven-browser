/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color as AndroidColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.ContainerState

/**
 * Profiles: the appearance a profile has on top of [ContainerState.color] and [ContainerState.icon] (which are fixed
 * enums): a custom ARGB [color] and/or an [emoji] that replaces the icon.
 */
data class ProfileLook(val color: Int? = null, val emoji: String? = null)

/**
 * Profiles: stores the [ProfileLook] of every profile (by contextId) in shared preferences, and exposes it as a
 * [StateFlow] that composables observe. Preferences are read on the IO dispatcher by [start] (debug builds crash on
 * main-thread disk reads); until then the flow is empty and profiles show their container color and icon.
 */
object ProfileAppearance {
    private const val PREFS = "profiles_appearance"
    private const val COLOR_SUFFIX = ".color"
    private const val EMOJI_SUFFIX = ".emoji"

    private val _looks = MutableStateFlow<Map<String, ProfileLook>>(emptyMap())

    /** contextId -> look of the profiles that have a custom color or an emoji. */
    val looks: StateFlow<Map<String, ProfileLook>> = _looks.asStateFlow()

    /** The look of the profile [contextId], or null if it uses its container color and icon. */
    fun lookOf(contextId: String?): ProfileLook? = contextId?.let { _looks.value[it] }

    /** The emoji of the profile [contextId], or null. */
    fun emojiOf(contextId: String?): String? = lookOf(contextId)?.emoji

    /** The ARGB color to draw [container]: its custom color, else the color of its container color. */
    fun colorOf(container: ContainerState): Int = lookOf(container.contextId)?.color ?: container.color.toArgb()

    /** Loads the stored looks on the IO dispatcher. Call once at startup. */
    fun start(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val loaded = read(prefs(appContext))
            _looks.update { loaded + it } // changes made while loading win
        }
    }

    /** Sets (or, if both are null, clears) the look of [contextId]; the flow updates at once, storage in the background. */
    fun set(context: Context, contextId: String, color: Int?, emoji: String?) {
        val look = ProfileLook(color, emoji?.takeIf { it.isNotBlank() })
        _looks.update { if (look.color == null && look.emoji == null) it - contextId else it + (contextId to look) }
        persist(context.applicationContext) { editor ->
            editor.remove("$contextId$COLOR_SUFFIX").remove("$contextId$EMOJI_SUFFIX")
            look.color?.let { editor.putInt("$contextId$COLOR_SUFFIX", it) }
            look.emoji?.let { editor.putString("$contextId$EMOJI_SUFFIX", it) }
        }
    }

    /**
     * Stores the look chosen in the editor for [contextId] and returns the [ContainerState.Color] to set on its
     * container: the exact enum color if [argb] is one, else the nearest (the real color is kept as a custom color).
     */
    fun apply(context: Context, contextId: String, argb: Int, emoji: String?): ContainerState.Color {
        val exact = ProfileColorPalette.firstOrNull { it.toArgb() == argb }
        set(context, contextId, if (exact == null) argb else null, emoji)
        return exact ?: nearestColor(argb)
    }

    /** Forgets the look of a deleted profile. */
    fun remove(context: Context, contextId: String) = set(context, contextId, null, null)

    private fun persist(appContext: Context, edit: (SharedPreferences.Editor) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { prefs(appContext).edit().also(edit).apply() }
    }

    private fun prefs(appContext: Context): SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(prefs: SharedPreferences): Map<String, ProfileLook> {
        val all = prefs.all
        val ids = all.keys.map { it.substringBeforeLast('.') }.toSet()
        return ids.mapNotNull { id ->
            val color = all["$id$COLOR_SUFFIX"] as? Int
            val emoji = (all["$id$EMOJI_SUFFIX"] as? String)?.takeIf { it.isNotBlank() }
            if (color == null && emoji == null) null else id to ProfileLook(color, emoji)
        }.toMap()
    }

    /** The preset color nearest to [argb], for the [ContainerState.color] the engine side keeps. */
    fun nearestColor(argb: Int): ContainerState.Color =
        ProfileColorPalette.minBy { distance(it.toArgb(), argb) }

    private fun distance(a: Int, b: Int): Int {
        val dr = AndroidColor.red(a) - AndroidColor.red(b)
        val dg = AndroidColor.green(a) - AndroidColor.green(b)
        val db = AndroidColor.blue(a) - AndroidColor.blue(b)
        return dr * dr + dg * dg + db * db
    }
}
