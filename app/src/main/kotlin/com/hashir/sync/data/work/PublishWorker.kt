package com.hashir.sync.data.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.hashir.sync.api.GraphError
import com.hashir.sync.data.local.AccountDao
import com.hashir.sync.data.local.JobState
import com.hashir.sync.data.local.PageDao
import com.hashir.sync.data.local.PostType
import com.hashir.sync.data.local.PublishJobDao
import com.hashir.sync.data.local.PublishJobEntity
import com.hashir.sync.data.repository.PublishRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

// Aliased because kotlin.Result is also in play here -- the repository returns one, and
// this worker returns the other. Spelling both out in full is unreadable.
import androidx.work.ListenableWorker.Result as WorkResult

/**
 * Performs one publish, durably.
 *
 * The reason publishing goes through WorkManager rather than a ViewModel coroutine is
 * survival: a video upload can outlast the user's patience with staring at the screen, and
 * a coroutine dies the moment the process does. Here the job row is the source of truth,
 * so the app can be swiped away mid-upload and the post still lands.
 */
@HiltWorker
class PublishWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val jobDao: PublishJobDao,
    private val pageDao: PageDao,
    private val accountDao: AccountDao,
    private val publishRepository: PublishRepository,
    private val notifications: PublishNotifications,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): WorkResult {
        val jobId = inputData.getLong(KEY_JOB_ID, -1L)
        if (jobId < 0L) return WorkResult.failure()

        val job = jobDao.getById(jobId) ?: return WorkResult.failure()

        // WorkManager can legitimately re-run a worker that already finished, for example
        // after the process is killed between the API call succeeding and the result being
        // recorded. Without this guard that would publish the same post twice.
        if (job.state == JobState.DONE) return WorkResult.success()

        runCatching { setForeground(buildForegroundInfo(job)) }
        // Not fatal if it fails: on some OEM builds the foreground promotion is refused
        // when the app is backgrounded. The upload still runs, just less protected.

        markState(job, JobState.UPLOADING)

        val result = when (job.postType) {
            PostType.PHOTO -> {
                val uri = job.localMediaUri?.let(Uri::parse)
                    ?: return fail(job, "This post lost its image before it could be uploaded.")
                publishRepository.publishPhoto(
                    pageId = job.pageId,
                    photoUri = uri,
                    caption = job.caption,
                    scheduledForEpochSeconds = job.scheduledFor?.let { it / 1000 },
                )
            }

            PostType.TEXT -> publishRepository.publishText(
                pageId = job.pageId,
                message = job.caption,
                scheduledForEpochSeconds = job.scheduledFor?.let { it / 1000 },
            )

            PostType.VIDEO -> {
                val uri = job.localMediaUri?.let(Uri::parse)
                    ?: return fail(job, "This post lost its video before it could be uploaded.")

                // The progress callback is invoked synchronously from OkHttp's write loop,
                // which is not a coroutine, so the database write is dispatched onto this
                // worker's own scope instead of being called directly.
                val scope = CoroutineScope(currentCoroutineContext())
                var lastPersisted = job.uploadedBytes

                publishRepository.publishVideo(
                    pageId = job.pageId,
                    videoUri = uri,
                    caption = job.caption,
                    scheduledForEpochSeconds = job.scheduledFor?.let { it / 1000 },
                    resumeFromOffset = 0,
                    onTranscodeStart = {
                        // Re-encoding a long clip can take a while, so the queue says
                        // "Preparing video" rather than sitting on "Uploading" with a
                        // progress bar that has not moved.
                        scope.launch { markState(job, JobState.TRANSCODING) }
                    },
                    onProgress = { progress ->
                        // Throttled: this fires every 64 KB, and writing to Room that
                        // often would cost more than the upload itself.
                        if (progress.bytesSent - lastPersisted >= OFFSET_PERSIST_INTERVAL) {
                            lastPersisted = progress.bytesSent
                            scope.launch {
                                persistProgress(job.id, progress.bytesSent, progress.totalBytes)
                            }
                        }
                    },
                )
            }
        }

        return result.fold(
            onSuccess = { published ->
                jobDao.upsert(
                    job.copy(
                        state = JobState.DONE,
                        resultPostId = published.postId,
                        errorCode = null,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                WorkResult.success()
            },
            onFailure = { error ->
                if (isWorthRetrying(error) && runAttemptCount < MAX_ATTEMPTS) {
                    // Left in UPLOADING so the queue shows it as still in flight rather
                    // than flashing a failure the user cannot act on.
                    WorkResult.retry()
                } else {
                    // A dead token is an account-level problem, not a job-level one.
                    // Flagging it here is what surfaces the reconnect banner on the
                    // dashboard; otherwise every later post fails the same way with no
                    // indication of the actual cause.
                    if (error is GraphError.Api && error.isTokenExpired) {
                        markAccountNeedsReauth(job.pageId)
                    }
                    fail(job, describe(error), codeOf(error))
                }
            },
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val jobId = inputData.getLong(KEY_JOB_ID, -1L)
        val job = if (jobId >= 0L) jobDao.getById(jobId) else null
        return buildForegroundInfo(job)
    }

    private fun buildForegroundInfo(job: PublishJobEntity?): ForegroundInfo {
        val notification = notifications.progress(
            title = "Publishing to Facebook",
            text = job?.caption?.take(60)?.ifBlank { "Uploading..." } ?: "Uploading...",
        )
        val id = PublishNotifications.notificationId(job?.id ?: 0L)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ demands the service type be declared here as well as in the
            // manifest, and rejects the promotion outright if they disagree.
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private suspend fun markAccountNeedsReauth(pageId: String) {
        val accountId = pageDao.getById(pageId)?.accountId ?: return
        accountDao.setNeedsReauth(accountId, needsReauth = true)
    }

    private suspend fun markState(job: PublishJobEntity, state: JobState) {
        jobDao.upsert(job.copy(state = state, updatedAt = System.currentTimeMillis()))
    }

    /**
     * Records how far the upload got.
     *
     * Re-read rather than reusing the captured job, because the row may have been updated
     * since this worker started and writing a stale copy would undo that.
     */
    private suspend fun persistProgress(jobId: Long, sent: Long, total: Long) {
        val current = jobDao.getById(jobId) ?: return
        jobDao.upsert(
            current.copy(
                uploadedBytes = sent,
                totalBytes = total,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun fail(
        job: PublishJobEntity,
        message: String,
        code: String? = null,
    ): WorkResult {
        jobDao.upsert(
            job.copy(
                state = JobState.FAILED,
                errorCode = code,
                errorMessage = message,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return WorkResult.failure()
    }

    /**
     * Retry only what a retry can actually fix.
     *
     * A dropped connection or a Facebook rate limit will clear on its own. A missing
     * permission or a dead token will not, and retrying those just burns battery and
     * delays telling the user something they need to act on.
     */
    private fun isWorthRetrying(error: Throwable): Boolean = when (error) {
        is GraphError.Network -> true
        is GraphError.Api -> error.isRateLimited
        else -> false
    }

    private fun codeOf(error: Throwable): String? =
        (error as? GraphError.Api)?.let { api ->
            api.subcode?.let { "${api.code}/$it" } ?: api.code.toString()
        }

    private fun describe(error: Throwable): String = when (error) {
        is GraphError.Network -> "No connection. Retry when you are back online."

        is GraphError.Api -> when {
            error.isTokenExpired -> "That account's login expired. Reconnect it and retry."
            error.isPermissionDenied ->
                "Facebook refused this: ${error.fbMessage} Check you are an admin of the Page."
            error.isRateLimited -> "Facebook is rate limiting this app. Try again later."
            else -> "Facebook error ${error.code}: ${error.fbMessage}"
        }

        else -> error.message ?: "Publishing failed."
    }

    companion object {
        const val KEY_JOB_ID = "job_id"

        /**
         * Four attempts with WorkManager's exponential backoff spans roughly ten minutes,
         * which covers a tunnel or a lift without leaving a doomed job retrying all day.
         */
        const val MAX_ATTEMPTS = 4

        /** Write the resume offset roughly every megabyte, not on every 64 KB chunk. */
        const val OFFSET_PERSIST_INTERVAL = 1024L * 1024L

        fun workName(jobId: Long): String = "publish-$jobId"
    }
}
