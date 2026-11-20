/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.request.RequestInterceptor.InterceptionResponse
import org.mozilla.fenix.ext.components

/** Profiles: what to do with a top-level navigation, given the tab's profile and the site rules. */
sealed interface SiteRoute {
    /** Load in the current tab. */
    data object Stay : SiteRoute

    /** Reopen in a new tab of [contextId] (null for the Default profile). */
    data class Open(val contextId: String?) : SiteRoute
}

/**
 * Profiles: "Always open this site in…" and "Limit to designated sites" for every navigation, not only links from
 * other apps. [route] is pure; [intercept] is the thin glue called from the request interceptor.
 *
 * Skipped: sub-frames, private tabs, custom tabs, non-http(s) URLs and loads that already bounced between profiles
 * recently (so a redirect chain can't ping-pong forever).
 */
class ProfileSiteRouter(private val context: Context) {

    private val storage by lazy { ProfileStorage(context) }
    private val recent = LinkedHashMap<String, Long>()

    /**
     * Called on a top-level load of [uri] in [engineSession]. Returns [InterceptionResponse.Deny] after opening the
     * URL in another profile's tab, or null to let the load proceed.
     */
    fun intercept(
        engineSession: EngineSession,
        uri: String,
        lastUri: String?,
        isSubframeRequest: Boolean,
    ): InterceptionResponse? {
        if (isSubframeRequest || !(uri.startsWith("http://") || uri.startsWith("https://"))) return null
        val host = runCatching { uri.toUri().host }.getOrNull()?.lowercase()
        if (host.isNullOrEmpty()) return null

        val components = context.components
        val tab = components.core.store.state.findTabOrCustomTab(engineSession) as? TabSessionState ?: return null
        if (tab.content.private) return null

        val rules = storage.rules()
        val limited = storage.limitedProfiles()
        if (rules.isEmpty() && limited.isEmpty()) return null

        val known = components.core.store.state.containers.keys
        val route = route(host, tab.contextId, rules, limited, known) as? SiteRoute.Open ?: return null
        if (!allowRedirect(tab.id, uri)) return null

        // A blank tab with no history has nothing to go back to: replace it instead of leaving it behind.
        val replaceTab = !tab.content.canGoBack && isBlank(lastUri, uri)
        val desktop = tab.content.desktopMode
        Handler(Looper.getMainLooper()).post {
            val newTabId = components.useCases.tabsUseCases.addTab(
                url = uri,
                selectTab = true,
                contextId = route.contextId,
            )
            if (desktop) components.useCases.sessionUseCases.requestDesktopSite(true, newTabId)
            if (replaceTab) components.useCases.tabsUseCases.removeTab(tab.id)
        }
        return InterceptionResponse.Deny
    }

    @Synchronized
    private fun allowRedirect(tabId: String, uri: String, now: Long = System.currentTimeMillis()): Boolean {
        recent.entries.removeAll { now - it.value > WINDOW_MS }
        if (recent.containsKey("$tabId|$uri") || recent.size >= MAX_REDIRECTS_PER_WINDOW) return false
        recent["$tabId|$uri"] = now
        return true
    }

    private fun isBlank(lastUri: String?, uri: String) =
        lastUri.isNullOrBlank() || lastUri.startsWith("about:") || lastUri == uri

    companion object {
        private const val WINDOW_MS = 5_000L
        private const val MAX_REDIRECTS_PER_WINDOW = 4

        /**
         * Decides where [host], loading in a tab of [tabContextId] (null for Default), belongs.
         *
         * @param rules host -> contextId ([ProfileStorage.DEFAULT_ID] for Default) of "always open" sites.
         * @param limited Profiles limited to their designated sites.
         * @param knownContextIds Existing profiles; rules pointing at others are stale and ignored.
         */
        fun route(
            host: String,
            tabContextId: String?,
            rules: Map<String, String>,
            limited: Set<String>,
            knownContextIds: Set<String>,
        ): SiteRoute {
            val current = tabContextId ?: ProfileStorage.DEFAULT_ID
            val target = matchRule(host, rules)
                ?.takeIf { it == ProfileStorage.DEFAULT_ID || it in knownContextIds }
            val destination = when {
                target != null -> target
                current in limited -> ProfileStorage.DEFAULT_ID
                else -> current
            }
            return if (destination == current) {
                SiteRoute.Stay
            } else {
                SiteRoute.Open(destination.takeIf { it != ProfileStorage.DEFAULT_ID })
            }
        }

        /** The rule for [host] or its closest parent domain ("a.b.com" matches "b.com"), else null. */
        fun matchRule(host: String, rules: Map<String, String>): String? {
            var candidate = host.lowercase()
            while (true) {
                rules[candidate]?.let { return it }
                if (!candidate.contains('.')) return null
                candidate = candidate.substringAfter('.')
            }
        }
    }
}
