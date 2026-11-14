/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.Intent
import androidx.core.net.toUri
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.intent.ext.getSessionId
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileShortcutIntentProcessorTest {
    private val work =
        ContainerState("work-id", "Work", ContainerState.Color.BLUE, ContainerState.Icon.CIRCLE)
    private val addNewTab = mockk<TabsUseCases.AddNewTabUseCase>(relaxed = true)
    private val selectTab = mockk<TabsUseCases.SelectTabUseCase>(relaxed = true)
    private val requestDesktop = mockk<SessionUseCases.RequestDesktopSiteUseCase>(relaxed = true)

    // url and contextId of every tab added through [addNewTab].
    private val addedTabs = mutableListOf<Pair<String, String?>>()

    init {
        // AddNewTabUseCase.invoke has 17 parameters; url is the first one, contextId the sixth.
        every {
            addNewTab(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } answers {
            addedTabs += firstArg<String>() to arg<String?>(5)
            "new-tab"
        }
    }

    private fun processor(state: BrowserState) =
        ProfileShortcutIntentProcessor(BrowserStore(state), addNewTab, selectTab, requestDesktop)

    private fun intent(url: String, contextId: String? = "work-id", desktop: Boolean = false) =
        Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            contextId?.let { putExtra(EXTRA_PROFILE_CONTEXT_ID, it) }
            putExtra(EXTRA_PROFILE_DESKTOP, desktop)
        }

    private val baseState = BrowserState(containers = mapOf(work.contextId to work))

    @Test
    fun `intents without profile extras are not handled`() {
        val plain = Intent(Intent.ACTION_VIEW, "https://example.com".toUri())

        assertFalse(processor(baseState).process(plain))
        assertTrue(addedTabs.isEmpty())
    }

    @Test
    fun `non http urls are rejected`() {
        assertFalse(processor(baseState).process(intent("javascript:alert(1)")))
        assertFalse(processor(baseState).process(intent("file:///sdcard/x.html")))
    }

    @Test
    fun `unknown profile is not handled`() {
        assertFalse(processor(baseState).process(intent("https://example.com", contextId = "gone")))
    }

    @Test
    fun `opens a new tab in the profile`() {
        val i = intent("https://example.com/a")

        assertTrue(processor(baseState).process(i))

        assertEquals(listOf("https://example.com/a" to "work-id"), addedTabs)
        assertEquals("new-tab", i.getSessionId())
        verify(exactly = 0) { requestDesktop(any(), any()) }
    }

    @Test
    fun `applies desktop mode to the new tab`() {

        assertTrue(processor(baseState).process(intent("https://example.com", desktop = true)))

        verify { requestDesktop(true, "new-tab") }
    }

    @Test
    fun `selects an existing tab of the same profile and host`() {
        val state =
            baseState.copy(
                tabs =
                    listOf(
                        createTab("https://example.com/x", id = "default-tab"),
                        createTab("https://example.com/y", id = "work-tab", contextId = "work-id"),
                    ),
            )
        val i = intent("https://example.com/other")

        assertTrue(processor(state).process(i))

        verify { selectTab("work-tab") }
        assertEquals("work-tab", i.getSessionId())
        assertTrue(addedTabs.isEmpty())
    }

    @Test
    fun `default profile shortcut does not reuse a profile tab`() {
        val state =
            baseState.copy(
                tabs = listOf(createTab("https://example.com/y", id = "work-tab", contextId = "work-id")),
            )

        assertTrue(processor(state).process(intent("https://example.com", contextId = null)))

        assertEquals(listOf("https://example.com" to null), addedTabs)
        verify(exactly = 0) { selectTab("work-tab") }
    }
}
