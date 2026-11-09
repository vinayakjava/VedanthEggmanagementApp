package com.eggbusinessmanager.app.feature.backup.auto

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.backup.failureMessage
import com.eggbusinessmanager.domain.backup.BackupFailure
import com.eggbusinessmanager.domain.backup.BackupFrequency
import com.eggbusinessmanager.domain.backup.BackupNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Failure reporting outside the app (Phase 10): when an automatic backup gives up, a notification says
 * why. It never contains business data. Without the notification permission the history and the backup
 * screen still show the failure.
 */
@Singleton
class AndroidBackupNotifier @Inject constructor(@ApplicationContext private val context: Context) : BackupNotifier {

    override fun notifyFailure(failure: BackupFailure) =
        show(context.getString(R.string.auto_failed_title), context.getString(failureMessage(failure)))

    override fun notifyMissed(periods: Int, frequency: BackupFrequency) {
        val unit = when (frequency) {
            BackupFrequency.DAILY -> R.plurals.auto_missed_days
            BackupFrequency.WEEKLY -> R.plurals.auto_missed_weeks
            BackupFrequency.MONTHLY -> R.plurals.auto_missed_months
        }
        show(context.getString(R.string.auto_missed_title), context.resources.getQuantityString(unit, periods, periods))
    }

    /** One notification at a time: a newer message replaces the older one. Never contains business data. */
    private fun show(title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    override fun clear() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.auto_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    private companion object {
        const val CHANNEL = "backup_status"
        const val NOTIFICATION_ID = 4101
    }
}
