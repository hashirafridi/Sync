package com.hashir.sync.data.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves finished downloads into the phone's public Movies folder so they show up in the
 * gallery, in Files, and anywhere else the user looks.
 *
 * yt-dlp writes to app-private storage because the native process needs a real filesystem
 * path and cannot write through MediaStore. That location is invisible to the gallery and
 * is wiped on uninstall, so leaving files there means the user can never find what they
 * downloaded. This is the second half of that trip.
 */
@Singleton
class GalleryExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Moves [source] into `Movies/Sync` and returns a URI for the new location.
     *
     * A move rather than a copy: a downloaded video can be hundreds of megabytes and
     * keeping two identical copies on a phone is indefensible.
     *
     * Returns null if the export fails, in which case the caller should keep using the
     * original file. Failing to appear in the gallery is a nuisance; losing the download
     * would not be.
     */
    suspend fun exportVideo(source: File, displayName: String? = null): Uri? =
        withContext(Dispatchers.IO) {
            if (!source.isFile) return@withContext null
            val name = sanitize(displayName ?: source.name)

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    exportViaMediaStore(source, name)
                } else {
                    exportViaLegacyPath(source, name)
                }
            }.getOrNull()
        }

    /**
     * Android 10 and later: insert a MediaStore row and stream into it.
     *
     * No storage permission is needed for this, because the app only ever writes its own
     * entry rather than roaming the filesystem.
     */
    private fun exportViaMediaStore(source: File, name: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            // Hides the entry from the gallery until the bytes are actually there, so a
            // half-written file never shows up as a broken thumbnail.
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values) ?: return null

        resolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        } ?: run {
            resolver.delete(uri, null, null)
            return null
        }

        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
            null,
            null,
        )

        source.delete()
        return uri
    }

    /**
     * Android 9 and earlier: write to the public Movies folder directly, then tell the
     * media scanner, which is the only way it appears in the gallery on these versions.
     */
    private fun exportViaLegacyPath(source: File, name: String): Uri? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            FOLDER,
        ).apply { mkdirs() }

        val target = File(dir, uniqueName(dir, name))
        source.copyTo(target, overwrite = true)
        source.delete()

        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        return Uri.fromFile(target)
    }

    /** Where the user should look. Shown in the UI so nobody has to go hunting. */
    fun publicFolderLabel(): String = "Movies/$FOLDER"

    private fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "mp4")
        var index = 1
        while (File(dir, "$base ($index).$ext").exists()) index++
        return "$base ($index).$ext"
    }

    /**
     * Video titles routinely contain characters that are illegal in filenames, and on the
     * legacy path a name containing a separator would escape the target directory.
     */
    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(120)
        return if (cleaned.endsWith(".mp4", ignoreCase = true)) cleaned else "$cleaned.mp4"
    }

    private companion object {
        const val FOLDER = "Sync"
    }
}
