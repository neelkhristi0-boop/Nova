package com.laughterlabs.nova

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

private fun listenIntent(ctx: Context) = Intent(ctx, MainActivity::class.java)
    .putExtra(MainActivity.EXTRA_LISTEN, true)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

private fun listenPending(ctx: Context) = PendingIntent.getActivity(
    ctx, 7, listenIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)

/** A pinned notification: pull down from any app, tap "Talk to Nova". */
object NovaNotification {
    private const val CHANNEL = "nova"

    fun show(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nova quick access", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Nova is ready")
            .setContentText("Tap to talk")
            .setContentIntent(listenPending(ctx))
            .setOngoing(true)
            .build()
        nm.notify(1, n)
    }
}

/** Quick Settings tile: swipe down twice, tap Nova, start talking. */
class NovaTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_ACTIVE; label = "Nova"; updateTile() }
    }

    override fun onClick() {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(listenPending(this))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(listenIntent(this))
        }
    }
}
