package com.schindler.k2m

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

/** [SleepSync] on a real Android runtime, against a tiny in-process stand-in for the server's /api/sleep/file. */
@RunWith(AndroidJUnit4::class)
class SleepSyncDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var listener: ServerSocket
    private val screens = HashMap<String, ByteArray>()
    private lateinit var work: File

    private fun bmp(fill: Int) = ByteArray(4096) { if (it < 2) "BM"[it].code.toByte() else fill.toByte() }

    @Before fun setUp() {
        work = File(ctx.cacheDir, "sleeptest${System.nanoTime()}").also { it.mkdirs() }
        listener = ServerSocket(0)
        thread(isDaemon = true) {
            while (true) {
                val s = try { listener.accept() } catch (_: SocketException) { return@thread }
                s.use {
                    val line = it.getInputStream().bufferedReader().readLine() ?: return@use
                    val target = line.split(" ").getOrElse(1) { "" }
                    val body: ByteArray? = when {
                        target.startsWith("/api/health") -> "{\"ok\": true}".toByteArray()
                        target.startsWith("/api/sleep/file") ->
                            screens[java.net.URLDecoder.decode(target.substringAfter("name=").substringBefore('&'), "UTF-8")]
                        else -> null
                    }
                    val out = it.getOutputStream()
                    if (body == null) out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    else { out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body) }
                    out.flush()
                }
            }
        }
    }

    @After fun tearDown() { listener.close(); work.deleteRecursively() }

    private fun api() = Api(object : ServerSettings {
        override val homeUrl = "http://127.0.0.1:${listener.localPort}"
        override val remoteUrl = ""
        override val token = "t"
    })

    private fun send(ops: BookOps, api: Api, vararg names: String) =
        runBlocking { SleepSync.send(ops, api, File(work, "cache"), PushedStore(File(work, "pushed")), names.toList()) }

    @Test fun sendsSkipsAndRedraws() {
        val card = File(work, "card").also { it.mkdirs() }
        val ops = BookOps(FileStorage(card))
        val api = api()
        screens["Series A"] = bmp(1)
        screens["Series B"] = bmp(2)

        assertEquals(2, send(ops, api, "Series A", "Series B", "No Screen", "Series A"))
        assertArrayEquals(bmp(1), File(card, "sleep/Series A.bmp").readBytes())
        assertArrayEquals(bmp(2), File(card, "sleep/Series B.bmp").readBytes())
        assertFalse(File(card, "sleep/No Screen.bmp").exists())

        assertEquals("identical screens are not sent again", 0, send(ops, api, "Series A", "Series B"))

        screens["Series A"] = bmp(9)   // redrawn: same size, different pixels
        assertEquals(1, send(ops, api, "Series A", "Series B"))
        assertArrayEquals(bmp(9), File(card, "sleep/Series A.bmp").readBytes())

        File(card, "sleep/Series B.bmp").delete()
        assertEquals("a screen missing on the card is sent again", 1, send(ops, api, "Series B"))
    }

    @Test fun fetchFallsBackToPhoneCopy() = runBlocking {
        screens["Series A"] = bmp(3)
        val cache = File(work, "cache")
        assertArrayEquals(bmp(3), SleepSync.fetch(api(), cache, "Series A"))
        screens.clear()
        val again = SleepSync.fetch(api(), cache, "Series A")
        assertNotNull(again)
        assertArrayEquals("server has none now: use the cached BMP", bmp(3), again)
        assertNull(SleepSync.fetch(api(), cache, "Never Seen"))
    }

    @Test fun prefsPersistSleepSettings() {
        val p = Prefs(ctx)
        val g = p.sleepGenerate; val s = p.sleepSend
        try {
            p.sleepGenerate = true; p.sleepSend = true
            assertTrue(Prefs(ctx).sleepGenerate && Prefs(ctx).sleepSend)
            p.sleepGenerate = false; p.sleepSend = false
            assertFalse(Prefs(ctx).sleepGenerate || Prefs(ctx).sleepSend)
        } finally { p.sleepGenerate = g; p.sleepSend = s }
    }
}
