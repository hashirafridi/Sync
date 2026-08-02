package com.hashir.sync.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire shape of Facebook's error envelope: `{"error": {...}}`. */
@Serializable
internal data class ErrorEnvelope(val error: ApiErrorBody)

/**
 * The resumable-upload host reports failures in its own shape rather than the Graph API's
 * `error` envelope, so it needs parsing separately or the message reaches the user as an
 * unreadable JSON blob.
 */
@Serializable
internal data class UploadErrorEnvelope(
    @SerialName("debug_info") val debugInfo: UploadDebugInfo,
)

@Serializable
internal data class UploadDebugInfo(
    val retriable: Boolean = false,
    val type: String? = null,
    val message: String = "",
)

@Serializable
internal data class ApiErrorBody(
    val message: String = "",
    val type: String? = null,
    val code: Int = 0,
    @SerialName("error_subcode") val errorSubcode: Int? = null,
    @SerialName("fbtrace_id") val fbTraceId: String? = null,
)

/** Response of the `fb_exchange_token` grant. */
@Serializable
data class TokenExchangeResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    /**
     * Seconds until expiry. Long-lived user tokens report roughly 60 days.
     * Absent entirely on tokens that never expire.
     */
    @SerialName("expires_in") val expiresIn: Long? = null,
)

/** `GET /me` -- identifies which Facebook account a token belongs to. */
@Serializable
data class MeResponse(
    val id: String,
    val name: String = "",
    val picture: PictureEnvelope? = null,
)

/** One page of `GET /me/accounts`. */
@Serializable
data class AccountsResponse(
    val data: List<PageDto> = emptyList(),
    val paging: Paging? = null,
)

/**
 * A Facebook Page as returned by `/me/accounts`.
 *
 * [accessToken] is the Page token, and its lifetime is inherited from the user token
 * used to fetch it -- which is exactly why the long-lived exchange has to happen first.
 * Fetched with a short-lived user token these die in about two hours; fetched with a
 * long-lived one they do not expire at all.
 */
@Serializable
data class PageDto(
    val id: String,
    val name: String = "",
    @SerialName("access_token") val accessToken: String = "",
    val category: String? = null,
    /**
     * What this user may actually do with the Page. Publishing requires
     * `CREATE_CONTENT`; without it the Page should be shown disabled rather than
     * being allowed to fail at publish time.
     */
    val tasks: List<String> = emptyList(),
    val picture: PictureEnvelope? = null,
) {
    val canCreateContent: Boolean
        get() = tasks.any { it.equals(TASK_CREATE_CONTENT, ignoreCase = true) }

    val pictureUrl: String?
        get() = picture?.data?.url

    companion object {
        const val TASK_CREATE_CONTENT: String = "CREATE_CONTENT"
    }
}

@Serializable
data class PictureEnvelope(val data: PictureData = PictureData())

@Serializable
data class PictureData(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("is_silhouette") val isSilhouette: Boolean = false,
)

@Serializable
data class Paging(
    val next: String? = null,
    val previous: String? = null,
)

/**
 * What the publishing endpoints return.
 *
 * `/feed` answers with just `id`, which is already the post. `/photos` answers with the
 * *photo* id in `id` and the feed post in `post_id`, so the two are not interchangeable
 * when building a permalink.
 */
@Serializable
data class PublishResponse(
    val id: String,
    @SerialName("post_id") val postId: String? = null,
)

/** Response to the Reels upload `start` phase. */
@Serializable
data class ReelStartResponse(
    @SerialName("video_id") val videoId: String,
    @SerialName("upload_url") val uploadUrl: String? = null,
)

/** Response to the `finish` phase, and to the plain `/videos` endpoint. */
@Serializable
data class VideoFinishResponse(
    val success: Boolean = false,
    /** `/videos` returns an id here; the `video_reels` finish phase does not. */
    val id: String? = null,
    @SerialName("post_id") val postId: String? = null,
)

/**
 * `GET /{video_id}?fields=status`.
 *
 * A 200 from the upload does not mean the video is live. It still has to be processed and
 * then published, and either stage can fail on its own well after the bytes arrived. This
 * is how those are told apart.
 */
@Serializable
data class VideoStatusResponse(
    val status: VideoStatus = VideoStatus(),
    val id: String? = null,
)

@Serializable
data class VideoStatus(
    @SerialName("video_status") val videoStatus: String? = null,
    @SerialName("uploading_phase") val uploadingPhase: VideoPhase? = null,
    @SerialName("processing_phase") val processingPhase: VideoPhase? = null,
    @SerialName("publishing_phase") val publishingPhase: VideoPhase? = null,
) {
    val isReady: Boolean
        get() = publishingPhase?.status.equals(STATUS_COMPLETE, ignoreCase = true)

    /** Any phase reporting an error means waiting longer will not help. */
    val failedPhase: VideoPhase?
        get() = listOfNotNull(uploadingPhase, processingPhase, publishingPhase)
            .firstOrNull { it.status.equals(STATUS_ERROR, ignoreCase = true) }

    companion object {
        const val STATUS_COMPLETE = "complete"
        const val STATUS_ERROR = "error"
    }
}

@Serializable
data class VideoPhase(
    val status: String? = null,
    /**
     * How many bytes Facebook actually holds. A resumed upload must restart from exactly
     * this value: guessing means either duplicating bytes or leaving a hole.
     */
    @SerialName("bytes_transferred") val bytesTransferred: Long? = null,
    val errors: List<VideoPhaseError> = emptyList(),
)

@Serializable
data class VideoPhaseError(
    val code: Int? = null,
    val message: String? = null,
)
