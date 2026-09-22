package me.bazu.shitsuji.tools

import me.bazu.shitsuji.data.FitRange
import me.bazu.shitsuji.data.Preferences
import me.bazu.shitsuji.data.Span
import me.bazu.shitsuji.data.normalizeJa
import kotlin.math.roundToInt

/**
 * 実寸と好みの突き合わせ。純粋な計算で、LLM を一切呼ばない。
 *
 * これで 30 件を 10 件に絞ってからモデルに渡すことで、
 * 1 検索あたりの入力トークンを 1/3 に落としている。
 */
object FitScorer {

    data class Scored(
        val fitScore: Int,          // 0..100。実寸の一致度。
        val prefScore: Int,         // -100..100。色・ブランド・素材の好み。
        val confidence: Confidence, // 実寸がどれだけ取れたか。
        val reasons: List<String>,  // 人間にもモデルにも読める根拠。
    ) {
        /** 並べ替えに使う総合点。実寸が取れていない候補は自動的に沈む。 */
        val total: Int
            get() = (fitScore * confidence.weight + prefScore * 0.3).roundToInt()
    }

    enum class Confidence(val weight: Double, val label: String) {
        /** 3 部位以上の実寸あり。 */
        HIGH(1.0, "実寸あり"),
        /** 1〜2 部位。 */
        MEDIUM(0.7, "実寸一部"),
        /** 実寸なし。サイズ表記(M/L)しか手がかりがない。 */
        LOW(0.35, "実寸なし"),
    }

    fun score(
        m: SizeExtractor.Measurements,
        range: FitRange?,
        preferences: Preferences,
        title: String,
        description: String,
    ): Scored {
        val reasons = mutableListOf<String>()

        val confidence = when (m.filledCount) {
            0 -> Confidence.LOW
            1, 2 -> Confidence.MEDIUM
            else -> Confidence.HIGH
        }

        val fit = if (range == null) {
            reasons += "このカテゴリの基準服が未登録のため実寸判定なし"
            50
        } else {
            scoreAgainstRange(m, range, reasons)
        }

        val pref = scorePreferences(preferences, "$title $description", reasons)
        return Scored(fit, pref, confidence, reasons)
    }

    private fun scoreAgainstRange(
        m: SizeExtractor.Measurements,
        range: FitRange,
        reasons: MutableList<String>,
    ): Int {
        // 部位ごとの重み。肩幅が合わないと他が合っていても着られない。
        val parts = listOf(
            Part("肩幅", m.shoulderCm, range.shoulderCm, weight = 3.0, toleranceCm = 2.0),
            Part("身幅", m.chestWidthCm, range.chestWidthCm, weight = 2.0, toleranceCm = 4.0),
            Part("着丈", m.lengthCm, range.lengthCm, weight = 2.0, toleranceCm = 4.0),
            Part("袖丈", m.sleeveCm, range.sleeveCm, weight = 1.0, toleranceCm = 3.0),
        )

        var weighted = 0.0
        var totalWeight = 0.0
        for (p in parts) {
            val value = p.value ?: continue
            val span = p.span ?: continue
            totalWeight += p.weight
            val dev = span.deviation(value)
            // 許容 tolerance を超えた分だけ線形に減点し、2 倍でゼロになる。
            val partScore = (1.0 - (dev / (p.toleranceCm * 2.0))).coerceIn(0.0, 1.0)
            weighted += partScore * p.weight
            reasons += describe(p.label, value, span, dev)
        }

        if (totalWeight == 0.0) {
            reasons += "照合できる実寸がなかった"
            return 50
        }
        return ((weighted / totalWeight) * 100).roundToInt()
    }

    private fun describe(label: String, value: Double, span: Span, dev: Double): String {
        val v = trimNum(value)
        val lo = trimNum(span.min)
        val hi = trimNum(span.max)
        return when {
            dev == 0.0 -> "$label ${v}cm — 適正($lo〜$hi)"
            value < span.min -> "$label ${v}cm — ${trimNum(dev)}cm小さい(適正 $lo〜$hi)"
            else -> "$label ${v}cm — ${trimNum(dev)}cm大きい(適正 $lo〜$hi)"
        }
    }

    private fun trimNum(d: Double): String =
        if (d % 1.0 == 0.0) d.toInt().toString() else String.format("%.1f", d)

    private fun scorePreferences(
        prefs: Preferences,
        haystackRaw: String,
        reasons: MutableList<String>,
    ): Int {
        val hay = haystackRaw.normalizeJa().lowercase()
        var score = 0

        fun hits(terms: List<String>) = terms.filter { it.isNotBlank() && hay.contains(it.normalizeJa().lowercase()) }

        hits(prefs.dislikedBrands).forEach { reasons += "苦手ブランド: $it"; score -= 60 }
        hits(prefs.avoidMaterials).forEach { reasons += "避けたい素材: $it"; score -= 40 }
        hits(prefs.dislikedColors).forEach { reasons += "苦手な色: $it"; score -= 30 }
        hits(prefs.likedBrands).forEach { reasons += "好きなブランド: $it"; score += 40 }
        hits(prefs.likedColors).forEach { reasons += "好きな色: $it"; score += 20 }

        return score.coerceIn(-100, 100)
    }

    private data class Part(
        val label: String,
        val value: Double?,
        val span: Span?,
        val weight: Double,
        val toleranceCm: Double,
    )
}
