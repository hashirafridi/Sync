package com.hashir.sync.data.repository

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.hashir.sync.data.local.JobState
import com.hashir.sync.data.local.PostType
import com.hashir.sync.data.local.PublishJobDao
import com.hashir.sync.data.local.PublishJobEntity
import com.hashir.sync.data.prefs.AppPreferences
import com.hashir.sync.data.work.PublishWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns "publish this" into a durable job.
 *
 * The editor deliberately does not wait for the network. It records the job, hands it to
 * WorkManager and returns immediately, so the user is never held on a spinner while a
 * large upload runs. Progress and failures surface in the queue screen instead.
 */
@Singleton
class PublishQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val jobDao: PublishJobDao,
    private val preferences: AppPreferences,
) {

    fun observeJobs(): Flow<List<PublishJobEntity>> = jobDao.observeAll()

    /**
     * Queues a photo or a video.
     *
     * [isVideo] only selects which endpoint the worker uses. It is not a user-facing
     * choice between "video" and "reel" -- publishing always tries Reels first and falls
     * back on its own if Facebook refuses the media.
     */
    suspend fun enqueueMedia(
        pageId: String,
        mediaUri: String,
        caption: String,
        isVideo: Boolean,
        scheduledFor: Long? = null,
    ): Long = enqueue(
        pageId = pageId,
        postType = if (isVideo) PostType.VIDEO else PostType.PHOTO,
        mediaUri = mediaUri,
        caption = caption,
        scheduledFor = scheduledFor,
    )

    suspend fun enqueueText(
        pageId: String,
        message: String,
        scheduledFor: Long? = null,
    ): Long = enqueue(
        pageId = pageId,
        postType = PostType.TEXT,
        mediaUri = null,
        caption = message,
        scheduledFor = scheduledFor,
    )

    /** Re-runs a failed job rather than making the user rebuild the post from scratch. */
    suspend fun retry(jobId: Long) {
        val job = jobDao.getById(jobId) ?: return
        jobDao.upsert(
            job.copy(
                state = JobState.PENDING,
                errorCode = null,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        schedule(jobId)
    }

    suspend fun delete(jobId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(PublishWorker.workName(jobId))
        jobDao.delete(jobId)
    }

    /**
     * How many Reels this Page has had published through the API in the last 24 hours.
     *
     * Facebook caps that at 30 per Page per day. Surfacing the number lets the editor warn
     * before the limit is hit, instead of the user meeting an opaque rate-limit error.
     */
    suspend fun videosPublishedToday(pageId: String): Int {
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        return jobDao.countVideosPublishedSince(pageId, since)
    }

    private suspend fun enqueue(
        pageId: String,
        postType: PostType,
        mediaUri: String?,
        caption: String,
        scheduledFor: Long?,
    ): Long {
        val now = System.currentTimeMillis()
        val jobId = jobDao.upsert(
            PublishJobEntity(
                pageId = pageId,
                postType = postType,
                localMediaUri = mediaUri,
                caption = caption,
                state = JobState.PENDING,
                scheduledFor = scheduledFor,
                createdAt = now,
                updatedAt = now,
            ),
        )
        schedule(jobId)
        return jobId
    }

    private suspend fun schedule(jobId: Long) {
        val networkType = if (preferences.wifiOnlyUploadsNow()) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }

        val request = OneTimeWorkRequestBuilder<PublishWorker>()
            .setInputData(Data.Builder().putLong(PublishWorker.KEY_JOB_ID, jobId).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(networkType)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG_PUBLISH)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            PublishWorker.workName(jobId),
            // KEEP, not REPLACE: if this job is already running, re-enqueuing must not
            // restart it and risk publishing the same post twice.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val TAG_PUBLISH = "publish"
        private const val BACKOFF_SECONDS = 30L
    }
}
