package io.github.tophtab.lspstore.data.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import io.github.tophtab.lspstore.LspStoreApp
import io.github.tophtab.lspstore.R

/**
 * Keeps APK downloads alive while the app is in the background and shows their
 * progress. Downloads themselves already run in the app process (see
 * [DownloadManager]); this service only keeps that process from being frozen and
 * gives the user a single place to see and cancel active downloads.
 *
 * If the platform refuses a background start, [start] silently does nothing and
 * downloads still work as long as the app process lives.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: DownloadManager
    private var observing = false

    override fun onCreate() {
        super.onCreate()
        manager = (application as LspStoreApp).container.downloadManager
        ensureChannel()
        Log.d(TAG, "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            manager.cancelAllActive()
            return START_NOT_STICKY
        }

        val initial = activeTasks()
        Log.d(TAG, "onStartCommand active=${initial.size}")
        if (initial.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(initial))

        if (!observing) {
            observing = true
            scope.launch {
                manager.tasks.collect { tasks ->
                    val active = tasks.values.filter {
                        it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PAUSED
                    }
                    if (active.isEmpty()) {
                        Log.d(TAG, "all downloads finished, stopping")
                        stopSelf()
                        return@collect
                    }
                    NotificationManagerCompat.from(this@DownloadService)
                        .notify(NOTIFICATION_ID, buildNotification(active))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun activeTasks(): List<DownloadTask> =
        manager.tasks.value.values.filter {
            it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PAUSED
        }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }
    }

    private fun buildNotification(tasks: List<DownloadTask>): Notification {
        val downloaded = tasks.sumOf { it.downloaded }
        val total = tasks.sumOf { it.total }
        val text = if (total > 0) {
            getString(R.string.download_notification_text, formatSize(downloaded), formatSize(total))
        } else {
            getString(R.string.download_notification_unknown)
        }
        val cancelAll = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.download_notification_title, tasks.size))
            .setContentText(text)
            .setProgress(
                if (total > 0) total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
                if (total > 0) downloaded.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
                total <= 0,
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.action_cancel_all), cancelAll)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.download_channel_desc)
                setShowBadge(false)
            },
        )
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 KB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) {
            String.format(java.util.Locale.getDefault(), "%.1f MB", mb)
        } else {
            String.format(java.util.Locale.getDefault(), "%.0f KB", bytes / 1024.0)
        }
    }

    companion object {
        private const val TAG = "DownloadService"
        const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 2002
        const val ACTION_CANCEL_ALL = "io.github.tophtab.lspstore.action.CANCEL_ALL_DOWNLOADS"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            // minSdk is 26, so startForegroundService is always available.
            runCatching { context.startForegroundService(intent) }
        }
    }
}