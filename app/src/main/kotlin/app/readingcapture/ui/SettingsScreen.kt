package app.readingcapture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readingcapture.AppContainer
import app.readingcapture.BuildConfig
import app.readingcapture.core.inbox.InboxConfig
import app.readingcapture.core.inbox.InboxException
import app.readingcapture.data.SyncCounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 設定。持ち主が触るのは、鍵を入れるところだけ。 */
@Composable
fun SettingsScreen(container: AppContainer, counts: SyncCounts, onBack: () -> Unit) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    val status by container.syncRunner.status.collectAsStateWithLifecycle()

    var repo by remember { mutableStateOf(container.settings.inboxRepo) }
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(container.vault.hasToken) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var numbers by remember { mutableStateOf(container.settings.showNumbers) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction("‹ 戻る", onClick = onBack)
        }
        Text("設定", style = Type.bodyStrong.copy(fontSize = Type.body.fontSize * 1.25f), color = c.text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))

        Heading("GitHub 連携")
        Label("リポジトリ（owner/repo）")
        Field(repo, { repo = it }, placeholder = "例: owner/reading-inbox")
        Label(if (hasToken) "アクセストークン（設定済み。変更するときだけ入力）" else "アクセストークン")
        Field(token, { token = it }, placeholder = if (hasToken) "••••••••" else "github_pat_…", secret = true)
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                if (checking) "接続テスト中…" else "保存して接続テスト",
                enabled = !checking && InboxConfig.parseRepo(repo) != null && (token.isNotBlank() || hasToken),
                onClick = {
                    checking = true
                    result = null
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching {
                                container.settings.inboxRepo = repo
                                if (token.isNotBlank()) container.vault.save(token.trim())
                                val client = container.syncRunner.clientOrNull() ?: error("トークンを読み込めません")
                                client.checkAccess()
                                container.syncRunner.refreshBooks(force = true)
                            }
                        }
                        checking = false
                        hasToken = container.vault.hasToken
                        token = ""
                        result = outcome.fold(
                            onSuccess = { true to "接続に成功しました（書き込み可）" },
                            onFailure = { e ->
                                false to when (e) {
                                    is InboxException.Unauthorized -> "トークンが無効です。入力ミスか、有効期限切れの可能性があります"
                                    is InboxException.NoPermission -> "認証は成功しましたが、書き込み権限がありません。Contents を Read and write に設定してください"
                                    is InboxException.NotFound -> "リポジトリが見つかりません。リポジトリ名と、トークンの対象リポジトリを確認してください"
                                    is InboxException.Network -> "接続できません。ネットワーク接続を確認してください"
                                    else -> "接続テストに失敗しました（${e.message ?: "原因不明"}）"
                                }
                            },
                        )
                        if (outcome.isSuccess) {
                            container.syncRunner.clearProblem()
                            container.sync.kick()
                        }
                    }
                },
            )
        }
        result?.let { (ok, text) ->
            Text(text, style = Type.small, color = if (ok) c.accent else c.warn, modifier = Modifier.padding(horizontal = 22.dp))
        }
        if (hasToken) {
            TextAction("トークンを端末から削除", onClick = {
                container.vault.clear()
                hasToken = false
                result = false to "トークンを削除しました。アップロードするには再設定してください"
            }, color = c.danger, modifier = Modifier.padding(horizontal = 12.dp))
        }
        Note("トークンの作成手順: GitHub の Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token。Repository access は Only select repositories で対象のリポジトリだけを選択し、Permissions の Contents を Read and write に設定します。")
        Note("トークンは端末内に暗号化して保存します。端末を紛失した場合は、GitHub の同じ画面でトークンを削除してください。")

        Heading("アップロード状況")
        val line = buildString {
            append("未アップロード ${counts.pending}")
            append(" ・ 今日のアップロード ${counts.uploadedToday}")
        }
        Text(line, style = Type.body, color = c.text, modifier = Modifier.padding(horizontal = 22.dp))
        status.problem?.let { Text(Texts.syncProblem(it), style = Type.small, color = c.warn, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) }
        Row(Modifier.padding(horizontal = 12.dp)) {
            TextAction(if (status.running) "アップロード中…" else "今すぐアップロード", onClick = {
                container.syncRunner.clearProblem()
                container.sync.kick()
            }, color = c.accent, enabled = !status.running)
        }
        Note("写真とメモは、アップロードの完了を確認するまで端末から削除しません。オフライン時は端末に保存し、接続が回復したら自動でアップロードします。")

        Heading("撮影完了時のバイブレーション")
        Note("タップすると再生されます。選択したものが、撮影完了時のバイブレーションになります。")
        var chosen by remember { mutableStateOf(container.haptics.selected.id) }
        for ((title, choices) in listOf("長さ指定" to container.haptics.lengths, "プリセット" to container.haptics.presets, "その他" to container.haptics.primitives)) {
            if (choices.isEmpty()) continue
            Label(title)
            for (row in choices.chunked(3)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    for (choice in row) {
                        val on = chosen == choice.id
                        Box(
                            Modifier.weight(1f).padding(horizontal = 4.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (on) c.accentBg else c.sunken)
                                .quietClickable {
                                    container.haptics.play(choice)
                                    container.settings.capturedHaptic = choice.id
                                    chosen = choice.id
                                }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(choice.label, style = if (on) Type.labelStrong else Type.label, color = if (on) c.accent else c.text)
                        }
                    }
                    // 行の残りを空けておく（最後の行でボタンが広がらないように）
                    repeat(3 - row.size) { Box(Modifier.weight(1f).padding(horizontal = 4.dp)) }
                }
            }
        }

        Heading("デバッグ")
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("検出の数値を撮影画面に表示", style = Type.body, color = c.text, modifier = Modifier.weight(1f))
            Switch(
                checked = numbers,
                onCheckedChange = {
                    numbers = it
                    container.settings.showNumbers = it
                },
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
            )
        }
        Note("撮り逃しや重複撮影が続くときの原因調査用です。通常はオフのままで構いません。")

        Spacer(Modifier.height(24.dp))
        Text("Reading Capture ${BuildConfig.VERSION_NAME}", style = Type.tiny, color = c.text3, modifier = Modifier.padding(horizontal = 22.dp))
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = Type.small, color = LocalColors.current.text2, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 8.dp))
}

@Composable
private fun Label(text: String) {
    Text(text, style = Type.small, color = LocalColors.current.text2, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 6.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = Type.small, color = LocalColors.current.text3, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, secret: Boolean = false) {
    val c = LocalColors.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(c.sunken).padding(horizontal = 14.dp, vertical = 13.dp)) {
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.replace("\n", "")) },
            singleLine = true,
            textStyle = Type.body.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = Type.body, color = c.text3)
                inner()
            },
        )
    }
}
