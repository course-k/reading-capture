package app.readingcapture.share

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import app.readingcapture.App
import app.readingcapture.AppContainer
import app.readingcapture.capture.CaptureController
import app.readingcapture.core.inbox.InboxLayout
import app.readingcapture.core.inbox.PhotoKind
import app.readingcapture.core.quality.PhotoWarnings
import app.readingcapture.data.Photo
import app.readingcapture.data.PhotoState
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * ほかのアプリの「共有」から画像を受け取る口。
 *
 * 受け取った画像は、選んでいる本の写真として取り込み箱に上げる。画面は出さず、
 * 受け取ったことを一言だけ知らせて、もとのアプリ（Kindle など）にすぐ戻る。
 *
 * 画像は受け取ったその場で自分の領域に写す。共有で渡される読み取りの許可は、
 * この画面が閉じると切れるため（あとで上げるときには読めない）。
 * https://developer.android.com/training/sharing/receive （2026-11-20 に確認）
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as App).container
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "受け取れる画像がありませんでした", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val bookId = container.currentBookId
        val bookTitle = container.books.book(bookId)?.title
        // 写し終えるまで、この画面（透明）を開いたままにする。許可が切れないように
        Thread {
            var saved = 0
            val base = System.currentTimeMillis()
            for ((i, uri) in uris.withIndex()) {
                if (runCatching { import(container, uri, bookId, base + i) }.onFailure { container.diag.log("share_error", "error" to it.toString()) }.isSuccess) saved++
            }
            container.diag.log("share", "count" to uris.size, "saved" to saved)
            container.sync.kick()
            runOnUiThread {
                val text = when {
                    saved == 0 -> "受け取れませんでした"
                    bookTitle != null -> "$saved 枚を受け取りました（$bookTitle）"
                    else -> "$saved 枚を受け取りました"
                }
                Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
                finish()
            }
        }.start()
    }

    private fun import(container: AppContainer, uri: Uri, bookId: String, atMs: Long) {
        val id = CaptureController.newPhotoId(atMs)
        val mime = contentResolver.getType(uri)
        // そのまま置ける形式は、もとの中身のまま置く。それ以外は JPEG に直す
        val extension = when (mime) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> null
        }
        val fileName = "$id.${extension ?: "jpg"}"
        val target = container.store.photoFile(fileName)
        val tmp = container.store.tempFile(id)
        if (extension != null) {
            contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(tmp).use { input.copyTo(it); it.fd.sync() } }
        } else {
            val bitmap = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) } ?: error("画像を開けない")
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it); it.fd.sync() }
            bitmap.recycle()
        }
        if (tmp.length() == 0L) {
            tmp.delete()
            error("空の画像")
        }
        if (!tmp.renameTo(target)) error("保存できない")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.path, bounds)
        writeThumb(target, container.store.thumbFile(id), bounds.outWidth)
        val at = OffsetDateTime.ofInstant(Instant.ofEpochMilli(atMs), ZoneId.systemDefault())
        container.db.insertPhoto(
            Photo(
                id = id, bookId = bookId, capturedAt = atMs, kind = PhotoKind.SHARED, state = PhotoState.READY,
                remotePath = InboxLayout.photo(bookId, at, PhotoKind.SHARED, extension ?: "jpg"), fileName = fileName,
                width = bounds.outWidth, height = bounds.outHeight, bytes = target.length(),
                warnings = PhotoWarnings(), blobSha = null, uploadedAt = null,
            ),
        )
    }

    private fun writeThumb(source: File, thumb: File, width: Int) {
        runCatching {
            var sample = 1
            while (width / (sample * 2) >= 360) sample *= 2
            val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
            FileOutputStream(thumb).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            bitmap.recycle()
        }
    }
}
