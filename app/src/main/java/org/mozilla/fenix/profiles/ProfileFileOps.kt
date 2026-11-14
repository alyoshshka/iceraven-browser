/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.DownloadAction
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.support.utils.DefaultDownloadFileUtils
import org.mozilla.fenix.downloads.listscreen.store.FileItem

/**
 * Profiles: file operations behind the Downloads file manager. A location is a profile id (files
 * kept in the app-private folder of the profile) or null (shared public Downloads folder).
 * All IO runs on [Dispatchers.IO]; results are reported with a toast.
 */
object ProfileFileOps {
    private const val TAG = "ProfileFileOps"

    /** Outlives the Downloads screen so a started move or import always finishes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val publicDownloadsPath: String
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path

    /** Moves [items] to [target] (profile id, or null for Shared) and updates the download database. */
    suspend fun move(context: Context, store: BrowserStore, items: Collection<FileItem>, target: String?) {
        val app = context.applicationContext
        var moved = 0
        withContext(Dispatchers.IO) {
            val storage = ProfileStorage(app)
            val fileUtils = DefaultDownloadFileUtils(app)
            for (item in items) {
                val download = store.state.downloads[item.id] ?: continue
                val name = download.fileName ?: continue
                if (storage.profileOfPath(download.directoryPath) == target) continue
                try {
                    val result = moveOne(app, storage, fileUtils, download, name, target) ?: continue
                    store.dispatch(
                        DownloadAction.UpdateDownloadAction(
                            download.copy(directoryPath = result.first, fileName = result.second),
                        ),
                    )
                    moved++
                } catch (e: IOException) {
                    Log.w(TAG, "Move failed for $name", e)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Move not permitted for $name", e)
                }
            }
        }
        toast(app, "Moved $moved of ${items.size} file(s)")
    }

    /** Returns the new (directoryPath, fileName), or null when the file could not be moved. */
    private fun moveOne(
        app: Context,
        storage: ProfileStorage,
        fileUtils: DefaultDownloadFileUtils,
        download: DownloadState,
        name: String,
        target: String?,
    ): Pair<String, String>? {
        val source = storage.profileOfPath(download.directoryPath)
        val mime = download.contentType ?: mimeOf(name)
        return when {
            source != null && target != null -> {
                val src = File(download.directoryPath, name)
                if (!src.exists()) return null
                val destDir = storage.privateDir(target).apply { mkdirs() }
                val dest = File(destDir, uniqueName(destDir, name))
                if (!src.renameTo(dest)) {
                    src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
                    src.delete()
                }
                destDir.absolutePath to dest.name
            }
            source != null -> { // private -> Shared
                val src = File(download.directoryPath, name)
                if (!src.exists()) return null
                val newName = src.inputStream().use { saveToShared(app, name, mime, it) }
                src.delete()
                publicDownloadsPath to newName
            }
            target != null -> { // Shared -> private
                val uri = fileUtils.findDownloadFileUri(name, download.directoryPath) ?: return null
                val destDir = storage.privateDir(target).apply { mkdirs() }
                val dest = File(destDir, uniqueName(destDir, name))
                val input = app.contentResolver.openInputStream(uri) ?: return null
                input.use { i -> dest.outputStream().use { i.copyTo(it) } }
                fileUtils.deleteMediaFile(app.contentResolver, name, download.directoryPath)
                destDir.absolutePath to dest.name
            }
            else -> null
        }
    }

    /** Copies the file behind [item] to [dest] (a document Uri from CreateDocument). The original stays. */
    suspend fun export(context: Context, item: FileItem, dest: Uri) {
        val app = context.applicationContext
        val ok = withContext(Dispatchers.IO) {
            try {
                val uri = DefaultDownloadFileUtils(app).findDownloadFileUri(item.fileName, item.directoryPath)
                    ?: return@withContext false
                val input = app.contentResolver.openInputStream(uri) ?: return@withContext false
                val output = app.contentResolver.openOutputStream(dest, "wt") ?: return@withContext false
                input.use { i -> output.use { i.copyTo(it) } }
                true
            } catch (e: IOException) {
                Log.w(TAG, "Export failed", e)
                false
            } catch (e: SecurityException) {
                Log.w(TAG, "Export not permitted", e)
                false
            }
        }
        toast(app, if (ok) "Exported" else "Export failed")
    }

    /** Copies [uris] into [target] (profile id, or null for Shared) and registers them as completed downloads. */
    suspend fun import(context: Context, store: BrowserStore, uris: List<Uri>, target: String?) {
        val app = context.applicationContext
        var imported = 0
        withContext(Dispatchers.IO) {
            val storage = ProfileStorage(app)
            for (uri in uris) {
                try {
                    val (displayName, size) = queryNameAndSize(app, uri)
                    val mime = app.contentResolver.getType(uri) ?: mimeOf(displayName)
                    val input = app.contentResolver.openInputStream(uri) ?: continue
                    val dir: String
                    val finalName: String
                    var length = size
                    if (target != null) {
                        val destDir = storage.privateDir(target).apply { mkdirs() }
                        val dest = File(destDir, uniqueName(destDir, displayName))
                        input.use { i -> dest.outputStream().use { i.copyTo(it) } }
                        dir = destDir.absolutePath
                        finalName = dest.name
                        length = dest.length()
                    } else {
                        finalName = input.use { saveToShared(app, displayName, mime, it) }
                        dir = publicDownloadsPath
                    }
                    store.dispatch(
                        DownloadAction.AddDownloadAction(
                            DownloadState(
                                url = "about:blank",
                                fileName = finalName,
                                contentType = mime,
                                contentLength = length,
                                currentBytesCopied = length ?: 0,
                                status = DownloadState.Status.COMPLETED,
                                directoryPath = dir,
                                createdTime = System.currentTimeMillis(),
                                private = false,
                            ),
                        ),
                    )
                    imported++
                } catch (e: IOException) {
                    Log.w(TAG, "Import failed for $uri", e)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Import not permitted for $uri", e)
                }
            }
        }
        toast(app, "Imported $imported of ${uris.size} file(s)")
    }

    /** Writes [input] into the public Downloads folder and returns the final file name. */
    private fun saveToShared(app: Context, name: String, mime: String, input: java.io.InputStream): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)?.use { input.copyTo(it) } ?: throw IOException("No output stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: IOException) {
                resolver.delete(uri, null, null)
                throw e
            }
            // MediaStore may have changed the name to avoid a clash.
            return resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: name
        }
        val dir = File(publicDownloadsPath).apply { mkdirs() }
        val dest = File(dir, uniqueName(dir, name))
        dest.outputStream().use { input.copyTo(it) }
        return dest.name
    }

    private fun queryNameAndSize(app: Context, uri: Uri): Pair<String, Long?> {
        var name: String? = null
        var size: Long? = null
        app.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n)
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
        return (name ?: uri.lastPathSegment ?: "file") to size
    }

    /** [name], or "base (n).ext" if it already exists in [dir]. */
    private fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (File(dir, "$base ($n)$ext").exists()) n++
        return "$base ($n)$ext"
    }

    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private suspend fun toast(app: Context, text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
    }
}
