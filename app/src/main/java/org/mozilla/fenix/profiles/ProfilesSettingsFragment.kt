/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
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

        screen.addPreference(
            Preference(context).apply {
                isIconSpaceReserved = false
                title = getString(R.string.profiles_add)
                setOnPreferenceClickListener {
                    showAddDialog()
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
                        showDeleteDialog(container)
                        true
                    }
                },
            )
        }
    }

    private fun showAddDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.profiles_name_hint)
            setSingleLine()
        }
        val colors = ContainerState.Color.values().filter { it != ContainerState.Color.TOOLBAR }
        val spinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                colors.map { it.displayName() },
            )
        }
        val padding = (DIALOG_PADDING_DP * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
            addView(spinner)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.profiles_add)
            .setView(layout)
            .setPositiveButton(R.string.profiles_create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    requireComponents.core.store.dispatch(
                        ContainerAction.AddContainerAction(
                            ContainerState(
                                contextId = UUID.randomUUID().toString(),
                                name = name,
                                color = colors[spinner.selectedItemPosition],
                                icon = ContainerState.Icon.CIRCLE,
                            ),
                        ),
                    )
                }
            }
            .setNegativeButton(R.string.profiles_cancel, null)
            .create()
        dialog.show()
    }

    private fun showDeleteDialog(container: ContainerState) {
        AlertDialog.Builder(requireContext())
            .setTitle(container.name)
            .setItems(arrayOf(getString(R.string.profiles_delete))) { _, _ ->
                confirmDelete(container)
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
    }

    private companion object {
        const val ICON_SIZE_PX = 48
        const val DIALOG_PADDING_DP = 20
    }
}
