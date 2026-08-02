package com.hashir.sync.api

import com.hashir.sync.api.model.ErrorEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP layer over the Meta Graph API.
 *
 * Deliberately not Retrofit. There are only about eight distinct calls in this product,
 * two of them (the resumable upload phases) don't fit a declarative interface anyway,
 * and Retrofit would drag Android-flavoured tooling into what is meant to stay a plain
 * JVM module. Hand-writing the calls costs a few lines and keeps the module portable.
 *
 * Feature APIs ([com.hashir.sync.api.auth.AuthApi], [com.hashir.sync.api.pages.PagesApi])
 * build on this rather than talking to OkHttp directly.
 */
class GraphClient(
    val config: GraphConfig,
    private val http: OkHttpClient = defaultHttpClient(),
    val json: Json = defaultJson(),
) {

    /** Builds `{graphHost}/{apiVersion}/{path}?{params}`. */
    fun graphUrl(path: String, params: Map<String, String> = emptyMap()): HttpUrl =
        buildUrl(config.graphHost, path, params)

    /**
     * Builds a URL on the resumable-upload host.
     *
     * Deliberately does NOT insert the API version the way [graphUrl] does. This host
     * orders its path differently -- `video-upload/{version}/{id}` rather than
     * `{version}/video-upload/{id}` -- so the caller passes the whole path including the
     * version. Reusing the Graph layout here yields
     * `InvalidEndpointError: Endpoint /v25.0/video-upload/... doesn't exist`.
     */
    fun uploadUrl(path: String, params: Map<String, String> = emptyMap()): HttpUrl {
        val base = "${config.uploadHost.trimEnd('/')}/${path.trimStart('/')}"
        return base.toHttpUrl().newBuilder()
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
    }

    private fun buildUrl(host: String, path: String, params: Map<String, String>): HttpUrl {
        val base = "${host.trimEnd('/')}/${config.apiVersion}/${path.trimStart('/')}"
        return base.toHttpUrl().newBuilder()
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
    }

    suspend fun get(url: HttpUrl, token: String? = null): String =
        execute(Request.Builder().url(url).get().authorized(token).build())

    suspend fun post(url: HttpUrl, body: RequestBody, token: String? = null): String =
        execute(Request.Builder().url(url).post(body).authorized(token).build())

    /**
     * Follows an absolute URL verbatim -- used for `paging.next`, which Facebook returns
     * fully formed with its own cursor and token already embedded.
     */
    suspend fun getAbsolute(url: String, token: String? = null): String =
        execute(Request.Builder().url(url.toHttpUrl()).get().authorized(token).build())

    /** Escape hatch for calls that need unusual headers, such as the upload phase. */
    suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw GraphError.Network(e)
        }

        response.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw parseError(body, r.code)

            // Graph occasionally returns HTTP 200 carrying an error envelope, so a
            // successful status code alone is not proof the call worked.
            asApiError(body, r.code)?.let { throw it }

            body
        }
    }

    inline fun <reified T> decode(body: String): T =
        try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            throw GraphError.Unexpected(
                "Could not parse ${T::class.simpleName} from response: ${body.take(500)}",
                e,
            )
        }

    private fun parseError(body: String, httpStatus: Int): GraphError =
        asApiError(body, httpStatus)
            ?: asUploadError(body, httpStatus)
            ?: GraphError.Unexpected("HTTP $httpStatus with unrecognised body: ${body.take(500)}")

    /**
     * The resumable-upload host answers with `debug_info` rather than the Graph API's
     * `error` envelope. Without this the raw JSON reaches the user verbatim.
     */
    private fun asUploadError(body: String, httpStatus: Int): GraphError.Api? {
        if (!body.contains("debug_info")) return null
        val envelope = runCatching {
            json.decodeFromString<com.hashir.sync.api.model.UploadErrorEnvelope>(body)
        }.getOrNull() ?: return null

        return GraphError.Api(
            code = 0,
            subcode = null,
            type = envelope.debugInfo.type,
            fbMessage = envelope.debugInfo.message,
            fbTraceId = null,
            httpStatus = httpStatus,
        )
    }

    /**
     * Returns an [GraphError.Api] if the body is an error envelope, or null if it isn't.
     *
     * The discriminator is simply that `error` is a required field on [ErrorEnvelope],
     * so a normal response fails to decode and yields null.
     */
    private fun asApiError(body: String, httpStatus: Int): GraphError.Api? {
        if (body.isBlank() || !body.contains("\"error\"")) return null
        val envelope = runCatching { json.decodeFromString<ErrorEnvelope>(body) }.getOrNull()
            ?: return null
        return GraphError.Api(
            code = envelope.error.code,
            subcode = envelope.error.errorSubcode,
            type = envelope.error.type,
            fbMessage = envelope.error.message,
            fbTraceId = envelope.error.fbTraceId,
            httpStatus = httpStatus,
        )
    }

    private fun Request.Builder.authorized(token: String?): Request.Builder =
        if (token.isNullOrBlank()) this else header("Authorization", "Bearer $token")

    companion object {
        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
        }

        /**
         * Timeouts are tuned for video: a Reel can be a couple of hundred megabytes on a
         * phone connection. `callTimeout` stays disabled so a slow-but-progressing upload
         * is never killed outright -- stalls are caught by the read/write timeouts instead.
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
