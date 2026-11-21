/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.profiles

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import mozilla.components.support.ktx.android.notification.ChannelData
import mozilla.components.support.ktx.android.notification.ensureNotificationChannelExists
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R

/**
 * Profiles: foreground service that keeps the app process alive while tabs are kept running in the background
 * ([ProfileKeepAlive]). Its ongoing, silent notification says how many tabs are kept and offers "Stop".
 */
class ProfileKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ProfileKeepAlive.clear(this) // the store watcher then stops the service
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(count),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            } else {
                0
            },
        )
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        runningCount = 0
        super.onDestroy()
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(count: Int) = NotificationCompat.Builder(this, ensureChannel(this))
        .setSmallIcon(R.drawable.ic_status_logo)
        .setContentTitle(getString(R.string.profiles_keep_alive_notification_title))
        .setContentText(
            resources.getQuantityString(R.plurals.profiles_keep_alive_notification_text, count, count),
        )
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0,
            getString(R.string.profiles_keep_alive_stop),
            PendingIntent.getService(
                this,
                1,
                Intent(this, ProfileKeepAliveService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    companion object {
        private const val NOTIFICATION_ID = 0x5241 // "RA"
        private const val CHANNEL_ID = "profiles_keep_alive"
        private const val ACTION_STOP = "app.ravenprofiles.action.KEEP_ALIVE_STOP"
        private const val EXTRA_COUNT = "count"

        @Volatile private var running = false

        @Volatile private var runningCount = 0

        private fun ensureChannel(context: Context) = ensureNotificationChannelExists(
            context,
            ChannelData(CHANNEL_ID, R.string.profiles_keep_alive_channel, NotificationManager.IMPORTANCE_MIN),
            onSetupChannel = { setShowBadge(false) },
        )

        /** Starts the service, or updates its notification, for [count] kept tabs. */
        fun start(context: Context, count: Int) {
            if (running && runningCount == count) return
            runningCount = count
            context.startKeepAliveService(
                Intent(context, ProfileKeepAliveService::class.java).putExtra(EXTRA_COUNT, count),
            )
        }

        /** Stops the service if it runs. */
        fun stop(context: Context) {
            if (!running) return
            context.stopService(Intent(context, ProfileKeepAliveService::class.java))
        }
    }
}
