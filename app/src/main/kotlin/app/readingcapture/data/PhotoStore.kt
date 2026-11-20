package app.readingcapture.data

import android.content.Context
import java.io.File

/**
 * 写真のファイルの置き場（アプリ専用の領域。ほかのアプリからは見えない）。
 *
 * - raw/    カメラが保存した直後のもの。仕上げが済んだら消す
 * - photos/ 仕上げ済み。取り込み箱に上げるもの。上がって数日たつまで残す
 * - thumbs/ 画面に出す小さな写し
 */
class PhotoStore(context: Context) {

    private val root = File(context.filesDir, "capture")
    private val rawDir = File(root, "raw").apply { mkdirs() }
    private val photoDir = File(root, "photos").apply { mkdirs() }
    private val thumbDir = File(root, "thumbs").apply { mkdirs() }

    fun rawFile(id: String) = File(rawDir, "$id.jpg")
    fun photoFile(fileName: String) = File(photoDir, fileName)
    fun thumbFile(id: String) = File(thumbDir, "$id.jpg")

    /** 仕上げの途中のファイル。書き終えてから [photoFile] の名前に付け替える。 */
    fun tempFile(id: String) = File(photoDir, "$id.tmp")

    fun deleteAll(id: String, fileName: String?) {
        rawFile(id).delete()
        thumbFile(id).delete()
        tempFile(id).delete()
        fileName?.let { photoFile(it).delete() }
    }

    /** 台帳に載っていない raw（保存の直後に落ちた、など）。 */
    fun rawIds(): List<String> = rawDir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") && it.length() > 0 }?.map { it.nameWithoutExtension } ?: emptyList()
}
