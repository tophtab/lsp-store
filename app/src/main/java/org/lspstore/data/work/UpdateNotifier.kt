package org.lspstore.data.work

import android.Manifest
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
import org.lspstore.MainActivity
import org.lspstore.R
import org.lspstore.domain.model.UpdateInfo

object UpdateNotifier {
    const val CHANNEL_ID = "module_updates"
    const val EXTRA_OPEN_UPDATES = "org.lspstore.extra.OPEN_UPDATES"
    private const val NOTIFICATION_ID = 2001

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notifier_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notifier_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * @param ambiguous updates that exist but need the user to pick a variant.
     *   They are never downloaded in the background, but staying silent about
     *   them makes the notification count disagree with the Installed tab.
     */
    fun notifyUpdates(context: Context, updates: List<UpdateInfo>, ambiguous: Int = 0) {
        if ((updates.isEmpty() && ambiguous <= 0) || !canNotify(context)) return
        ensureChannel(context)
        val title = when {
            updates.isEmpty() -> context.getString(R.string.notifier_ambiguous_title, ambiguous)
            updates.size == 1 ->
                context.getString(R.string.notifier_single_update, updates.first().module.title)

            else -> context.getString(R.string.notifier_multi_updates, updates.size)
        }
        val text = when {
            updates.isEmpty() -> context.getString(R.string.notifier_ambiguous_text)
            updates.size == 1 -> context.getString(
                R.string.installed_version_arrow,
                updates.first().installed.versionName ?: "?",
                updates.first().latest.versionName,
            )

            else -> updates.take(6).joinToString(", ") { it.module.title }
        }.let { base ->
            if (ambiguous > 0 && updates.isNotEmpty()) {
                base + context.getString(R.string.notifier_ambiguous_more, ambiguous)
            } else {
                base
            }
        }
        post(context, title, text)
    }

    fun notifyAutoUpdateResult(context: Context, succeeded: Int, failed: Int) {
        if ((succeeded == 0 && failed == 0) || !canNotify(context)) return
        ensureChannel(context)
        val text = buildString {
            append(context.getString(R.string.notifier_auto_updated, succeeded))
            if (failed > 0) append(context.getString(R.string.notifier_auto_failed, failed))
        }
        post(context, context.getString(R.string.notifier_auto_title), text)
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, title: String, text: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_UPDATES, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
