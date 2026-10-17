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
import org.lspstore.domain.model.UpdateInfo

object UpdateNotifier {
    const val CHANNEL_ID = "module_updates"
    private const val NOTIFICATION_ID = 2001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Module updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "LSPosed module updates found by the background check"
        }
        manager.createNotificationChannel(channel)
    }

    fun notifyUpdates(context: Context, updates: List<UpdateInfo>) {
        if (updates.isEmpty() || !canNotify(context)) return
        ensureChannel(context)
        val title = if (updates.size == 1) {
            "${updates.first().module.title} update available"
        } else {
            "${updates.size} module updates available"
        }
        val text = if (updates.size == 1) {
            val update = updates.first()
            "${update.installed.versionName ?: "?"} -> ${update.latest.versionName}"
        } else {
            updates.take(6).joinToString(", ") { it.module.title }
        }
        post(context, title, text)
    }

    fun notifyAutoUpdateResult(context: Context, succeeded: Int, failed: Int) {
        if ((succeeded == 0 && failed == 0) || !canNotify(context)) return
        ensureChannel(context)
        val text = buildString {
            append("$succeeded module(s) updated")
            if (failed > 0) append(", $failed failed")
        }
        post(context, "Auto-update finished", text)
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, title: String, text: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
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
