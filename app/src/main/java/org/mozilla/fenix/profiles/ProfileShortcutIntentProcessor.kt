/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.feature.intent.ext.putSessionId
import mozilla.components.feature.intent.processing.IntentProcessor
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.state.ext.flow

/** Profiles: intent extra holding the contextId of the profile a home screen shortcut belongs to. */
const val EXTRA_PROFILE_CONTEXT_ID = "app.ravenprofiles.extra.CONTEXT_ID"

/** Profiles: intent extra (boolean) telling whether a home screen shortcut opens the desktop site. */
const val EXTRA_PROFILE_DESKTOP = "app.ravenprofiles.extra.DESKTOP"

/** Profiles: intent extra (boolean): open in a new tab instead of reusing an open tab of the same host. */
const val EXTRA_PROFILE_NEW_TAB = "app.ravenprofiles.extra.NEW_TAB"

/** Profiles: intent extra (boolean): the profile for this link is already decided, don't ask again. */
const val EXTRA_PROFILE_SKIP_ASK = "app.ravenprofiles.extra.SKIP_ASK"

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
    private val scope: CoroutineScope = MainScope(),
) : IntentProcessor {

    override fun process(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_VIEW) return false
        if (!intent.hasExtra(EXTRA_PROFILE_CONTEXT_ID) && !intent.hasExtra(EXTRA_PROFILE_DESKTOP)) return false

        val uri = intent.dataString?.let { runCatching { it.toUri() }.getOrNull() } ?: return false
        val host = uri.host
        if ((uri.scheme != "http" && uri.scheme != "https") || host.isNullOrEmpty()) return false

        val contextId = intent.getStringExtra(EXTRA_PROFILE_CONTEXT_ID)
        // A shortcut of a deleted profile must not silently create a new cookie jar; let others handle it.
        // Containers load asynchronously, so on a cold start the map is still empty: trust the shortcut then.
        val containers = store.state.containers
        if (contextId != null && containers.isNotEmpty() && !containers.containsKey(contextId)) return false
        val desktop = intent.getBooleanExtra(EXTRA_PROFILE_DESKTOP, false)

        val newTab = intent.getBooleanExtra(EXTRA_PROFILE_NEW_TAB, false)
        val existing = if (newTab) null else findExistingTab(contextId, host)
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
                ).also {
                    if (desktop) requestDesktopSite(true, it)
                    if (!store.state.restoreComplete && !newTab) reuseRestoredTabOnceRestored(it, contextId, host)
                }
            }

        intent.putSessionId(tabId)
        return true
    }

    /**
     * On a cold start this runs before session restore, so the tab opened above may duplicate a restored one. Once the
     * restore completes, switch to the restored tab of the same profile and host and drop the new tab.
     */
    private fun reuseRestoredTabOnceRestored(newTabId: String, contextId: String?, host: String) {
        scope.launch {
            store.flow().map { it.restoreComplete }.first { it } // completes after the first emission of true
            val restored = findExistingTab(contextId, host, excludeTabId = newTabId) ?: return@launch
            if (store.state.findTab(newTabId) == null) return@launch
            selectTab(restored.id)
            store.dispatch(TabListAction.RemoveTabAction(newTabId))
        }
    }

    private fun findExistingTab(contextId: String?, host: String, excludeTabId: String? = null): TabSessionState? {
        val candidates = store.state.tabs.filter { tab ->
            tab.id != excludeTabId &&
                !tab.content.private &&
                tab.contextId == contextId &&
                tab.content.url.toUri().host.equals(host, ignoreCase = true)
        }
        // Prefer the tab the user is already on.
        return candidates.firstOrNull { it.id == store.state.selectedTab?.id } ?: candidates.firstOrNull()
    }
}
