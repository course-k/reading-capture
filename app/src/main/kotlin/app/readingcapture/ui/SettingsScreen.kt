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

        Heading("取り込み箱")
        Label("置き場（持ち主/リポジトリ）")
        Field(repo, { repo = it }, placeholder = "例: name/reading-inbox")
        Label(if (hasToken) "鍵（入っています。入れ直すときだけ貼ってください）" else "鍵")
        Field(token, { token = it }, placeholder = if (hasToken) "••••••••" else "github_pat_ から始まる文字", secret = true)
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                if (checking) "確かめています" else "入れて、つながるか確かめる",
                enabled = !checking && InboxConfig.parseRepo(repo) != null && (token.isNotBlank() || hasToken),
                onClick = {
                    checking = true
                    result = null
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching {
                                container.settings.inboxRepo = repo
                                if (token.isNotBlank()) container.vault.save(token.trim())
                                val client = container.syncRunner.clientOrNull() ?: error("鍵を読めません")
                                client.checkAccess()
                                container.syncRunner.refreshBooks(force = true)
                            }
                        }
                        checking = false
                        hasToken = container.vault.hasToken
                        token = ""
                        result = outcome.fold(
                            onSuccess = { true to "つながりました。書き込めます" },
                            onFailure = { e ->
                                false to when (e) {
                                    is InboxException.Unauthorized -> "鍵が無効です。貼り間違いか、期限切れかもしれません"
                                    is InboxException.NoPermission -> "鍵は通りましたが、書き込めません。Contents を Read and write にしてください"
                                    is InboxException.NotFound -> "取り込み箱が見つかりません。置き場の名前と、鍵の対象のリポジトリを確かめてください"
                                    is InboxException.Network -> "つながりません。電波を確かめてください"
                                    else -> "確かめられませんでした（${e.message ?: "理由不明"}）"
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
            TextAction("鍵をこのスマホから消す", onClick = {
                container.vault.clear()
                hasToken = false
                result = false to "鍵を消しました。上げるには、もう一度入れてください"
            }, color = c.danger, modifier = Modifier.padding(horizontal = 12.dp))
        }
        Note("鍵の作り方: GitHub の Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token。Repository access は Only select repositories で取り込み箱だけを選び、Permissions の Contents を Read and write にします。")
        Note("鍵は、このスマホの中に暗号化して置きます。スマホを失くしたら、GitHub の同じ画面で鍵を消してください。")

        Heading("上げる状況")
        val line = buildString {
            append("まだ ${counts.pending}")
            append(" ・ 今日上げた ${counts.uploadedToday}")
        }
        Text(line, style = Type.body, color = c.text, modifier = Modifier.padding(horizontal = 22.dp))
        status.problem?.let { Text(Texts.syncProblem(it), style = Type.small, color = c.warn, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) }
        Row(Modifier.padding(horizontal = 12.dp)) {
            TextAction(if (status.running) "上げています" else "いま上げる", onClick = {
                container.syncRunner.clearProblem()
                container.sync.kick()
            }, color = c.accent, enabled = !status.running)
        }
        Note("写真と話した言葉は、取り込み箱に上がったと確かめられるまで、このスマホから消しません。電波が無いときはためておき、つながったら上げます。")

        Heading("合わせ込み")
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("検知の数値を「撮る」の画面に出す", style = Type.body, color = c.text, modifier = Modifier.weight(1f))
            Switch(
                checked = numbers,
                onCheckedChange = {
                    numbers = it
                    container.settings.showNumbers = it
                },
                colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
            )
        }
        Note("撮り逃しや二度撮りが続くときに、原因を探すためのものです。ふだんは切ったままで構いません。")

        Spacer(Modifier.height(24.dp))
        Text("読書の取り込み ${BuildConfig.VERSION_NAME}", style = Type.tiny, color = c.text3, modifier = Modifier.padding(horizontal = 22.dp))
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
