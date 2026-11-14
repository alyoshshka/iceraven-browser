/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.Context
import android.content.SharedPreferences
import android.provider.DocumentsContract
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Profiles: per-profile file storage settings (stored in SharedPreferences, keyed by contextId) and
 * helpers for the app-private download folders under `filesDir/profiles/<contextId>/downloads`.
 */
class ProfileStorage(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val profilesRoot: File get() = File(appContext.filesDir, "profiles")

    /** Whether the profile keeps its downloads inside the app (default true). */
    fun isPrivate(contextId: String): Boolean = prefs.getBoolean("$contextId.private", true)

    /** Whether the profile's private files appear in the system file picker (default true). */
    fun isPickerVisible(contextId: String): Boolean = prefs.getBoolean("$contextId.pickerVisible", true)

    fun setPrivate(contextId: String, value: Boolean) {
        prefs.edit().putBoolean("$contextId.private", value).apply()
        notifyChanged()
    }

    fun setPickerVisible(contextId: String, value: Boolean) {
        prefs.edit().putBoolean("$contextId.pickerVisible", value).apply()
        notifyChanged()
    }

    /** Remembers the profile name so the DocumentsProvider can label roots without the browser store. */
    fun setName(contextId: String, name: String) {
        prefs.edit().putString("$contextId.name", name).apply()
        notifyChanged()
    }

    /** contextId -> name of every profile known to the names cache. */
    fun knownProfiles(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.endsWith(NAME_SUFFIX) && it.value is String }
            .associate { it.key.removeSuffix(NAME_SUFFIX) to it.value as String }

    /**
     * Download directory for [contextId]: null for no profile or a shared profile (keep the global
     * location); for a private profile the (created) absolute path of its private downloads folder.
     */
    fun downloadDirFor(contextId: String?): String? {
        if (contextId == null || !isPrivate(contextId)) return null
        val dir = privateDir(contextId)
        dir.mkdirs()
        return dir.absolutePath
    }

    /** The profile's private downloads folder (not created). */
    fun privateDir(contextId: String): File = File(File(profilesRoot, contextId), "downloads")

    /** The contextId owning [path] if it is under `filesDir/profiles/<id>/`, else null (shared). */
    fun profileOfPath(path: String): String? {
        val rel = try {
            File(path).canonicalFile.relativeToOrNull(profilesRoot.canonicalFile)
        } catch (_: java.io.IOException) {
            null
        } ?: return null
        val parts = rel.path.split(File.separatorChar)
        return parts.firstOrNull()?.takeIf { it.isNotEmpty() && it != ".." && parts.size > 1 }
    }

    /** Deletes the profile's private folder and all its stored settings. */
    fun deleteProfileFiles(contextId: String) {
        File(profilesRoot, contextId).deleteRecursively()
        prefs.edit()
            .remove("$contextId.private")
            .remove("$contextId.pickerVisible")
            .remove("$contextId.name")
            .apply()
        notifyChanged()
    }

    private fun notifyChanged() {
        try {
            appContext.contentResolver.notifyChange(
                DocumentsContract.buildRootsUri(authority(appContext)),
                null,
            )
        } catch (_: RuntimeException) {
            // Best effort.
        }
        listeners.forEach { it.onChanged() }
    }

    /** Notified when any profile storage setting changes. */
    fun interface Listener {
        fun onChanged()
    }

    companion object {
        private const val PREFS = "profiles_storage"
        private const val NAME_SUFFIX = ".name"
        private val listeners = CopyOnWriteArraySet<Listener>()

        fun authority(context: Context) = context.packageName + ".profilefiles"
        fun addListener(listener: Listener) { listeners.add(listener) }
        fun removeListener(listener: Listener) { listeners.remove(listener) }
    }
}
