package com.hashir.sync.api

import java.io.IOException

/**
 * Every failure this module can produce, as one closed set.
 *
 * The point of modelling these rather than letting raw [IOException]s escape is that
 * the UI needs to tell three very different situations apart: "your phone lost signal"
 * (retry silently), "your token died" (make the user reconnect), and "Facebook rejected
 * this video" (show why, and fall back to the other endpoint).
 */
sealed class GraphError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Transport-level failure: no connectivity, DNS, TLS, timeout. Always worth retrying. */
    class Network(cause: Throwable) : GraphError(
        "Network error: ${cause.message ?: cause::class.simpleName}",
        cause,
    )

    /**
     * Facebook answered, and the answer was an error envelope.
     *
     * Codes worth knowing, because the app branches on them:
     *  - 190 (+ subcodes 463/467) -- token expired or invalidated; reconnect required
     *  - 200 / 10 / 3..7          -- missing permission or Page role
     *  - 4 / 17 / 32 / 613        -- rate limited, including the 30-reels-per-day cap
     */
    class Api(
        val code: Int,
        val subcode: Int?,
        val type: String?,
        val fbMessage: String,
        val fbTraceId: String?,
        val httpStatus: Int,
    ) : GraphError(
        buildString {
            append("Graph API error ").append(code)
            if (subcode != null) append('/').append(subcode)
            append(" (HTTP ").append(httpStatus).append("): ").append(fbMessage)
        },
    ) {
        /** The user must log in again -- no amount of retrying will fix this. */
        val isTokenExpired: Boolean
            get() = code == 190 || subcode == 463 || subcode == 467

        /**
         * Permission or Page-role problem. Retrying is pointless; the user must grant access.
         *
         * The ranges here must not overlap [isRateLimited]. Code 4 in particular is a rate
         * limit, not a permission failure -- treating it as one would tell the user to go
         * check their Page roles when all they actually need to do is wait.
         */
        val isPermissionDenied: Boolean
            get() = code == 3 || code == 10 || code in 200..299

        /** Back off and try later. Covers the 30-reels-per-Page-per-24h ceiling. */
        val isRateLimited: Boolean
            get() = code == 4 || code == 17 || code == 32 || code == 613
    }

    /** A response we could not make sense of. Usually means the API shape changed. */
    class Unexpected(message: String, cause: Throwable? = null) : GraphError(message, cause)
}
