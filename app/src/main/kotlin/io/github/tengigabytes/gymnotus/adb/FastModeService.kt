// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 tengigabytes and Gymnotus contributors

package io.github.tengigabytes.gymnotus.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import io.github.tengigabytes.gymnotus.GymnotusApp
import io.github.tengigabytes.gymnotus.R
import io.github.tengigabytes.gymnotus.power.FINE_PERMISSION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One-time setup of fast mode. The pairing code is typed into a notification because the system's pairing dialog
 * closes as soon as the user leaves Settings; the reply starts this service, which pairs and grants.
 */
class FastModeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString().orEmpty()
        // Accepted forms: "123456", or "123456 37215 41234" (code, pairing port, connect port) if mDNS finds nothing.
        val parts = reply.trim().split(Regex("\\s+"))
        val code = parts[0]
        if (!code.matches(Regex("\\d{6}"))) {
            notify(this, getString(R.string.notif_setup_bad_code), withInput = true)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        notify(this, getString(R.string.notif_setup_pairing), withInput = false)
        scope.launch {
            val result = SelfAdb.pairAndGrant(
                context = applicationContext,
                permission = FINE_PERMISSION,
                pairingCode = code,
                pairingPort = parts.getOrNull(1)?.toIntOrNull(),
                connectPort = parts.getOrNull(2)?.toIntOrNull(),
            )
            (application as GymnotusApp).sampler.onFinePermissionChanged()
            notify(this@FastModeService, result.message, withInput = !result.granted)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val KEY_CODE = "code"
        private const val CHANNEL_ID = "setup"
        private const val NOTIFICATION_ID = 2

        /** Shows the notification that takes the pairing code. */
        fun showPrompt(context: Context) = notify(context, context.getString(R.string.notif_setup_prompt), withInput = true)

        private fun notify(context: Context, text: String, withInput: Boolean) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_setup_channel), NotificationManager.IMPORTANCE_HIGH))
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_gymnotus)
                .setContentTitle(context.getString(R.string.notif_setup_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
            if (withInput) {
                // Mutable: the system adds the typed text to this intent.
                val reply = PendingIntent.getService(
                    context,
                    0,
                    Intent(context, FastModeService::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val input = RemoteInput.Builder(KEY_CODE).setLabel(context.getString(R.string.notif_setup_input_label)).build()
                builder.addAction(Notification.Action.Builder(null, context.getString(R.string.notif_setup_action), reply).addRemoteInput(input).build())
            }
            manager.notify(NOTIFICATION_ID, builder.build())
        }
    }
}
