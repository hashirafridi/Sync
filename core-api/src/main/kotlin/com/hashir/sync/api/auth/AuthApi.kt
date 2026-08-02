package com.hashir.sync.api.auth

import com.hashir.sync.api.GraphClient
import com.hashir.sync.api.model.MeResponse
import com.hashir.sync.api.model.TokenExchangeResponse

/**
 * Turning a fresh login into credentials that actually last.
 *
 * The token chain matters here and is easy to get wrong:
 *
 * ```
 * login  ->  short-lived user token (~2 h)
 *            +-- /me/accounts with it  ->  Page tokens that also die in ~2 h
 *
 * exchange (needs the app secret):
 *   short-lived  ->  long-lived user token (~60 days)
 *            +-- /me/accounts with it  ->  Page tokens that never expire
 * ```
 *
 * A Page token inherits the lifetime of whatever user token fetched it. So
 * [exchangeForLongLivedToken] must run *before* listing Pages, or the app ends up
 * asking the user to log in again every couple of hours.
 */
class AuthApi(private val client: GraphClient) {

    /**
     * Trades the short-lived token the Facebook login SDK just produced for one that
     * lasts about 60 days. Requires the app secret, which is why this call exists here
     * rather than being something the login SDK could do on its own.
     */
    suspend fun exchangeForLongLivedToken(shortLivedToken: String): TokenExchangeResponse {
        val url = client.graphUrl(
            path = "oauth/access_token",
            params = mapOf(
                "grant_type" to "fb_exchange_token",
                "client_id" to client.config.appId,
                "client_secret" to client.config.appSecret,
                "fb_exchange_token" to shortLivedToken,
            ),
        )
        return client.decode(client.get(url))
    }

    /**
     * Identifies which Facebook account a token belongs to.
     *
     * Needed because the app supports several accounts at once and the login SDK does
     * not tell us who just logged in -- without this, connecting a second account could
     * silently overwrite the first.
     */
    suspend fun fetchMe(userToken: String): MeResponse {
        val url = client.graphUrl(
            path = "me",
            params = mapOf("fields" to ME_FIELDS),
        )
        return client.decode(client.get(url, userToken))
    }

    private companion object {
        const val ME_FIELDS = "id,name,picture{url}"
    }
}
