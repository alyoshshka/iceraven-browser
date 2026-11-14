/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.downloads.listscreen

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import mozilla.components.lib.state.helpers.StoreProvider.Companion.fragmentStore
import mozilla.components.lib.state.helpers.StoreProvider.Companion.storeProvider
import mozilla.components.support.utils.DefaultDownloadFileUtils
import org.mozilla.fenix.components.appstate.AppAction
import org.mozilla.fenix.components.appstate.SupportedMenuNotifications
import org.mozilla.fenix.compose.snackbar.Snackbar
import org.mozilla.fenix.compose.snackbar.SnackbarState
import org.mozilla.fenix.downloads.getCannotOpenFileErrorMessage
import org.mozilla.fenix.downloads.listscreen.di.DownloadUIMiddlewareProvider
import org.mozilla.fenix.downloads.listscreen.store.DownloadUIAction
import org.mozilla.fenix.downloads.listscreen.store.DownloadUIState
import org.mozilla.fenix.downloads.listscreen.store.DownloadUIStore
import org.mozilla.fenix.downloads.listscreen.store.FileItem
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.hideToolbar
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.profiles.ProfileFileOps
import org.mozilla.fenix.settings.downloads.DownloadLocationManager
import org.mozilla.fenix.theme.FirefoxTheme

/** Fragment for displaying and managing the downloads list. */
class DownloadFragment : Fragment(), SystemInsetsPaddedFragment {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireContext()
            .applicationContext
            .components
            .appStore
            .dispatch(AppAction.MenuNotification.RemoveMenuNotification(SupportedMenuNotifications.Downloads))
    }

    private val downloadStore by
        fragmentStore(DownloadUIState.INITIAL) {
            DownloadUIStore(
                initialState = it,
                middleware =
                    DownloadUIMiddlewareProvider.provideMiddleware(
                        coroutineScope = storeProvider.viewModelScope,
                        applicationContext = requireContext().applicationContext,
                        navController = findNavController(),
                    ),
            )
        }

    // Profiles: export (CreateDocument) and import (OpenMultipleDocuments) via the system picker.
    private var pendingExport: FileItem? = null
    private var pendingImport: List<Uri> = emptyList()

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val item = pendingExport
            pendingExport = null
            if (uri != null && item != null) {
                val appContext = requireContext().applicationContext
                ProfileFileOps.scope.launch { ProfileFileOps.export(appContext, item, uri) }
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                pendingImport = uris
                downloadStore.dispatch(DownloadUIAction.ShowImportTargetDialog)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = content {
        FirefoxTheme {
            DownloadsScreen(
                downloadsStore = downloadStore,
                onItemClick = { openItem(it) },
                onMoveItems = { items, target ->
                    val appContext = requireContext().applicationContext
                    ProfileFileOps.scope.launch {
                        ProfileFileOps.move(appContext, appContext.components.core.store, items, target)
                    }
                },
                onExportItem = { item ->
                    pendingExport = item
                    exportLauncher.launch(item.fileName ?: "file")
                },
                onImportClick = { importLauncher.launch(arrayOf("*/*")) },
                onImportTarget = { target ->
                    val uris = pendingImport
                    pendingImport = emptyList()
                    val appContext = requireContext().applicationContext
                    ProfileFileOps.scope.launch {
                        ProfileFileOps.import(appContext, appContext.components.core.store, uris, target)
                    }
                },
                onImportCancelled = { pendingImport = emptyList() },
            )
        }
    }

    private fun openItem(item: FileItem) {
        context?.let {
            val fileUtils =
                DefaultDownloadFileUtils(
                    context = requireContext(),
                    downloadLocation = {
                        DownloadLocationManager(
                                requireComponents.settings,
                                requireContext().contentResolver,
                            )
                            .defaultLocation
                    },
                )
            val canOpenFile =
                fileUtils.openFile(
                    fileName = item.fileName,
                    directoryPath = item.directoryPath,
                    contentType = item.contentType,
                )

            val rootView = view
            if (!canOpenFile && rootView != null) {
                Snackbar.make(
                        snackBarParentView = rootView,
                        snackbarState =
                            SnackbarState(
                                message =
                                    getCannotOpenFileErrorMessage(
                                        context = it,
                                        filePath = item.filePath,
                                    )
                            ),
                    )
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideToolbar()
    }
}
