/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Profiles: app-only file storage. Every profile (and the default one, id [DEFAULT_ID]) downloads
 * into `filesDir/profiles/<id>/downloads`, which no other app can reach. Files leave the app only
 * through explicit user actions (export, move to Device Downloads, open/share).
 */
class ProfileStorage(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val profilesRoot: File get() = File(appContext.filesDir, "profiles")

    /** Remembers the profile name so labels work for files of profiles that no longer load. */
    fun setName(contextId: String, name: String) {
        prefs.edit().putString("$contextId.name", name).apply()
    }

    /** contextId -> name of every profile known to the names cache. */
    fun knownProfiles(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.endsWith(NAME_SUFFIX) && it.value is String }
            .associate { it.key.removeSuffix(NAME_SUFFIX) to it.value as String }

    /** The (created) absolute path of the app-only downloads folder; null contextId means the default profile. */
    fun downloadDirFor(contextId: String?): String {
        val dir = privateDir(contextId ?: DEFAULT_ID)
        dir.mkdirs()
        return dir.absolutePath
    }

    /** The profile's app-only downloads folder (not created). */
    fun privateDir(contextId: String): File = File(File(profilesRoot, contextId), "downloads")

    /** The profile id owning [path] if it is under `filesDir/profiles/<id>/`, else null (Device Downloads). */
    fun profileOfPath(path: String): String? {
        val rel = try {
            File(path).canonicalFile.relativeToOrNull(profilesRoot.canonicalFile)
        } catch (_: java.io.IOException) {
            null
        } ?: return null
        val parts = rel.path.split(File.separatorChar)
        return parts.firstOrNull()?.takeIf { it.isNotEmpty() && it != ".." && parts.size > 1 }
    }

    /** Deletes the profile's folder and its cached name. */
    fun deleteProfileFiles(contextId: String) {
        File(profilesRoot, contextId).deleteRecursively()
        prefs.edit().remove("$contextId.name").apply()
    }

    companion object {
        /** Reserved id of the default (container-less) profile; container ids are UUIDs, so it can't collide. */
        const val DEFAULT_ID = "default"
        private const val PREFS = "profiles_storage"
        private const val NAME_SUFFIX = ".name"
    }
}
