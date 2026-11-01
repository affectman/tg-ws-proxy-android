package io.github.affectman.tgwsproxy.update

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.affectman.tgwsproxy.App
import io.github.affectman.tgwsproxy.BuildConfig
import io.github.affectman.tgwsproxy.R
import io.github.affectman.tgwsproxy.app
import io.github.affectman.tgwsproxy.data.UpdateInfo
import io.github.affectman.tgwsproxy.ui.MainActivity
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Daily update check. JobScheduler batches it with other work, and it waits
 * for a network and a battery that isn't low, so it costs next to nothing.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = applicationContext.app.settings
        if (!repo.current().checkUpdates) return Result.success()
        return try {
            val info = UpdateChecker.fetchNewer()
            repo.markUpdateChecked(System.currentTimeMillis())
            val known = repo.pendingUpdate.first()
            repo.setPendingUpdate(info)
            if (info != null && info.versionCode != known?.versionCode) notify(applicationContext, info)
            Result.success()
        } catch (e: Exception) {
            // No retries with backoff: GitHub may be unreachable for hours, and
            // tomorrow's run (or opening the app) checks again for free.
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "update-check"
        private const val NOTIFICATION_ID = 2

        fun schedule(context: Context) {
            // Debug builds have their own applicationId; release APKs wouldn't replace them.
            if (BuildConfig.DEBUG) return
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun notify(context: Context, info: UpdateInfo) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
            val open = PendingIntent.getActivity(
                context, 3, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, App.CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_stat_proxy)
                .setContentTitle(context.getString(R.string.update_notif_title))
                .setContentText(context.getString(R.string.update_notif_text, info.versionName))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        }
    }
}
