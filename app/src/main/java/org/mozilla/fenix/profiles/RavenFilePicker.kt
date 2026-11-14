/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.app.Activity
import android.net.Uri
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import mozilla.components.browser.state.selector.findCustomTabOrSelectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.prompts.file.FileSourceChooser
import mozilla.components.feature.prompts.file.FileSourceRequest

/**
 * Profiles: when a web page asks for a file, first asks where to upload from: Raven files (app-only
 * storage, grouped by profile), Device files (the system picker) or Camera.
 */
class RavenFilePicker(
    private val activity: Activity,
    private val store: BrowserStore,
    private val customTabId: String?,
) : FileSourceChooser {

    private class Entry(val file: File, val groupName: String)

    override fun chooseSource(source: FileSourceRequest) {
        if (activity.isFinishing) {
            source.onCancel()
            return
        }
        val labels = mutableListOf(RAVEN_FILES, DEVICE_FILES)
        if (source.canCapture) labels.add(CAMERA)
        AlertDialog.Builder(activity)
            .setTitle("Upload from")
            .setItems(labels.toTypedArray()) { _, which ->
                when (labels[which]) {
                    RAVEN_FILES -> showRavenFiles(source)
                    DEVICE_FILES -> source.onDeviceFiles()
                    else -> source.onCamera()
                }
            }
            .setOnCancelListener { source.onCancel() }
            .show()
    }

    private fun showRavenFiles(source: FileSourceRequest) {
        val entries = listFiles(source.request.mimeTypes)
        if (entries.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(RAVEN_FILES)
                .setMessage("No matching files in Raven yet.")
                .setPositiveButton("Back") { _, _ -> chooseSource(source) }
                .setOnCancelListener { source.onCancel() }
                .show()
            return
        }
        val labels = entries.map {
            "${it.file.name}\n${it.groupName} · ${Formatter.formatShortFileSize(activity, it.file.length())}"
        }.toTypedArray()
        val multiple = source.request.isMultipleFilesSelection
        val checked = BooleanArray(entries.size)
        var single = -1
        val builder = AlertDialog.Builder(activity)
            .setTitle(RAVEN_FILES)
            .setPositiveButton("Upload") { _, _ ->
                val chosen = if (multiple) entries.filterIndexed { i, _ -> checked[i] } else listOfNotNull(entries.getOrNull(single))
                if (chosen.isEmpty()) {
                    source.onCancel()
                } else {
                    deliver(source, chosen)
                }
            }
            .setNegativeButton("Cancel") { _, _ -> source.onCancel() }
            .setOnCancelListener { source.onCancel() }
        if (multiple) {
            builder.setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
        } else {
            builder.setSingleChoiceItems(labels, -1) { _, which -> single = which }
        }
        builder.show()
    }

    private fun deliver(source: FileSourceRequest, chosen: List<Entry>) {
        val authority = activity.packageName + ".feature.downloads.fileprovider"
        try {
            source.onFilesChosen(chosen.map { FileProvider.getUriForFile(activity, authority, it.file) })
        } catch (e: IllegalArgumentException) {
            Toast.makeText(activity, "Could not share the file", Toast.LENGTH_SHORT).show()
            source.onCancel()
        }
    }

    /** App-only files, current tab's profile first, then Default, then the others by name. */
    private fun listFiles(accept: Array<out String>): List<Entry> {
        val storage = ProfileStorage(activity)
        val containers = store.state.containers
        val known = storage.knownProfiles()
        val currentId = store.state.findCustomTabOrSelectedTab(customTabId)?.contextId ?: ProfileStorage.DEFAULT_ID
        fun nameOf(id: String) =
            if (id == ProfileStorage.DEFAULT_ID) "Default" else containers[id]?.name ?: known[id] ?: id

        val root = File(activity.filesDir, "profiles")
        val ids = root.listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
        val ordered = ids.sortedWith(
            compareBy<String>(
                { if (it == currentId) 0 else if (it == ProfileStorage.DEFAULT_ID) 1 else 2 },
                { nameOf(it).lowercase() },
            ),
        )
        return ordered.flatMap { id ->
            val files = storage.privateDir(id).listFiles { f -> f.isFile && accepts(accept, f.name) }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            files.map { Entry(it, nameOf(id)) }
        }
    }

    private fun accepts(accept: Array<out String>, name: String): Boolean {
        val rules = accept.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (rules.isEmpty()) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return rules.any { rule ->
            when {
                rule == "*/*" -> true
                rule.startsWith(".") -> ext == rule.removePrefix(".")
                rule.endsWith("/*") -> mime?.startsWith(rule.removeSuffix("*")) == true
                else -> mime == rule
            }
        }
    }

    private companion object {
        const val RAVEN_FILES = "Raven files"
        const val DEVICE_FILES = "Device files"
        const val CAMERA = "Camera"
    }
}
