/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import mozilla.components.browser.state.action.ContainerAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.lib.state.ext.consumeFrom
import org.mozilla.fenix.R
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.showToolbar
import java.util.UUID

/** Profiles: lists, adds and deletes profiles (containers). */
class ProfilesSettingsFragment : PreferenceFragmentCompat(), SystemInsetsPaddedFragment {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        consumeFrom(requireComponents.core.store) { state -> render(state) }
    }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(R.string.profiles_title))
    }

    private fun render(state: BrowserState) {
        val context = requireContext()
        val screen = preferenceScreen
        screen.removeAll()
        // Keep the names cache (used by the system file picker provider) in sync with the store.
        val storage = ProfileStorage(context)
        val known = storage.knownProfiles()
        state.containers.values.forEach { if (known[it.contextId] != it.name) storage.setName(it.contextId, it.name) }

        screen.addPreference(
            Preference(context).apply {
                isIconSpaceReserved = false
                title = getString(R.string.profiles_add)
                setOnPreferenceClickListener {
                    showProfileDialog(null)
                    true
                }
            },
        )

        if (state.containers.isEmpty()) {
            screen.addPreference(
                Preference(context).apply {
                    isIconSpaceReserved = false
                    isEnabled = false
                    title = getString(R.string.profiles_empty)
                },
            )
        }

        state.containers.values.sortedBy { it.name.lowercase() }.forEach { container ->
            screen.addPreference(
                Preference(context).apply {
                    title = container.name
                    isIconSpaceReserved = true
                    icon = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(container.color.toArgb())
                        setSize(ICON_SIZE_PX, ICON_SIZE_PX)
                    }
                    setOnPreferenceClickListener {
                        showProfileMenuDialog(container)
                        true
                    }
                },
            )
        }
    }

    /** Shows the add dialog ([existing] null) or the rename / change color dialog for [existing]. */
    private fun showProfileDialog(existing: ContainerState?) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.profiles_name_hint)
            setSingleLine()
            existing?.let { setText(it.name) }
        }
        val colors = ContainerState.Color.values().filter { it != ContainerState.Color.TOOLBAR }
        val spinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                colors.map { it.displayName() },
            )
            existing?.let { setSelection(colors.indexOf(it.color).coerceAtLeast(0)) }
        }
        val storage = ProfileStorage(requireContext())
        val privateSwitch = Switch(requireContext()).apply {
            text = getString(R.string.profiles_private_downloads)
            isChecked = existing?.let { storage.isPrivate(it.contextId) } ?: true
        }
        val pickerSwitch = Switch(requireContext()).apply {
            text = getString(R.string.profiles_picker_visible)
            isChecked = existing?.let { storage.isPickerVisible(it.contextId) } ?: true
        }
        val padding = (DIALOG_PADDING_DP * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
            addView(spinner)
            addView(privateSwitch)
            addView(pickerSwitch)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) R.string.profiles_add else R.string.profiles_rename_title)
            // Scrollable so that the buttons stay reachable with the on-screen keyboard.
            .setView(ScrollView(requireContext()).apply { addView(layout) })
            .setPositiveButton(if (existing == null) R.string.profiles_create else R.string.profiles_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    // Adding a container with an existing contextId replaces it (see ContainerReducer).
                    val contextId = existing?.contextId ?: UUID.randomUUID().toString()
                    storage.setPrivate(contextId, privateSwitch.isChecked)
                    storage.setPickerVisible(contextId, pickerSwitch.isChecked)
                    storage.setName(contextId, name)
                    requireComponents.core.store.dispatch(
                        ContainerAction.AddContainerAction(
                            ContainerState(
                                contextId = contextId,
                                name = name,
                                color = colors[spinner.selectedItemPosition],
                                icon = existing?.icon ?: ContainerState.Icon.CIRCLE,
                            ),
                        ),
                    )
                }
            }
            .setNegativeButton(R.string.profiles_cancel, null)
            .create()
        @Suppress("DEPRECATION") // Resize (not pan) so the buttons stay above the keyboard.
        val resize = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        dialog.window?.setSoftInputMode(resize)
        dialog.show()
    }

    private fun showProfileMenuDialog(container: ContainerState) {
        AlertDialog.Builder(requireContext())
            .setTitle(container.name)
            .setItems(
                arrayOf(getString(R.string.profiles_rename), getString(R.string.profiles_delete)),
            ) { _, which ->
                if (which == 0) showProfileDialog(container) else confirmDelete(container)
            }
            .setNegativeButton(R.string.profiles_cancel, null)
            .show()
    }

    private fun confirmDelete(container: ContainerState) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.profiles_delete_confirm_title, container.name))
            .setMessage(R.string.profiles_delete_confirm_message)
            .setPositiveButton(R.string.profiles_delete) { _, _ -> deleteProfile(container) }
            .setNegativeButton(R.string.profiles_cancel, null)
            .show()
    }

    private fun deleteProfile(container: ContainerState) {
        val components = requireComponents
        val store = components.core.store
        val tabIds = store.state.tabs.filter { it.contextId == container.contextId }.map { it.id }
        if (tabIds.isNotEmpty()) {
            components.useCases.tabsUseCases.removeTabs(tabIds)
        }
        components.core.engine.clearDataForContext(container.contextId)
        store.dispatch(ContainerAction.RemoveContainerAction(container.contextId))
        ProfileStorage(requireContext()).deleteProfileFiles(container.contextId)
    }

    private companion object {
        const val ICON_SIZE_PX = 48
        const val DIALOG_PADDING_DP = 20
    }
}
