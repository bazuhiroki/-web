package me.bazu.shitsuji.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.bazu.shitsuji.data.Store

/**
 * tool use のループ本体。
 *
 * 作りは意図的に素朴（手書きの while ループ）。Android 向けの公式 SDK は無く、
 * この loop は 60 行で書けて、挙動が全部見える。ここが読めないと
 * トークンがどこで消えているかも追えなくなる。
 */
class Agent(
    private val store: Store,
    private val anthropic: Anthropic,
    private val tools: Tools,
    private val budget: Budget,
) {
    sealed interface Event {
        /** モデルの発話。 */
        data class Say(val text: String) : Event
        /** ツールを呼び始めた。UI に「メルカリを見ています…」と出すため。 */
        data class ToolStart(val name: String, val summary: String) : Event
        data class ToolEnd(val name: String, val ok: Boolean) : Event
        /** 1 ターン終了。使用額つき。 */
        data class Done(val state: Budget.State) : Event
        data class Failed(val message: String) : Event
    }

    /** 会話履歴。ChatActivity が生きている間だけ保つ。 */
    private val history = mutableListOf<JsonObject>()

    /**
     * システムプレフィックスがキャッシュ閾値を超えているか。
     * 一度だけ count_tokens で測って覚える（プロフィール編集で無効化される）。
     */
    private var cacheableChecked: String? = null
    private var cacheable = false

    fun reset() {
        history.clear()
        cacheableChecked = null
    }

    fun run(userText: String): Flow<Event> = flow {
        val state = budget.state()
        if (state.exhausted) {
            emit(
                Event.Failed(
                    "今月の上限 ¥${state.capJpy} に到達したため停止しています。" +
                        "設定で上限を上げるか、来月まで待ってください。"
                )
            )
            return@flow
        }

        val profile = store.profile()
        val settings = store.settings()
        val model = ModelChoice.byId(settings.defaultModelId)
        val system = SystemPrompt.build(profile)

        ensureCacheDecision(model, system, profile.hashCode().toString())

        history += userMessage(userText)

        var turns = 0
        var lastState = state
        while (turns < MAX_TURNS) {
            turns++

            if (!budget.canSpend()) {
                emit(Event.Failed("途中で今月の上限に達しました。ここまでの結果で判断してください。"))
                return@flow
            }

            val reply = try {
                anthropic.send(
                    model = model.id,
                    system = system,
                    messages = JsonArray(history.toList()),
                    tools = tools.definitions,
                    maxTokens = MAX_OUTPUT_TOKENS,
                    // Opus に上げたときだけ effort を絞る。Haiku は effort 非対応。
                    effort = if (model == ModelChoice.OPUS) "low" else null,
                    cacheSystem = cacheable,
                )
            } catch (e: Anthropic.ApiException) {
                emit(Event.Failed(describeApiError(e)))
                return@flow
            } catch (e: Exception) {
                emit(Event.Failed("通信に失敗しました: ${e.message ?: e::class.simpleName}"))
                return@flow
            }

            lastState = budget.record(model, reply.usage)

            // assistant ターンをそのまま積み直す。tool_use ブロックを落とすと次が通らない。
            history += buildJsonObject {
                put("role", "assistant")
                put("content", reply.content)
            }

            reply.textParts.filter { it.isNotBlank() }.forEach { emit(Event.Say(it)) }

            val calls = reply.toolUses
            if (calls.isEmpty() || reply.stopReason != "tool_use") {
                emit(Event.Done(lastState))
                return@flow
            }

            // 並列に呼ばれた分はまとめて 1 つの user メッセージで返す。
            // 分割して返すとモデルが並列呼び出しをしなくなる。
            val results = mutableListOf<JsonObject>()
            for (call in calls) {
                emit(Event.ToolStart(call.name, summarize(call)))
                val output = tools.dispatch(call.name, call.input)
                val failed = output.startsWith("ツール実行に失敗") || output.startsWith("検索に失敗")
                emit(Event.ToolEnd(call.name, !failed))
                results += buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", call.id)
                    put("content", output)
                    if (failed) put("is_error", true)
                }
            }
            history += buildJsonObject {
                put("role", "user")
                put("content", JsonArray(results))
            }
        }

        emit(Event.Failed("ツール呼び出しが $MAX_TURNS 往復を超えたため打ち切りました。"))
        emit(Event.Done(lastState))
    }

    /**
     * Haiku 4.5 はプレフィックスが 4096 トークン未満だと、cache_control を付けても
     * 黙って無視される（エラーは出ず、cache_creation_input_tokens が 0 になるだけ）。
     * 無駄な 1.25 倍の書き込み料金を払わないよう、事前に測ってから決める。
     */
    private suspend fun ensureCacheDecision(model: ModelChoice, system: List<String>, stamp: String) {
        if (cacheableChecked == stamp) return
        cacheableChecked = stamp
        cacheable = runCatching {
            val n = anthropic.countTokens(
                model = model.id,
                system = system,
                messages = buildJsonArray { add(userMessage("x")) },
                tools = tools.definitions,
            )
            n >= model.minCacheablePrefixTokens
        }.getOrDefault(false)
    }

    private fun userMessage(text: String): JsonObject = buildJsonObject {
        put("role", "user")
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }
    }

    private fun summarize(call: Anthropic.ToolUse): String = when (call.name) {
        "mercari_search" -> {
            val kw = call.input.str("keyword").orEmpty()
            val max = call.input["price_max"]?.toString()?.takeIf { it != "null" }
            if (max != null) "メルカリ検索: $kw（〜¥$max）" else "メルカリ検索: $kw"
        }
        "remember" -> "覚えています"
        "scrape_diagnostics" -> "取得状況を確認中"
        else -> call.name
    }

    private fun describeApiError(e: Anthropic.ApiException): String = when (e.status) {
        401 -> "APIキーが拒否されました。設定画面で入れ直してください。"
        429 -> "レート上限に当たりました。少し待ってからもう一度。"
        400 -> "リクエストが不正でした（${e.body.take(200)}）"
        in 500..599 -> "Anthropic 側で一時的な障害が起きています。少し待ってからもう一度。"
        else -> "API エラー ${e.status}: ${e.body.take(200)}"
    }

    companion object {
        /** ツール往復の上限。暴走してトークンを溶かさないための安全弁。 */
        const val MAX_TURNS = 8
        const val MAX_OUTPUT_TOKENS = 1536
    }
}
