/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import mozilla.components.support.base.log.logger.Logger

/**
 * Profiles: fail-closed gate for per-profile proxies. A page can only be requested by an engine session, and every
 * engine session is created through [EngineAction.CreateEngineSessionAction] (new, restored and linked tabs, kept
 * tabs, loads in tabs without a session). This holds that action back for a tab of a profile (non-null contextId,
 * normal tab) while [ProfileProxy.isSessionAllowed] is false: the proxy settings are not loaded yet, or the profile
 * has a proxy and the proxy extension is not ready. Held actions are kept per tab (the latest wins) and dispatched in
 * order, on the main thread, once the gate opens. If the extension never becomes ready, proxied profiles never load.
 *
 * Default tabs and private tabs are never held. Must come before the engine middlewares.
 */
class ProxyGateMiddleware(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val isAllowed: (String?) -> Boolean = ProfileProxy::isSessionAllowed,
) : Middleware<BrowserState, BrowserAction> {
    private val logger = Logger("ProxyGate")
    private val held = LinkedHashMap<String, EngineAction.CreateEngineSessionAction>() // guarded by itself
    private var watcher: Job? = null // guarded by held

    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        if (action is EngineAction.CreateEngineSessionAction) {
            val tab = store.state.findTabOrCustomTab(action.tabId)
            val contextId = tab?.contextId
            if (tab != null && !tab.content.private && contextId != null && !isAllowed(contextId)) {
                synchronized(held) {
                    held[action.tabId] = action
                    startWatcher(store)
                }
                logger.info("Holding engine session creation for a tab of a proxied or not yet ready profile")
                // Re-check: the gate may have opened between the check and the insertion.
                release(store)
                return
            }
        }
        next(action)
    }

    private fun startWatcher(store: Store<BrowserState, BrowserAction>) {
        if (watcher != null) return
        watcher = scope.launch {
            combine(ProfileProxy.settings, ProfileProxy.ready) { _, _ -> Unit }.collect { release(store) }
        }
    }

    private fun release(store: Store<BrowserState, BrowserAction>) {
        val toDispatch = synchronized(held) {
            val ready = held.values.filter { action ->
                val contextId = store.state.findTabOrCustomTab(action.tabId)?.contextId
                // A tab that is gone or lost its profile is released too; the engine middleware ignores it.
                contextId == null || isAllowed(contextId)
            }
            ready.forEach { held.remove(it.tabId) }
            ready
        }
        if (toDispatch.isEmpty()) return
        scope.launch { toDispatch.forEach { store.dispatch(it) } }
    }
}
