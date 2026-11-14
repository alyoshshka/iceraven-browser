/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Profiles: read-only system file picker roots, one per private profile whose files are made visible.
 * Document ids are "<contextId>" (root dir) or "<contextId>/<fileName>".
 */
class ProfileFilesProvider : DocumentsProvider() {
    private val storage get() = ProfileStorage(context!!)

    override fun onCreate(): Boolean = true

    private fun visibleProfiles(): Map<String, String> =
        storage.knownProfiles().filterKeys { storage.isPrivate(it) && storage.isPickerVisible(it) }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        visibleProfiles().forEach { (id, name) ->
            result.newRow().apply {
                add(Root.COLUMN_ROOT_ID, id)
                add(Root.COLUMN_DOCUMENT_ID, id)
                add(Root.COLUMN_TITLE, "Raven · $name")
                add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
                add(Root.COLUMN_ICON, context!!.applicationInfo.icon)
                add(Root.COLUMN_MIME_TYPES, "*/*")
            }
        }
        return result
    }

    /** Resolves [documentId] to a file inside a visible profile's folder, rejecting traversal. */
    private fun resolve(documentId: String): File {
        val slash = documentId.indexOf('/')
        val id = if (slash < 0) documentId else documentId.substring(0, slash)
        val name = if (slash < 0) null else documentId.substring(slash + 1)
        if (id.isEmpty() || id == ".." || id.contains("..") ||
            !visibleProfiles().containsKey(id)
        ) {
            throw FileNotFoundException("Unknown document: $documentId")
        }
        val dir = storage.privateDir(id)
        if (name == null) return dir
        if (name.isEmpty() || name.contains('/') || name == "." || name == ".." || name.contains("..")) {
            throw FileNotFoundException("Invalid document: $documentId")
        }
        val file = File(dir, name)
        if (file.canonicalFile.parentFile != dir.canonicalFile) {
            throw FileNotFoundException("Invalid document: $documentId")
        }
        return file
    }

    private fun addRow(result: MatrixCursor, documentId: String, file: File) {
        val isDir = file.isDirectory
        val slash = documentId.indexOf('/')
        val name = if (slash < 0) {
            "Raven · " + (visibleProfiles()[documentId] ?: documentId)
        } else {
            file.name
        }
        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_SIZE, if (isDir) null else file.length())
            add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mimeOf(file.name))
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, 0)
        }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val file = resolve(documentId)
        if (!file.exists() && documentId.contains('/')) throw FileNotFoundException(documentId)
        addRow(result, documentId, file)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (parentDocumentId.contains('/')) return result // Only the root dir has children.
        val dir = resolve(parentDocumentId)
        dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }?.forEach {
            addRow(result, "$parentDocumentId/${it.name}", it)
        }
        return result
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        !parentDocumentId.contains('/') && documentId.startsWith("$parentDocumentId/")

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only provider")
        val file = resolve(documentId)
        if (!file.isFile) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private companion object {
        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES,
        )
        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
