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
 * available" and the silent auto-update summary.
 *
 * Every method reports whether the notification was actually posted, so the
 * check only records its dedup signature when the user could see the result: a
 * denied permission leaves the same update set announceable later.
 */
class UpdateNotifier(private val context: Context) {

    /** Posts the "updates available" notification; false when it could not post. */
    fun notifyUpdates(updates: List<UpdateInfo>): Boolean {
        if (updates.isEmpty() || !canPost()) return false
        ensureChannel()
        val title = if (updates.size == 1) {
            val only = updates.first()
            context.getString(R.string.updates_notification_title_one, only.module.title)
        } else {
            context.getString(R.string.updates_notification_title_many, updates.size)
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

    /** Posts the auto-update summary; false when it could not post. */
    fun notifyAutoUpdateResult(succeeded: Int, failed: Int): Boolean {
        if (succeeded == 0 && failed == 0) return true
        if (!canPost()) return false
        ensureChannel()
        val text = if (failed > 0) {
            context.getString(R.string.updates_auto_text, succeeded, failed)
        } else {
            context.getString(R.string.updates_auto_text_ok, succeeded)
        }
        return post(build(context.getString(R.string.updates_auto_title), text))
    }

    private fun build(title: String, text: String) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(openInstalledIntent())
        .setOnlyAlertOnce(true)
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

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
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

    companion object {
        const val CHANNEL_ID = "module_updates"
        const val EXTRA_OPEN_INSTALLED = "io.github.tophtab.lspstore.extra.OPEN_INSTALLED"
        private const val NOTIFICATION_ID = 2001
    }
}
