package com.rimvon.app

import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

class RimvonApp : Application() {
    companion object {
        lateinit var core: Core
            private set
    }

    override fun onCreate() {
        super.onCreate()
        core = Core(this)
    }
}

/**
 * Foreground service that keeps the connection to the relay alive so messages and calls arrive
 * without Google push services (which are blocked in some countries).
 */
class RelayService : Service() {
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var inCall = false
    private var video = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                RimvonApp.core.relay.nudge()
            }
        }
        callback = cb
        try { cm.registerDefaultNetworkCallback(cb) } catch (_: Exception) {}
    }

    private fun goForeground() {
        val core = RimvonApp.core
        val n = core.notifier.serviceNotification(if (inCall) "مكالمة جارية" else "متصل بشكل آمن")
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (inCall) {
                    if (has(android.Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    if (video && has(android.Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                startForeground(Notifier.ID_SVC, n, type)
            } else {
                startForeground(Notifier.ID_SVC, n)
            }
        } catch (e: Exception) {
            try { startForeground(Notifier.ID_SVC, n) } catch (_: Exception) {}
        }
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "call_on" -> { inCall = true; video = intent?.getBooleanExtra("video", false) ?: false }
            "call_off" -> inCall = false
        }
        goForeground()
        val core = RimvonApp.core
        if (!core.loggedIn.value) {
            stopSelf()
            return START_NOT_STICKY
        }
        core.relay.ensure()
        return START_STICKY
    }

    override fun onDestroy() {
        callback?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (RimvonApp.core.loggedIn.value) RimvonApp.core.startService()
    }
}
