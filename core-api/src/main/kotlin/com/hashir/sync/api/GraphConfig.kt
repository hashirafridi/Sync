package com.hashir.sync.api

/**
 * Everything the Graph API layer needs to know about *which* Meta app it is acting as.
 *
 * Credentials arrive here as constructor arguments and are never read from a global.
 * On Android the app passes them in from `BuildConfig`; a server would pass them from
 * environment variables. That is the whole reason this module can be lifted into a
 * web backend later without edits.
 *
 * [graphHost] and [uploadHost] are overridable so tests can point the entire client at
 * a local MockWebServer. Production code should leave them alone.
 */
data class GraphConfig(
    val appId: String,
    val appSecret: String,
    val apiVersion: String = DEFAULT_API_VERSION,
    val graphHost: String = DEFAULT_GRAPH_HOST,
    val uploadHost: String = DEFAULT_UPLOAD_HOST,
) {
    /**
     * False when local.properties hasn't been filled in yet. The app is expected to
     * check this and show a "connect your Meta app" state rather than firing doomed
     * requests at Facebook with an empty app id.
     */
    val isConfigured: Boolean
        get() = appId.isNotBlank() && appSecret.isNotBlank()

    companion object {
        /**
         * Graph API versions are sunset roughly two years after release, so this
         * deliberately lives in exactly one place. When it moves, it moves here.
         */
        const val DEFAULT_API_VERSION: String = "v25.0"

        const val DEFAULT_GRAPH_HOST: String = "https://graph.facebook.com"

        /** Resumable video uploads go to a different host than the rest of the API. */
        const val DEFAULT_UPLOAD_HOST: String = "https://rupload.facebook.com"
    }
}
