package io.github.tophtab.lspstore.data.update

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.tophtab.lspstore.MainActivity
import io.github.tophtab.lspstore.R
import io.github.tophtab.lspstore.domain.model.UpdateInfo

/**
 * Posts the two notifications the background check produces: "updates
 * available" on the alerting channel and the auto-update summary on its own
 * low-importance silent channel.
 *
 * Every method reports whether the notification was actually posted, so the
 * check only records its dedup signature when the user could see the result: a
 * denied permission leaves the same update set announceable later.
 */
class UpdateNotifier(private val context: Context) {

    /** Posts the "updates available" notification; false when it could not post. */
    fun notifyUpdates(updates: List<UpdateInfo>): Boolean {
        if (updates.isEmpty() || !canPost()) return false
        ensureChannels()
        val title = if (updates.size == 1) {
            val only = updates.first()
            context.getString(R.string.updates_notification_title_one, only.module.title)
        } else {
            context.resources.getQuantityString(R.plurals.updates_notification_title_many, updates.size, updates.size)
        }
        val text = if (updates.size == 1) {
            val only = updates.first()
            context.getString(
                R.string.installed_version_arrow,
                only.installed.versionName ?: "?",
                only.latest.versionName,
            )
        } else {
            updates.take(4).joinToString(", ") { it.module.title }
        }
        return post(build(title, text))
    }

    /**
     * Posts the auto-update summary on the silent channel: the installs already
     * ran without UI, so the summary must not wake the device either.
     */
    fun notifyAutoUpdateResult(succeeded: Int, failed: Int): Boolean {
        if (succeeded == 0 && failed == 0) return true
        if (!canPost()) return false
        ensureChannels()
        val succeededText = context.resources.getQuantityString(R.plurals.updates_auto_text_ok, succeeded, succeeded)
        val text = if (failed > 0) {
            val failedText = context.resources.getQuantityString(R.plurals.updates_auto_text_failed, failed, failed)
            context.getString(R.string.updates_auto_text, succeededText, failedText)
        } else {
            succeededText
        }
        return post(
            build(
                title = context.getString(R.string.updates_auto_title),
                text = text,
                channelId = AUTO_UPDATE_CHANNEL_ID,
                silent = true,
            ),
        )
    }

    private fun build(
        title: String,
        text: String,
        channelId: String = CHANNEL_ID,
        silent: Boolean = false,
    ) = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setPriority(
            if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT,
        )
        .setContentIntent(openInstalledIntent())
        .setOnlyAlertOnce(true)
        .setSilent(silent)
        .setAutoCancel(true)
        .build()

    private fun openInstalledIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_INSTALLED, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @SuppressLint("MissingPermission")
    private fun post(notification: android.app.Notification): Boolean = runCatching {
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }.isSuccess

    private fun canPost(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.updates_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.updates_channel_desc)
                    setShowBadge(true)
                },
            )
        }
        if (manager.getNotificationChannel(AUTO_UPDATE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    AUTO_UPDATE_CHANNEL_ID,
                    context.getString(R.string.updates_auto_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.updates_auto_channel_desc)
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "module_updates"
        const val AUTO_UPDATE_CHANNEL_ID = "module_auto_updates"
        const val EXTRA_OPEN_INSTALLED = "io.github.tophtab.lspstore.extra.OPEN_INSTALLED"
        private const val NOTIFICATION_ID = 2001
    }
}
