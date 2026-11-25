package com.symphonia.gate2.prototype.harness

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.security.SecureRandom
import java.util.UUID

internal class Gate2PhoneRelay(
    private val context: Context,
    private val selectedAddress: String?,
    private val onDisconnected: () -> Unit,
) : AutoCloseable {
    data class LocalNetwork(val interfaceName: String, val address: String)

    companion object {
        private const val PORT = 18766
        private val wifiName = Regex("^(wlan|ap|swlan|softap)[0-9]+$")
        private fun worker(context: Context) =
            File(context.applicationInfo.nativeLibraryDir, "libmediasoup_worker.so")

        fun isAvailable(context: Context): Boolean = worker(context).isFile &&
            context.assets.list("")?.contains("phone-relay.zip") == true

        fun localNetworks(): List<LocalNetwork> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && wifiName.matches(it.name) }
                .flatMap { network ->
                    network.inetAddresses.toList().filterIsInstance<Inet4Address>()
                        .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                        .mapNotNull { address -> address.hostAddress?.let { LocalNetwork(network.name, it) } }
                }
                .distinctBy { it.address }
        }.getOrDefault(emptyList())
    }

    private val sessionId = UUID.randomUUID().toString()
    private val directory = File(context.cacheDir, "gate2-phone-relay/$sessionId")
    private val hostKey = ByteArray(32).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Volatile private var closed = false
    @Volatile private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit
        override fun onServiceDisconnected(name: ComponentName) {
            if (!closed) onDisconnected()
        }
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
    }

    @Synchronized
    fun bind() {
        check(!closed) { "Relay session was stopped" }
        val networks = localNetworks()
        val network = if (selectedAddress == null) networks.singleOrNull()
            else networks.firstOrNull { it.address == selectedAddress }
        val ip = network?.address ?: error("The selected Wi-Fi or hotspot is unavailable")
        check(directory.mkdirs()) { "Unable to create relay session" }
        File(directory, "config.json").writeText(JSONObject()
            .put("workerPath", worker(context).path)
            .put("ip", ip)
            .put("hostKey", hostKey)
            .toString())
        val intent = Intent().setClassName(context.packageName, "${context.packageName}.Gate2PhoneRelayService")
            .putExtra("session", sessionId)
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        check(bound) { "Unable to bind on-phone relay" }
    }

    fun awaitReady(): String {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        val ready = File(directory, "ready")
        val failed = File(directory, "failed")
        val ended = File(directory, "ended")
        while (SystemClock.elapsedRealtime() < deadline && !closed && !failed.exists() && !ended.exists()) {
            if (ready.exists()) return "ws://127.0.0.1:$PORT/gate2?hostKey=$hostKey"
            Thread.sleep(100)
        }
        error("On-phone relay did not become ready")
    }

    fun invite(roomId: String): String {
        val url = URL("http://127.0.0.1:$PORT/invite?roomId=$roomId&hostKey=$hostKey")
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            connection.inputStream.bufferedReader().use { it.readText() }
                .also { check(it.startsWith("http://") && it.contains("#invite=")) }
        } finally {
            connection.disconnect()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (directory.isDirectory) runCatching { File(directory, "stop").createNewFile() }
        if (bound) context.unbindService(connection)
        else directory.deleteRecursively()
    }
}
