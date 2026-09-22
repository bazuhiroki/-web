package me.bazu.shitsuji.agent

import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 使えるモデルと、その値段。
 *
 * 月 500 円という上限がモデル選択をほぼ決めている。
 * Haiku 4.5 を常用機にし、判断が要る場面だけ Opus 5 に上げる。
 */
enum class ModelChoice(
    val id: String,
    val label: String,
    /** USD / 100万入力トークン */
    val inputPerMTok: Double,
    /** USD / 100万出力トークン */
    val outputPerMTok: Double,
    /** キャッシュが効く最小プレフィックス長(トークン)。これ未満だと黙って効かない。 */
    val minCacheablePrefixTokens: Int,
) {
    HAIKU(
        id = "claude-haiku-4-5",
        label = "Haiku 4.5（常用）",
        inputPerMTok = 1.00,
        outputPerMTok = 5.00,
        minCacheablePrefixTokens = 4096,
    ),
    OPUS(
        id = "claude-opus-5",
        label = "Opus 5（難しい判断のみ）",
        inputPerMTok = 5.00,
        outputPerMTok = 25.00,
        minCacheablePrefixTokens = 512,
    );

    /** キャッシュ書き込みは基本入力単価の 1.25 倍（5分TTL）。 */
    val cacheWritePerMTok: Double get() = inputPerMTok * 1.25

    /** キャッシュ読み出しは基本入力単価の 0.1 倍。 */
    val cacheReadPerMTok: Double get() = inputPerMTok * 0.10

    fun costUsd(u: Anthropic.Usage): Double =
        u.inputTokens / 1_000_000.0 * inputPerMTok +
            u.outputTokens / 1_000_000.0 * outputPerMTok +
            u.cacheCreationInputTokens / 1_000_000.0 * cacheWritePerMTok +
            u.cacheReadInputTokens / 1_000_000.0 * cacheReadPerMTok

    companion object {
        fun byId(id: String): ModelChoice = entries.firstOrNull { it.id == id } ?: HAIKU
    }
}

/**
 * 月次の使用額を見張る。
 *
 * 「気づいたら1万円」を構造的に防ぐのが目的なので、見積もりではなく
 * レスポンスの usage を積む実測方式。上限に達したらエージェントは止まる。
 */
class Budget(
    private val store: BudgetStore,
) {
    data class State(
        val monthKey: String,
        val spentUsd: Double,
        val capJpy: Int,
        val usdJpy: Double,
        val calls: Int,
    ) {
        val spentJpy: Int get() = (spentUsd * usdJpy).roundToInt()
        val remainingJpy: Int get() = (capJpy - spentJpy).coerceAtLeast(0)
        val exhausted: Boolean get() = spentJpy >= capJpy
        val usedFraction: Float get() = (spentJpy.toFloat() / capJpy.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    interface BudgetStore {
        suspend fun read(monthKey: String): Pair<Double, Int>
        suspend fun write(monthKey: String, spentUsd: Double, calls: Int)
        suspend fun capJpy(): Int
        suspend fun usdJpy(): Double
    }

    suspend fun state(): State {
        val key = currentMonthKey()
        val (spent, calls) = store.read(key)
        return State(key, spent, store.capJpy(), store.usdJpy(), calls)
    }

    /** 呼び出し前のゲート。残額がなければ false。 */
    suspend fun canSpend(): Boolean = !state().exhausted

    /** 呼び出し後に実測を積む。 */
    suspend fun record(model: ModelChoice, usage: Anthropic.Usage): State {
        val key = currentMonthKey()
        val (spent, calls) = store.read(key)
        val next = spent + model.costUsd(usage)
        store.write(key, next, calls + 1)
        return State(key, next, store.capJpy(), store.usdJpy(), calls + 1)
    }

    companion object {
        fun currentMonthKey(): String {
            val c = Calendar.getInstance()
            return String.format(Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
        }
    }
}
