package com.hashir.sync.api.pages

import com.hashir.sync.api.GraphClient
import com.hashir.sync.api.GraphError
import com.hashir.sync.api.model.AccountsResponse
import com.hashir.sync.api.model.PageDto

/**
 * Reading the Pages a Facebook account administers.
 *
 * Call this with a **long-lived** user token. The Page tokens that come back inherit
 * that token's lifetime, so calling it with a short-lived token yields Page tokens that
 * expire within hours. See [com.hashir.sync.api.auth.AuthApi].
 */
class PagesApi(private val client: GraphClient) {

    /**
     * Every Page the token's owner administers, following pagination to the end.
     *
     * Note that [PageDto.accessToken] on each result is that Page's own token -- the app
     * stores them per-Page and publishes with the matching one, which is what lets a
     * single dashboard span several Facebook accounts.
     */
    suspend fun listPages(userToken: String): List<PageDto> {
        val first = client.graphUrl(
            path = "me/accounts",
            params = mapOf(
                "fields" to PAGE_FIELDS,
                "limit" to PAGE_SIZE.toString(),
            ),
        )

        val collected = mutableListOf<PageDto>()
        var body = client.get(first, userToken)
        var pagesFetched = 0

        while (true) {
            val response: AccountsResponse = client.decode(body)
            collected += response.data

            val next = response.paging?.next
            if (next.isNullOrBlank()) break

            if (++pagesFetched >= MAX_PAGES) {
                // Refuse to loop forever if the cursor never terminates. Surfaced as an
                // error rather than silently returning a truncated list, because a
                // half-populated dashboard is worse than a visible failure.
                throw GraphError.Unexpected(
                    "Stopped after $MAX_PAGES pages of /me/accounts -- pagination did not terminate.",
                )
            }

            // `paging.next` comes back fully formed, token and cursor included.
            body = client.getAbsolute(next)
        }

        return collected
    }

    private companion object {
        /**
         * `tasks` is what tells us whether this user may actually publish to the Page.
         * Fetching it here means the dashboard can disable Pages up front instead of
         * letting a publish fail after the user has written a caption.
         */
        const val PAGE_FIELDS = "id,name,access_token,category,tasks,picture{url}"

        const val PAGE_SIZE = 100
        const val MAX_PAGES = 50
    }
}
