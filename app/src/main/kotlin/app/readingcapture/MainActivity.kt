package app.readingcapture

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.readingcapture.ui.AppRoot
import app.readingcapture.ui.AppTheme
import app.readingcapture.ui.Overlay
import app.readingcapture.ui.Tab

class MainActivity : ComponentActivity() {

    private var tab by mutableStateOf(Tab.CAPTURE)
    private var overlay by mutableStateOf(Overlay.NONE)

    private val container get() = (application as App).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString(KEY_TAB)?.let { tab = Tab.valueOf(it) }
        setContent {
            AppTheme {
                AppRoot(container, tab, onTab = { tab = it }, overlay = overlay, onOverlay = { overlay = it })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 開くたびに、本の一覧と目次が新しくなっていないかを見て、たまっているものを上げる
        container.refreshBooks(force = false)
        container.sync.kick()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, tab.name)
    }

    // 「撮る」の画面では、音量ボタンをシャッターにする（台に置いたまま、どちらのボタンでも撮れる）
    private val shutterKeysActive get() = tab == Tab.CAPTURE && overlay == Overlay.NONE

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (shutterKeysActive && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            // 押しっぱなしで続けて撮らない
            if (event.repeatCount == 0) container.capture.shutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (shutterKeysActive && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }

    private companion object {
        const val KEY_TAB = "tab"
    }
}
