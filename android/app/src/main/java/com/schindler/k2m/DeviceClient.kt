package com.schindler.k2m

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** The X4 can't be reached at all (as opposed to one request failing). */
class DeviceUnreachable(msg: String) : IOException(msg)

/**
 * Talks to an X4 in File Transfer mode (Matcha/CrossPoint firmware), mirroring k2m_server.py's
 * DevicePusher:
 *  - discovery: UDP "hello" to port 8134, answer "crosspoint (on <hostname>);<wsPort>"
 *  - WebDAV on port 80: PUT needs its parent folder (MKCOL each level; 405 = exists), any path segment
 *    starting with '.' is refused, DELETE is recursive
 *  - panels.idx is uploaded last, so an interrupted copy is never listed as a book on the device
 *
 * All traffic is pinned to the WiFi network. On the X4's own hotspot ("CrossPoint-Reader") WiFi has no
 * internet, so Android keeps mobile data as the default network and unpinned sockets would go out over
 * mobile data, never reaching 192.168.4.1. Pinning lets the phone talk to the X4 over WiFi while it
 * still reaches the server over mobile data.
 */
class DeviceClient(private val ctx: Context?) {
    /** Set by [push] after a delta sync (null after a whole copy). */
    @Volatile var lastDelta: Delta? = null

    private val base = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** The WiFi network, if the phone is on one (connected WiFi, with or without internet). */
    fun wifiNetwork(): Network? {
        val cm = ctx?.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    private fun client(connectSeconds: Long = 5): OkHttpClient {
        val b = base.newBuilder().connectTimeout(connectSeconds, TimeUnit.SECONDS)
        wifiNetwork()?.let { b.socketFactory(it.socketFactory) }
        return b.build()
    }

    suspend fun discover(timeoutMs: Int = 2000): List<Device> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, Device>()
        val wifi = wifiNetwork()
        DatagramSocket().use { s ->
            wifi?.bindSocket(s)
            s.broadcast = true
            s.soTimeout = 250
            val hello = "hello".toByteArray()
            for (target in broadcastAddresses(wifi)) {
                runCatching { s.send(DatagramPacket(hello, hello.size, target, UDP_PORT)) }
            }
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(256)
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                val msg = String(p.data, 0, p.length)
                Regex("crosspoint \\(on (.*)\\);(\\d+)").find(msg)?.let {
                    val ip = p.address.hostAddress ?: return@let
                    found[ip] = Device(ip, it.groupValues[1], "phone")
                }
            }
        }
        found.values.toList()
    }

    /** 255.255.255.255 plus the WiFi subnet's own broadcast address (some routers drop the former). */
    private fun broadcastAddresses(wifi: Network?): List<InetAddress> {
        val out = mutableListOf(InetAddress.getByName("255.255.255.255"))
        val cm = ctx?.getSystemService(ConnectivityManager::class.java) ?: return out
        cm.getLinkProperties(wifi ?: cm.activeNetwork)?.linkAddresses?.forEach { la ->
            val a = la.address as? Inet4Address ?: return@forEach
            val ip = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
            val mask = if (la.prefixLength == 0) 0 else -1 shl (32 - la.prefixLength)
            val bc = ip or mask.inv()
            out += InetAddress.getByAddress(byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte()))
        }
        return out
    }

    /**
     * Find the X4: the [manual] address if set, the [last] one that worked, the WiFi gateway (on the X4's own
     * hotspot that *is* the X4), a UDP discovery broadcast, then the hotspot default. Null if none answers.
     */
    suspend fun find(manual: String, last: String, onStatus: (String) -> Unit = {}): String? {
        val tried = mutableSetOf<String>()
        suspend fun answers(ip: String?) = !ip.isNullOrBlank() && tried.add(ip) &&
            runCatching { checkReachable(ip) }.isSuccess
        if (answers(manual)) return manual
        if (answers(last)) return last
        wifiGateway()?.let { if (answers(it)) return it }
        onStatus("Looking for the X4 on the network…")
        runCatching { discover() }.getOrDefault(emptyList()).forEach { if (answers(it.ip)) return it.ip }
        return if (answers(HOTSPOT_IP)) HOTSPOT_IP else null
    }

    /** The WiFi network's default gateway (the X4 itself when the phone is on its hotspot). */
    private fun wifiGateway(): String? {
        val cm = ctx?.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = wifiNetwork() ?: return null
        return cm.getLinkProperties(net)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    }

    /** Throws [DeviceUnreachable] within a few seconds if the X4's WebDAV server can't be reached. */
    suspend fun checkReachable(ip: String) = withContext(Dispatchers.IO) {
        val r = Request.Builder().url("http://$ip/").method("PROPFIND", null).header("Depth", "0").build()
        try {
            // Only a WebDAV answer counts: a remembered address now used by something else (a router, a web
            // server) would answer HTTP too, but not with a 207 multistatus to PROPFIND.
            client(connectSeconds = 3).newCall(r).execute().use { if (it.code != 207) throw IOException("HTTP ${it.code}, not WebDAV") }
        } catch (e: IOException) {
            val how = if (wifiNetwork() == null) "The phone isn't on any WiFi." else "The phone is on WiFi, but the X4 didn't answer at $ip."
            throw DeviceUnreachable("$how Start File Transfer on the X4 and connect the phone to the same WiFi " +
                "(or to the X4's own \"CrossPoint-Reader\" network, where the X4 is 192.168.4.1).")
        }
    }

    /**
     * Copy one book to [dest] (e.g. "/manga/<title>/<chapter>") on the device at [ip].
     * [files] are (relative name, size) with panels.idx last, as the server's manifest lists them.
     * Returns "pushed" or "skipped". A connection failure mid-way throws [DeviceUnreachable].
     */
    /** Book operations on the X4 at [ip] over WiFi (see [BookOps], [WebDavStorage]). */
    fun ops(ip: String) = BookOps(WebDavStorage(client(), "http://$ip"))

    suspend fun push(ip: String, dest: String, files: List<Pair<String, Long>>, replace: Boolean,
                     fetch: suspend (String) -> ByteArray, onBytes: (Long) -> Unit = {},
                     shas: Map<String, String> = emptyMap(), known: Map<String, String> = emptyMap(),
                     onFile: (Int) -> Unit): String {
        val o = ops(ip)
        return o.push(dest, files, replace, fetch, onBytes, shas, known, onFile).also { lastDelta = o.lastDelta }
    }

    suspend fun plan(ip: String, dest: String, files: List<Pair<String, Long>>, shas: Map<String, String>,
                     known: Map<String, String>): DeltaPlan? = ops(ip).plan(dest, files, shas, known)

    suspend fun scanLibrary(ip: String, onFolder: (String) -> Unit): DeviceLibrary {
        checkReachable(ip)
        return ops(ip).scanLibrary(onFolder)
    }

    suspend fun readingProgress(ip: String, books: List<String>, onBook: (Int) -> Unit): Map<String, ReadingProgress?> {
        checkReachable(ip)
        return ops(ip).readingProgress(books, onBook)
    }

    suspend fun deleteBooks(ip: String, books: List<String>, onBook: (Int) -> Unit): List<String> {
        checkReachable(ip)
        return ops(ip).deleteBooks(books, onBook)
    }

    suspend fun compareBook(ip: String, devicePath: String, files: List<Pair<String, Long>>) = ops(ip).compareBook(devicePath, files)
    suspend fun markRead(ip: String, bookPath: String) = ops(ip).markRead(bookPath)
    suspend fun deleteFolder(ip: String, path: String) = ops(ip).deleteFolder(path)

    /**
     * Read one of the X4's settings (keys as in the firmware's SettingsList, e.g. "reversePageTurn") through
     * its web settings API (GET /api/settings: a JSON list of {key, value, ...}). Null if the X4 doesn't have it.
     */
    suspend fun getSetting(ip: String, key: String): Int? = withContext(Dispatchers.IO) {
        val body = client(connectSeconds = 3).newCall(Request.Builder().url("http://$ip/api/settings").build()).execute()
            .use { if (it.code == 200) it.body!!.string() else throw IOException("HTTP ${it.code}") }
        val list = org.json.JSONArray(body)
        (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optString("key") == key }
            ?.let { if (it.has("value")) it.getInt("value") else null }
    }

    /** Change one X4 setting (POST /api/settings with {key: value}; the firmware saves it). */
    suspend fun setSetting(ip: String, key: String, value: Int) = withContext(Dispatchers.IO) {
        val body = org.json.JSONObject().put(key, value).toString().toRequestBody("application/json".toMediaType())
        client(connectSeconds = 3).newCall(Request.Builder().url("http://$ip/api/settings").post(body).build()).execute()
            .use { if (it.code !in 200..299) throw IOException("The X4 refused the setting: HTTP ${it.code}") }
    }

    companion object {
        const val UDP_PORT = 8134
        const val HOTSPOT_IP = "192.168.4.1"
    }
}
