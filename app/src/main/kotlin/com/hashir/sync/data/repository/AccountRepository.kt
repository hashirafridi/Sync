package com.hashir.sync.data.repository

import com.hashir.sync.api.GraphError
import com.hashir.sync.api.auth.AuthApi
import com.hashir.sync.api.model.PageDto
import com.hashir.sync.api.pages.PagesApi
import com.hashir.sync.data.local.AccountDao
import com.hashir.sync.data.local.AccountWithPages
import com.hashir.sync.data.local.FbAccountEntity
import com.hashir.sync.data.local.FbPageEntity
import com.hashir.sync.data.local.PageDao
import com.hashir.sync.data.security.EncryptedTokenStore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** What the UI needs to know after a connect attempt. */
data class ConnectedAccount(
    val fbUserId: String,
    val name: String,
    val pageCount: Int,
    val publishablePageCount: Int,
)

@Singleton
class AccountRepository @Inject constructor(
    private val authApi: AuthApi,
    private val pagesApi: PagesApi,
    private val accountDao: AccountDao,
    private val pageDao: PageDao,
    private val tokenStore: EncryptedTokenStore,
) {

    fun observeAccountsWithPages(): Flow<List<AccountWithPages>> =
        accountDao.observeAccountsWithPages()

    /**
     * Turns a fresh login into a fully connected account.
     *
     * Order matters and is not interchangeable:
     *
     *  1. exchange the short-lived token for a long-lived one
     *  2. identify who just logged in
     *  3. list Pages **with the long-lived token**
     *
     * Step 3 has to come after step 1. Page tokens inherit the lifetime of the user token
     * that fetched them, so listing Pages with the short-lived token would yield Page
     * tokens that stop working within about two hours.
     */
    suspend fun connectAccount(shortLivedToken: String): Result<ConnectedAccount> = runCatching {
        val longLived = authApi.exchangeForLongLivedToken(shortLivedToken)
        val me = authApi.fetchMe(longLived.accessToken)

        tokenStore.putUserToken(me.id, longLived.accessToken)

        accountDao.upsert(
            FbAccountEntity(
                fbUserId = me.id,
                name = me.name,
                pictureUrl = me.picture?.data?.url,
                connectedAt = System.currentTimeMillis(),
                userTokenExpiresAt = longLived.expiresIn?.let {
                    System.currentTimeMillis() + it * 1_000
                },
                needsReauth = false,
            ),
        )

        val pages = pagesApi.listPages(longLived.accessToken)
        persistPages(me.id, pages)

        ConnectedAccount(
            fbUserId = me.id,
            name = me.name,
            pageCount = pages.size,
            publishablePageCount = pages.count { it.canCreateContent },
        )
    }

    /** Re-reads an account's Pages, picking up newly created Pages and revoked access. */
    suspend fun refreshPages(fbUserId: String): Result<Int> = runCatching {
        val userToken = tokenStore.getUserToken(fbUserId)
            ?: error("No stored token for account $fbUserId; reconnect required.")

        val pages = pagesApi.listPages(userToken)
        persistPages(fbUserId, pages)
        accountDao.setNeedsReauth(fbUserId, needsReauth = false)
        pages.size
    }.onFailure { error ->
        if (error is GraphError.Api && error.isTokenExpired) {
            accountDao.setNeedsReauth(fbUserId, needsReauth = true)
        }
    }

    /**
     * Forgets an account entirely.
     *
     * Room cascades the Pages away; the token store is cleared explicitly because it has
     * no foreign keys. Page ids are read *before* the delete, since afterwards there is
     * nothing left to enumerate.
     *
     * Note this only forgets the account locally. It does not revoke the app's access on
     * Facebook's side, which is done from Facebook's own app settings.
     */
    suspend fun disconnectAccount(fbUserId: String) {
        val pageIds = pageDao.getPageIdsForAccount(fbUserId)
        accountDao.delete(fbUserId)
        tokenStore.removeAccount(fbUserId, pageIds)
    }

    private suspend fun persistPages(accountId: String, pages: List<PageDto>) {
        val now = System.currentTimeMillis()

        // Each Page carries its own token, and publishing uses the matching one. That is
        // what allows a single dashboard to span several Facebook accounts.
        pages.forEach { page ->
            if (page.accessToken.isNotBlank()) {
                tokenStore.putPageToken(page.id, page.accessToken)
            }
        }

        pageDao.replaceForAccount(
            accountId = accountId,
            pages = pages.map { page ->
                FbPageEntity(
                    pageId = page.id,
                    accountId = accountId,
                    name = page.name,
                    category = page.category,
                    pictureUrl = page.pictureUrl,
                    canCreateContent = page.canCreateContent,
                    lastSyncedAt = now,
                )
            },
        )
    }
}
