package `in`.setu.app.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import `in`.setu.app.R
import `in`.setu.app.SetuApp
import `in`.setu.app.ui.MainActivity
import `in`.setu.protocol.PacketType
import `in`.setu.routing.DutyCycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The always-on part of the app. Keeps Nearby advertising, switches discovery
 * on and off according to the duty cycle, resends the active SOS, expires old
 * packets, and drains the store to the relay whenever the phone is online.
 */
class MeshService : LifecycleService() {
    private val g by lazy { SetuApp.graph(application) }
    private lateinit var transport: NearbyTransport
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        transport = NearbyTransport(this, g.store, g.clock, g.stats) { p ->
            if (g.sos.onPacketAccepted(p)) notifyAcked()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop == null) start()
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(0, 0), type)
        true
    } catch (e: Exception) {
        // Android 14+: missing runtime permission for the service type.
        Log.w(TAG, "cannot start foreground", e)
        false
    }

    private fun start() {
        g.stats.update { it.copy(serviceRunning = true) }
        g.connectivity.start()
        loop = lifecycleScope.launch(Dispatchers.IO) {
            launch { dutyLoop() }
            launch { housekeepingLoop() }
            launch { uploadWhenOnline() }
        }
    }

    private suspend fun dutyLoop() {
        transport.startAdvertising()
        while (currentCoroutineIsActive()) {
            val b = g.battery.read()
            val s = g.settings.snapshot()
            val wanted = (s.courierEnabled || s.sosActive) && DutyCycle.shouldRelay(b.percent, s.sosActive, b.charging)
            if (!s.courierEnabled && !s.sosActive) {
                // Nothing to do: the user turned courier off and has no SOS.
                withContext(Dispatchers.Main) { stopSelf() }
                return
            }
            if (!wanted) {
                transport.stopDiscovery()
                delay(60_000)
                continue
            }
            val duty = DutyCycle.forState(b.percent, s.sosActive, b.charging)
            g.stats.update { it.copy(duty = duty) }
            transport.startDiscovery()
            delay(duty.discoverySeconds * 1000L)
            transport.stopDiscovery()
            delay(duty.sleepSeconds * 1000L)
        }
    }

    private suspend fun housekeepingLoop() {
        while (currentCoroutineIsActive()) {
            runCatching { g.store.expire() }.onFailure { Log.w(TAG, "expire", it) }
            runCatching { g.sos.resendIfDue() }.onFailure { Log.w(TAG, "resend", it) }
            updateNotification()
            delay(60_000)
        }
    }

    private suspend fun uploadWhenOnline() {
        g.connectivity.online.collectLatest { online ->
            g.stats.update { it.copy(online = online) }
            if (!online) return@collectLatest
            // A little settling time; captive portals flap at first.
            delay(3_000)
            val n = g.uploader.uploadAll()
            if (n > 0) updateNotification()
            // Retry periodically while still online for packets that arrive later.
            while (currentCoroutineIsActive()) {
                delay(UPLOAD_INTERVAL_MS)
                g.uploader.uploadAll()
            }
        }
    }

    private suspend fun currentCoroutineIsActive(): Boolean =
        kotlinx.coroutines.currentCoroutineContext().isActive

    private fun updateNotification() {
        val total = g.store.size
        val sos = g.store.byType(PacketType.SOS).size
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(total, sos))
    }

    private fun buildNotification(total: Int, sos: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_MESH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_body, total, sos))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifyAcked() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_acked_title))
            .setContentText(getString(R.string.notif_acked_body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ACK_ID, n)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESH, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, getString(R.string.notif_channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    override fun onDestroy() {
        loop?.cancel()
        loop = null
        runCatching { transport.stopAll() }
        g.connectivity.stop()
        g.stats.update { it.copy(serviceRunning = false, radioOn = false) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MeshService"
        const val ACTION_STOP = "in.setu.app.STOP"
        const val CHANNEL_MESH = "mesh"
        const val CHANNEL_ALERTS = "alerts"
        const val NOTIF_ID = 1
        const val NOTIF_ACK_ID = 2
        const val UPLOAD_INTERVAL_MS = 120_000L

        fun start(context: Context) {
            val i = Intent(context, MeshService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MeshService::class.java).setAction(ACTION_STOP))
        }
    }
}
