/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.feature.intent.ext.putSessionId
import mozilla.components.feature.intent.processing.IntentProcessor
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases

/** Profiles: intent extra holding the contextId of the profile a home screen shortcut belongs to. */
const val EXTRA_PROFILE_CONTEXT_ID = "app.ravenprofiles.extra.CONTEXT_ID"

/** Profiles: intent extra (boolean) telling whether a home screen shortcut opens the desktop site. */
const val EXTRA_PROFILE_DESKTOP = "app.ravenprofiles.extra.DESKTOP"

/** Builds the extras stored on a home screen shortcut created from [tab]. */
fun profileShortcutExtras(tab: SessionState): Bundle = Bundle().apply {
    tab.contextId?.let { putString(EXTRA_PROFILE_CONTEXT_ID, it) }
    putBoolean(EXTRA_PROFILE_DESKTOP, tab.content.desktopMode)
}

/**
 * Handles launches of home screen shortcuts created with [profileShortcutExtras]: opens the shortcut's URL in a tab of
 * the shortcut's profile (reusing an open tab of the same profile and host) and applies desktop mode.
 */
class ProfileShortcutIntentProcessor(
    private val store: BrowserStore,
    private val addNewTab: TabsUseCases.AddNewTabUseCase,
    private val selectTab: TabsUseCases.SelectTabUseCase,
    private val requestDesktopSite: SessionUseCases.RequestDesktopSiteUseCase,
) : IntentProcessor {

    override fun process(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_VIEW) return false
        if (!intent.hasExtra(EXTRA_PROFILE_CONTEXT_ID) && !intent.hasExtra(EXTRA_PROFILE_DESKTOP)) return false

        val uri = intent.dataString?.let { runCatching { it.toUri() }.getOrNull() } ?: return false
        val host = uri.host
        if ((uri.scheme != "http" && uri.scheme != "https") || host.isNullOrEmpty()) return false

        val contextId = intent.getStringExtra(EXTRA_PROFILE_CONTEXT_ID)
        // A shortcut of a deleted profile must not silently create a new cookie jar; let others handle it.
        if (contextId != null && !store.state.containers.containsKey(contextId)) return false
        val desktop = intent.getBooleanExtra(EXTRA_PROFILE_DESKTOP, false)

        val existing = findExistingTab(contextId, host)
        val tabId =
            if (existing != null) {
                selectTab(existing.id)
                existing.id
            } else {
                addNewTab(
                    url = uri.toString(),
                    flags = EngineSession.LoadUrlFlags.external(),
                    source = SessionState.Source.Internal.HomeScreen,
                    selectTab = true,
                    startLoading = true,
                    contextId = contextId,
                ).also { if (desktop) requestDesktopSite(true, it) }
            }

        intent.putSessionId(tabId)
        return true
    }

    private fun findExistingTab(contextId: String?, host: String): TabSessionState? {
        val candidates = store.state.tabs.filter { tab ->
            !tab.content.private &&
                tab.contextId == contextId &&
                tab.content.url.toUri().host.equals(host, ignoreCase = true)
        }
        // Prefer the tab the user is already on.
        return candidates.firstOrNull { it.id == store.state.selectedTab?.id } ?: candidates.firstOrNull()
    }
}
