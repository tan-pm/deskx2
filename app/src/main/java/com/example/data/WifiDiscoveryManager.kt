package com.example.data

import android.content.Context
import android.net.wifi.WifiManager
import com.example.model.DiscoveredPc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket

class WifiDiscoveryManager(
    private val context: Context,
) {
    companion object {
        private const val DISCOVERY_PORT = 9877
        private const val MULTICAST_GROUP = "239.255.77.77"
        private const val STALE_MS = 9_000L
    }

    private val _discoveredPcs = MutableStateFlow<List<DiscoveredPc>>(emptyList())
    val discoveredPcs: StateFlow<List<DiscoveredPc>> = _discoveredPcs.asStateFlow()

    private var discoveryJob: Job? = null
    private var probeJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (discoveryJob?.isActive == true) return

        discoveryJob = scope.launch(Dispatchers.IO) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifiManager?.createMulticastLock("deskdeck_wifi_discovery")?.apply {
                setReferenceCounted(true)
                runCatching { acquire() }
            }

            var socket: MulticastSocket? = null
            try {
                socket = MulticastSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 1500
                    bind(InetSocketAddress(DISCOVERY_PORT))
                    runCatching { joinGroup(InetAddress.getByName(MULTICAST_GROUP)) }
                }

                val buffer = ByteArray(2048)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val json = JSONObject(text)
                        if (json.optString("service") == "deskdeck") {
                            val host = json.optString("host").ifBlank { packet.address.hostAddress ?: "" }
                            val port = json.optInt("port", 9876)
                            val name = json.optString("name", "PC Host")
                            val token = json.optString("token", "")
                            val osName = json.optString("os", "PC")
                            if (host.isNotBlank()) {
                                val entry = DiscoveredPc(
                                    name = name,
                                    host = host,
                                    port = port,
                                    token = token,
                                    osName = osName,
                                )
                                updateEntry(entry)
                            }
                        }
                    } catch (_: Exception) {
                        pruneStale()
                    }
                }
            } catch (_: Exception) {
                // Socket bind may be restricted in sandboxed/emulator networks; silently ignore
            } finally {
                runCatching { socket?.close() }
                runCatching { if (lock?.isHeld == true) lock.release() }
            }
        }

        probeJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                sendProbeOnce()
                delay(2500)
            }
        }
    }

    fun triggerProbe(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { sendProbeOnce() }
    }

    private fun sendProbeOnce() {
        runCatching {
            DatagramSocket().use { ds ->
                ds.broadcast = true
                val msg = "DESKDECK_DISCOVER".toByteArray(Charsets.UTF_8)
                ds.send(DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                ds.send(DatagramPacket(msg, msg.size, InetAddress.getByName(MULTICAST_GROUP), DISCOVERY_PORT))
            }
        }
    }

    private fun updateEntry(entry: DiscoveredPc) {
        val now = System.currentTimeMillis()
        val updated = _discoveredPcs.value
            .filterNot { it.host == entry.host && it.port == entry.port }
            .filter { now - it.discoveredAt < STALE_MS }
            .toMutableList()
        updated.add(0, entry.copy(discoveredAt = now))
        _discoveredPcs.value = updated
    }

    private fun pruneStale() {
        val now = System.currentTimeMillis()
        val filtered = _discoveredPcs.value.filter { now - it.discoveredAt < STALE_MS }
        if (filtered.size != _discoveredPcs.value.size) {
            _discoveredPcs.value = filtered
        }
    }

    fun stop() {
        discoveryJob?.cancel()
        probeJob?.cancel()
        discoveryJob = null
        probeJob = null
    }
}
