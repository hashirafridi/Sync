package com.hashir.sync.api.publish

import com.hashir.sync.api.GraphClient
import com.hashir.sync.api.model.PublishResponse
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/** Where a successful publish ended up. */
data class PublishResult(
    /** The id to use for a permalink. */
    val postId: String,
    /** The raw id the endpoint returned, which for photos is the photo rather than the post. */
    val rawId: String,
) {
    val permalinkUrl: String get() = "https://www.facebook.com/$postId"
}

/**
 * Publishing to a Page: photos, text and links.
 *
 * Video lives elsewhere because it is a three-phase resumable upload with entirely
 * different mechanics. These two are single POSTs, which is exactly why they are built
 * first -- they prove authentication, Page tokens and permissions all work before any of
 * the upload machinery is introduced.
 */
class PublishApi(private val client: GraphClient) {

    /**
     * Publishes a text post, optionally with a link attached.
     *
     * Pass [scheduledForEpochSeconds] to schedule rather than publish immediately.
     */
    suspend fun publishText(
        pageId: String,
        pageToken: String,
        message: String,
        link: String? = null,
        scheduledForEpochSeconds: Long? = null,
    ): PublishResult {
        require(message.isNotBlank() || !link.isNullOrBlank()) {
            "A text post needs either a message or a link."
        }

        val form = FormBody.Builder().apply {
            add("message", message)
            if (!link.isNullOrBlank()) add("link", link)
            scheduleFields(scheduledForEpochSeconds).forEach { (key, value) -> add(key, value) }
        }.build()

        val body = client.post(
            url = client.graphUrl("$pageId/feed"),
            body = form,
            token = pageToken,
        )
        return client.decode<PublishResponse>(body).toResult()
    }

    /**
     * Uploads and publishes a single photo.
     *
     * The file is streamed by OkHttp rather than read into memory -- phone photos are
     * large enough that buffering one whole is a real risk on a low-end device.
     */
    suspend fun publishPhoto(
        pageId: String,
        pageToken: String,
        photo: File,
        caption: String,
        scheduledForEpochSeconds: Long? = null,
    ): PublishResult {
        require(photo.isFile) { "Photo not found: ${photo.absolutePath}" }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("caption", caption)
            .addFormDataPart(
                "source",
                photo.name,
                photo.asRequestBody(guessImageMediaType(photo).toMediaType()),
            )
            .apply {
                scheduleFields(scheduledForEpochSeconds).forEach { (key, value) ->
                    addFormDataPart(key, value)
                }
            }
            .build()

        val body = client.post(
            url = client.graphUrl("$pageId/photos"),
            body = multipart,
            token = pageToken,
        )
        return client.decode<PublishResponse>(body).toResult()
    }

    private fun PublishResponse.toResult() = PublishResult(
        // `/photos` returns the photo id in `id` and the actual feed post in `post_id`.
        // `/feed` returns only `id`, which already is the post. Preferring post_id keeps
        // the permalink pointing at something a person can actually open.
        postId = postId ?: id,
        rawId = id,
    )

    companion object {
        /**
         * Turns a schedule time into the two fields Facebook needs.
         *
         * Scheduling is not one field but a pair: the post has to be marked unpublished
         * *and* given a time. Sending only the time publishes immediately, which is an
         * unpleasant way to discover the mistake.
         */
        internal fun scheduleFields(epochSeconds: Long?): List<Pair<String, String>> {
            if (epochSeconds == null) return emptyList()
            return listOf(
                "published" to "false",
                "scheduled_publish_time" to epochSeconds.toString(),
            )
        }

        internal fun guessImageMediaType(file: File): String =
            when (file.extension.lowercase()) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                else -> "image/jpeg"
            }
    }
}
