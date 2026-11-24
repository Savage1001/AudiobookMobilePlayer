package app.localchapter.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class Book(val id: String, val folder: String, val title: String, val tracks: List<Track>)
internal data class Bookmark(val uri: String = "", val position: Long = 0, val speed: Float = 1f)

internal class Library(private val context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun books(): List<Book> {
        val books = JSONArray(prefs.getString("books", "[]"))
        return List(books.length()) { index ->
            val item = books.getJSONObject(index)
            val files = item.getJSONArray("files")
            Book(item.getString("id"), item.getString("folder"), item.getString("title"),
                TrackOrder.scan(List(files.length()) {
                    val file = files.getJSONArray(it)
                    file.getString(0) to file.getString(1)
                }))
        }
    }

    fun save(book: Book) {
        val books = books().toMutableList()
        val index = books.indexOfFirst { it.id == book.id }
        if (index < 0) books.add(book) else books[index] = book
        prefs.edit().putString("books", encodeBooks(books)).apply()
    }

    fun remove(id: String): Boolean {
        val books = books()
        val removed = books.find { it.id == id } ?: return false
        val remaining = books.filterNot { it.id == id }
        prefs.edit().putString("books", encodeBooks(remaining))
            .remove("track:$id").remove("position:$id").remove("speed:$id").apply {
                if (lastBook() == id) remove("lastBook")
            }.apply()
        if (remaining.none { it.folder == removed.folder }) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(removed.folder), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // The folder grant may already have been revoked.
            }
        }
        return true
    }

    private fun encodeBooks(books: List<Book>): String {
        val result = JSONArray()
        books.forEach { item ->
            val files = JSONArray()
            item.tracks.forEach { files.put(JSONArray().put(it.filename).put(it.uri)) }
            result.put(JSONObject().put("id", item.id).put("folder", item.folder)
                .put("title", item.title).put("files", files))
        }
        return result.toString()
    }

    fun scan(tree: Uri): Book {
        val resolver = context.contentResolver
        val root = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, root)
        val title = resolver.query(document, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Audiobook"
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val files = resolver.query(children, columns, null, null, null)?.use { cursor ->
            buildList<Pair<String, String>> {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    if (cursor.getString(2) == Document.MIME_TYPE_DIR) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    add(name to uri.toString())
                }
            }
        } ?: error("Folder unavailable")
        return Book(UUID.randomUUID().toString(), tree.toString(), title, TrackOrder.scan(files))
    }

    fun bookmark(id: String): Bookmark = Bookmark(
        prefs.getString("track:$id", "").orEmpty(),
        prefs.getLong("position:$id", 0),
        prefs.getFloat("speed:$id", 1f)
    )

    fun remember(id: String, bookmark: Bookmark) {
        prefs.edit().putString("track:$id", bookmark.uri)
            .putLong("position:$id", bookmark.position)
            .putFloat("speed:$id", bookmark.speed)
            .putString("lastBook", id).apply()
    }

    fun lastBook(): String? = prefs.getString("lastBook", null)
}
