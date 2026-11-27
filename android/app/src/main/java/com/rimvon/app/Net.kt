package com.rimvon.app

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Keeps one WebSocket to the relay alive: automatic reconnect with back-off,
 * rotation through backup addresses (in case one address gets blocked), keep-alive pings.
 */
class RelayClient(private val core: Core) {
    private val http = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var wanted = false
    @Volatile private var online = false
    @Volatile private var generation = 0
    private var attempt = 0
    private var idx = 0
    private var retryJob: Job? = null

    /** 0 = offline, 1 = connecting, 2 = online */
    val state = MutableStateFlow(0)

    fun isOnline() = online

    @Synchronized fun start() {
        wanted = true
        attempt = 0
        connect()
    }

    /** Starts only if not already running; used by the foreground service. */
    @Synchronized fun ensure() {
        if (!wanted) start() else if (!online && state.value == 0) nudge()
    }

    @Synchronized fun stop() {
        wanted = false
        retryJob?.cancel()
        generation++
        ws?.cancel()
        ws = null
        online = false
        state.value = 0
    }

    /** Called when the network comes back: reconnect immediately instead of waiting for the back-off. */
    @Synchronized fun nudge() {
        if (wanted && !online) {
            retryJob?.cancel()
            attempt = 0
            connect()
        }
    }

    fun send(o: JSONObject): Boolean = ws?.send(o.toString()) ?: false

    @Synchronized private fun connect() {
        if (!wanted) return
        val hello = try { core.helloJson() } catch (e: Exception) { null } ?: return
        val list = core.servers()
        if (list.isEmpty()) return
        val url = list[idx % list.size]
        ws?.cancel()
        online = false
        val gen = ++generation
        state.value = 1
        val req = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            scheduleRetry()
            return
        }
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen == generation) webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen == generation) handle(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(gen)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(gen)
        })
    }

    private fun handle(text: String) {
        val j = try { JSONObject(text) } catch (e: Exception) { return }
        when (j.optString("type")) {
            "ready" -> {
                synchronized(this) { online = true; attempt = 0; state.value = 2 }
                core.onReady(j)
            }
            "err" -> if (!online) core.onAuthError(j.optString("code")) else core.onServerError(j.optString("code"))
            "kicked" -> core.onKicked()
            else -> core.onPacket(j)
        }
    }

    @Synchronized private fun dropped(gen: Int) {
        if (gen != generation) return
        online = false
        ws = null
        state.value = 0
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (!wanted) return
        val d = minOf(30_000L, 1_000L shl minOf(attempt, 5))
        attempt++
        idx++
        retryJob?.cancel()
        retryJob = core.scope.launch {
            delay(d)
            connect()
        }
    }
}
