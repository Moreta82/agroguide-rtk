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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
    private var ntripSession: NtripSession? = null
    private var collectorJob: kotlinx.coroutines.Job? = null
    private var mockPublisher: com.agroguide.rtk.device.MockLocationPublisher? = null

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
        activeBt = source as? BtSppPositionSource
        startNtripIfRequested(intent, source)
        // mock output: never in Android-location mode (feedback loop)
        mockPublisher?.stop(); mockPublisher = null
        if (intent?.getBooleanExtra(EXTRA_MOCK, false) == true &&
            intent.getStringExtra(EXTRA_MODE) != MODE_ANDROID
        ) {
            mockPublisher = com.agroguide.rtk.device.MockLocationPublisher(this)
                .takeIf { it.start() }
        }
        startVehicleCollector()
        JobManager.tryResumeOnStart(applicationContext)
        acquireWakeLock()
        return START_STICKY
    }

    /**
     * Guidance and coverage are driven from the service so they keep running
     * with the screen off; the UI only observes repositories.
     */
    private fun startVehicleCollector() {
        collectorJob?.cancel()
        collectorJob = lifecycleScope.launch {
            PositionRepository.vehicle.collect { state ->
                if (state != null) {
                    mockPublisher?.publish(state)
                    GuidanceRepository.update(state)
                    val work = GuidanceRepository.implement.value.workPoint(
                        com.agroguide.rtk.geo.EnuPoint(state.x, state.y), state.headingDeg,
                    )
                    // paused job = no painting and no recording
                    if (!JobManager.isPaused) {
                        CoverageRepository.update(state, work)
                        var mask = 0
                        CoverageRepository.sections.value.forEachIndexed { i, on -> if (on) mask = mask or (1 shl i) }
                        JobManager.onState(state, work, mask)
                    }
                }
            }
        }
    }

    private fun startNtripIfRequested(intent: Intent?, source: PositionSource) {
        ntripSession?.stop(); ntripSession = null
        val bt = source as? BtSppPositionSource ?: return
        val events = PositionRepository.sharedEvents ?: return
        if (intent?.getBooleanExtra(EXTRA_NTRIP, false) != true) return
        val session = NtripSession(
            scope = lifecycleScope,
            bt = bt,
            sourceEvents = events,
            creds = com.agroguide.rtk.ntrip.NtripCredentials(
                intent.getStringExtra(EXTRA_NTRIP_HOST) ?: return,
                intent.getIntExtra(EXTRA_NTRIP_PORT, 2101),
                intent.getStringExtra(EXTRA_NTRIP_USER) ?: "",
                intent.getStringExtra(EXTRA_NTRIP_PASS) ?: "",
            ),
            mountPoint = intent.getStringExtra(EXTRA_NTRIP_MOUNT) ?: "",
            version = if (intent.getBooleanExtra(EXTRA_NTRIP_V2, false)) {
                com.agroguide.rtk.ntrip.NtripVersion.V2
            } else {
                com.agroguide.rtk.ntrip.NtripVersion.V1
            },
            uploadGga = intent.getBooleanExtra(EXTRA_NTRIP_GGA, true),
            ggaIntervalS = intent.getIntExtra(EXTRA_NTRIP_GGA_INT, 10),
        )
        ntripSession = session
        session.start()
        lifecycleScope.launch { session.stats.collect { PositionRepository.publishNtrip(it) } }
    }

    override fun onDestroy() {
        mockPublisher?.stop(); mockPublisher = null
        collectorJob?.cancel(); collectorJob = null
        ntripSession?.stop(); ntripSession = null
        activeBt = null
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
        const val EXTRA_MOCK = "mock_provider"
        const val EXTRA_NTRIP = "ntrip"
        const val EXTRA_NTRIP_HOST = "ntrip_host"
        const val EXTRA_NTRIP_PORT = "ntrip_port"
        const val EXTRA_NTRIP_USER = "ntrip_user"
        const val EXTRA_NTRIP_PASS = "ntrip_pass"
        const val EXTRA_NTRIP_MOUNT = "ntrip_mount"
        const val EXTRA_NTRIP_V2 = "ntrip_v2"
        const val EXTRA_NTRIP_GGA = "ntrip_gga"
        const val EXTRA_NTRIP_GGA_INT = "ntrip_gga_int"
        const val MODE_DEMO = "demo"
        const val MODE_BT = "bt"
        const val MODE_ANDROID = "android"
        private const val CHANNEL_ID = "guidance"
        private const val NOTIF_ID = 1

        /** The live BT source, for receiver utilities (UBX writes from the UI). */
        @Volatile var activeBt: BtSppPositionSource? = null
            private set

        fun start(
            context: Context,
            mode: String,
            btAddress: String? = null,
            ntrip: NtripStartConfig? = null,
            mockProvider: Boolean = false,
        ) {
            val i = Intent(context, GuidanceService::class.java)
                .putExtra(EXTRA_MODE, mode).putExtra(EXTRA_BT_ADDRESS, btAddress)
                .putExtra(EXTRA_MOCK, mockProvider)
            if (ntrip != null) {
                i.putExtra(EXTRA_NTRIP, true)
                    .putExtra(EXTRA_NTRIP_HOST, ntrip.host)
                    .putExtra(EXTRA_NTRIP_PORT, ntrip.port)
                    .putExtra(EXTRA_NTRIP_USER, ntrip.username)
                    .putExtra(EXTRA_NTRIP_PASS, ntrip.password)
                    .putExtra(EXTRA_NTRIP_MOUNT, ntrip.mountPoint)
                    .putExtra(EXTRA_NTRIP_V2, ntrip.v2)
                    .putExtra(EXTRA_NTRIP_GGA, ntrip.uploadGga)
                    .putExtra(EXTRA_NTRIP_GGA_INT, ntrip.ggaIntervalS)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) = context.stopService(Intent(context, GuidanceService::class.java))
    }

    /** Flattened NTRIP profile passed through the start intent. */
    data class NtripStartConfig(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val mountPoint: String,
        val v2: Boolean,
        val uploadGga: Boolean,
        val ggaIntervalS: Int,
    )
}
