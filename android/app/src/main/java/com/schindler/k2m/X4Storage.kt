package com.schindler.k2m

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

/** A file or folder on the X4's SD card. */
data class CardEntry(val name: String, val dir: Boolean, val size: Long)

/**
 * The X4's SD card, however it's reached: over WiFi ([WebDavStorage]), plugged into the phone ([SafStorage]), or
 * a plain folder (tests). Paths are absolute on the card ("/manga/T/Ch 1", "/.crosspoint/manga_123/progress.bin").
 * [BookOps] builds everything the app does with books on top of these five operations.
 */
interface X4Storage {
    /** Entries directly in a folder, or null if it doesn't exist. */
    suspend fun list(path: String): List<CardEntry>?
    /** A file's bytes, or null if it doesn't exist. */
    suspend fun read(path: String): ByteArray?
    /** Create or replace a file; its folder must exist. */
    suspend fun write(path: String, data: ByteArray)
    /** Create a folder and any missing parents (existing ones are fine). */
    suspend fun mkdirs(path: String)
    /** Delete a file, or a folder with everything in it; a missing one is fine. */
    suspend fun delete(path: String)
    /** A short name for messages ("the X4", "the SD card"). */
    val label: String
}

private fun isHidden(path: String) = path.split('/').any { it.startsWith(".") }

/**
 * The X4 in File Transfer mode, over WiFi. Normal paths use its WebDAV server (PUT needs an existing folder,
 * MKCOL answers 405 for one that exists, DELETE is recursive). WebDAV refuses any '.'-prefixed path segment,
 * so /.crosspoint (reading progress, render caches) goes through the web UI's endpoints instead:
 * /api/files (list), /download (read), /delete, /mkdir and /upload (which never overwrites, so a file is
 * deleted first). Connection failures become [DeviceUnreachable].
 */
class WebDavStorage(private val c: OkHttpClient, private val base: String) : X4Storage {
    override val label = "the X4"
    private val octet = "application/octet-stream".toMediaType()

    private fun url(path: String) = base + path.split('/').joinToString("/") { Api.enc(it) }  // '+' → %2B for the firmware

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: java.net.ConnectException) {
            throw DeviceUnreachable("Lost the connection to the X4 (${e.message})")
        } catch (e: SocketTimeoutException) {
            throw DeviceUnreachable("The X4 stopped answering (${e.message})")
        } catch (e: java.net.NoRouteToHostException) {
            throw DeviceUnreachable("No route to the X4 (${e.message})")
        }
    }

    private fun call(r: Request): Pair<Int, String> = c.newCall(r).execute().use { it.code to (it.body?.string() ?: "") }
    private fun form(vararg kv: Pair<String, String>) = okhttp3.FormBody.Builder().apply { kv.forEach { add(it.first, it.second) } }.build()

    override suspend fun list(path: String): List<CardEntry>? = io {
        if (isHidden(path)) {
            val (code, body) = call(Request.Builder().url("$base/api/files?path=${Api.enc(path)}").build())
            if (code != 200) return@io null
            val a = org.json.JSONArray(body)
            // /api/files answers [] for a missing folder too; treat empty as missing only if the parent lacks it.
            (0 until a.length()).map { a.getJSONObject(it) }.map { CardEntry(it.getString("name"), it.optBoolean("isDirectory"), it.optLong("size")) }
        } else {
            val r = Request.Builder().url(url(path)).method("PROPFIND", null).header("Depth", "1").build()
            c.newCall(r).execute().use { resp ->
                if (resp.code == 404) return@io null
                if (resp.code != 207) throw IOException("PROPFIND $path: HTTP ${resp.code}")
                val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                    .newDocumentBuilder().parse(resp.body!!.byteStream())
                val self = path.trimEnd('/')
                val out = mutableListOf<CardEntry>()
                val responses = doc.getElementsByTagNameNS("DAV:", "response")
                for (i in 0 until responses.length) {
                    val r0 = responses.item(i) as Element
                    val href = URLDecoder.decode(
                        (r0.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: continue).replace("+", "%2B"), "UTF-8")
                    val isDir = href.endsWith("/") || r0.getElementsByTagNameNS("DAV:", "collection").length > 0
                    val clean = href.trimEnd('/')
                    if (clean == self || (self.isEmpty() && clean.isEmpty())) continue
                    val size = r0.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: 0
                    out += CardEntry(clean.substringAfterLast('/'), isDir, size)
                }
                out
            }
        }
    }

    override suspend fun read(path: String): ByteArray? = io {
        c.newCall(Request.Builder().url("$base/download?path=${Api.enc(path)}").build()).execute()
            .use { if (it.code == 200) it.body!!.bytes() else null }
    }

    override suspend fun write(path: String, data: ByteArray) = io {
        if (isHidden(path)) {
            call(Request.Builder().url("$base/delete").post(form("path" to path)).build())
            val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("file", path.substringAfterLast('/'), data.toRequestBody(octet)).build()
            val (code, msg) = call(Request.Builder().url("$base/upload?path=${Api.enc(path.substringBeforeLast('/'))}").post(body).build())
            if (code != 200) throw IOException("Upload of ${path.substringAfterLast('/')} refused: HTTP $code ${msg.take(80)}")
        } else {
            val (code, _) = call(Request.Builder().url(url(path)).put(data.toRequestBody(octet)).build())
            if (code !in listOf(200, 201, 204)) throw IOException("PUT ${path.substringAfterLast('/')}: HTTP $code")
        }
    }

    override suspend fun mkdirs(path: String) = io {
        var cur = ""
        for (seg in path.trim('/').split('/').filter { it.isNotEmpty() }) {
            val parent = cur.ifEmpty { "/" }
            cur += "/$seg"
            if (isHidden(cur)) {
                val (code, _) = call(Request.Builder().url("$base/mkdir").post(form("path" to parent, "name" to seg)).build())
                if (code !in listOf(200, 400)) throw IOException("mkdir $cur: HTTP $code")     // 400 = already exists
            } else {
                val (code, _) = call(Request.Builder().url(url(cur)).method("MKCOL", null).build())
                if (code !in listOf(200, 201, 405)) throw IOException("MKCOL $cur: HTTP $code")
            }
        }
    }

    override suspend fun delete(path: String) = io {
        if (isHidden(path)) {
            call(Request.Builder().url("$base/delete").post(form("path" to path)).build())
        } else {
            val (code, _) = call(Request.Builder().url(url(path)).delete().build())
            if (code !in listOf(200, 204, 404)) throw IOException("DELETE $path: HTTP $code")
        }
        Unit
    }
}

/** A folder on this machine laid out like the card (tests; also any card the OS mounts as a plain path). */
class FileStorage(private val root: File) : X4Storage {
    override val label = "the card"
    private fun f(path: String) = File(root, path.trimStart('/'))
    override suspend fun list(path: String): List<CardEntry>? = withContext(Dispatchers.IO) {
        f(path).takeIf { it.isDirectory }?.listFiles()?.map { CardEntry(it.name, it.isDirectory, if (it.isFile) it.length() else 0) }
    }
    override suspend fun read(path: String) = withContext(Dispatchers.IO) { f(path).takeIf { it.isFile }?.readBytes() }
    override suspend fun write(path: String, data: ByteArray) = withContext(Dispatchers.IO) {
        val t = f(path)
        if (!t.parentFile!!.isDirectory) throw IOException("no folder for $path")
        t.writeBytes(data)
    }
    override suspend fun mkdirs(path: String) { withContext(Dispatchers.IO) { f(path).mkdirs() } }
    override suspend fun delete(path: String) { withContext(Dispatchers.IO) { f(path).deleteRecursively() } }
}
