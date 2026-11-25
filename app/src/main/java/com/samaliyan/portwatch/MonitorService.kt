package com.samaliyan.portwatch

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps checking all servers at the chosen interval until stopped. */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Store.setMonitoring(this, false)
            Live.monitoring.value = false
            stopSelf()
            return START_NOT_STICKY
        }

        Notifier.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.MONITOR_ID, Notifier.monitorNotification(this), type)

        Store.setMonitoring(this, true)
        Live.monitoring.value = true

        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PortWatch:monitor").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    try {
                        Engine.check(this@MonitorService)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    delay(Store.intervalSec(this@MonitorService) * 1000L)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Live.monitoring.value = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.samaliyan.portwatch.STOP"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
        }

        fun stop(ctx: Context) {
            Store.setMonitoring(ctx, false)
            Live.monitoring.value = false
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }
    }
}

/** Restarts monitoring after the phone reboots, if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Store.monitoring(context)) {
            try {
                MonitorService.start(context)
            } catch (_: Exception) {
            }
        }
    }
}
