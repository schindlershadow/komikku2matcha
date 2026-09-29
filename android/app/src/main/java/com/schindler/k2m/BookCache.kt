package com.schindler.k2m

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Books saved on the phone, so they can be sent to the X4 with no internet (e.g. on the X4's own
 * hotspot with no mobile data). Laid out like the SD card: <root>/manga/<title>/<chapter>/...
 * A book counts as saved once its .manifest.json is written, which happens after every file is in.
 */
class BookCache(private val root: File) {
    private fun dir(path: String): File {
        require(path.startsWith("manga/") && path.split('/').none { it == ".." || it.startsWith(".") }) { "bad book path $path" }
        return File(root, path)
    }

    /** The book's folder as an [X4Storage], for something that writes a book directly into the cache
     *  (phone-side conversion) rather than downloading one the server already built. */
    fun localStorage(path: String): X4Storage = FileStorage(dir(path))

    /** Mark a book saved without going through the server (see [localStorage]): write its manifest
     *  once every file in [files] is actually on disk at the size given, exactly like [save] does at
     *  the end of a download. No sha1 recorded -- there is no prior server copy to delta against. */
    suspend fun markConverted(path: String, files: List<Pair<String, Long>>) = withContext(Dispatchers.IO) {
        File(dir(path), MANIFEST).writeText(JSONArray(files.map { (n, s) -> JSONObject().put("name", n).put("size", s) }).toString())
    }

    /** The saved book's files (panels.idx last), or null if it isn't fully saved. */
    fun saved(path: String): List<Pair<String, Long>>? {
        val d = dir(path)
        val m = File(d, MANIFEST).takeIf { it.isFile } ?: return null
        val files = runCatching {
            val a = JSONArray(m.readText())
            (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("name") to o.getLong("size") } }
        }.getOrNull() ?: return null
        return files.takeIf { it.all { (n, s) -> File(d, n).length() == s } }
    }

    fun bytes(path: String, name: String): ByteArray = File(dir(path), name).readBytes()

    /** The saved book's content hashes (file → sha1), as the server listed them. */
    fun shas(path: String): Map<String, String> = runCatching {
        val a = JSONArray(File(dir(path), MANIFEST).readText())
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.has("sha1") }.associate { it.getString("name") to it.getString("sha1") }
    }.getOrDefault(emptyMap())

    /** Download a book from the server into the cache; files already saved with the right size are kept. */
    suspend fun save(api: Api, path: String, onFile: (Int, Int) -> Unit): List<Pair<String, Long>> {
        val (files, shas) = api.manifestWithShas(path)
        val d = dir(path)
        files.forEachIndexed { i, (name, size) ->
            val f = File(d, name)
            if (f.length() != size) {
                val data = api.fileBytes(path, name)
                RateMeter.add(data.size.toLong())
                withContext(Dispatchers.IO) {
                    f.parentFile!!.mkdirs()
                    val tmp = File(f.parentFile, f.name + ".part")
                    tmp.writeBytes(data)
                    tmp.renameTo(f)
                }
            }
            onFile(i + 1, files.size)
        }
        withContext(Dispatchers.IO) {
            File(d, MANIFEST).writeText(JSONArray(files.map { (n, s) ->
                JSONObject().put("name", n).put("size", s).apply { shas[n]?.let { put("sha1", it) } } }).toString())
        }
        return files
    }

    /**
     * Download only [names] of a book (the files a delta sync will send), keeping any already here with the
     * right size. The book isn't marked saved: it's a partial copy, used for this send and then removed.
     */
    suspend fun fetch(api: Api, path: String, names: Set<String>, files: List<Pair<String, Long>>, onFile: (String) -> Unit) {
        val d = dir(path)
        for ((name, size) in files) {
            if (name !in names) continue
            val f = File(d, name)
            if (f.length() == size) continue
            onFile(name)
            val data = api.fileBytes(path, name)
            RateMeter.add(data.size.toLong())
            withContext(Dispatchers.IO) {
                f.parentFile!!.mkdirs()
                val tmp = File(f.parentFile, f.name + ".part")
                tmp.writeBytes(data)
                tmp.renameTo(f)
            }
        }
    }

    fun has(path: String, name: String, size: Long) = File(dir(path), name).length() == size

    fun remove(path: String) { dir(path).deleteRecursively() }

    /** Paths of every fully saved book. */
    fun savedPaths(): Set<String> =
        File(root, "manga").walkTopDown().filter { it.name == MANIFEST }
            .map { it.parentFile!!.relativeTo(root).invariantSeparatorsPath }
            .filter { saved(it) != null }.toSet()

    fun bytesUsed(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object { private const val MANIFEST = ".manifest.json" }
}

/**
 * What this app last sent to each X4 book folder (file → sha1), so a delta sync also catches a file whose
 * content changed but whose size didn't. One small JSON file per folder.
 */
class PushedStore(private val root: File) {
    private fun file(dest: String): File {
        val key = java.security.MessageDigest.getInstance("SHA-1").digest(dest.trim('/').lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(root, "$key.json")
    }

    fun load(dest: String): Map<String, String> = runCatching {
        val o = JSONObject(file(dest).readText())
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    fun save(dest: String, shas: Map<String, String>) {
        if (shas.isEmpty()) return
        root.mkdirs()
        file(dest).writeText(JSONObject(shas).toString())
    }
}
