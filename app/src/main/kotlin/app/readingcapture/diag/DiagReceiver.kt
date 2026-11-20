package app.readingcapture.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.readingcapture.App

/**
 * 合わせ込み用の合図を Mac から受け取る口。
 * `adb shell am broadcast -n app.readingcapture/.diag.DiagReceiver -a app.readingcapture.REINSPECT`
 * で、端末に残っている写真の「文字の行の枠の並び」（数値だけ）を記録に書き出す。
 */
class DiagReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as App).container
        if (intent.action == "app.readingcapture.REINSPECT") container.processor.reinspect()
    }
}
