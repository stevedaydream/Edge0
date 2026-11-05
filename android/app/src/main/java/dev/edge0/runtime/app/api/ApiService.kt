// api/ApiService.kt - foreground service that owns the loopback API server.
// Foreground so Android (and aggressive OEM battery managers) keep the process - and
// the loaded model - alive while other apps call http://127.0.0.1:<port>/v1.
package dev.edge0.runtime.app.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import dev.edge0.runtime.app.Edge0App
import dev.edge0.runtime.app.MainActivity
import dev.edge0.runtime.app.R
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ApiService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var token: String? = null
    private var server: LocalHttpServer? = null
    private var starting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (starting) return START_STICKY
        starting = true
        val container = (application as Edge0App).container
        startForeground(NOTIF_ID, notification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        scope.launch {
            val s = container.settings.flow.first()
            token = s.apiToken
            // token edits apply live, without restarting the server
            container.settings.flow.map { it.apiToken }.distinctUntilChanged()
                .onEach { token = it }.launchIn(scope)
            val router = ApiRouter(container.apiBackend, token = { token })
            val srv = LocalHttpServer(s.apiPort) { req, res -> router.handle(req, res) }
            try {
                withContext(Dispatchers.IO) { srv.start() }
                server = srv
                _running.value = s.apiPort
                updateNotification("http://127.0.0.1:${s.apiPort}/v1")
                Log.i(TAG, "API listening on 127.0.0.1:${s.apiPort}")
            } catch (e: IOException) {
                Log.e(TAG, "bind failed on port ${s.apiPort}", e)
                _lastError.value = "Port ${s.apiPort} unavailable: ${e.message}"
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop(); server = null
        _running.value = null
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, ApiService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Edge0 local API")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "Edge0Api"
        private const val CHANNEL = "edge0_api"
        private const val NOTIF_ID = 4201
        private const val ACTION_STOP = "dev.edge0.runtime.app.api.STOP"

        private val _running = MutableStateFlow<Int?>(null)
        /** Port the server is listening on, or null when stopped. */
        val running: StateFlow<Int?> = _running
        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError

        fun start(ctx: Context) {
            _lastError.value = null
            ctx.startForegroundService(Intent(ctx, ApiService::class.java))
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ApiService::class.java)) }

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Local API",
                    NotificationManager.IMPORTANCE_LOW))
        }
    }
}
