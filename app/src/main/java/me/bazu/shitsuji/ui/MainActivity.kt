package me.bazu.shitsuji.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.bazu.shitsuji.agent.ModelChoice
import me.bazu.shitsuji.agent.SystemPrompt
import me.bazu.shitsuji.container
import me.bazu.shitsuji.data.Settings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ShitsujiTheme { SettingsRoot() } }
    }
}

@Composable
private fun SettingsRoot() {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("設定", "プロフィール", "診断")

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> SettingsTab()
                1 -> ProfileTab()
                else -> DiagnosticsTab()
            }
        }
    }
}

@Composable
private fun SettingsTab() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(Settings()) }
    var status by remember { mutableStateOf("") }
    var spent by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        settings = c.store.settings()
        apiKey = if (c.store.hasApiKey()) MASK else ""
        val s = c.budget.state()
        spent = "今月の使用額: ¥${s.spentJpy} / ¥${s.capJpy}（API ${s.calls} 回）"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Anthropic APIキー", style = MaterialTheme.typography.titleMedium)
        Text(
            "console.anthropic.com で発行。端末の Keystore で暗号化して保存され、Anthropic 以外には送られません。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-ant-...") },
            singleLine = true,
        )
        Button(onClick = {
            if (apiKey.isNotBlank() && apiKey != MASK) {
                c.store.setApiKey(apiKey)
                apiKey = MASK
                status = "APIキーを保存しました。"
            }
        }) { Text("保存") }

        HorizontalDivider()

        Text("予算", style = MaterialTheme.typography.titleMedium)
        Text(spent, style = MaterialTheme.typography.bodyMedium)
        Text(
            "上限に達するとエージェントは停止します。使用額はレスポンスの実測トークン数から計算していて、見積もりではありません。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = settings.capJpy.toString(),
            onValueChange = { v -> v.toIntOrNull()?.let { settings = settings.copy(capJpy = it) } },
            label = { Text("月の上限（円）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = settings.usdJpy.toString(),
            onValueChange = { v -> v.toDoubleOrNull()?.let { settings = settings.copy(usdJpy = it) } },
            label = { Text("為替レート（1 USD = ? 円）") },
            singleLine = true,
        )

        Text("モデル", style = MaterialTheme.typography.titleMedium)
        ModelChoice.entries.forEach { m ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { settings = settings.copy(defaultModelId = m.id) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (settings.defaultModelId == m.id) "● ${m.label}" else "○ ${m.label}")
                }
            }
            Text(
                "入力 \$${m.inputPerMTok}/Mtok・出力 \$${m.outputPerMTok}/Mtok。" +
                    "キャッシュはプレフィックス ${m.minCacheablePrefixTokens} トークン以上で有効。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(onClick = {
            scope.launch {
                c.store.saveSettings(settings)
                status = "設定を保存しました。"
            }
        }) { Text("設定を保存") }

        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        HorizontalDivider()
        Text("ポップアップの出し方", style = MaterialTheme.typography.titleMedium)
        Text(
            """
            1. クイック設定を編集し「執事」タイルを追加する（どの端末でも使える）
            2. 設定 →「アプリ」→「既定のアプリ」→「デジタルアシスタントアプリ」を「執事」にすると
               電源ボタン長押しで出せる（機種による）
            3. どのアプリでも文字を選択 → メニューの「執事」
            """.trimIndent(),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ProfileTab() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()

    var json by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var tokenInfo by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { json = c.store.profileJson() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("プロフィール", style = MaterialTheme.typography.titleMedium)
        Text(
            "ここが製品の本体です。手持ちで「これは似合う」と思う服の実寸（肩幅・身幅・着丈）を " +
                "カテゴリごとに2〜3着入れてください。メジャーで測る30分が、一番効きます。",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = json,
            onValueChange = { json = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    status = c.store.saveProfileJson(json).fold(
                        onSuccess = { "保存しました。" },
                        onFailure = { "JSONが不正です: ${it.message?.take(160)}" },
                    )
                }
            }) { Text("保存") }

            OutlinedButton(onClick = { json = TEMPLATE }) { Text("記入例を入れる") }
        }

        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)

        HorizontalDivider()

        Text("プロンプトキャッシュ", style = MaterialTheme.typography.titleMedium)
        Text(
            "Haiku 4.5 はシステムプロンプトが 4096 トークン以上でないとキャッシュが効きません " +
                "（エラーは出ず、黙って課金されます）。超えていれば 2 回目以降の同じ前置きが 1/10 の単価になります。" +
                "つまりプロフィールを詳しく書くほど、判定が良くなり、かつ安くなります。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = {
            scope.launch {
                tokenInfo = runCatching {
                    val profile = c.store.profile()
                    val model = ModelChoice.byId(c.store.settings().defaultModelId)
                    val n = c.anthropic.countTokens(
                        model = model.id,
                        system = SystemPrompt.build(profile),
                        messages = buildJsonArray {
                            add(buildJsonObject {
                                put("role", "user")
                                putJsonArray("content") {
                                    add(buildJsonObject { put("type", "text"); put("text", "x") })
                                }
                            })
                        },
                        tools = c.tools.definitions,
                    )
                    val need = model.minCacheablePrefixTokens
                    if (n >= need) {
                        "$n トークン — 閾値 $need を超えています。キャッシュ有効（入力単価 1/10）。"
                    } else {
                        "$n トークン — 閾値 $need に ${need - n} 足りません。" +
                            "キャッシュは効きません。基準服や好みを書き足すと届きます。"
                    }
                }.getOrElse { "測定に失敗: ${it.message}" }
            }
        }) { Text("いま何トークンか測る（無料）") }

        if (tokenInfo.isNotBlank()) {
            Text(tokenInfo, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun DiagnosticsTab() {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()

    var keyword by remember { mutableStateOf("カーディガン") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("スクレイパ診断", style = MaterialTheme.typography.titleMedium)
        Text(
            "検索が0件になったらここを実行してください。メルカリ側の構造が変わったのか、" +
                "通信やログインの問題なのかが切り分けられます。結果をそのまま貼れば、" +
                "アプリを作り直さずに下の「抽出スクリプト」を差し替えて直せます。",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            label = { Text("検索語") },
            singleLine = true,
        )

        Button(
            enabled = !running,
            onClick = {
                running = true
                output = "実行中…"
                scope.launch {
                    output = c.tools.dispatch(
                        "scrape_diagnostics",
                        buildJsonObject { put("keyword", keyword) },
                    )
                    running = false
                }
            },
        ) { Text(if (running) "実行中…" else "実行") }

        if (output.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        output,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

private const val MASK = "••••••••••••"

private val TEMPLATE = """
{
  "body": {
    "heightCm": 172.0,
    "weightKg": 63.0,
    "shoulderCm": 44.0,
    "chestCm": 92.0,
    "notes": "少しなで肩。腕は平均より長め。"
  },
  "fitReferences": [
    {
      "label": "無印良品 ネイビーカーディガン",
      "category": "カーディガン",
      "verdict": "GOOD",
      "shoulderCm": 44.0,
      "chestWidthCm": 52.0,
      "lengthCm": 68.0,
      "sleeveCm": 60.0,
      "note": "一番よく着る。これが基準。"
    },
    {
      "label": "ユニクロ グレーカーディガン",
      "category": "カーディガン",
      "verdict": "ACCEPTABLE",
      "shoulderCm": 45.5,
      "chestWidthCm": 54.0,
      "lengthCm": 70.0,
      "note": "少し大きいがゆったり着るなら可。"
    }
  ],
  "preferences": {
    "likedColors": ["ネイビー", "チャコール", "生成り"],
    "dislikedColors": ["赤", "ビビッドな色"],
    "likedBrands": ["無印良品", "ユニクロ"],
    "dislikedBrands": [],
    "avoidMaterials": ["アクリル100%"],
    "silhouetteNotes": "ドロップショルダーは肩が落ちすぎるので苦手。ジャストサイズが良い。"
  },
  "freeNotes": "古着可。毛玉とヨレには敏感。喫煙者の出品は避けたい。",
  "decisions": []
}
""".trim()
