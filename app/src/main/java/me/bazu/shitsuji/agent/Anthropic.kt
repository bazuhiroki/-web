package me.bazu.shitsuji.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Messages API の最小クライアント。
 *
 * 公式 Java SDK (com.anthropic:anthropic-java) を使っていないのは意図的:
 * あれは jackson-databind と Apache HttpCore 5 を引き込むサーバー JVM 向けで、
 * APK に入れると R8 の keep ルールとメソッド数で手間が増える割に、
 * こちらが必要なのは /v1/messages ただ一本だから。
 * Android 向けの公式 SDK は存在しないので、ここは生 HTTP が正しい選択。
 *
 * レスポンスも sealed class にマップせず JsonElement のまま読む。
 * 将来 API が新しいコンテンツブロック型を足しても実行時に落ちない。
 */
class Anthropic(private val apiKeyProvider: () -> String?) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    data class Usage(
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val cacheCreationInputTokens: Int = 0,
        val cacheReadInputTokens: Int = 0,
    )

    data class Reply(
        /** content 配列そのもの。assistant ターンとしてそのまま履歴に積み直す。 */
        val content: JsonArray,
        val stopReason: String?,
        val usage: Usage,
        val model: String,
    ) {
        val textParts: List<String>
            get() = content.mapNotNull { b ->
                b.jsonObject.takeIf { it.str("type") == "text" }?.str("text")
            }

        val toolUses: List<ToolUse>
            get() = content.mapNotNull { b ->
                val o = b.jsonObject
                if (o.str("type") != "tool_use") return@mapNotNull null
                ToolUse(
                    id = o.str("id") ?: return@mapNotNull null,
                    name = o.str("name") ?: return@mapNotNull null,
                    input = o["input"]?.jsonObject ?: JsonObject(emptyMap()),
                )
            }
    }

    data class ToolUse(val id: String, val name: String, val input: JsonObject)

    class ApiException(val status: Int, val body: String) :
        IOException("Anthropic API error $status: ${body.take(400)}")

    /**
     * 1 往復。
     *
     * @param system システムプロンプトのブロック列。安定した前置きを先頭に置き、
     *   最後のブロックに cache_control を付けてキャッシュ境界にする。
     * @param cacheSystem true のとき system の末尾に cache_control を付ける。
     *   Haiku 4.5 はプレフィックスが 4096 トークン未満だと黙ってキャッシュされない
     *   ので、呼び出し側で長さを確かめてから渡すこと。
     */
    suspend fun send(
        model: String,
        system: List<String>,
        messages: JsonArray,
        tools: JsonArray,
        maxTokens: Int = 2048,
        effort: String? = null,
        cacheSystem: Boolean = false,
    ): Reply = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()
            ?: throw IllegalStateException("APIキーが未設定です。設定画面で登録してください。")

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", buildJsonArray {
                system.forEachIndexed { i, text ->
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                        if (cacheSystem && i == system.lastIndex) {
                            putJsonObject("cache_control") { put("type", "ephemeral") }
                        }
                    })
                }
            })
            put("messages", messages)
            if (tools.isNotEmpty()) put("tools", tools)
            if (effort != null) {
                putJsonObject("output_config") { put("effort", effort) }
            }
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, text)
            val root = json.parseToJsonElement(text).jsonObject
            Reply(
                content = root["content"]?.jsonArray ?: JsonArray(emptyList()),
                stopReason = root.str("stop_reason"),
                model = root.str("model") ?: model,
                usage = root["usage"]?.jsonObject?.let { u ->
                    Usage(
                        inputTokens = u.num("input_tokens"),
                        outputTokens = u.num("output_tokens"),
                        cacheCreationInputTokens = u.num("cache_creation_input_tokens"),
                        cacheReadInputTokens = u.num("cache_read_input_tokens"),
                    )
                } ?: Usage(),
            )
        }
    }

    /**
     * 課金せずにトークン数だけ数える。
     * システムプロンプトが Haiku のキャッシュ閾値 4096 を超えたかの確認に使う。
     */
    suspend fun countTokens(
        model: String,
        system: List<String>,
        messages: JsonArray,
        tools: JsonArray,
    ): Int = withContext(Dispatchers.IO) {
        val key = apiKeyProvider() ?: throw IllegalStateException("APIキーが未設定です。")
        val body = buildJsonObject {
            put("model", model)
            put("system", buildJsonArray {
                system.forEach { add(buildJsonObject { put("type", "text"); put("text", it) }) }
            })
            put("messages", messages)
            if (tools.isNotEmpty()) put("tools", tools)
        }
        val request = Request.Builder()
            .url(COUNT_ENDPOINT)
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, text)
            json.parseToJsonElement(text).jsonObject.num("input_tokens")
        }
    }

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val COUNT_ENDPOINT = "https://api.anthropic.com/v1/messages/count_tokens"
        private const val API_VERSION = "2023-06-01"
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}

internal fun JsonObject.str(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it != "null" }

internal fun JsonObject.num(key: String): Int =
    this[key]?.let { runCatching { it.jsonPrimitive.int }.getOrNull() } ?: 0

internal fun JsonElement.asObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
