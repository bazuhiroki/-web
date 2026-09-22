package me.bazu.shitsuji.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * ポップアップ本体。
 *
 * 透過テーマの上に下部シートだけを描くので、直前に見ていたアプリの上に
 * 重なって出てくるように見える。背景をタップすれば閉じる。
 *
 * 起動経路は 3 つ:
 *  - 端末の「アシスタントアプリ」に設定 → 電源ボタン長押し
 *  - クイック設定タイル
 *  - 任意のアプリで文字選択 →「執事」/ 共有シート
 */
class ChatActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val seed = intent.seedText()
        setContent {
            ShitsujiTheme {
                ChatPopup(seedText = seed, onDismiss = { finish() })
            }
        }
    }

    private fun Intent?.seedText(): String = when (this?.action) {
        Intent.ACTION_PROCESS_TEXT ->
            getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        Intent.ACTION_SEND -> getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        else -> ""
    }
}

@Composable
private fun ChatPopup(seedText: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val vm: ChatViewModel = viewModel(factory = ChatViewModel.factory(context))
    var input by remember { mutableStateOf(seedText) }
    val listState = rememberLazyListState()

    LaunchedEffect(vm.lines.size) {
        if (vm.lines.isNotEmpty()) listState.animateScrollToItem(vm.lines.lastIndex)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景。タップで閉じる。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss)
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding(),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Handle()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = vm.budgetLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { context.openSettings() }) {
                        Icon(Icons.Filled.Settings, contentDescription = "設定")
                    }
                }
                LinearProgressIndicator(
                    progress = { vm.budgetFraction },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                if (vm.lines.isEmpty()) {
                    EmptyHint()
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(vm.lines) { line -> ChatLineRow(line) }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("指示をどうぞ") },
                        maxLines = 4,
                        enabled = !vm.busy,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (vm.busy) {
                        Box(Modifier.width(48.dp).height(56.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.width(24.dp))
                        }
                    } else {
                        IconButton(
                            onClick = {
                                val text = input.trim()
                                if (text.isNotEmpty()) {
                                    input = ""
                                    vm.send(text)
                                }
                            },
                            modifier = Modifier.height(56.dp),
                        ) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "送信")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Handle() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    RoundedCornerShape(2.dp),
                )
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun EmptyHint() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "例:「カーディガン 5000円以下で似合うの探して」",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ChatLineRow(line: ChatLine) {
    when (line) {
        is ChatLine.Me -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    line.text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        is ChatLine.Agent -> Text(
            line.text,
            style = MaterialTheme.typography.bodyMedium,
        )

        is ChatLine.Status -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                line.text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is ChatLine.Error -> Text(
            line.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun android.content.Context.openSettings() {
    startActivity(
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    (this as? Activity)?.finish()
}
