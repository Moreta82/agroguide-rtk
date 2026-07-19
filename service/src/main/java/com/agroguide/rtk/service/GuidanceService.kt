package com.agroguide.rtk.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.agroguide.rtk.device.AndroidLocationSource
import com.agroguide.rtk.device.DemoPositionSource
import com.agroguide.rtk.device.PositionSource
import com.agroguide.rtk.device.bt.BtSppPositionSource

/**
 * Foreground service that owns the position source (and, in later phases,
 * the NTRIP session and job recorder). Started with an EXTRA_MODE of
 * demo|bt|android; bt also needs EXTRA_BT_ADDRESS.
 */
class GuidanceService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startAsForeground()
        val source: PositionSource = when (intent?.getStringExtra(EXTRA_MODE)) {
            MODE_BT -> {
                val address = intent.getStringExtra(EXTRA_BT_ADDRESS)
                if (address == null) { stopSelf(); return START_NOT_STICKY }
                BtSppPositionSource(address)
            }
            MODE_ANDROID -> AndroidLocationSource(this)
            else -> DemoPositionSource()
        }
        PositionRepository.attach(source) { SystemClock.elapsedRealtime() }
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        PositionRepository.detach()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Guiado", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AgroGuide RTK")
            .setContentText("Guiado activo")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agroguide:guidance")
        }
        wakeLock?.let { if (!it.isHeld) it.acquire(12 * 60 * 60 * 1000L) }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_BT_ADDRESS = "bt_address"
        const val MODE_DEMO = "demo"
        const val MODE_BT = "bt"
        const val MODE_ANDROID = "android"
        private const val CHANNEL_ID = "guidance"
        private const val NOTIF_ID = 1

        fun start(context: Context, mode: String, btAddress: String? = null) {
            val i = Intent(context, GuidanceService::class.java)
                .putExtra(EXTRA_MODE, mode).putExtra(EXTRA_BT_ADDRESS, btAddress)
            context.startForegroundService(i)
        }

        fun stop(context: Context) = context.stopService(Intent(context, GuidanceService::class.java))
    }
}
