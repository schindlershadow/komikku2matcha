package com.schindler.k2m

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An .epub file found directly inside a picked folder. */
data class FoundEpub(val name: String, val uri: Uri)

/**
 * The .epub files directly inside a folder the user granted (not recursive: "a folder with epubs
 * in it" means the files sit right there, same as how Komikku's own chapter folders work), plus
 * the folder's own name for the shelf they'll share on the X4 (CoverLibraryActivity::loadShelves
 * groups books by their containing folder). Queries DocumentsContract directly, like
 * [KomikkuScanner] -- DocumentFile does one query per property per file.
 */
object EpubFolderScanner {
    suspend fun scan(ctx: Context, tree: Uri): Pair<String, List<FoundEpub>> = withContext(Dispatchers.IO) {
        val treeDocId = DocumentsContract.getTreeDocumentId(tree)
        val name = ctx.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId),
            arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Books"
        val found = mutableListOf<FoundEpub>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocId)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val n = c.getString(1) ?: continue
                if (c.getString(2) == Document.MIME_TYPE_DIR) continue
                if (n.endsWith(".epub", ignoreCase = true)) found += FoundEpub(n, DocumentsContract.buildDocumentUriUsingTree(tree, id))
            }
        }
        name to found
    }
}
