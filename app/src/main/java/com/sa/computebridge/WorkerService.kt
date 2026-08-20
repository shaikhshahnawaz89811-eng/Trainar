package com.sa.computebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat

class WorkerService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        startForeground(1001, notification("Worker is starting"))
        try {
            WorkerRuntime.start(this)
            update("Worker ready on port ${WorkerRuntime.getPort()}")
        } catch (t: Throwable) {
            update("Worker failed to start safely: ${t.message ?: "unknown error"}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        WorkerRuntime.stop(this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("compute_worker", "Compute Bridge Worker", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, "compute_worker")
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("Compute Bridge")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun update(text: String) = getSystemService(NotificationManager::class.java).notify(1001, notification(text))
}
