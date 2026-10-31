package com.facevideo.search

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Foreground service so the scan keeps going when you leave the app or turn the screen off. */
class ScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(IndexState(running = true)),
            if (android.os.Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (Indexer.job?.isActive == true) return START_NOT_STICKY

        val mode = runCatching { Indexer.Mode.valueOf(intent?.getStringExtra("mode") ?: "") }
            .getOrDefault(Indexer.Mode.BALANCED)

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "facevideo:scan")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        val nm = getSystemService(NotificationManager::class.java)
        Indexer.job = scope.launch {
            val progress = launch {
                Indexer.state.collect { nm.notify(NOTIF_ID, buildNotification(it)) }
            }
            try {
                Indexer.run(applicationContext, mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Indexer.state.update { it.copy(running = false, error = e.message ?: e.javaClass.simpleName) }
            } finally {
                progress.cancel()
                ServiceCompat.stopForeground(this@ScanService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Face scan", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(s: IndexState): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Scanning videos for faces")
            .setContentText(if (s.total > 0) "${s.done} / ${s.total} videos" else "Preparing…")
            .setProgress(s.total, s.done, s.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val CHANNEL = "scan"
        private const val NOTIF_ID = 1
    }
}
