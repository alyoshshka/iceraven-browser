/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.shortcut

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import org.mozilla.fenix.R
import org.mozilla.fenix.databinding.FragmentCreateShortcutBinding
import org.mozilla.fenix.ext.loadIntoView
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.profiles.ProfileAppearance
import org.mozilla.fenix.profiles.badgeIcon
import org.mozilla.fenix.profiles.profileShortcutExtras

class CreateShortcutFragment : DialogFragment() {
    private var _binding: FragmentCreateShortcutBinding? = null
    private val binding
        get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.CreateShortcutDialogStyle)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCreateShortcutBinding.inflate(inflater, container, false)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tab = requireComponents.core.store.state.selectedTab

        if (tab == null) {
            dismiss()
        } else {
            requireComponents.core.icons.loadIntoView(binding.faviconImage, tab.content.url)

            binding.cancelButton.setOnClickListener { dismiss() }
            binding.addButton.setOnClickListener {
                val text = binding.shortcutText.text.toString().trim()
                // Profiles: remember the tab's profile, desktop mode and "open as app" in the shortcut.
                val extras = profileShortcutExtras(tab, binding.appModeSwitch.isChecked, text)
                val container = tab.contextId?.let { requireComponents.core.store.state.containers[it] }
                val badge: ((Bitmap) -> Bitmap)? = container?.let {
                    val color = ProfileAppearance.colorOf(it)
                    val emoji = ProfileAppearance.emojiOf(it.contextId);
                    { icon -> badgeIcon(icon, color, emoji) }
                }
                requireActivity().lifecycleScope.launch {
                    requireComponents.useCases.webAppUseCases.addToHomescreen(
                        overrideBasicShortcutName = text,
                        basicShortcutExtras = extras,
                        // A profile's shortcut keeps its profile even if the site is an installable web app.
                        forceBasicShortcut = container != null,
                        basicShortcutIconDecorator = badge,
                    )
                }
                dismiss()
            }

            binding.shortcutText.addTextChangedListener {
                updateAddButtonEnabledState()
            }

            // Profiles: suggested label "<title> (<profile name>)" for profile tabs.
            val profileName = tab.contextId?.let { requireComponents.core.store.state.containers[it]?.name }
            // A blank title falls back to the host without "www.".
            val title = tab.content.title.ifBlank {
                tab.content.url.toUri().host.orEmpty().removePrefix("www.")
            }
            binding.shortcutText.setText(
                if (profileName != null) {
                    getString(R.string.profiles_shortcut_label, title, profileName)
                } else {
                    title
                },
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        _binding = null
    }

    private fun updateAddButtonEnabledState() {
        val text = binding.shortcutText.text
        binding.addButton.isEnabled = text.isNotBlank()
        binding.addButton.alpha = if (text.isNotBlank()) ENABLED_ALPHA else DISABLED_ALPHA
    }

    companion object {
        private const val ENABLED_ALPHA = 1.0f
        private const val DISABLED_ALPHA = 0.4f
    }
}
