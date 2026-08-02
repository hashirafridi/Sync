package com.hashir.sync.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * A connected Facebook account.
 *
 * Note what is *not* here: the access token. Tokens live in the Keystore-backed
 * EncryptedTokenStore, keyed by [fbUserId], never in the database. Room files are
 * trivially readable on a rooted device and end up in backups.
 */
@Entity(tableName = "fb_accounts")
data class FbAccountEntity(
    @PrimaryKey val fbUserId: String,
    val name: String,
    val pictureUrl: String?,
    val connectedAt: Long,
    /**
     * When the long-lived *user* token lapses, roughly 60 days out. Null means the API
     * reported no expiry. Page tokens derived from a long-lived user token do not expire,
     * so this is mostly an early warning that a reconnect is coming.
     */
    val userTokenExpiresAt: Long?,
    /** Set when the API reports code 190 -- the dashboard shows a reconnect banner. */
    val needsReauth: Boolean = false,
)

/**
 * A Page, owned by exactly one connected account.
 *
 * The cascade delete is deliberate and load-bearing: removing one account must take its
 * Pages with it and leave the other accounts' Pages untouched.
 */
@Entity(
    tableName = "fb_pages",
    foreignKeys = [
        ForeignKey(
            entity = FbAccountEntity::class,
            parentColumns = ["fbUserId"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("accountId")],
)
data class FbPageEntity(
    @PrimaryKey val pageId: String,
    val accountId: String,
    val name: String,
    val category: String?,
    val pictureUrl: String?,
    /**
     * Whether this user actually holds CREATE_CONTENT on the Page. Stored so the
     * dashboard can disable it up front instead of letting a publish fail after the
     * user has already written a caption.
     */
    val canCreateContent: Boolean,
    val lastSyncedAt: Long,
)

/**
 * The dashboard's shape, straight out of Room: every account with its Pages beneath it.
 * This is what makes three separate Facebook accounts read as one list.
 */
data class AccountWithPages(
    @Embedded val account: FbAccountEntity,
    @Relation(parentColumn = "fbUserId", entityColumn = "accountId")
    val pages: List<FbPageEntity>,
)

enum class PostType {
    /**
     * Anything with a video track. There is intentionally no user-facing distinction
     * between "video" and "reel" -- publishing tries the Reels endpoint and falls back
     * to the plain video endpoint only if the API rejects it.
     */
    VIDEO,
    PHOTO,
    TEXT,
}

enum class JobState {
    PENDING,
    DOWNLOADING,
    TRANSCODING,
    UPLOADING,
    PUBLISHING,
    DONE,
    FAILED,
}

/**
 * One publish attempt, durable across process death.
 *
 * [uploadedBytes] is what makes a dropped connection survivable: the resumable upload
 * restarts from that offset instead of pushing a 200 MB file again from zero.
 */
@Entity(
    tableName = "publish_jobs",
    indices = [Index("pageId"), Index("state")],
)
data class PublishJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: String,
    val postType: PostType,
    val localMediaUri: String?,
    val caption: String,
    val state: JobState,
    /** Assigned by Facebook during the upload's start phase. */
    val graphVideoId: String? = null,
    val uploadedBytes: Long = 0,
    val totalBytes: Long = 0,
    /** Set on success -- used for the "View on Facebook" link. */
    val resultPostId: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    /** Epoch millis for a scheduled publish, or null to publish immediately. */
    val scheduledFor: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A completed download, kept so the same file can be re-published without re-fetching. */
@Entity(tableName = "download_records")
data class DownloadRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUrl: String,
    val title: String?,
    val description: String?,
    val localPath: String,
    val thumbnailUrl: String?,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    /** Which resolution the user actually chose, for display in history. */
    val heightPicked: Int,
    val downloadedAt: Long,
)
