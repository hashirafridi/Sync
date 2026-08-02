package com.hashir.sync.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    /**
     * The dashboard's single source of truth. Emits every connected account with its
     * Pages attached, so three Facebook accounts render as one grouped list and any
     * change -- a new account, a revoked token, a renamed Page -- pushes through
     * automatically.
     */
    @Transaction
    @Query("SELECT * FROM fb_accounts ORDER BY connectedAt ASC")
    fun observeAccountsWithPages(): Flow<List<AccountWithPages>>

    @Query("SELECT * FROM fb_accounts ORDER BY connectedAt ASC")
    fun observeAccounts(): Flow<List<FbAccountEntity>>

    @Query("SELECT * FROM fb_accounts WHERE fbUserId = :fbUserId")
    suspend fun getById(fbUserId: String): FbAccountEntity?

    @Upsert
    suspend fun upsert(account: FbAccountEntity)

    /** Cascades to this account's Pages; other accounts are untouched. */
    @Query("DELETE FROM fb_accounts WHERE fbUserId = :fbUserId")
    suspend fun delete(fbUserId: String)

    @Query("UPDATE fb_accounts SET needsReauth = :needsReauth WHERE fbUserId = :fbUserId")
    suspend fun setNeedsReauth(fbUserId: String, needsReauth: Boolean)
}

@Dao
interface PageDao {

    @Query("SELECT * FROM fb_pages WHERE pageId = :pageId")
    suspend fun getById(pageId: String): FbPageEntity?

    @Query("SELECT * FROM fb_pages ORDER BY name ASC")
    fun observeAll(): Flow<List<FbPageEntity>>

    @Upsert
    suspend fun upsertAll(pages: List<FbPageEntity>)

    @Query("DELETE FROM fb_pages WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    /**
     * Read before disconnecting an account, so the matching Page tokens can be wiped from
     * the token store. Room cascades the rows away, but the token store has no foreign
     * keys and would otherwise keep working credentials for Pages the app has forgotten.
     */
    @Query("SELECT pageId FROM fb_pages WHERE accountId = :accountId")
    suspend fun getPageIdsForAccount(accountId: String): List<String>

    /**
     * Replaces an account's Pages wholesale after a refresh.
     *
     * Delete-then-insert rather than upsert alone, so a Page the user has lost access to
     * actually disappears instead of lingering in the dashboard and failing at publish.
     */
    @Transaction
    suspend fun replaceForAccount(accountId: String, pages: List<FbPageEntity>) {
        deleteForAccount(accountId)
        upsertAll(pages)
    }
}

@Dao
interface PublishJobDao {

    @Query("SELECT * FROM publish_jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PublishJobEntity>>

    @Query("SELECT * FROM publish_jobs WHERE id = :id")
    suspend fun getById(id: Long): PublishJobEntity?

    @Query("SELECT * FROM publish_jobs WHERE state NOT IN ('DONE', 'FAILED') ORDER BY createdAt ASC")
    suspend fun getUnfinished(): List<PublishJobEntity>

    @Upsert
    suspend fun upsert(job: PublishJobEntity): Long

    @Query("DELETE FROM publish_jobs WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Counts Reels published to a Page in the trailing 24 hours.
     *
     * Facebook caps API-published reels at 30 per Page per day, and hitting that limit
     * blind produces a baffling error, so the editor shows the remaining allowance.
     */
    @Query(
        """
        SELECT COUNT(*) FROM publish_jobs
        WHERE pageId = :pageId AND postType = 'VIDEO' AND state = 'DONE'
          AND updatedAt >= :since
        """,
    )
    suspend fun countVideosPublishedSince(pageId: String, since: Long): Int
}

@Dao
interface DownloadDao {

    @Query("SELECT * FROM download_records ORDER BY downloadedAt DESC")
    fun observeAll(): Flow<List<DownloadRecordEntity>>

    @Query("SELECT * FROM download_records WHERE id = :id")
    suspend fun getById(id: Long): DownloadRecordEntity?

    @Upsert
    suspend fun upsert(record: DownloadRecordEntity): Long

    @Query("DELETE FROM download_records WHERE id = :id")
    suspend fun delete(id: Long)
}
