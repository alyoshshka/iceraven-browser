/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.net.toUri
import java.util.concurrent.ConcurrentHashMap
import mozilla.components.browser.state.state.CustomTabConfig
import mozilla.components.browser.state.state.CustomTabSessionState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.intent.ext.putSessionId
import mozilla.components.feature.intent.processing.IntentProcessor
import mozilla.components.feature.pwa.SHORTCUT_CATEGORY
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.CustomTabsUseCases
import org.mozilla.fenix.BuildConfig

/** Profiles: intent extra (boolean): the shortcut opens in its own app window; missing means true. */
const val EXTRA_PROFILE_APP_MODE = "app.ravenprofiles.extra.APP_MODE"

/** Profiles: intent extra (string): the label of a shortcut, shown for its app window in the recent apps. */
const val EXTRA_PROFILE_APP_LABEL = "app.ravenprofiles.extra.APP_LABEL"

/** Profiles: debug builds only (end-to-end tests): the name of the profile, instead of its contextId. */
const val EXTRA_PROFILE_CONTEXT_NAME = "app.ravenprofiles.extra.CONTEXT_NAME"

private const val APP_CATEGORY_PREFIX = "app.ravenprofiles.category.APP."

/**
 * Profiles: bookkeeping of the custom tab sessions that are shown as an app window (home screen shortcut opened as an
 * app). The registry is in memory only: custom tabs do not survive a process death either.
 */
object ProfileAppMode {
    private class Info(val key: String, val label: String?)

    private val apps = ConcurrentHashMap<String, Info>()

    /** Whether the custom tab [sessionId] is an app window of a home screen shortcut. */
    fun isAppSession(sessionId: String?): Boolean = sessionId != null && apps.containsKey(sessionId)

    /** The label of the shortcut of the app window [sessionId], if known. */
    fun labelOf(sessionId: String?): String? = sessionId?.let { apps[it]?.label }

    /** The session of the open app window of the shortcut with [key]. */
    fun findSession(store: BrowserStore, key: String): String? =
        store.state.customTabs.firstOrNull { apps[it.id]?.key == key }?.id

    internal fun register(sessionId: String, key: String, label: String?) {
        apps[sessionId] = Info(key, label)
    }

    /** Identity of a shortcut: its profile and start URL. */
    internal fun keyOf(contextId: String?, url: String) = "${contextId.orEmpty()}|$url"

    /** Forgets app windows whose session is gone. */
    internal fun prune(store: BrowserStore) {
        val alive = store.state.customTabs.mapTo(HashSet()) { it.id }
        apps.keys.retainAll(alive)
    }
}

/**
 * Handles launches of home screen shortcuts that open as an app: creates (or finds) a custom tab in the shortcut's
 * profile, which the external app window ([org.mozilla.fenix.customtabs.ExternalAppBrowserActivity]) then shows with
 * a slim bar instead of the address bar. Shortcuts with [EXTRA_PROFILE_APP_MODE] set to false are left to
 * [ProfileShortcutIntentProcessor].
 */
class ProfileAppIntentProcessor(
    private val store: BrowserStore,
    private val addCustomTab: CustomTabsUseCases.AddCustomTabUseCase,
    private val requestDesktopSite: SessionUseCases.RequestDesktopSiteUseCase,
) : IntentProcessor {

    @Suppress("ReturnCount")
    override fun process(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_VIEW) return false
        val isShortcut = intent.hasCategory(SHORTCUT_CATEGORY) ||
            intent.hasExtra(EXTRA_PROFILE_CONTEXT_ID) ||
            intent.hasExtra(EXTRA_PROFILE_DESKTOP) ||
            (BuildConfig.DEBUG && intent.hasExtra(EXTRA_PROFILE_CONTEXT_NAME))
        if (!isShortcut || !intent.getBooleanExtra(EXTRA_PROFILE_APP_MODE, true)) return false

        val url = intent.dataString ?: return false
        val uri = runCatching { url.toUri() }.getOrNull() ?: return false
        if ((uri.scheme != "http" && uri.scheme != "https") || uri.host.isNullOrEmpty()) return false

        val contextId = intent.getStringExtra(EXTRA_PROFILE_CONTEXT_ID) ?: debugContextId(intent, store)
        // A shortcut of a deleted profile must not silently create a new cookie jar; let others handle it.
        // Containers load asynchronously, so on a cold start the map is still empty: trust the shortcut then.
        val containers = store.state.containers
        if (contextId != null && containers.isNotEmpty() && !containers.containsKey(contextId)) return false

        ProfileAppMode.prune(store)
        val key = ProfileAppMode.keyOf(contextId, url)
        val sessionId = ProfileAppMode.findSession(store, key) ?: addCustomTab(
            url = url,
            customTabConfig = CustomTabConfig(),
            // CustomTab (not HomeScreen): back at the first page then closes the window like any custom tab.
            source = SessionState.Source.Internal.CustomTab,
            contextId = contextId,
        ).also {
            ProfileAppMode.register(it, key, intent.getStringExtra(EXTRA_PROFILE_APP_LABEL))
            // New custom tabs follow the global desktop setting; a shortcut has its own.
            val desktop = intent.getBooleanExtra(EXTRA_PROFILE_DESKTOP, false)
            if (store.state.customTabs.firstOrNull { tab -> tab.id == it }?.content?.desktopMode != desktop) {
                requestDesktopSite(desktop, it)
            }
        }

        intent.putSessionId(sessionId)
        // One task (and entry in the recent apps) per shortcut: a new document task, told apart from the tasks of
        // other shortcuts by a category (Android compares action, data and categories, not extras).
        intent.addCategory(APP_CATEGORY_PREFIX + key.hashCode())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        return true
    }
}

/** Debug builds only: the contextId of the profile named in [EXTRA_PROFILE_CONTEXT_NAME] (end-to-end tests). */
internal fun debugContextId(intent: Intent, store: BrowserStore): String? {
    if (!BuildConfig.DEBUG) return null
    val name = intent.getStringExtra(EXTRA_PROFILE_CONTEXT_NAME) ?: return null
    return store.state.containers.values.firstOrNull { it.name == name }?.contextId
}

/** The custom tab [sessionId] of an app window, if it is one and still exists. */
fun BrowserStore.appModeTab(sessionId: String?): CustomTabSessionState? =
    if (ProfileAppMode.isAppSession(sessionId)) state.customTabs.firstOrNull { it.id == sessionId } else null

/**
 * Profiles: draws the mark of a profile in the bottom-right corner of a shortcut or task icon: its [emoji] on a white
 * disc, or a disc in [color]. Returns a new bitmap; [source] is left alone.
 */
fun badgeIcon(source: Bitmap, color: Int, emoji: String?): Bitmap {
    val size = maxOf(source.width, source.height, MIN_ICON_PX)
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val base = if (source.width == size && source.height == size) {
        source
    } else {
        Bitmap.createScaledBitmap(source, size, size, true)
    }
    canvas.drawBitmap(base, 0f, 0f, null)

    val radius = size * BADGE_RADIUS_FRACTION
    val cx = size - radius - size * BADGE_MARGIN_FRACTION
    val cy = size - radius - size * BADGE_MARGIN_FRACTION
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0xFFFFFFFF.toInt()
    canvas.drawCircle(cx, cy, radius, paint)
    if (emoji != null) {
        paint.typeface = Typeface.DEFAULT
        paint.textSize = radius * EMOJI_TEXT_FRACTION
        paint.textAlign = Paint.Align.CENTER
        val baseline = cy - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(emoji, cx, baseline, paint)
    } else {
        paint.color = color
        canvas.drawCircle(cx, cy, radius * DOT_FRACTION, paint)
    }
    return out
}

private const val MIN_ICON_PX = 192
private const val BADGE_RADIUS_FRACTION = 0.2f
private const val BADGE_MARGIN_FRACTION = 0.02f
private const val EMOJI_TEXT_FRACTION = 1.35f
private const val DOT_FRACTION = 0.72f
