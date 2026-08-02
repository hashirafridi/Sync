package com.hashir.sync.data.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges Android's `content://` world to the plain [File] that `:core-api` expects.
 *
 * This class exists precisely so that `:core-api` never has to know what a `Uri` is. The
 * Photo Picker hands back an opaque content URI which may not correspond to any readable
 * path -- it can be backed by a cloud provider or a media store row -- so the only
 * reliable way to hand the bytes to an HTTP layer is to stream them into a real file
 * first.
 */
@Singleton
class MediaResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Copies the contents of [uri] into app cache and returns the file.
     *
     * Streamed rather than read into memory: a 4K video from the Photo Picker can be
     * hundreds of megabytes, and buffering one whole would kill a low-end device.
     */
    suspend fun toFile(uri: Uri, fileNameHint: String? = null): File = withContext(Dispatchers.IO) {
        // Anything the downloader produced is already a real file we own. Copying it into
        // cache would double the disk usage and the wait for no benefit -- and a
        // downloaded video can easily be a couple of hundred megabytes.
        asOwnedFile(uri)?.let { return@withContext it }

        val name = fileNameHint ?: displayName(uri) ?: "upload_${System.currentTimeMillis()}"
        val target = File(uploadCacheDir(), sanitize(name))

        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        } ?: error("Could not open $uri. The file may have been deleted or moved.")

        target
    }

    /**
     * Returns the underlying file when [uri] is a `file://` URI inside our own storage.
     *
     * Scoped to our own directories on purpose: a `file://` URI pointing anywhere else is
     * not something this app should be reading directly, and staging a copy is the correct
     * handling for it.
     */
    private fun asOwnedFile(uri: Uri): File? {
        if (uri.scheme != "file") return null
        val file = uri.path?.let(::File) ?: return null
        if (!file.isFile) return null

        val ownedRoots = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.getExternalFilesDir(null),
        ).map { it.canonicalPath }

        val path = file.canonicalPath
        return file.takeIf { ownedRoots.any { root -> path.startsWith(root) } }
    }

    fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }

    fun sizeBytes(uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else null
            }

    fun mimeType(uri: Uri): String? = context.contentResolver.getType(uri)

    fun isVideo(uri: Uri): Boolean = mimeType(uri)?.startsWith("video/") == true

    /** Clears staged copies. Safe at any time; these are reproducible from the source URI. */
    fun clearCache() {
        uploadCacheDir().listFiles()?.forEach { it.delete() }
    }

    private fun uploadCacheDir(): File =
        File(context.cacheDir, "uploads").apply { mkdirs() }

    /**
     * Display names come from user-controlled filenames and can contain path separators.
     * Without this, a name like `../../databases/sync.db` would escape the cache directory.
     */
    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(MAX_NAME_LENGTH).ifBlank { "upload" }

    private companion object {
        const val MAX_NAME_LENGTH = 100
    }
}
