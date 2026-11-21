/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.customtabs

import android.app.ActivityManager
import android.app.assist.AssistContent
import android.os.Bundle
import android.view.MotionEvent
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import mozilla.components.lib.state.ext.flow
import mozilla.components.browser.state.selector.findCustomTab
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.utils.ABOUT_HOME_URL
import mozilla.components.support.utils.OnEnterAnimationCompleteListener
import mozilla.components.support.utils.SafeIntent
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.getIntentSessionId
import org.mozilla.fenix.profiles.ProfileAppMode
import org.mozilla.fenix.profiles.ProfileAppearance
import org.mozilla.fenix.profiles.badgeIcon

const val EXTRA_IS_SANDBOX_CUSTOM_TAB = "org.mozilla.fenix.customtabs.EXTRA_IS_SANDBOX_CUSTOM_TAB"

/**
 * Activity that holds the [ExternalAppBrowserFragment] that is launched within an external app, such as custom tabs and
 * progressive web apps.
 */
@Suppress("TooManyFunctions")
open class ExternalAppBrowserActivity : HomeActivity() {
    private var isFinishedAnimating = false

    override fun onResume() {
        super.onResume()
        updateProfileAppTask()

        if (!hasExternalTab()) {
            // An ExternalAppBrowserActivity is always bound to a specific tab. If this tab doesn't
            // exist anymore on resume then this activity has nothing to display anymore. Let's just
            // finish it AND remove this task to avoid it hanging around in the recent apps screen.
            // Without this the parent HomeActivity class may decide to show the browser UI and we
            // end up with multiple browsers (causing "display already acquired" crashes).
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (isFinishing) {
            // When this activity finishes, the process is staying around and the session still
            // exists then remove it now to free all its resources. Once this activity is finished
            // then there's no way to get back to it other than relaunching it.
            val tabId = getExternalTabId()
            val customTab = tabId?.let { components.core.store.state.findCustomTab(it) }
            if (tabId != null && customTab != null) {
                components.useCases.customTabsUseCases.remove(tabId)
            }
        }
    }

    /**
     * Profiles: gives the window of a home screen shortcut opened as an app its own entry in the recent apps, with the
     * label of the shortcut and the icon of the site (with the profile badge), and keeps it up to date.
     */
    private fun updateProfileAppTask() {
        val tabId = getExternalTabId()
        if (!ProfileAppMode.isAppSession(tabId) || profileAppTaskJob != null) return
        val store = components.core.store
        profileAppTaskJob = lifecycleScope.launch {
            store.flow()
                .mapNotNull { state -> state.findCustomTab(tabId!!) }
                .map { Triple(it.content.title, it.content.icon, it.contextId) }
                .distinctUntilChanged { old, new -> old.first == new.first && old.second === new.second }
                .collect { (title, icon, contextId) ->
                    val label = ProfileAppMode.labelOf(tabId).orEmpty().ifBlank { title }
                    val container = contextId?.let { store.state.containers[it] }
                    val badged = icon?.takeUnless { it.isRecycled }?.let {
                        container?.let { c ->
                            badgeIcon(it, ProfileAppearance.colorOf(c), ProfileAppearance.emojiOf(c.contextId))
                        } ?: it
                    }
                    @Suppress("DEPRECATION")
                    setTaskDescription(ActivityManager.TaskDescription(label.ifBlank { null }, badged))
                }
        }
    }

    private var profileAppTaskJob: Job? = null

    /**
     * [ExternalAppBrowserActivity], which is responsible for custom tabs, shares the [BrowserStore] and observing
     * [AboutHomeBinding] would navigate the custom tab to the homepage when the selected tab's URL is [ABOUT_HOME_URL],
     * so this is intentionally a no-op.
     */
    @VisibleForTesting override fun addAboutHomeBinding(lifecycle: Lifecycle) = Unit

    @VisibleForTesting override fun addHomepageTabBinding(lifecycle: Lifecycle) = Unit

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun hasExternalTab(): Boolean {
        return getExternalTab() != null
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun getExternalTab(): SessionState? {
        val id = getExternalTabId() ?: return null
        return components.core.store.state.findCustomTab(id)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun getExternalTabId(): String? {
        return getIntentSessionId(SafeIntent(intent))
    }

    override fun onProvideAssistContent(outContent: AssistContent?) {
        super.onProvideAssistContent(outContent)
        val currentTabUrl = getExternalTab()?.content?.url
        outContent?.webUri = currentTabUrl?.let { it.toUri() }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        isFinishedAnimating = true
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (!isFinishedAnimating) {
            return true
        }

        return super.dispatchTouchEvent(ev)
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        isFinishedAnimating = true

        val fragments = supportFragmentManager.fragments.toMutableList()
        while (fragments.isNotEmpty()) {
            val fragment = fragments.removeAt(0)
            if (fragment is OnEnterAnimationCompleteListener) {
                fragment.onEnterAnimationComplete()
            }
            fragments.addAll(fragment.childFragmentManager.fragments)
        }
    }
}
