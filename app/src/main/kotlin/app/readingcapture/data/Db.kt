package app.readingcapture.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.readingcapture.core.inbox.PhotoKind
import app.readingcapture.core.quality.PhotoWarnings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 端末の中の台帳。写真と話した言葉が、いまどの段階にあるかを持つ。
 *
 * 写真や言葉そのものを失わないための要。書き込みはすべてここを通し、
 * 「取り込み箱に上がった」と確かめられるまで、端末の中のファイルは消さない。
 */
class Db(context: Context, name: String? = "capture.db") : SQLiteOpenHelper(context, name, null, 1) {

    private val _version = MutableStateFlow(0L)

    /** 中身が変わるたびに増える。画面はこれを見て読み直す。 */
    val version: StateFlow<Long> = _version

    private fun changed() {
        _version.update { it + 1 }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE photo (
                id TEXT PRIMARY KEY,
                book_id TEXT NOT NULL,
                captured_at INTEGER NOT NULL,
                kind TEXT NOT NULL,
                state TEXT NOT NULL,
                remote_path TEXT NOT NULL,
                file_name TEXT,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                bytes INTEGER NOT NULL DEFAULT 0,
                warnings INTEGER NOT NULL DEFAULT 0,
                blob_sha TEXT,
                uploaded_at INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX photo_state ON photo(state)")
        db.execSQL("CREATE INDEX photo_book ON photo(book_id, captured_at)")
        db.execSQL(
            """
            CREATE TABLE note (
                id TEXT PRIMARY KEY,
                book_id TEXT NOT NULL,
                book_title TEXT NOT NULL,
                chapter_id TEXT,
                chapter_title TEXT,
                section_id TEXT,
                section_title TEXT,
                text TEXT NOT NULL,
                spoken_at INTEGER NOT NULL,
                state TEXT NOT NULL,
                remote_path TEXT NOT NULL,
                ever_uploaded INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX note_state ON note(state)")
        db.execSQL("CREATE INDEX note_place ON note(book_id, chapter_id, section_id, spoken_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---- 写真 ----

    fun insertPhoto(p: Photo) {
        writableDatabase.insertOrThrow("photo", null, ContentValues().apply {
            put("id", p.id)
            put("book_id", p.bookId)
            put("captured_at", p.capturedAt)
            put("kind", p.kind.name)
            put("state", p.state.name)
            put("remote_path", p.remotePath)
            put("file_name", p.fileName)
            put("width", p.width)
            put("height", p.height)
            put("bytes", p.bytes)
            put("warnings", p.warnings.toBits())
            put("blob_sha", p.blobSha)
            put("uploaded_at", p.uploadedAt)
        })
        changed()
    }

    fun photo(id: String): Photo? = queryPhotos("id = ?", arrayOf(id), null, null).firstOrNull()

    fun photosInState(state: PhotoState, limit: Int? = null): List<Photo> =
        queryPhotos("state = ?", arrayOf(state.name), "captured_at ASC", limit)

    /** ある本の、[sinceMs] 以降の写真（消したものを除く）。新しい順。 */
    fun photosOfBook(bookId: String, sinceMs: Long, limit: Int? = null): List<Photo> =
        queryPhotos(
            "book_id = ? AND captured_at >= ? AND state != ?",
            arrayOf(bookId, sinceMs.toString(), PhotoState.DELETE_PENDING.name),
            "captured_at DESC",
            limit,
        )

    fun countPhotosOfBook(bookId: String, sinceMs: Long): Int = count(
        "photo", "book_id = ? AND captured_at >= ? AND state != ?",
        arrayOf(bookId, sinceMs.toString(), PhotoState.DELETE_PENDING.name),
    )

    /** 仕上げが済んだ。 */
    fun markPhotoReady(id: String, fileName: String, width: Int, height: Int, bytes: Long, warnings: PhotoWarnings) {
        // 仕上げのあいだに持ち主が消していたら、消した状態のままにする
        writableDatabase.update("photo", ContentValues().apply {
            put("state", PhotoState.READY.name)
            put("file_name", fileName)
            put("width", width)
            put("height", height)
            put("bytes", bytes)
            put("warnings", warnings.toBits())
        }, "id = ? AND state = ?", arrayOf(id, PhotoState.RAW.name))
        changed()
    }

    fun setPhotoBlobSha(id: String, sha: String) {
        writableDatabase.update("photo", ContentValues().apply { put("blob_sha", sha) }, "id = ?", arrayOf(id))
    }

    /** 取り込み箱に上がった。持ち主がそのあいだに消していたら、状態は変えない（次の回で取り込み箱から消す）。 */
    fun markPhotoUploaded(id: String, atMs: Long) {
        val db = writableDatabase
        db.update("photo", ContentValues().apply {
            put("state", PhotoState.UPLOADED.name)
            put("uploaded_at", atMs)
        }, "id = ? AND state = ?", arrayOf(id, PhotoState.READY.name))
        // 消す予約が入っていた場合も、上がった事実は残す（取り込み箱から消す必要がある）
        db.update("photo", ContentValues().apply { put("uploaded_at", atMs) }, "id = ? AND state = ?", arrayOf(id, PhotoState.DELETE_PENDING.name))
        changed()
    }

    /**
     * 持ち主が消した。まだ上がっていなければ台帳からも消し、上がっていれば取り込み箱から消す予約にする。
     * @return 端末の中のファイル名（呼び出し側が消す）
     */
    fun deletePhoto(id: String): String? {
        val db = writableDatabase
        var fileName: String? = null
        db.beginTransaction()
        try {
            val p = queryPhotos("id = ?", arrayOf(id), null, null).firstOrNull() ?: return null
            fileName = p.fileName
            // 仕上げ済みの写真は、いま上げている最中かもしれない（中身を上げ終える前でも、同じ回のコミットに入る）。
            // 取り込み箱を確かめて消す側に倒す。仕上げ前の写真は、まだ上げる対象になっていないので、その場で消す
            if (p.state != PhotoState.RAW) {
                db.update("photo", ContentValues().apply {
                    put("state", PhotoState.DELETE_PENDING.name)
                    putNull("file_name")
                }, "id = ?", arrayOf(id))
            } else {
                db.delete("photo", "id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
        return fileName
    }

    fun removePhotoRow(id: String) {
        writableDatabase.delete("photo", "id = ?", arrayOf(id))
        changed()
    }

    /** 上がってから日がたった写真の、端末の中のファイルを片付ける対象。 */
    fun uploadedPhotosBefore(uploadedBeforeMs: Long): List<Photo> =
        queryPhotos("state = ? AND uploaded_at < ? AND file_name IS NOT NULL", arrayOf(PhotoState.UPLOADED.name, uploadedBeforeMs.toString()), null, null)

    fun clearPhotoFile(id: String) {
        writableDatabase.update("photo", ContentValues().apply { putNull("file_name") }, "id = ?", arrayOf(id))
    }

    fun removeUploadedPhotoRowsBefore(uploadedBeforeMs: Long) {
        writableDatabase.delete("photo", "state = ? AND uploaded_at < ? AND file_name IS NULL", arrayOf(PhotoState.UPLOADED.name, uploadedBeforeMs.toString()))
    }

    // ---- 話した言葉 ----

    fun insertNote(n: Note) {
        writableDatabase.insertOrThrow("note", null, ContentValues().apply {
            put("id", n.id)
            put("book_id", n.bookId)
            put("book_title", n.bookTitle)
            put("chapter_id", n.chapterId)
            put("chapter_title", n.chapterTitle)
            put("section_id", n.sectionId)
            put("section_title", n.sectionTitle)
            put("text", n.text)
            put("spoken_at", n.spokenAt)
            put("state", n.state.name)
            put("remote_path", n.remotePath)
            put("ever_uploaded", if (n.everUploaded) 1 else 0)
        })
        changed()
    }

    /** ある節で話したこと。古い順。 */
    fun notesAt(bookId: String, chapterId: String?, sectionId: String?): List<Note> {
        val where = StringBuilder("book_id = ? AND state != ?")
        val args = arrayListOf(bookId, NoteState.DELETE_PENDING.name)
        if (chapterId == null) where.append(" AND chapter_id IS NULL") else {
            where.append(" AND chapter_id = ?"); args += chapterId
        }
        if (sectionId == null) where.append(" AND section_id IS NULL") else {
            where.append(" AND section_id = ?"); args += sectionId
        }
        return queryNotes(where.toString(), args.toTypedArray(), "spoken_at ASC")
    }

    fun notesInState(state: NoteState): List<Note> = queryNotes("state = ?", arrayOf(state.name), "spoken_at ASC")

    fun note(id: String): Note? = queryNotes("id = ?", arrayOf(id), null).firstOrNull()

    /**
     * 上がった。上げているあいだに持ち主が書き換えていたら（[uploadedText] などが今と違えば）、まだ上げる側に残す。
     */
    fun markNoteUploaded(id: String, uploaded: Note) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = queryNotes("id = ?", arrayOf(id), null).firstOrNull() ?: return
            val same = now.text == uploaded.text && now.chapterId == uploaded.chapterId && now.sectionId == uploaded.sectionId
            db.update("note", ContentValues().apply {
                put("ever_uploaded", 1)
                if (same && now.state == NoteState.PENDING) put("state", NoteState.UPLOADED.name)
            }, "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    /** 入れる節を変える。上げ直す。 */
    fun moveNote(id: String, chapterId: String?, chapterTitle: String?, sectionId: String?, sectionTitle: String?) {
        writableDatabase.update("note", ContentValues().apply {
            put("chapter_id", chapterId)
            put("chapter_title", chapterTitle)
            put("section_id", sectionId)
            put("section_title", sectionTitle)
            put("state", NoteState.PENDING.name)
        }, "id = ? AND state != ?", arrayOf(id, NoteState.DELETE_PENDING.name))
        changed()
    }

    fun deleteNote(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val n = queryNotes("id = ?", arrayOf(id), null).firstOrNull() ?: return
            // 上げている最中かもしれないので、まだ上がっていなくても取り込み箱を確かめて消す側に倒す
            db.update("note", ContentValues().apply { put("state", NoteState.DELETE_PENDING.name) }, "id = ?", arrayOf(n.id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    fun removeNoteRow(id: String) {
        writableDatabase.delete("note", "id = ?", arrayOf(id))
        changed()
    }

    // ---- 数 ----

    fun syncCounts(sinceMs: Long): SyncCounts {
        val pendingPhotos = count("photo", "state IN (?, ?)", arrayOf(PhotoState.RAW.name, PhotoState.READY.name))
        val pendingNotes = count("note", "state = ?", arrayOf(NoteState.PENDING.name))
        val uploadedPhotos = count("photo", "state = ? AND uploaded_at >= ?", arrayOf(PhotoState.UPLOADED.name, sinceMs.toString()))
        val uploadedNotes = count("note", "state = ? AND spoken_at >= ?", arrayOf(NoteState.UPLOADED.name, sinceMs.toString()))
        return SyncCounts(pendingPhotos + pendingNotes, uploadedPhotos + uploadedNotes)
    }

    fun hasWorkToSync(): Boolean =
        count("photo", "state IN (?, ?, ?)", arrayOf(PhotoState.RAW.name, PhotoState.READY.name, PhotoState.DELETE_PENDING.name)) > 0 ||
            count("note", "state IN (?, ?)", arrayOf(NoteState.PENDING.name, NoteState.DELETE_PENDING.name)) > 0

    // ---- 内部 ----

    private fun count(table: String, where: String, args: Array<String>): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun queryPhotos(where: String, args: Array<String>, orderBy: String?, limit: Int?): List<Photo> =
        readableDatabase.query("photo", null, where, args, null, null, orderBy, limit?.toString()).use { c ->
            val out = ArrayList<Photo>(c.count)
            while (c.moveToNext()) out += c.toPhoto()
            out
        }

    private fun queryNotes(where: String, args: Array<String>, orderBy: String?): List<Note> =
        readableDatabase.query("note", null, where, args, null, null, orderBy).use { c ->
            val out = ArrayList<Note>(c.count)
            while (c.moveToNext()) out += c.toNote()
            out
        }

    private fun Cursor.str(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))

    private fun Cursor.toPhoto() = Photo(
        id = str("id")!!,
        bookId = str("book_id")!!,
        capturedAt = long("captured_at"),
        kind = PhotoKind.valueOf(str("kind")!!),
        state = PhotoState.valueOf(str("state")!!),
        remotePath = str("remote_path")!!,
        fileName = str("file_name"),
        width = int("width"),
        height = int("height"),
        bytes = long("bytes"),
        warnings = PhotoWarnings.fromBits(int("warnings")),
        blobSha = str("blob_sha"),
        uploadedAt = getColumnIndexOrThrow("uploaded_at").let { if (isNull(it)) null else getLong(it) },
    )

    private fun Cursor.toNote() = Note(
        id = str("id")!!,
        bookId = str("book_id")!!,
        bookTitle = str("book_title")!!,
        chapterId = str("chapter_id"),
        chapterTitle = str("chapter_title"),
        sectionId = str("section_id"),
        sectionTitle = str("section_title"),
        text = str("text")!!,
        spokenAt = long("spoken_at"),
        state = NoteState.valueOf(str("state")!!),
        remotePath = str("remote_path")!!,
        everUploaded = int("ever_uploaded") != 0,
    )
}
