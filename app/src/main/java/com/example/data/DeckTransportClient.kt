package com.example.data

import com.example.model.ActionAck
import com.example.model.DeckActionEvent
import com.example.model.DeckLayoutConfig
import com.example.model.PcHostProfile
import com.example.model.TelemetrySnapshot
import com.example.model.TransportMode
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class DeckTransportClient(
    private val moshi: Moshi,
) {
    interface Listener {
        fun onConnected(profile: PcHostProfile, mode: TransportMode)
        fun onTelemetry(snapshot: TelemetrySnapshot)
        fun onLayoutUpdated(layout: DeckLayoutConfig)
        fun onActionAck(ack: ActionAck)
        fun onLatencyMeasured(latencyMs: Int)
        fun onDisconnected(reason: String?)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private val telemetryAdapter = moshi.adapter(TelemetrySnapshot::class.java)
    private val actionAdapter = moshi.adapter(DeckActionEvent::class.java)
    private val ackAdapter = moshi.adapter(ActionAck::class.java)
    private val layoutAdapter = moshi.adapter(DeckLayoutConfig::class.java)

    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var activeProfile: PcHostProfile? = null

    fun connect(
        profile: PcHostProfile,
        mode: TransportMode,
        scope: CoroutineScope,
        listener: Listener,
    ) {
        disconnect()
        activeProfile = profile
        val host = if (mode == TransportMode.USB) "127.0.0.1" else profile.host
        val encodedToken = URLEncoder.encode(profile.token, "UTF-8")
        val wsUrl = "ws://$host:${profile.port}/ws?token=$encodedToken"

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                listener.onConnected(profile, mode)
                startPingLoop(ws, scope)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                runCatching {
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "telemetry" -> {
                            telemetryAdapter.fromJson(text)?.let(listener::onTelemetry)
                        }
                        "layout_update" -> {
                            val layoutJson = obj.optJSONObject("layout")?.toString()
                            if (layoutJson != null) {
                                layoutAdapter.fromJson(layoutJson)?.let(listener::onLayoutUpdated)
                            }
                        }
                        "ack" -> {
                            ackAdapter.fromJson(text)?.let(listener::onActionAck)
                        }
                        "pong" -> {
                            val sentTs = obj.optLong("ts", 0L)
                            if (sentTs > 0L) {
                                val rtt = (System.currentTimeMillis() - sentTs).toInt().coerceAtLeast(1)
                                listener.onLatencyMeasured(rtt)
                            }
                        }
                    }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                pingJob?.cancel()
                val msg = if (response?.code == 401) {
                    "Mã Token không khớp với PC Agent. Hãy quét lại mã QR."
                } else {
                    "Không kết nối được tới $host:${profile.port} (${t.localizedMessage ?: "Kiểm tra Wi-Fi/Agent"})."
                }
                listener.onDisconnected(msg)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                pingJob?.cancel()
                listener.onDisconnected(reason.ifBlank { null })
            }
        })
    }

    private fun startPingLoop(ws: WebSocket, scope: CoroutineScope) {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pingPayload = """{"type":"ping","ts":${System.currentTimeMillis()}}"""
                ws.send(pingPayload)
                delay(3000)
            }
        }
    }

    fun sendAction(event: DeckActionEvent): Boolean {
        val json = actionAdapter.toJson(event)
        return webSocket?.send(json) == true
    }

    fun sendLayoutUpdate(layout: DeckLayoutConfig): Boolean {
        val layoutJson = layoutAdapter.toJson(layout)
        val payload = """{"type":"update_layout","layout":$layoutJson}"""
        return webSocket?.send(payload) == true
    }

    fun disconnect() {
        pingJob?.cancel()
        pingJob = null
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        activeProfile = null
    }
}
