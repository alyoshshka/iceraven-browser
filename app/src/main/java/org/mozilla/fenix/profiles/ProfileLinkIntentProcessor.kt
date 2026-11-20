/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.customtabs.isCustomTabIntent
import mozilla.components.feature.intent.processing.IntentProcessor
import mozilla.components.support.utils.EXTRA_ACTIVITY_REFERRER_PACKAGE
import org.mozilla.fenix.HomeActivity.Companion.PRIVATE_BROWSING_MODE

/**
 * Profiles: decides what happens to an http(s) link opened by another app.
 *
 * - With a saved site rule the intent is rewritten to open in that profile (or Default) and `false` is returned so the
 *   following processors ([ProfileShortcutIntentProcessor] / TabIntentProcessor) open it.
 * - Without a rule, when the "ask" setting is on and at least one profile exists, `true` is returned and
 *   [needsChooser] is set; IntentReceiverActivity then shows [ProfileChooserActivity] instead of opening the link.
 *
 * Skipped (returns `false`, untouched): non-VIEW intents, non-http(s) data, our own profile intents (extras), Custom
 * Tabs, private browsing and intents coming from this app itself. PWA/web-app intents are handled by processors that
 * run earlier in the list.
 */
class ProfileLinkIntentProcessor(
    private val context: Context,
    private val store: BrowserStore,
    private val storage: ProfileStorage = ProfileStorage(context),
) : IntentProcessor {

    /** Set by the last [process] call that wants the profile chooser shown. */
    @Volatile
    var needsChooser: Boolean = false
        private set

    override fun process(intent: Intent): Boolean {
        needsChooser = false
        if (intent.action != Intent.ACTION_VIEW) return false
        if (intent.hasExtra(EXTRA_PROFILE_CONTEXT_ID) || intent.hasExtra(EXTRA_PROFILE_DESKTOP) ||
            intent.hasExtra(EXTRA_PROFILE_SKIP_ASK)
        ) {
            return false
        }
        if (isCustomTabIntent(intent)) return false
        if (intent.getBooleanExtra(PRIVATE_BROWSING_MODE, false)) return false
        if (intent.getStringExtra(EXTRA_ACTIVITY_REFERRER_PACKAGE) == context.packageName) return false

        val uri = intent.dataString?.let { runCatching { it.toUri() }.getOrNull() } ?: return false
        val host = uri.host
        if ((uri.scheme != "http" && uri.scheme != "https") || host.isNullOrEmpty()) return false

        val containers = store.state.containers
        // Containers load asynchronously on a cold start; the names cache knows the profiles by then.
        val hasProfiles = containers.isNotEmpty() || storage.knownProfiles().isNotEmpty()
        if (!hasProfiles) return false

        val rule = ProfileSiteRouter.matchRule(host.lowercase(), storage.rules())
        if (rule != null) {
            val stale = rule != ProfileStorage.DEFAULT_ID && containers.isNotEmpty() && !containers.containsKey(rule)
            if (!stale) {
                if (rule == ProfileStorage.DEFAULT_ID) {
                    intent.putExtra(EXTRA_PROFILE_SKIP_ASK, true)
                } else {
                    intent.putExtra(EXTRA_PROFILE_CONTEXT_ID, rule)
                    intent.putExtra(EXTRA_PROFILE_DESKTOP, false)
                    intent.putExtra(EXTRA_PROFILE_NEW_TAB, true)
                }
                return false
            }
        }

        if (!storage.askForExternalLinks) return false

        needsChooser = true
        return true
    }
}
