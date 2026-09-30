package com.schindler.k2m

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Sleep screens on the X4. Matcha's "Custom" sleep screen shows a random BMP from /sleep on the card; the server
 * keeps one per series (drawn from the series art, or the user's own image), named after the series folder.
 * WebDAV can't reach a '.'-prefixed folder, so this uses the visible /sleep (Matcha reads /.sleep first, if it exists).
 */
object SleepSync {
    const val DIR = "/sleep"

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    /** Keep a copy of [name]'s BMP on the phone so a later send works with no server. Returns the bytes, or null if there's none. */
    suspend fun fetch(api: Api, cacheDir: File, name: String): ByteArray? {
        val f = File(cacheDir, "$name.bmp")
        val fresh = runCatching { api.sleepFile(name) }.getOrNull()
        if (fresh != null) withContext(Dispatchers.IO) { cacheDir.mkdirs(); f.writeBytes(fresh) }
        return fresh ?: f.takeIf { it.isFile }?.let { withContext(Dispatchers.IO) { it.readBytes() } }
    }

    /**
     * Copy the sleep screens of [names] (series folders) to /sleep on [ops]'s card, skipping any the X4 already has
     * with the same size and the same content as last sent ([store] remembers that, since a redrawn BMP is the same size).
     * Series without a sleep screen are skipped. Returns how many were sent.
     */
    suspend fun send(ops: BookOps, api: Api, cacheDir: File, store: PushedStore, names: Collection<String>): Int {
        val have = ops.storage.list(DIR)?.filter { !it.dir }?.associate { it.name to it.size }
        val known = store.load(DIR).toMutableMap()
        var dirReady = have != null
        var sent = 0
        for (name in names.distinct()) {
            val data = fetch(api, cacheDir, name) ?: continue
            val file = "$name.bmp"
            val sha = sha1(data)
            if (have?.get(file) == data.size.toLong() && known[file] == sha) continue
            if (!dirReady) { ops.storage.mkdirs(DIR); dirReady = true }
            ops.storage.write("$DIR/$file", data)
            known[file] = sha
            sent++
        }
        if (sent > 0) store.save(DIR, known)
        return sent
    }
}
