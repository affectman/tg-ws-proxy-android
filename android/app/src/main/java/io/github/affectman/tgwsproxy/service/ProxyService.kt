package io.github.affectman.tgwsproxy.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.affectman.tgwsproxy.App
import io.github.affectman.tgwsproxy.R
import io.github.affectman.tgwsproxy.app
import io.github.affectman.tgwsproxy.core.EngineOptions
import io.github.affectman.tgwsproxy.core.EngineState
import io.github.affectman.tgwsproxy.core.ProxyEngine
import io.github.affectman.tgwsproxy.core.ProxyState
import io.github.affectman.tgwsproxy.data.AppSettings
import io.github.affectman.tgwsproxy.data.BatteryProfile
import io.github.affectman.tgwsproxy.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the Python proxy alive.
 *
 * Battery rules: no wake locks, no periodic work. The notification changes
 * only when the proxy state changes, and the screen/network watchers exist
 * only while the proxy runs.
 */
class ProxyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: Job? = null
    private var networkDebounce: Job? = null
    private var settings = AppSettings()
    private var link: String? = null
    private var watching = false
    private var lastNetwork: Network? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> if (settings.idleWhenScreenOff) ProxyEngine.setPowerMode(idle = true)
                Intent.ACTION_SCREEN_ON -> ProxyEngine.setPowerMode(idle = false)
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = lastNetwork
            lastNetwork = network
            if (previous == null || previous == network) return
            // Wi-Fi <-> mobile hand-overs fire several callbacks; act once they settle.
            networkDebounce?.cancel()
            networkDebounce = scope.launch {
                delay(NETWORK_SETTLE_MS)
                ProxyEngine.onNetworkChanged()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The service scope dies with onDestroy; this write must not.
            app.scope.launch { app.settings.setWantRunning(false) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val restart = intent?.action == ACTION_RESTART
        session?.cancel()
        session = scope.launch { runSession(restart) }
        return START_STICKY
    }

    override fun onDestroy() {
        unwatch()
        scope.cancel()
        ProxyEngine.stopInBackground()
        super.onDestroy()
    }

    private suspend fun runSession(restart: Boolean) {
        settings = app.settings.current()
        if (restart) ProxyEngine.stop()
        app.settings.setWantRunning(true)
        watch()
        val config = settings.coreConfigJson()
        link = ProxyEngine.link(this, config)
        val options = EngineOptions(
            verbose = settings.verboseLogs,
            logDir = if (settings.logToFile) ProxyEngine.logDir(this).absolutePath else null,
            eagerRewarm = settings.profile == BatteryProfile.SPEED,
        )
        if (ProxyEngine.start(this, config, options) != null) {
            stopSelf()  // config error; the UI shows it from ProxyEngine.state
            return
        }
        ProxyEngine.state
            .onEach { if (it.state.isActive) notify(buildNotification(it)) }
            .first { !it.state.isActive }
        stopSelf()  // bind error or the core stopped; the error stays in the state
    }

    private fun watch() {
        if (watching) return
        watching = true
        val power = getSystemService(PowerManager::class.java)
        ProxyEngine.setPowerMode(idle = settings.idleWhenScreenOff && !power.isInteractive)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        lastNetwork = null
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    private fun unwatch() {
        if (!watching) return
        watching = false
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        networkDebounce?.cancel()
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(EngineState(ProxyState.STARTING)),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        true
    } catch (e: Exception) {
        // E.g. a sticky restart the system no longer lets go foreground.
        Log.w(TAG, "Cannot enter foreground", e)
        false
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun buildNotification(state: EngineState): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProxyService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val running = state.state == ProxyState.RUNNING
        val builder = NotificationCompat.Builder(this, App.CHANNEL_PROXY)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle(getString(if (running) R.string.notif_running else R.string.notif_starting))
            .setContentText(getString(R.string.notif_address, LOOPBACK, settings.port))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stat_proxy, getString(R.string.action_stop), stop)
        link?.let {
            val tg = PendingIntent.getActivity(
                this, 2, Intent(Intent.ACTION_VIEW, it.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(R.drawable.ic_stat_proxy, getString(R.string.action_open_telegram), tg)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "ProxyService"
        private const val NOTIFICATION_ID = 1
        private const val NETWORK_SETTLE_MS = 2_000L
        private const val ACTION_START = "start"
        private const val ACTION_RESTART = "restart"
        private const val ACTION_STOP = "stop"
        const val LOOPBACK = "127.0.0.1"

        /** Starts the proxy, or restarts it with the saved settings when [restart] is true. */
        fun start(context: Context, restart: Boolean = false) {
            val intent = Intent(context, ProxyService::class.java)
                .setAction(if (restart) ACTION_RESTART else ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, ProxyService::class.java).setAction(ACTION_STOP))
            } catch (e: IllegalStateException) {
                // Background start not allowed; stopping directly still works.
                context.stopService(Intent(context, ProxyService::class.java))
            }
        }
    }
}
