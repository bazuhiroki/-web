package me.bazu.shitsuji.data

import kotlinx.serialization.Serializable

/**
 * 「私に似合うか」を機械的に判定するための土台。
 *
 * 設計の要点: 身長体重だけでは「似合う」は判定できない。効くのは
 * [FitReference] ―― 本人が「これは似合う」と確認済みの手持ち服の実寸。
 * 出品説明文から拾った実寸をこのレンジと突き合わせることで、
 * LLM の当てずっぽうではなく数値の比較で判定できる。
 */
@Serializable
data class Profile(
    val body: Body = Body(),
    /** 「これは似合う」と本人が確認済みの手持ち服。カテゴリごとに数着。 */
    val fitReferences: List<FitReference> = emptyList(),
    val preferences: Preferences = Preferences(),
    /** 上のどれにも当てはまらない自由記述。システムプロンプトにそのまま載る。 */
    val freeNotes: String = "",
    /** 過去に本人が下した判断の履歴。同じ失敗を繰り返さないための教師データ。 */
    val decisions: List<Decision> = emptyList(),
) {
    /** 指定カテゴリの許容レンジを、登録済みの当たり服から導出する。 */
    fun fitRangeFor(category: String): FitRange? {
        val refs = fitReferences.filter { it.category.equalsLoose(category) && it.verdict != Verdict.TOO_OFF }
        if (refs.isEmpty()) return null
        return FitRange(
            category = category,
            shoulderCm = refs.spanOf { it.shoulderCm },
            chestWidthCm = refs.spanOf { it.chestWidthCm },
            lengthCm = refs.spanOf { it.lengthCm },
            sleeveCm = refs.spanOf { it.sleeveCm },
            sampleSize = refs.size,
        )
    }

    private fun List<FitReference>.spanOf(pick: (FitReference) -> Double?): Span? {
        val values = mapNotNull(pick)
        if (values.isEmpty()) return null
        // 1着しか登録がないときは ±TOLERANCE を広めに取る。標本が増えるほど絞る。
        val slack = if (values.size == 1) SINGLE_SAMPLE_SLACK_CM else MULTI_SAMPLE_SLACK_CM
        return Span(min = values.min() - slack, max = values.max() + slack)
    }

    companion object {
        const val SINGLE_SAMPLE_SLACK_CM = 3.0
        const val MULTI_SAMPLE_SLACK_CM = 1.5
    }
}

@Serializable
data class Body(
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val shoulderCm: Double? = null,
    val chestCm: Double? = null,
    val waistCm: Double? = null,
    val armLengthCm: Double? = null,
    /** 「なで肩」「腕が長め」など、数値に出ない特徴。 */
    val notes: String = "",
)

@Serializable
enum class Verdict {
    /** ちょうど良い。レンジの中心。 */
    GOOD,
    /** 許容範囲。レンジの端。 */
    ACCEPTABLE,
    /** 合わない。レンジ算出から除外する。 */
    TOO_OFF,
}

/** 本人が着たことのある実物の服と、その実寸。 */
@Serializable
data class FitReference(
    val label: String,
    /** "カーディガン" "シャツ" "パンツ" など。表記ゆれは equalsLoose で吸収。 */
    val category: String,
    val verdict: Verdict = Verdict.GOOD,
    val shoulderCm: Double? = null,
    val chestWidthCm: Double? = null,
    val lengthCm: Double? = null,
    val sleeveCm: Double? = null,
    val note: String = "",
)

@Serializable
data class Preferences(
    val likedColors: List<String> = emptyList(),
    val dislikedColors: List<String> = emptyList(),
    val likedBrands: List<String> = emptyList(),
    val dislikedBrands: List<String> = emptyList(),
    val avoidMaterials: List<String> = emptyList(),
    /** 「ドロップショルダーは肩が落ちすぎる」など形の好み。 */
    val silhouetteNotes: String = "",
)

@Serializable
data class Decision(
    val itemSummary: String,
    val chose: Boolean,
    val reason: String,
    val atEpochMs: Long = 0L,
)

/** カテゴリの表記ゆれ吸収。全角半角・大小文字・空白を無視して比較する。 */
fun String.equalsLoose(other: String): Boolean =
    normalizeJa().equals(other.normalizeJa(), ignoreCase = true)

/** 全角英数を半角に、空白と記号を落とす。 */
fun String.normalizeJa(): String = buildString {
    for (ch in this@normalizeJa) {
        val c = when (ch) {
            in '０'..'９' -> ch - 0xFEE0  // 全角数字
            in 'Ａ'..'Ｚ' -> ch - 0xFEE0  // 全角大文字
            in 'ａ'..'ｚ' -> ch - 0xFEE0  // 全角小文字
            '　' -> ' '
            else -> ch
        }
        if (!c.isWhitespace() && c != '・' && c != '-' && c != 'ー') append(c)
    }
}

@Serializable
data class Span(val min: Double, val max: Double) {
    fun contains(v: Double) = v in min..max
    /** レンジからの逸脱量(cm)。範囲内なら 0。 */
    fun deviation(v: Double): Double = when {
        v < min -> min - v
        v > max -> v - max
        else -> 0.0
    }
}

/** あるカテゴリについての、この人の許容実寸レンジ。 */
@Serializable
data class FitRange(
    val category: String,
    val shoulderCm: Span? = null,
    val chestWidthCm: Span? = null,
    val lengthCm: Span? = null,
    val sleeveCm: Span? = null,
    val sampleSize: Int = 0,
)
