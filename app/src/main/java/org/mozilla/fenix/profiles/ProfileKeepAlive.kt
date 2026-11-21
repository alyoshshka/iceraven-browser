/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession.SessionPriority
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.log.logger.Logger

/**
 * Profiles: "Keep running in background" for chosen tabs, so that web apps such as chat keep their connection and can
 * show notifications while Raven is in the background (Iceraven has no web push).
 *
 * A kept tab:
 * - always has an engine session (it is created at startup and again after a crash);
 * - stays active while not visible ([mozilla.components.concept.engine.EngineSession.setKeepActive]), so its timers
 *   and scripts keep running;
 * - is never suspended to free memory ([KeepAliveMiddleware]);
 * - keeps a high process priority, like the selected tab;
 * - is covered by [ProfileKeepAliveService], a foreground service that keeps the app process alive.
 *
 * Kept tab ids are stored in shared preferences; ids of tabs that no longer exist are dropped once the session is
 * restored.
 */
object ProfileKeepAlive {
    private const val PREFS = "profiles_keep_alive"
    private const val KEY_TABS = "tabs"
    private val logger = Logger("ProfileKeepAlive")

    private val _keptTabs = MutableStateFlow<Set<String>?>(null)

    // Tabs whose engine sessions were last told to stay active (main thread only).
    private var appliedTabs: Set<String> = emptySet()

    /** Ids of the kept tabs (empty until [load] ran). */
    val keptTabs: StateFlow<Set<String>?> = _keptTabs.asStateFlow()

    /** Ids of the kept tabs; safe to call from any thread once [load] ran. */
    fun keptTabIds(): Set<String> = _keptTabs.value.orEmpty()

    /** Whether the tab with [tabId] is kept running. */
    fun isKept(tabId: String): Boolean = tabId in keptTabIds()

    /** Loads the kept tab ids (idempotent). */
    fun load(context: Context) {
        if (_keptTabs.value != null) return
        _keptTabs.value = prefs(context).getStringSet(KEY_TABS, emptySet()).orEmpty().toSet()
    }

    /** Keeps the tab with [tabId] running in the background, or stops doing so. */
    fun setKept(context: Context, tabId: String, kept: Boolean) {
        load(context)
        update(context) { if (kept) it + tabId else it - tabId }
    }

    /** Stops keeping any tab running. */
    fun clear(context: Context) {
        load(context)
        update(context) { emptySet() }
    }

    private fun update(context: Context, change: (Set<String>) -> Set<String>) {
        val updated = change(keptTabIds())
        if (updated == keptTabIds()) return
        _keptTabs.value = updated
        prefs(context).edit().putStringSet(KEY_TABS, updated).apply()
    }

    /**
     * Starts watching the store: creates engine sessions for kept tabs, raises their priority, drops ids of closed
     * tabs and starts or stops [ProfileKeepAliveService]. Call once from the application.
     */
    fun start(context: Context, store: BrowserStore, scope: CoroutineScope) {
        val appContext = context.applicationContext
        scope.launch(Dispatchers.Main) {
            withContext(Dispatchers.IO) { load(appContext) } // shared preferences read: not on the main thread
            combine(store.flow(), keptTabs) { state, kept -> state to kept.orEmpty() }
                .distinctUntilChanged { old, new -> old.second == new.second && sameKeptTabs(old, new) }
                .collect { (state, kept) -> sync(appContext, store, state, kept) }
        }
    }

    private fun sameKeptTabs(old: Pair<BrowserState, Set<String>>, new: Pair<BrowserState, Set<String>>): Boolean {
        if (old.first.restoreComplete != new.first.restoreComplete) return false
        return new.second.all { id ->
            val a = old.first.findTab(id)
            val b = new.first.findTab(id)
            (a == null) == (b == null) &&
                a?.engineState?.engineSession === b?.engineState?.engineSession &&
                a?.engineState?.crashed == b?.engineState?.crashed
        }
    }

    private fun sync(context: Context, store: BrowserStore, state: BrowserState, kept: Set<String>) {
        if (state.restoreComplete) {
            val gone = kept.filter { state.findTab(it) == null || state.findTab(it)?.content?.private == true }
            if (gone.isNotEmpty()) {
                logger.info("Dropping ${gone.size} closed kept tab(s)")
                update(context) { it - gone.toSet() }
                return // the kept set changed; we run again with it
            }
        }
        (appliedTabs - kept).forEach { id -> state.findTab(id)?.engineState?.engineSession?.setKeepActive(false) }
        appliedTabs = kept
        val tabs = kept.mapNotNull { state.findTab(it) }
        tabs.forEach { tab ->
            val engineSession = tab.engineState.engineSession
            when {
                engineSession != null -> {
                    engineSession.setKeepActive(true)
                    engineSession.updateSessionPriority(SessionPriority.HIGH)
                }
                !tab.engineState.crashed && !tab.engineState.initializing ->
                    store.dispatch(EngineAction.CreateEngineSessionAction(tab.id))
            }
        }
        if (tabs.isEmpty()) {
            ProfileKeepAliveService.stop(context)
        } else {
            ProfileKeepAliveService.start(context, tabs.size)
        }
    }

    /** Keeps the kept tabs' engine sessions active and at high priority (e.g. after a session was (re)created). */
    internal fun raisePriorities(state: BrowserState) {
        keptTabIds().forEach { id ->
            state.findTab(id)?.engineState?.engineSession?.let {
                it.setKeepActive(true)
                it.updateSessionPriority(SessionPriority.HIGH)
            }
        }
    }

    // Always through the application context, whose preferences cache was filled off the main thread by [start].
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Profiles: keeps the engine sessions of kept tabs ([ProfileKeepAlive]) alive and at high priority.
 * - Drops [EngineAction.SuspendEngineSessionAction] (low memory) for kept tabs that have not crashed; a crashed kept
 *   tab is suspended as usual and then recreated by [ProfileKeepAlive].
 * - Re-raises their priority after the actions on which SessionPrioritizationMiddleware lowers priorities, so it must
 *   come before that middleware in the list.
 */
class KeepAliveMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        if (action is EngineAction.SuspendEngineSessionAction && ProfileKeepAlive.isKept(action.tabId)) {
            val tab = store.state.findTab(action.tabId)
            if (tab != null && !tab.engineState.crashed) return
        }
        next(action)
        when (action) {
            is TabListAction,
            is EngineAction.LinkEngineSessionAction,
            is EngineAction.UnlinkEngineSessionAction,
            is ContentAction.UpdateHasFormDataAction,
            is ContentAction.UpdatePriorityToDefaultAfterTimeoutAction,
            -> if (ProfileKeepAlive.keptTabIds().isNotEmpty()) ProfileKeepAlive.raisePriorities(store.state)
            else -> Unit
        }
    }
}

/** Whether "Keep running in background" applies to [tab] (normal, non-private tabs only). */
fun isKeepAliveSupported(tab: TabSessionState): Boolean = !tab.content.private

/** Starts [ProfileKeepAliveService] in the foreground; failures (e.g. started from the background) are logged. */
internal fun Context.startKeepAliveService(intent: Intent) {
    try {
        ContextCompat.startForegroundService(this, intent)
    } catch (e: IllegalStateException) {
        Logger("ProfileKeepAlive").warn("Could not start the keep-alive service", e)
    }
}
