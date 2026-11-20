package app.readingcapture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readingcapture.core.inbox.PhotoKind
import app.readingcapture.core.quality.PhotoWarnings
import app.readingcapture.core.quality.Side
import app.readingcapture.data.Db
import app.readingcapture.data.Note
import app.readingcapture.data.NoteState
import app.readingcapture.data.Photo
import app.readingcapture.data.PhotoState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 台帳の決まり。「上げる」と「消す」が重なっても、写真や言葉を失わない・取り込み箱に残さないこと。 */
@RunWith(AndroidJUnit4::class)
class DbTest {

    // 名前なし = メモリの中だけの台帳
    private val db = Db(InstrumentationRegistry.getInstrumentation().targetContext, name = null)

    @After
    fun tearDown() = db.close()

    private fun photo(id: String, at: Long = 1000) = Photo(
        id = id, bookId = "book-a", capturedAt = at, kind = PhotoKind.CAMERA, state = PhotoState.RAW,
        remotePath = "books/book-a/photos/2026-11-20/$id-cam.jpg", fileName = null,
        width = 0, height = 0, bytes = 10, warnings = PhotoWarnings(), blobSha = null, uploadedAt = null,
    )

    private fun note(id: String, section: String? = "s1") = Note(
        id = id, bookId = "book-a", bookTitle = "本", chapterId = "c1", chapterTitle = "第1章", sectionId = section, sectionTitle = "節",
        text = "言葉", spokenAt = 2000, state = NoteState.PENDING, remotePath = "books/book-a/notes/2026-11-20/$id.json", everUploaded = false,
    )

    @Test
    fun 写真は_撮った_仕上げた_上がった_の順に進む() {
        db.insertPhoto(photo("p1"))
        assertEquals(1, db.syncCounts(0).pending)
        db.markPhotoReady("p1", "p1.jpg", 2304, 3072, 1_500_000, PhotoWarnings(cut = setOf(Side.RIGHT)))
        val ready = db.photo("p1")!!
        assertEquals(PhotoState.READY, ready.state)
        assertEquals(setOf(Side.RIGHT), ready.warnings.cut)
        assertTrue(db.hasWorkToSync())
        db.setPhotoBlobSha("p1", "abc")
        db.markPhotoUploaded("p1", 5000)
        assertEquals(PhotoState.UPLOADED, db.photo("p1")!!.state)
        assertEquals(0, db.syncCounts(0).pending)
        assertEquals(1, db.syncCounts(0).uploadedToday)
        assertFalse(db.hasWorkToSync())
    }

    @Test
    fun まだ上げていない写真を消すと_台帳からも消える() {
        db.insertPhoto(photo("p1"))
        db.markPhotoReady("p1", "p1.jpg", 1, 1, 1, PhotoWarnings())
        assertEquals("p1.jpg", db.deletePhoto("p1"))
        assertNull(db.photo("p1"))
        assertFalse(db.hasWorkToSync())
    }

    @Test
    fun 上がった写真を消すと_取り込み箱から消す予約になる() {
        db.insertPhoto(photo("p1"))
        db.markPhotoReady("p1", "p1.jpg", 1, 1, 1, PhotoWarnings())
        db.markPhotoUploaded("p1", 5000)
        db.deletePhoto("p1")
        assertEquals(PhotoState.DELETE_PENDING, db.photo("p1")!!.state)
        assertTrue(db.hasWorkToSync())
        assertEquals(0, db.countPhotosOfBook("book-a", 0))
        db.removePhotoRow("p1")
        assertNull(db.photo("p1"))
    }

    @Test
    fun 上げている最中に消した写真は_上がったあとも消す予約のまま() {
        db.insertPhoto(photo("p1"))
        db.markPhotoReady("p1", "p1.jpg", 1, 1, 1, PhotoWarnings())
        // 中身を上げ始めた（取り込み箱に入るかもしれない）
        db.setPhotoBlobSha("p1", "abc")
        db.deletePhoto("p1")
        assertEquals(PhotoState.DELETE_PENDING, db.photo("p1")!!.state)
        // 上げ終わった知らせが、消したあとに届く
        db.markPhotoUploaded("p1", 5000)
        assertEquals("消した写真が「上がった」に戻らない", PhotoState.DELETE_PENDING, db.photo("p1")!!.state)
    }

    @Test
    fun 仕上げの途中で消した写真は_仕上がっても戻らない() {
        db.insertPhoto(photo("p1"))
        db.deletePhoto("p1")
        db.markPhotoReady("p1", "p1.jpg", 1, 1, 1, PhotoWarnings())
        assertNull(db.photo("p1"))
    }

    @Test
    fun 枚数と直前の_1_枚は_本ごと_消したものを除く() {
        db.insertPhoto(photo("p1", at = 1000))
        db.insertPhoto(photo("p2", at = 2000))
        db.insertPhoto(photo("p3", at = 3000).copy(bookId = "book-b"))
        assertEquals(2, db.countPhotosOfBook("book-a", 0))
        assertEquals("p2", db.photosOfBook("book-a", 0, limit = 1).single().id)
        assertEquals(1, db.countPhotosOfBook("book-a", 1500))
        db.deletePhoto("p2")
        assertEquals("p1", db.photosOfBook("book-a", 0, limit = 1).single().id)
    }

    @Test
    fun 言葉は_その節の下に_話した順で並ぶ() {
        db.insertNote(note("n2").copy(spokenAt = 3000))
        db.insertNote(note("n1").copy(spokenAt = 1000))
        db.insertNote(note("n3", section = "s2"))
        assertEquals(listOf("n1", "n2"), db.notesAt("book-a", "c1", "s1").map { it.id })
        assertEquals(listOf("n3"), db.notesAt("book-a", "c1", "s2").map { it.id })
        assertTrue(db.notesAt("book-a", null, null).isEmpty())
    }

    @Test
    fun 上がった言葉の節を変えると_上げ直す対象になる() {
        val n = note("n1")
        db.insertNote(n)
        db.markNoteUploaded("n1", n)
        assertEquals(NoteState.UPLOADED, db.note("n1")!!.state)
        db.moveNote("n1", "c1", "第1章", "s2", "別の節")
        val moved = db.note("n1")!!
        assertEquals(NoteState.PENDING, moved.state)
        assertEquals("s2", moved.sectionId)
        assertTrue(moved.everUploaded)
    }

    @Test
    fun 上げているあいだに節を変えた言葉は_上がった扱いにしない() {
        val before = note("n1")
        db.insertNote(before)
        db.moveNote("n1", "c1", "第1章", "s2", "別の節")
        // 変える前の内容が上がった、という知らせが届く
        db.markNoteUploaded("n1", before)
        assertEquals("新しい内容をまだ上げていない", NoteState.PENDING, db.note("n1")!!.state)
    }

    @Test
    fun 言葉を消すと_取り込み箱から消す予約になり_一覧から外れる() {
        db.insertNote(note("n1"))
        db.deleteNote("n1")
        assertTrue(db.notesAt("book-a", "c1", "s1").isEmpty())
        assertEquals(listOf("n1"), db.notesInState(NoteState.DELETE_PENDING).map { it.id })
        // 消したあとに節を変えようとしても、戻らない
        db.moveNote("n1", "c1", "第1章", "s2", "別の節")
        assertEquals(NoteState.DELETE_PENDING, db.note("n1")!!.state)
    }
}
