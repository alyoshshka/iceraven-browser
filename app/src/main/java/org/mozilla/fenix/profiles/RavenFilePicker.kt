/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.app.Activity
import android.net.Uri
import android.text.format.Formatter
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import java.io.File
import mozilla.components.browser.state.selector.findCustomTabOrSelectedTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.compose.base.button.TextButton
import mozilla.components.feature.prompts.R as promptsR
import mozilla.components.feature.prompts.file.FileSourceChooser
import mozilla.components.feature.prompts.file.FileSourceRequest
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.IconListItem
import org.mozilla.fenix.compose.list.RadioButtonListItem
import org.mozilla.fenix.compose.list.SelectableListItem
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Profiles: when a web page asks for a file, first asks where to upload from: Raven files (app-only
 * storage, grouped by profile), Device files (the system picker) or Camera.
 */
class RavenFilePicker(
    private val activity: Activity,
    private val store: BrowserStore,
    private val customTabId: String?,
) : FileSourceChooser {

    private class Entry(val file: File, val groupName: String, val color: Int?, val profileId: String)

    override fun chooseSource(source: FileSourceRequest) {
        if (activity.isFinishing) {
            source.onCancel()
            return
        }
        // Compose dialogs are hosted by a ComposeView added to the activity; it is removed when the prompt ends.
        val host = ComposeView(activity)
        val finish: () -> Unit = { (host.parent as? ViewGroup)?.removeView(host) }
        host.setContent {
            FirefoxTheme {
                RavenFilePickerDialogs(
                    canCapture = source.canCapture,
                    multiple = source.request.isMultipleFilesSelection,
                    loadEntries = { listFiles(source.request.mimeTypes) },
                    onRavenFilesChosen = { chosen ->
                        finish()
                        if (chosen.isEmpty()) source.onCancel() else deliver(source, chosen)
                    },
                    onDeviceFiles = {
                        finish()
                        source.onDeviceFiles()
                    },
                    onCamera = {
                        finish()
                        source.onCamera()
                    },
                    onCancel = {
                        finish()
                        source.onCancel()
                    },
                )
            }
        }
        activity.addContentView(
            host,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    @Composable
    private fun RavenFilePickerDialogs(
        canCapture: Boolean,
        multiple: Boolean,
        loadEntries: () -> List<Entry>,
        onRavenFilesChosen: (List<Entry>) -> Unit,
        onDeviceFiles: () -> Unit,
        onCamera: () -> Unit,
        onCancel: () -> Unit,
    ) {
        var showingFiles by remember { mutableStateOf(false) }
        if (!showingFiles) {
            SourceDialog(
                canCapture = canCapture,
                onRavenFiles = { showingFiles = true },
                onDeviceFiles = onDeviceFiles,
                onCamera = onCamera,
                onCancel = onCancel,
            )
        } else {
            val entries = remember { loadEntries() }
            FilesDialog(
                entries = entries,
                multiple = multiple,
                onBack = { showingFiles = false },
                onUpload = onRavenFilesChosen,
                onCancel = onCancel,
            )
        }
    }

    @Composable
    private fun SourceDialog(
        canCapture: Boolean,
        onRavenFiles: () -> Unit,
        onDeviceFiles: () -> Unit,
        onCamera: () -> Unit,
        onCancel: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = {
                Text(
                    text = stringResource(R.string.profiles_upload_title),
                    style = FirefoxTheme.typography.headline5,
                )
            },
            text = {
                Column {
                    IconListItem(
                        label = stringResource(R.string.profiles_upload_raven_files),
                        beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_folder_24),
                        onClick = onRavenFiles,
                    )
                    IconListItem(
                        label = stringResource(R.string.profiles_upload_device_files),
                        beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_device_mobile_24),
                        onClick = onDeviceFiles,
                    )
                    if (canCapture) {
                        IconListItem(
                            label = stringResource(R.string.profiles_upload_camera),
                            beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_camera_24),
                            onClick = onCamera,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    text = stringResource(promptsR.string.mozac_feature_prompts_cancel),
                    onClick = onCancel,
                )
            },
        )
    }

    @Composable
    private fun FilesDialog(
        entries: List<Entry>,
        multiple: Boolean,
        onBack: () -> Unit,
        onUpload: (List<Entry>) -> Unit,
        onCancel: () -> Unit,
    ) {
        var selected by remember { mutableStateOf(setOf<Int>()) }
        val isEmpty = entries.isEmpty()
        AlertDialog(
            onDismissRequest = onCancel,
            title = {
                Text(
                    text = stringResource(R.string.profiles_upload_raven_files),
                    style = FirefoxTheme.typography.headline5,
                )
            },
            text = {
                if (isEmpty) {
                    Text(
                        text = stringResource(R.string.profiles_upload_empty),
                        style = FirefoxTheme.typography.body2,
                    )
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        entries.forEachIndexed { index, entry ->
                            val isSelected = index in selected
                            val onClick = {
                                selected = when {
                                    !multiple -> setOf(index)
                                    isSelected -> selected - index
                                    else -> selected + index
                                }
                            }
                            val description = stringResource(
                                R.string.profiles_file_description,
                                entry.groupName,
                                Formatter.formatShortFileSize(activity, entry.file.length()),
                            )
                            val dot: (@Composable () -> Unit)? = entry.color?.let { { ProfileTabIcon(icon = null, color = Color(it), profileId = entry.profileId) } }
                            if (multiple) {
                                SelectableListItem(
                                    label = entry.file.name,
                                    description = description,
                                    icon = iconsR.drawable.mozac_ic_save_file_24,
                                    isSelected = isSelected,
                                    modifier = Modifier.clickable(onClick = onClick),
                                    labelOverflow = TextOverflow.MiddleEllipsis,
                                    afterListItemAction = {},
                                    descriptionLeading = dot,
                                )
                            } else {
                                RadioButtonListItem(
                                    label = entry.file.name,
                                    selected = isSelected,
                                    description = description,
                                    descriptionLeading = dot,
                                    onClick = onClick,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (isEmpty) {
                    TextButton(text = stringResource(R.string.profiles_upload_back), onClick = onBack)
                } else {
                    TextButton(
                        text = stringResource(R.string.profiles_upload_confirm),
                        onClick = { onUpload(entries.filterIndexed { i, _ -> i in selected }) },
                    )
                }
            },
            dismissButton = {
                TextButton(
                    text = stringResource(promptsR.string.mozac_feature_prompts_cancel),
                    onClick = onCancel,
                )
            },
        )
    }

    private fun deliver(source: FileSourceRequest, chosen: List<Entry>) {
        val authority = activity.packageName + ".feature.downloads.fileprovider"
        try {
            source.onFilesChosen(chosen.map { FileProvider.getUriForFile(activity, authority, it.file) })
        } catch (e: IllegalArgumentException) {
            Toast.makeText(activity, R.string.profiles_upload_share_error, Toast.LENGTH_SHORT).show()
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
            if (id == ProfileStorage.DEFAULT_ID) {
                activity.getString(R.string.profiles_default_name)
            } else {
                containers[id]?.name ?: known[id] ?: id
            }

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
            files.map { Entry(it, nameOf(id), containers[id]?.color?.toArgb(), id) }
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
}
