package com.ryn.aispeed

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ryn.aispeed.core.database.DownloadDao
import com.ryn.aispeed.ui.LanguageStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service for background downloads: keeps the app process alive (so the engine keeps working
 * with the screen off or the app closed) and shows one live progress notification. It stops by itself a few
 * seconds after the last active download ends. The download engine is a process singleton; this service
 * only observes the database.
 */
@AndroidEntryPoint
class DownloadForegroundService : Service() {

    @Inject
    lateinit var downloadDao: DownloadDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate() // Hilt injects here
        createChannel()
        val notification = buildNotification(0, 0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (watcher == null) {
            watcher = scope.launch { watch() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watch() {
        var idleRounds = 0
        while (true) {
            val active = downloadDao.observeAll().first().filter { it.status in ACTIVE_STATUSES }
            if (active.isEmpty()) {
                idleRounds++
                if (idleRounds >= IDLE_ROUNDS_BEFORE_STOP) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return
                }
            } else {
                idleRounds = 0
                val known = active.filter { (it.fileSize ?: 0L) > 0L }
                val total = known.sumOf { it.fileSize ?: 0L }
                val done = known.sumOf { minOf(it.downloadedBytes, it.fileSize ?: 0L) }
                val percent = if (total > 0L) (done * 100L / total).toInt() else 0
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(active.size, percent))
            }
            delay(POLL_MS)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val strings = LanguageStore.stringsFor(LanguageStore.load(this))
        val channel = NotificationChannel(CHANNEL_ID, strings.notifChannelName, NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(count: Int, percent: Int): android.app.Notification {
        val strings = LanguageStore.stringsFor(LanguageStore.load(this))
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("RYN-AI-Speed")
            .setContentText(strings.notifProgress(count, percent))
            .setProgress(100, percent, count == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ryn_downloads"
        private const val NOTIFICATION_ID = 4101
        private const val POLL_MS = 2_000L
        private const val IDLE_ROUNDS_BEFORE_STOP = 3
        private val ACTIVE_STATUSES = setOf("DOWNLOADING", "CONNECTING", "RETRYING", "QUEUED")

        /** Safe to call whenever a download starts or resumes; a no-op if the service already runs. */
        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, DownloadForegroundService::class.java)
                )
            } catch (e: Exception) {
                // The system may refuse (e.g. restricted background start): downloads still run while the app is open.
            }
        }
    }
}
