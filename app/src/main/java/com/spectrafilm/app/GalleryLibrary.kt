/*
 * Spektrafilm for Android — RAW folder gallery: folder grant + listing. GPLv3.
 *
 * The gallery is a Lightroom-mobile-style entry point: the user grants ONE folder once
 * (Storage Access Framework tree, read-only, persisted), and every launch shows the RAW
 * files in it as a grid. Tapping a photo hands its tree-derived document Uri to the
 * existing editor through the same VIEW path as an inbound share (#162), so the editor,
 * recipes, and export pipeline are untouched.
 */
package com.spectrafilm.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.spectrafilm.libraw.RawDecoder

internal data class GalleryItem(
    val uri: Uri,
    val name: String,
    val lastModified: Long,
    val size: Long,
    val edited: Boolean,
)

internal object GalleryLibrary {
    private const val PREFS = "gallery_folder"
    private const val KEY_TREE = "tree_uri"

    /** True for a bare SAF tree grant Uri (content://authority/tree/<id>). */
    fun isTreeGrantUri(uri: Uri): Boolean {
        val segments = uri.pathSegments
        return segments.size == 2 && segments[0] == "tree"
    }

    /** True for any Uri that lives under a SAF tree (the tree itself or a child document). */
    fun isTreeDerivedUri(uri: Uri): Boolean = uri.pathSegments.firstOrNull() == "tree"

    fun loadFolder(ctx: Context): Uri? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)
            ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        // A grant the user revoked in system settings (or a reinstall) means re-pick.
        val granted = ctx.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        return if (granted) uri else null
    }

    fun saveFolder(ctx: Context, tree: Uri) {
        val resolver = ctx.contentResolver
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val previous = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TREE, tree.toString())
            .apply()
        if (previous != null && previous != tree.toString()) {
            runCatching {
                resolver.releasePersistableUriPermission(
                    Uri.parse(previous),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    /** Human-readable folder label, e.g. "DCIM/MotionCam". */
    fun folderLabel(tree: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return ""
        val path = id.substringAfter(':', id)
        return path.ifBlank { id.substringBefore(':') }
    }

    /** RAW files directly inside [tree], newest first. Blocking: call off the main thread. */
    fun listRawFiles(ctx: Context, tree: Uri): List<GalleryItem> {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = ArrayList<GalleryItem>()
        ctx.contentResolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                if (!RawDecoder.isRawFileName(name)) continue
                val lastModified = if (c.isNull(3)) 0L else c.getLong(3)
                val size = if (c.isNull(4)) 0L else c.getLong(4)
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                val edited = runCatching { Recipes.exists(ctx, Recipes.keyFor(uri)) }.getOrDefault(false)
                out += GalleryItem(uri, name, lastModified, size, edited)
            }
        }
        out.sortWith(compareByDescending<GalleryItem> { it.lastModified }.thenBy { it.name })
        return out
    }
}
