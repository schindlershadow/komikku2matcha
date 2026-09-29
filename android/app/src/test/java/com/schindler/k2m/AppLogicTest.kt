package com.schindler.k2m

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/** Pure logic: ordering and phone/server library merging. */
class LibraryLogicTest {
    @Test fun naturalOrder() {
        val sorted = listOf("Chapter 10.cbz", "Chapter 2.cbz", "chapter 1.cbz", "Chapter 76.2.cbz", "Chapter 76.10.cbz", "Chapter 9.5.cbz")
            .sortedWith(NaturalOrder)
        assertEquals(listOf("chapter 1.cbz", "Chapter 2.cbz", "Chapter 9.5.cbz", "Chapter 10.cbz", "Chapter 76.2.cbz", "Chapter 76.10.cbz"), sorted)
    }

    @Test fun mergeStates() {
        val uri = "content://test"
        val phone = listOf(
            PhoneChapter("T", "Ch 1.cbz", 100, uri),   // converted on the server, same size
            PhoneChapter("T", "Ch 2.cbz", 200, uri),   // server has a different size: re-downloaded
            PhoneChapter("T", "Ch 3.cbz", 300, uri),   // not on the server
        )
        val server = listOf(ServerTitle("src/T", "T", listOf(
            ServerChapter("Ch 1.cbz", 100, "manga/T/Ch 1", "converted", 5),
            ServerChapter("Ch 2.cbz", 199, "manga/T/Ch 2", "converted", 5),
            ServerChapter("Ch 4.cbz", 400, "manga/T/Ch 4", "pending", null),  // server only
        )))
        val rows = mergeLibrary(phone, server).single().chapters.associate { it.file to it }
        assertEquals("converted", rows["Ch 1.cbz"]!!.state)
        assertEquals("changed", rows["Ch 2.cbz"]!!.state)
        assertEquals("new", rows["Ch 3.cbz"]!!.state)
        assertEquals("pending", rows["Ch 4.cbz"]!!.state)
        assertEquals("src/T/Ch 4.cbz", rows["Ch 4.cbz"]!!.serverKey)   // server's own dir, for --exact
        assertEquals("T/Ch 3.cbz", rows["Ch 3.cbz"]!!.serverKey)       // where the upload will land
    }
}

/**
 * End to end against a running k2m_server.py and a fake X4 (tests are skipped without them):
 *   K2M_TEST_URL, K2M_TEST_TOKEN, K2M_TEST_X4 (ip:port), K2M_TEST_FIXTURE (a .cbz), K2M_TEST_OUT (server output dir)
 */
class ServerIntegrationTest {
    private val url = System.getenv("K2M_TEST_URL")
    private val token = System.getenv("K2M_TEST_TOKEN")
    private val x4 = System.getenv("K2M_TEST_X4")
    private val fixture = System.getenv("K2M_TEST_FIXTURE")
    private val out = System.getenv("K2M_TEST_OUT")

    private fun settings(home: String, remote: String, tok: String = token) = object : ServerSettings {
        override val homeUrl = home
        override val remoteUrl = remote
        override val token = tok
    }

    @Test fun endToEnd() = runBlocking {
        assumeTrue(url != null && token != null && x4 != null && fixture != null && out != null)

        // Home URL dead (away from home) → falls back to the remote URL.
        val api = Api(settings("http://127.0.0.1:1", url!!))
        assertEquals(url!!, api.baseUrl())

        // Wrong token is reported as such.
        val bad = runCatching { Api(settings(url, "", "wrong")).library() }.exceptionOrNull()
        assertTrue(bad?.message, bad?.message?.contains("Wrong token") == true)

        // Upload into a title with '+' and a comma: both must survive the URL encoding on the way to the X4.
        val title = "Plus+Test, Vol"
        val file = File(fixture!!)
        var lastProgress = 0L
        api.upload({ FileInputStream(file) }, file.length(), title, file.name) { lastProgress = it }
        assertEquals(file.length(), lastProgress)
        val row = api.library().single { it.dir == title }.chapters.single()
        assertTrue(row.status, row.status in setOf("pending", "changed"))  // "changed" when a previous run converted it

        // Convert just that chapter and wait for it.
        var job = api.startJob(listOf("$title/${file.name}"), force = true)
        while (job.active) { delay(2000); job = api.job(job.id) }
        assertEquals("done", job.status)
        assertEquals("converted", job.outcomes.single().status)
        val book = api.books().single { it.path == row.book }

        // Phone-side push, exactly as SyncService does it.
        val files = api.manifest(book.path)
        assertEquals("panels.idx", files.last().first)
        val dev = DeviceClient(null)
        var sent = 0
        // replace=true always copies (and clears whatever an earlier run left); then an identical copy is skipped.
        assertEquals("pushed", dev.push(x4!!, "/${book.path}", files, true, { api.fileBytes(book.path, it) }) { sent = it })
        assertEquals(files.size, sent)
        assertEquals("skipped", dev.push(x4, "/${book.path}", files, false, { api.fileBytes(book.path, it) }) { })

        // Server-side push of the same book: already there.
        var push = api.serverPush(x4, listOf(book.path), replace = false)
        while (push.active) { delay(1000); push = api.job(push.id) }
        assertEquals("skipped", push.outcomes.single().status)

        // Zip download URL is reachable with the auth header (DownloadManager sends the same header).
        val zip = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(api.zipUrl(book.path))
            .header("Authorization", api.authHeader).build()).execute()
        assertEquals(200, zip.code)
        assertEquals(zip.header("Content-Length")!!.toLong(), zip.body!!.bytes().size.toLong())
        println("K2M-TEST book=${book.path} files=${files.size}")
    }
}

/** Save-to-phone and fail-fast behaviour (same environment variables as [ServerIntegrationTest]). */
class OfflineSendTest {
    private val url = System.getenv("K2M_TEST_URL")
    private val token = System.getenv("K2M_TEST_TOKEN")
    private val x4 = System.getenv("K2M_TEST_X4")

    @Test fun unreachableX4FailsFast() = runBlocking {
        val dev = DeviceClient(null)
        val t0 = System.currentTimeMillis()
        val e = runCatching { dev.checkReachable("192.0.2.1:1") }.exceptionOrNull()
        val took = System.currentTimeMillis() - t0
        assertTrue("got $e", e is DeviceUnreachable)
        assertTrue("took ${took}ms", took < 4000)
        // A push that can't connect is reported as unreachable too, so the service stops instead of trying every book.
        val p = runCatching { dev.push("192.0.2.1:1", "/manga/x/y", listOf("panels.idx" to 1L), false, { ByteArray(1) }) { } }.exceptionOrNull()
        assertTrue("got $p", p is DeviceUnreachable)
    }

    @Test fun saveThenSendWithoutServer() = runBlocking {
        assumeTrue(url != null && token != null && x4 != null)
        val api = Api(object : ServerSettings {
            override val homeUrl = url!!; override val remoteUrl = ""; override val token = this@OfflineSendTest.token!!
        })
        val book = api.books().first().path
        val root = kotlin.io.path.createTempDirectory("k2m-cache").toFile()
        val cache = BookCache(root)
        var last = 0 to 0
        val files = cache.save(api, book) { n, total -> last = n to total }
        assertEquals(files.size to files.size, last)
        assertEquals(files, cache.saved(book))
        assertEquals(setOf(book), cache.savedPaths())

        // Send using only the phone's copy: nothing is fetched from the server.
        val r = DeviceClient(null).push(x4!!, "/$book", cache.saved(book)!!, true, { cache.bytes(book, it) }) { }
        assertEquals("pushed", r)

        // A half-downloaded book doesn't count as saved.
        java.io.File(root, "$book/${files.first().first}").writeBytes(ByteArray(3))
        assertEquals(null, cache.saved(book))
        cache.remove(book)
        assertEquals(emptySet<String>(), cache.savedPaths())
        root.deleteRecursively()
        Unit
    }
}

/** "Check X4": walking a fake SD card (K2M_TEST_SD is the fake X4's root folder). */
class DeviceScanTest {
    private val x4 = System.getenv("K2M_TEST_X4")
    private val sd = System.getenv("K2M_TEST_SD")

    @Test fun scanFindsBooksAndSkipsHidden() = runBlocking {
        assumeTrue(x4 != null && sd != null)
        fun put(rel: String, size: Int) = File(sd, rel).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(size)) }
        put("manga/Scan+Test, A/Ch 1/panels.idx", 20)
        put("manga/Scan+Test, A/Ch 1/panels.dat", 1234)
        put("manga/Scan+Test, A/Ch 1/page_0000.jpg", 50)
        put("manga/Scan+Test, A/Ch 1/panels/p0_0.jpg", 50)       // inside a book: must not be walked into
        put("Books/Novels/story.epub", 10)
        put("Books/notes.bin", 10)                               // not a book
        put(".hidden/secret.epub", 10)                           // hidden by the firmware
        put("manga/Scan+Test, A/Ch 1/panels/deep/other.epub", 10) // unreachable: inside a book

        var folders = 0
        val lib = DeviceClient(null).scanLibrary(x4!!) { folders++ }
        assertEquals(bookSig(listOf("panels.idx" to 20L, "panels.dat" to 1234L, "page_0000.jpg" to 50L)),
            lib.manga["manga/Scan+Test, A/Ch 1"])
        assertTrue(lib.otherBooks.toString(), "Books/Novels/story.epub" in lib.otherBooks)
        assertTrue(lib.otherBooks.none { "secret" in it || "other.epub" in it || "notes" in it })
        assertTrue("walked $folders folders", folders < 20)

        val book = Book("manga/Scan+Test, A/Ch 1", "t", 1, 1, dat = 1234)
        assertEquals(mapOf(book.path to "manga/Scan+Test, A/Ch 1"), lib.match(listOf(book, book.copy(path = "manga/Other/Ch 1"))))

        // Unreachable X4: fails fast rather than hanging.
        val e = runCatching { DeviceClient(null).scanLibrary("192.0.2.1:1") { } }.exceptionOrNull()
        assertTrue("got $e", e is DeviceUnreachable)
    }
}

class FirmwareHashTest {
    /** Reference values from g++ / libstdc++ with a 32-bit size_t (linux/386), the X4's word size. */
    @Test fun matchesLibstdcxx32() {
        assertEquals(3990065800L, firmwareStringHash(""))
        assertEquals(2167009006L, firmwareStringHash("a"))
        assertEquals(2805137849L, firmwareStringHash("ab"))
        assertEquals(3350977461L, firmwareStringHash("abc"))
        assertEquals(804720481L, firmwareStringHash("abcd"))
        assertEquals(3747661524L, firmwareStringHash("/manga/KUMO DESU GA, NANI KA_ - RAW/Chapter 76.1"))
        assertEquals(3624170559L, firmwareStringHash("/manga/Witch Hat Atelier/Chapter 40"))
        assertEquals(2610530474L, firmwareStringHash("/manga/Fate_stay Night［unlimited Blade Works］/Chapter 3"))
        assertEquals("/.crosspoint/manga_3624170559/progress.bin", mangaProgressFile("/manga/Witch Hat Atelier/Chapter 40"))
    }
}

/** Clean-up: reading progress from the X4 and deleting books (K2M_TEST_SD is the fake X4's root folder). */
class CleanupTest {
    private val x4 = System.getenv("K2M_TEST_X4")
    private val sd = System.getenv("K2M_TEST_SD")

    private fun le32(v: Long) = ByteArray(4) { ((v shr (8 * it)) and 0xff).toByte() }

    @Test fun progressAndDelete() = runBlocking {
        assumeTrue(x4 != null && sd != null)
        val title = "manga/Clean+Up, A"
        fun book(ch: String, page: Long?) {
            val dir = File(sd, "$title/$ch").apply { mkdirs() }
            File(dir, "panels.idx").writeBytes(le32(3) + le32(20) + ByteArray(12 * 20))   // version 3, 20 pages
            File(dir, "panels.dat").writeBytes(ByteArray(100))
            if (page != null) File(sd, mangaProgressFile("/$title/$ch").trimStart('/'))
                .apply { parentFile!!.mkdirs() }.writeBytes(le32(page) + byteArrayOf(0, 0, 0))  // + panel, panels-only
        }
        book("Ch 1", 19)    // on the last page: finished
        book("Ch 2", 5)     // in progress
        book("Ch 3", null)  // never opened
        book("Ch 4", 20)    // "mark as read" writes the page count
        val dev = DeviceClient(null)
        val paths = (1..4).map { "$title/Ch $it" }
        val p = dev.readingProgress(x4!!, paths) { }
        assertEquals(true, p["$title/Ch 1"]!!.read)
        assertEquals(false, p["$title/Ch 2"]!!.read)
        assertEquals(30, p["$title/Ch 2"]!!.percent)
        assertEquals(null, p["$title/Ch 3"])
        assertEquals(true, p["$title/Ch 4"]!!.read)

        // Delete the finished ones: the title folder stays while other chapters are in it.
        assertEquals(listOf("$title/Ch 1", "$title/Ch 4"), dev.deleteBooks(x4, listOf("$title/Ch 1", "$title/Ch 4")) { })
        assertTrue(!File(sd, "$title/Ch 1").exists() && !File(sd, "$title/Ch 4").exists())
        assertTrue(File(sd, "$title/Ch 2").exists() && File(sd, "manga").exists())
        // ...and their reading state is gone too, while Ch 2's is kept.
        fun state(ch: String) = File(sd, mangaProgressFile("/$title/$ch").trimStart('/')).parentFile!!
        assertTrue(!state("Ch 1").exists() && !state("Ch 4").exists())
        assertTrue(state("Ch 2").exists())
        // Deleting the rest removes the now-empty title folder, but never manga/ itself.
        dev.deleteBooks(x4, listOf("$title/Ch 2", "$title/Ch 3")) { }
        assertTrue(!File(sd, title).exists())
        assertTrue(File(sd, "manga").exists() || File(sd, "manga").parentFile!!.exists())
    }
}

/** Recognising books copied to the X4 by hand, as on the real X4 (Manga/ capitalised, a renamed title folder). */
class MatchTest {
    private fun book(path: String, title: String) = Book(path, title, 1, 1, dat = 100)

    @Test fun realLayout() {
        val fate = book("manga/Fate_stay Night［unlimited Blade Works］/Chapter 3", "Fate/stay Night［unlimited Blade Works］ - Chapter 03")
        val kumo1 = book("manga/KUMO DESU GA, NANI KA_ - RAW/Chapter 76.1", "KUMO DESU GA, NANI KA? - RAW - Chapter 76.1")
        val kumo2 = book("manga/KUMO DESU GA, NANI KA_ - RAW/Chapter 77.1", "KUMO DESU GA, NANI KA? - RAW - Chapter 77.1")
        val witch = book("manga/Witch Hat Atelier/Chapter 40", "Witch Hat Atelier - Chapter 40")
        val short = book("manga/AB/Chapter 1", "AB - Chapter 1")
        val lib = DeviceLibrary(
            manga = mapOf(
                "Manga/Fate_stay Night［unlimited Blade Works］/Chapter 3" to "s1",  // same path, other case
                "Manga/KUMO DESU GA, NANI KA/Chapter 76.1" to "s2",                  // renamed folder, has meta.bin
                "Manga/KUMO DESU GA, NANI KA/Chapter 77.1" to "s3",                  // renamed folder, no meta.bin
                "Manga/A/Chapter 1" to "s4",                                         // too short to fuzzy-match "AB"
            ),
            otherBooks = emptyList(), scannedAt = 1,
            titles = mapOf("Manga/KUMO DESU GA, NANI KA/Chapter 76.1" to "KUMO DESU GA, NANI KA? - RAW - Chapter 76.1"),
        )
        val m = lib.match(listOf(fate, kumo1, kumo2, witch, short))
        assertEquals("Manga/Fate_stay Night［unlimited Blade Works］/Chapter 3", m[fate.path])
        assertEquals("Manga/KUMO DESU GA, NANI KA/Chapter 76.1", m[kumo1.path])
        assertEquals("Manga/KUMO DESU GA, NANI KA/Chapter 77.1", m[kumo2.path])
        assertEquals(null, m[witch.path])
        assertEquals(null, m[short.path])
        // Leading zeros and punctuation don't matter for titles.
        assertEquals(DeviceLibrary.norm("Fate/stay Night - Chapter 03"), DeviceLibrary.norm("fate_stay night: chapter 3"))
    }

    @Test fun scanReadsTitles() = runBlocking {
        val x4 = System.getenv("K2M_TEST_X4"); val sd = System.getenv("K2M_TEST_SD")
        assumeTrue(x4 != null && sd != null)
        val dir = File(sd, "Manga/Meta Test/Chapter 9").apply { mkdirs() }
        File(dir, "panels.idx").writeBytes(ByteArray(8)); File(dir, "panels.dat").writeBytes(ByteArray(7))
        val t = "Meta Test - Chapter 9".toByteArray()
        File(dir, "meta.bin").writeBytes(byteArrayOf(1, 0, 0, 0, t.size.toByte(), 0, 0, 0) + t)
        val lib = DeviceClient(null).scanLibrary(x4!!) { }
        assertEquals("Meta Test - Chapter 9", lib.titles["Manga/Meta Test/Chapter 9"])
        assertEquals("Manga/Meta Test/Chapter 9",
            lib.match(listOf(Book("manga/Other Name/Chapter 9", "Meta Test - Chapter 09", 1, 1, dat = 7)))["manga/Other Name/Chapter 9"])
    }
}


/** "Fix problems": version fingerprints, comparing an X4 copy file by file, clearing render caches on replace. */
class RepairTest {
    private val x4 = System.getenv("K2M_TEST_X4")
    private val sd = System.getenv("K2M_TEST_SD")

    @Test fun sigMatchesServer() {
        // k2m_server.book_sig for the same list (only top-level files count, order doesn't matter)
        assertEquals("e5d55eeaa5f812e8", bookSig(listOf("meta.bin" to 7L, "panels/p0_0.jpg" to 5L, "panels.idx" to 20L, "page_0000.jpg" to 100L)))
    }

    private fun make(dir: String, files: List<Pair<String, Long>>) = files.forEach { (n, size) ->
        File(sd, "$dir/$n").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(size.toInt())) }
    }

    @Test fun compareFindsDamage() = runBlocking {
        assumeTrue(x4 != null && sd != null)
        val want = listOf("page_0000.jpg" to 10L, "page_0001.jpg" to 11L, "panels/p0_0.jpg" to 5L, "meta.bin" to 3L, "panels.dat" to 4L, "panels.idx" to 8L)
        make("manga/Repair/Good", want)
        make("manga/Repair/Bad", want.filter { it.first != "page_0001.jpg" }.map { if (it.first == "panels/p0_0.jpg") it.first to 4L else it } +
            listOf("page_0005.png" to 9L))
        val dev = DeviceClient(null)
        assertEquals(emptyList<String>(), dev.compareBook(x4!!, "manga/Repair/Good", want))
        val bad = dev.compareBook(x4, "manga/Repair/Bad", want)
        assertTrue(bad.toString(), bad.any { "missing" in it && "page_0001.jpg" in it })
        assertTrue(bad.toString(), bad.any { "damaged" in it && "p0_0.jpg" in it })
        assertTrue(bad.toString(), bad.any { "stray" in it && "page_0005.png" in it })
    }

    @Test fun replaceClearsRenderCache() = runBlocking {
        assumeTrue(x4 != null && sd != null)
        val book = "manga/Repair/Cached"
        val files = listOf("page_0000.jpg" to 10L, "panels.idx" to 8L)
        make(book, files.map { it.first to it.second + 1 })   // an older copy is on the X4
        val cache = File(sd, mangaProgressFile("/$book").trimStart('/')).parentFile!!
        listOf("progress.bin", "page_0.2bp", "p0_0.2bp", "cover_thumb.bmp").forEach { File(cache, it).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(4)) } }
        // An older copy is updated by delta sync ("updated"), and its cached renders go with it.
        assertEquals("updated", DeviceClient(null).push(x4!!, "/$book", files, false, { n -> ByteArray(files.toMap()[n]!!.toInt()) }) { })
        assertEquals(listOf("progress.bin"), cache.list()!!.toList())   // renders gone, reading position kept
        assertEquals(10L, File(sd, "$book/page_0000.jpg").length())
    }
}

/** Chapter folders left without panels.idx (an interrupted copy): invisible on the X4, found and repaired here. */
class IncompleteTest {
    private val x4 = System.getenv("K2M_TEST_X4")
    private val sd = System.getenv("K2M_TEST_SD")

    @Test fun findAndRepair() = runBlocking {
        assumeTrue(x4 != null && sd != null)
        val dev = "Manga/Incomplete Test/Chapter 3"
        val dir = File(sd, dev).apply { mkdirs() }
        File(dir, "page_0000.jpg").writeBytes(ByteArray(10))
        File(dir, "page_0001.jpg").writeBytes(ByteArray(11))
        val t = "Incomplete Test - Chapter 03".toByteArray()
        File(dir, "meta.bin").writeBytes(byteArrayOf(1, 0, 0, 0, t.size.toByte(), 0, 0, 0) + t)
        File(sd, "Manga/Incomplete Test/Leftover/panels").mkdirs()                    // no pages, no meta: not a chapter
        File(sd, "Manga/Orphan/Chapter 1").apply { mkdirs() }.let { File(it, "page_0000.jpg").writeBytes(ByteArray(3)) }

        val client = DeviceClient(null)
        val lib = client.scanLibrary(x4!!) { }
        assertTrue(lib.incomplete.toString(), dev in lib.incomplete && "Manga/Orphan/Chapter 1" in lib.incomplete)
        assertTrue(dev !in lib.manga)
        assertTrue(lib.incomplete.none { "Leftover" in it })

        // Matched to the server's book even though the case differs ("manga/" vs "Manga/").
        val book = Book("manga/Incomplete Test/Chapter 3", "Incomplete Test - Chapter 3", 1, 1, sig = "x")
        assertEquals(mapOf(book.path to dev), lib.matchIncomplete(listOf(book)))

        // Repair = send it again in place, panels.idx last; afterwards the X4 lists it as a book.
        val files = listOf("meta.bin" to t.size.toLong() + 8, "page_0000.jpg" to 10L, "page_0001.jpg" to 12L, "panels.dat" to 4L, "panels.idx" to 8L)
        assertEquals("pushed", client.push(x4, "/$dev", files, true, { n -> ByteArray(files.toMap()[n]!!.toInt()) }) { })
        val after = client.scanLibrary(x4) { }
        assertTrue(dev in after.manga && dev !in after.incomplete)
        assertEquals(bookSig(files), after.manga[dev])

        // A leftover that matches nothing can be deleted.
        client.deleteBooks(x4, listOf("Manga/Orphan/Chapter 1")) { }
        assertTrue(!File(sd, "Manga/Orphan").exists())
    }
}

class TransferAndDiscoveryTest {
    private val x4 = System.getenv("K2M_TEST_X4")
    private val sd = System.getenv("K2M_TEST_SD")

    @Test fun rateMeter() {
        RateMeter.reset()
        RateMeter.tick()
        assertEquals(null, TaskBus.transfer.value)            // nothing moved: no tile
        RateMeter.add(500_000); RateMeter.tick()                  // 0.5 MB in 0.5 s
        assertEquals(1_000_000.0, TaskBus.transfer.value!!.bytesPerSec, 0.1)
        RateMeter.add(1_000_000); RateMeter.tick()               // 1 MB in the next 0.5 s
        val s = TaskBus.transfer.value!!
        assertEquals(1_500_000L, s.totalBytes)
        assertEquals(2_000_000.0, s.peak, 0.1)
        assertEquals(1_500_000.0, s.average, 0.1)                 // 1.5 MB over 1 s
        repeat(200) { RateMeter.add(1); RateMeter.tick() }
        assertEquals(120, TaskBus.transfer.value!!.history.size)  // one minute kept
        assertEquals("2.4 MB/s", formatRate(2_400_000.0)); assertEquals("640 KB/s", formatRate(640_000.0))
        RateMeter.reset()
    }

    @Test fun findPrefersLastAndRejectsNonX4() = runBlocking {
        assumeTrue(x4 != null)
        val dev = DeviceClient(null)
        // A dead manual address falls through to the last one that worked.
        assertEquals(x4, dev.find("192.0.2.1:1", x4!!))
        // A plain web server (this machine's Apache on :80) is not taken for an X4.
        val e = runCatching { dev.checkReachable("127.0.0.1:80") }.exceptionOrNull()
        assertTrue("got $e", e is DeviceUnreachable)
    }

    @Test fun coverOnlyChangeIsIncremental() = runBlocking {
        assumeTrue(x4 != null && sd != null)
        val book = "manga/Incremental/Chapter 1"
        val files = listOf("meta.bin" to 5L, "page_0000.jpg" to 100L, "page_0001.jpg" to 200L, "panels/p1_0.jpg" to 30L,
            "panels.dat" to 9L, "panels.idx" to 20L)
        val dev = DeviceClient(null)
        fun bytes(n: String, cover: Int) = ByteArray((if (n == "page_0000.jpg") cover else files.toMap()[n]!!).toInt())
        assertEquals("pushed", dev.push(x4!!, "/$book", files, true, { bytes(it, 100) }) { })
        val cache = File(sd, mangaProgressFile("/$book").trimStart('/')).parentFile!!
        listOf("progress.bin", "thumb_120.bmp").forEach { File(cache, it).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(3)) } }
        val page1 = File(sd, "$book/page_0001.jpg").lastModified()
        Thread.sleep(1100)
        val newCover = files.map { if (it.first == "page_0000.jpg") it.first to 150L else it }
        var sent = 0L
        assertEquals("updated", dev.push(x4, "/$book", newCover, false, { bytes(it, 150) }, onBytes = { sent += it }) { })
        assertEquals(150L, sent)                                                   // only the cover page went over
        assertEquals(150L, File(sd, "$book/page_0000.jpg").length())
        assertEquals(page1, File(sd, "$book/page_0001.jpg").lastModified())        // the rest untouched
        assertEquals(listOf("progress.bin"), cache.list()!!.toList())              // stale thumbnail gone
    }
}

/** Komikku backups: gzip'd protobuf; only title (3) and thumbnailUrl (9) of each BackupManga (1) are read. */
class KomikkuBackupTest {
    private fun varint(v: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(); var x = v
        while (true) { if (x and 0x7f.inv().toLong() == 0L) { out.write(x.toInt()); break }; out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
        return out.toByteArray()
    }
    private fun field(n: Int, bytes: ByteArray) = varint((n.toLong() shl 3) or 2) + varint(bytes.size.toLong()) + bytes
    private fun str(n: Int, s: String) = field(n, s.toByteArray())
    private fun num(n: Int, v: Long) = varint(n.toLong() shl 3) + varint(v)
    private fun fixed64(n: Int) = varint((n.toLong() shl 3) or 1) + ByteArray(8)

    @Test fun parsesTitlesAndCovers() {
        val manga1 = num(1, 6247824327199706550) + str(2, "/manga/kumo") + str(3, "KUMO DESU GA, NANI KA? - RAW") +
            field(7, "Action".toByteArray()) + num(8, 1) + str(9, "https://cdn.example/kumo.jpg") + fixed64(13) +
            field(16, num(1, 5) + str(2, "chapter"))                       // nested chapters, skipped
        val manga2 = num(1, 2) + str(3, "No Cover Manga")                  // no thumbnail: left out
        val backup = field(1, manga1) + field(1, manga2) + field(2, str(1, "Default")) + field(101, num(1, 3))
        val gz = java.io.ByteArrayOutputStream().also { java.util.zip.GZIPOutputStream(it).use { g -> g.write(backup) } }.toByteArray()
        assertEquals(listOf(BackupManga("KUMO DESU GA, NANI KA? - RAW", "https://cdn.example/kumo.jpg")), KomikkuBackup.parse(gz))
        // Titles match Komikku's folder names ignoring punctuation ("?" became "_").
        assertEquals(DeviceLibrary.norm("KUMO DESU GA, NANI KA? - RAW"), DeviceLibrary.norm("KUMO DESU GA, NANI KA_ - RAW"))
        assertTrue(runCatching { KomikkuBackup.parse(gz.copyOf(gz.size / 2)) }.isFailure)   // truncated: an error, not garbage
    }
}

class X4SettingsTest {
    @Test fun reversePageTurn() = runBlocking {
        val x4 = System.getenv("K2M_TEST_X4")
        assumeTrue(x4 != null)
        val dev = DeviceClient(null)
        assertEquals(0, dev.getSetting(x4!!, "reversePageTurn"))
        dev.setSetting(x4, "reversePageTurn", 1)
        assertEquals(1, dev.getSetting(x4, "reversePageTurn"))
        assertEquals(null, dev.getSetting(x4, "noSuchSetting"))
        dev.setSetting(x4, "reversePageTurn", 0)
    }
}

/** Delta sync: only missing, damaged or changed files are sent; files the book no longer has are removed. */
class DeltaSyncTest {
    private val x4 = System.getenv("K2M_TEST_X4")
    private val sd = System.getenv("K2M_TEST_SD")

    @Test fun onlyChangedFilesGoOver() = runBlocking {
        assumeTrue(x4 != null && sd != null)
        val book = "manga/Delta/Chapter 1"
        val v1 = linkedMapOf("meta.bin" to b(5, 1), "page_0000.jpg" to b(100, 1), "page_0001.jpg" to b(110, 1),
            "page_0002.png" to b(120, 1), "page_0003.jpg" to b(130, 1), "panels/p1_0.jpg" to b(20, 1),
            "panels/p3_0.jpg" to b(21, 1), "panels.dat" to b(40, 1), "panels.idx" to b(56, 1))
        fun files(m: Map<String, ByteArray>) = m.map { it.key to it.value.size.toLong() }
        fun shas(m: Map<String, ByteArray>) = m.mapValues { sha(it.value) }
        val dev = DeviceClient(null)
        var sent = mutableListOf<Long>()
        assertEquals("pushed", dev.push(x4!!, "/$book", files(v1), false, { v1[it]!! }) { })
        val known = shas(v1)

        // A fix: page 2 PNG -> JPEG, page 1 new content at the same size, a crop and panels.dat change.
        val v2 = LinkedHashMap(v1).apply {
            remove("page_0002.png"); remove("panels.idx")
            put("page_0002.jpg", b(90, 2)); put("page_0001.jpg", b(110, 2)); put("panels/p1_0.jpg", b(22, 2))
            put("panels.dat", b(44, 2)); put("panels.idx", b(56, 2))
        }
        val keep = File(sd, "$book/page_0003.jpg").lastModified()
        Thread.sleep(1100)
        sent = mutableListOf()
        assertEquals("updated", dev.push(x4, "/$book", files(v2), false, { v2[it]!! }, onBytes = { sent += it },
            shas = shas(v2), known = known) { })
        val d = dev.lastDelta!!
        assertEquals(setOf(90L, 110L, 22L, 44L, 56L), sent.toSet())            // page 2, page 1, crop, dat, idx
        assertEquals(5, d.sent); assertEquals(1, d.removed)
        assertTrue(!File(sd, "$book/page_0002.png").exists())                  // stray removed
        assertEquals(keep, File(sd, "$book/page_0003.jpg").lastModified())    // untouched
        v2.forEach { (n, bytes) -> assertTrue(n, File(sd, "$book/$n").readBytes().contentEquals(bytes)) }

        // Damaged on the X4 (truncated): only that page (and the index, last) is sent again.
        File(sd, "$book/page_0003.jpg").writeBytes(ByteArray(7))
        sent = mutableListOf()
        assertEquals("updated", dev.push(x4, "/$book", files(v2), false, { v2[it]!! }, onBytes = { sent += it },
            shas = shas(v2), known = shas(v2)) { })
        assertEquals(listOf(130L, 56L), sent)
        assertEquals(130L, File(sd, "$book/page_0003.jpg").length())
        // Identical: nothing to do.
        assertEquals("skipped", dev.push(x4, "/$book", files(v2), false, { v2[it]!! }, shas = shas(v2), known = shas(v2)) { })
    }

    private fun b(size: Int, fill: Int) = ByteArray(size) { fill.toByte() }
    private fun sha(x: ByteArray) = java.security.MessageDigest.getInstance("SHA-1").digest(x).joinToString("") { "%02x".format(it) }
}

class PlanAndIgnoreTest {
    private val x4 = System.getenv("K2M_TEST_X4")

    @Test fun planDownloadsOnlyWhatChanges() = runBlocking {
        assumeTrue(x4 != null)
        val dev = DeviceClient(null)
        val book = "/manga/Plan/Chapter 1"
        val v1 = listOf("meta.bin" to 5L, "page_0000.jpg" to 100L, "page_0001.jpg" to 110L, "panels.dat" to 40L, "panels.idx" to 56L)
        assertEquals(null, dev.plan(x4!!, book, v1, emptyMap(), emptyMap()))                 // not on the X4: whole copy
        dev.push(x4, book, v1, false, { n -> ByteArray(v1.toMap()[n]!!.toInt()) }) { }
        assertTrue(dev.plan(x4, book, v1, emptyMap(), emptyMap())!!.empty)                  // identical
        val cover = v1.map { if (it.first == "page_0000.jpg") it.first to 150L else it }
        assertEquals(setOf("page_0000.jpg"), dev.plan(x4, book, cover, emptyMap(), emptyMap())!!.toFetch())  // cover only
        val page = v1.map { if (it.first == "page_0001.jpg") it.first to 111L else it }
        assertEquals(setOf("page_0001.jpg", "panels.idx"), dev.plan(x4, book, page, emptyMap(), emptyMap())!!.toFetch())
        // Same size, new content: only caught through the hashes of what was sent before.
        assertEquals(setOf("page_0001.jpg", "panels.idx"),
            dev.plan(x4, book, v1, mapOf("page_0001.jpg" to "new"), mapOf("page_0001.jpg" to "old"))!!.toFetch())
    }

    @Test fun ignoredChaptersAreNotSynced() {
        val uri = "content://x"
        val phone = listOf(PhoneChapter("T", "Ch 1.cbz", 1, uri), PhoneChapter("T", "Ch 2.cbz", 1, uri), PhoneChapter("Gone", "Ch 1.cbz", 1, uri))
        val rows = mergeLibrary(phone, emptyList(), Ignored(titles = setOf("src/Gone"), chapters = setOf("T/Ch 2.cbz")))
        val all = rows.flatMap { it.chapters }.associate { "${it.title}/${it.file}" to it.state }
        assertEquals(mapOf("T/Ch 1.cbz" to "new", "T/Ch 2.cbz" to "ignored", "Gone/Ch 1.cbz" to "ignored"), all)
        assertEquals(listOf("Ch 1.cbz"), syncPlan(rows)!!.uploads.map { it.file })
    }
}

class ReadSyncTest {
    private fun str(n: Int, s: String) = Proto.lengthDelimited(n, s.toByteArray())
    private fun num(n: Int, v: Long) = Proto.key(n, 0) + Proto.varint(v)
    private fun f32(n: Int) = Proto.key(n, 5) + byteArrayOf(0, 0, 0x98.toByte(), 0x42)       // chapterNumber 76.0f
    private fun chapter(url: String, name: String, read: Boolean, scan: String = "") =
        str(1, url) + str(2, name) + (if (scan.isNotEmpty()) str(3, scan) else ByteArray(0)) +
            (if (read) num(4, 1) else ByteArray(0)) + num(6, 7) + num(7, 1_700_000_000_000) + f32(9)

    @Test fun markReadBackupRoundTrip() {
        val kumo = num(1, 99) + str(2, "/kumo") + str(3, "KUMO DESU GA, NANI KA? - RAW") + str(9, "https://c/k.jpg") +
            Proto.lengthDelimited(16, chapter("/k/76", "Chapter 76.1", false)) +
            Proto.lengthDelimited(16, chapter("/k/77", "Chapter 77.1", false, "Team")) +
            Proto.lengthDelimited(16, chapter("/k/75", "Chapter 75", true)) + num(100, 1)
        val other = num(1, 5) + str(3, "Other Manga") + Proto.lengthDelimited(16, chapter("/o/1", "Chapter 1", false))
        val raw = Proto.lengthDelimited(1, kumo) + Proto.lengthDelimited(1, other) +
            Proto.lengthDelimited(2, str(1, "Reading")) + Proto.lengthDelimited(101, num(1, 99) + str(2, "Source")) +
            Proto.lengthDelimited(104, str(1, "a.pref"))
        val gz = java.io.ByteArrayOutputStream().also { java.util.zip.GZIPOutputStream(it).use { g -> g.write(raw) } }.toByteArray()

        val b = KomikkuBackupFile.read(gz)
        assertEquals(listOf("KUMO DESU GA, NANI KA? - RAW", "Other Manga"), b.mangas.map { it.title })
        assertEquals(listOf(false, false, true), b.mangas[0].chapters.map { it.read })
        assertEquals("Team", b.mangas[0].chapters[1].scanlator)

        val out = KomikkuBackupFile.read(b.buildMarkRead(mapOf(b.mangas[0] to setOf("/k/77"))))
        assertEquals(listOf("KUMO DESU GA, NANI KA? - RAW"), out.mangas.map { it.title })            // only that manga
        assertEquals(listOf(false, true, true), out.mangas[0].chapters.map { it.read })              // only /k/77 changed
        val plain = java.util.zip.GZIPInputStream(b.buildMarkRead(mapOf(b.mangas[0] to setOf("/k/77"))).inputStream()).readBytes()
        assertTrue(plain.asList().windowed(5).any { it == str(1, "Reading").take(5) } )               // categories kept
        assertTrue(String(plain).contains("Source") && !String(plain).contains("a.pref"))            // sources kept, prefs dropped
        assertTrue(String(plain).contains("https://c/k.jpg"))                                        // other manga fields verbatim
    }

    @Test fun matchesKomikkuFileNames() {
        val chs = listOf(BackupChapterRef("/manga/kumo-79-2", "Chapter 79.2", "", false),
            BackupChapterRef("/c/2", "Chapter 2: Start?", "Team", false), BackupChapterRef("/c/3", "Chapter 3", "", false))
        val hash = chs[0].urlHash
        assertEquals(chs[0], matchBackupChapter("Chapter 79.2_$hash.cbz", chs))       // exact: URL hash suffix
        assertEquals(chs[1], matchBackupChapter("Team_Chapter 2_ Start_.cbz", chs))   // scanlator prefix, "?" and ":" made safe
        assertEquals(chs[2], matchBackupChapter("Chapter 3.cbz", chs))
        assertEquals(null, matchBackupChapter("Chapter 9.cbz", chs))
    }

    @Test fun markReadOnX4() = runBlocking {
        val x4 = System.getenv("K2M_TEST_X4"); val sd = System.getenv("K2M_TEST_SD")
        assumeTrue(x4 != null && sd != null)
        val book = "Manga/ReadSync/Chapter 5"
        File(sd, book).mkdirs()
        File(sd, "$book/panels.idx").writeBytes(byteArrayOf(3, 0, 0, 0, 20, 0, 0, 0) + ByteArray(240))
        File(sd, ".crosspoint").mkdirs()
        val dev = DeviceClient(null)
        dev.markRead(x4!!, book)                                                   // cache folder didn't exist yet
        assertEquals(true, dev.readingProgress(x4, listOf(book)) { }[book]?.read)
        val progress = File(sd, mangaProgressFile("/$book").trimStart('/'))
        assertEquals(listOf<Byte>(20, 0, 0, 0), progress.readBytes().toList())       // the page count, like "mark as read"
        progress.writeBytes(byteArrayOf(3, 0, 0, 0, -1, -1, 0))                    // reading page 3 again…
        dev.markRead(x4, book)                                                     // …an existing file is replaced
        assertEquals(listOf<Byte>(20, 0, 0, 0), progress.readBytes().toList())
    }
}

/** The same book operations on a card reached directly (a plain folder here; the phone uses SafStorage). */
class CardStorageTest {
    @Test fun booksOnADirectCard() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("k2m-card").toFile()
        File(root, ".crosspoint").mkdirs()
        val ops = BookOps(FileStorage(root))
        val book = "manga/Card Test/Chapter 1"
        val v1 = linkedMapOf("meta.bin" to ByteArray(12) { if (it == 4) 4 else 0 } , "page_0000.jpg" to ByteArray(100), "page_0001.png" to ByteArray(110),
            "panels/p1_0.jpg" to ByteArray(20), "panels.dat" to ByteArray(40), "panels.idx" to (byteArrayOf(3, 0, 0, 0, 2, 0, 0, 0) + ByteArray(24)))
        fun files(m: Map<String, ByteArray>) = m.map { it.key to it.value.size.toLong() }

        assertEquals("pushed", ops.push("/$book", files(v1), false, { v1[it]!! }) { })
        var lib = ops.scanLibrary { }
        assertEquals(bookSig(files(v1)), lib.manga[book])

        // A fix: PNG page -> JPEG. Delta: one page sent, the stray PNG removed, render cache cleared.
        val cache = File(root, mangaProgressFile("/$book").trimStart('/')).parentFile!!.apply { mkdirs() }
        File(cache, "page_1.2bp").writeBytes(ByteArray(5)); File(cache, "progress.bin").writeBytes(byteArrayOf(0, 0, 0, 0))  // on page 1 of 2
        val v2 = LinkedHashMap(v1).apply { remove("page_0001.png"); remove("panels.idx"); put("page_0001.jpg", ByteArray(90)); put("panels.idx", v1["panels.idx"]!!) }
        assertEquals("updated", ops.push("/$book", files(v2), false, { v2[it]!! }) { })
        assertEquals(1, ops.lastDelta!!.sent); assertEquals(1, ops.lastDelta!!.removed)
        assertTrue(!File(root, "$book/page_0001.png").exists() && File(root, "$book/page_0001.jpg").exists())
        assertEquals(listOf("progress.bin"), cache.list()!!.toList())

        // Progress and "mark as read" work directly on the card.
        assertEquals(false, ops.readingProgress(listOf(book)) { }[book]!!.read)
        ops.markRead(book)
        assertEquals(true, ops.readingProgress(listOf(book)) { }[book]!!.read)

        // An interrupted copy shows as incomplete; deleting removes the book, its state, and the empty title folder.
        File(root, "$book/panels.idx").delete()
        lib = ops.scanLibrary { }
        assertTrue(book in lib.incomplete && book !in lib.manga)
        ops.deleteBooks(listOf(book)) { }
        assertTrue(!File(root, "manga/Card Test").exists() && !cache.exists() && File(root, "manga").exists())
        root.deleteRecursively()
        Unit
    }
}
