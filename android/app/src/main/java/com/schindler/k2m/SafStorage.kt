package com.schindler.k2m

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The X4's SD card plugged into the phone (a card slot, or a USB card reader), reached through the folder
 * the user granted in Settings (Android's storage access framework). Much faster than the X4's WiFi, and
 * with full access: /.crosspoint (reading progress, render caches) is just another folder here.
 *
 * Names are matched ignoring case, like the card's FAT/exFAT filesystem. Folder listings are cached per
 * folder while this object lives (one send or check) and kept up to date by its own writes and deletes.
 */
class SafStorage(ctx: Context, private val tree: Uri) : X4Storage {
    override val label = "the SD card"
    private val cr = ctx.contentResolver
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private data class Child(val name: String, val id: String, val dir: Boolean, val size: Long)
    private val listings = HashMap<String, MutableList<Child>>()   // folder doc id → children

    private fun uri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun children(id: String): MutableList<Child> = listings.getOrPut(id) {
        val out = mutableListOf<Child>()
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), cols, null, null, null)?.use { c ->
            while (c.moveToNext()) out += Child(c.getString(1) ?: continue, c.getString(0), c.getString(2) == Document.MIME_TYPE_DIR, c.getLong(3))
        } ?: throw IOException("The SD card isn't available (is it plugged into the phone?)")
        out
    }

    private fun segments(path: String) = path.trim('/').split('/').filter { it.isNotEmpty() }

    /** The document id at [path], or null if something along it doesn't exist. */
    private fun resolve(path: String): String? {
        var id = rootId
        for (seg in segments(path)) id = children(id).firstOrNull { it.name.equals(seg, ignoreCase = true) }?.id ?: return null
        return id
    }

    /** Whether the granted folder can be read now (the card is plugged in). */
    suspend fun available(): Boolean = withContext(Dispatchers.IO) { runCatching { children(rootId) }.isSuccess }

    override suspend fun list(path: String): List<CardEntry>? = withContext(Dispatchers.IO) {
        val id = resolve(path) ?: return@withContext null
        children(id).map { CardEntry(it.name, it.dir, it.size) }
    }

    override suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val parent = resolve(path.substringBeforeLast('/', "/")) ?: return@withContext null
        val file = children(parent).firstOrNull { !it.dir && it.name.equals(path.substringAfterLast('/'), ignoreCase = true) }
            ?: return@withContext null
        cr.openInputStream(uri(file.id))?.use { it.readBytes() }
    }

    override suspend fun write(path: String, data: ByteArray) = withContext(Dispatchers.IO) {
        val parentPath = path.substringBeforeLast('/', "/")
        val parent = resolve(parentPath) ?: throw IOException("No folder $parentPath on the SD card")
        val name = path.substringAfterLast('/')
        val kids = children(parent)
        // Replace = delete + create: truncating in place isn't honoured by every storage provider.
        kids.firstOrNull { !it.dir && it.name.equals(name, ignoreCase = true) }?.let {
            DocumentsContract.deleteDocument(cr, uri(it.id)); kids.remove(it)
        }
        val created = DocumentsContract.createDocument(cr, uri(parent), "application/octet-stream", name)
            ?: throw IOException("Couldn't create $path on the SD card")
        cr.openOutputStream(created, "w")?.use { it.write(data) } ?: throw IOException("Couldn't write $path")
        kids += Child(name, DocumentsContract.getDocumentId(created), false, data.size.toLong())
        Unit
    }

    override suspend fun mkdirs(path: String) = withContext(Dispatchers.IO) {
        var id = rootId
        for (seg in segments(path)) {
            val kids = children(id)
            val existing = kids.firstOrNull { it.name.equals(seg, ignoreCase = true) }
            id = if (existing != null) existing.id else {
                val made = DocumentsContract.createDocument(cr, uri(id), Document.MIME_TYPE_DIR, seg)
                    ?: throw IOException("Couldn't create folder $seg on the SD card")
                DocumentsContract.getDocumentId(made).also { kids += Child(seg, it, true, 0) }
            }
        }
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val parent = resolve(path.substringBeforeLast('/', "/")) ?: return@withContext
        val kids = children(parent)
        val target = kids.firstOrNull { it.name.equals(path.substringAfterLast('/'), ignoreCase = true) } ?: return@withContext
        DocumentsContract.deleteDocument(cr, uri(target.id))   // recursive for folders
        kids.remove(target)
        listings.remove(target.id)
        Unit
    }
}
