package com.hashir.sync.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hashir.sync.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifications for background publishing.
 *
 * An upload has to run as a foreground service to survive the app being backgrounded, and
 * a foreground service must show a notification. So this is not decoration -- without it
 * Android kills a long upload the moment the user switches away.
 */
@Singleton
class PublishNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Publishing",
            // LOW keeps it silent. A progress bar that dings on every update is
            // intolerable, and the user already knows they pressed Publish.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress while posts are uploaded to Facebook"
            setShowBadge(false)
        }

        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /** Ongoing progress notification. [progress] is 0..100, or null for indeterminate. */
    fun progress(title: String, text: String, progress: Int? = null): android.app.Notification {
        ensureChannel()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_publish)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (progress == null) {
                    setProgress(0, 0, true)
                } else {
                    setProgress(100, progress.coerceIn(0, 100), false)
                }
            }
            .build()
    }

    companion object {
        const val CHANNEL_ID = "publishing"

        /**
         * Stable per job, so a second publish does not overwrite the first one's
         * notification. Offset well clear of any other ids the app might use.
         */
        fun notificationId(jobId: Long): Int = (NOTIFICATION_ID_BASE + jobId).toInt()

        private const val NOTIFICATION_ID_BASE = 10_000L
    }
}
