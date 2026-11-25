package com.samaliyan.portwatch

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
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

object Notifier {
    const val CH_MONITOR = "monitor"
    const val CH_ALERTS = "alerts"
    const val MONITOR_ID = 1

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, "Monitoring status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Server alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun monitorNotification(ctx: Context): Notification {
        val stop = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val count = Store.servers(ctx).size
        return NotificationCompat.Builder(ctx, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Monitoring active")
            .setContentText("$count servers · every ${Store.intervalSec(ctx)}s")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Stop", stop)
            .build()
    }

    fun down(ctx: Context, r: CheckResult) =
        post(ctx, r, "${r.serverName} is down", "${r.target.label}: ${r.detail}", NotificationCompat.PRIORITY_HIGH)

    fun up(ctx: Context, r: CheckResult) =
        post(ctx, r, "${r.serverName} is back", "${r.target.label}: ${r.detail}", NotificationCompat.PRIORITY_DEFAULT)

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, r: CheckResult, title: String, text: String, priority: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(r.key.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}
