// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 tengigabytes and Gymnotus contributors

package io.github.tengigabytes.gymnotus.sampler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import io.github.tengigabytes.gymnotus.GymnotusApp
import io.github.tengigabytes.gymnotus.MainActivity
import io.github.tengigabytes.gymnotus.R

/**
 * Keeps the process sampling and writing the log while the app is not on screen.
 *
 * It takes no wake lock on purpose: holding the CPU awake would change the very power being measured. While the
 * device sleeps no polls happen; the rails keep accumulating energy, so the first poll after wake-up covers the gap.
 */
class LogService : Service() {
    private val sampler get() = (application as GymnotusApp).sampler
    private var logging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Must come first: a service started with startForegroundService has a few seconds to do this.
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                val uri = intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                if (uri != null && !logging && sampler.startLog(uri, name)) {
                    logging = true
                    sampler.acquire(Sampler.Holder.LOG)
                } else if (!logging) {
                    stopSelf()
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (logging) {
            logging = false
            sampler.stopLog()
            sampler.release(Sampler.Holder.LOG)
        }
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_log_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, stopIntent(this), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gymnotus)
            .setContentTitle(getString(R.string.notif_log_title))
            .setContentText(getString(R.string.notif_log_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_log_stop), stop).build())
            .build()
    }

    companion object {
        private const val ACTION_START = "io.github.tengigabytes.gymnotus.action.START_LOG"
        private const val ACTION_STOP = "io.github.tengigabytes.gymnotus.action.STOP_LOG"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_NAME = "name"
        private const val CHANNEL_ID = "log"
        private const val NOTIFICATION_ID = 1

        fun startIntent(context: Context, uri: Uri, fileName: String): Intent =
            Intent(context, LogService::class.java).setAction(ACTION_START).putExtra(EXTRA_URI, uri).putExtra(EXTRA_NAME, fileName)

        fun stopIntent(context: Context): Intent = Intent(context, LogService::class.java).setAction(ACTION_STOP)
    }
}
