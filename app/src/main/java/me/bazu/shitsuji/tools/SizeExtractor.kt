package me.bazu.shitsuji.tools

import kotlinx.serialization.Serializable

/**
 * 出品説明文から実寸を取り出す。
 *
 * ここを LLM ではなく正規表現でやるのがコスト設計の肝。
 * 1回の検索で 30 件 × 500 トークンの説明文を全部モデルに渡すと
 * 月の予算が一瞬で溶ける。日本語の出品文の実寸表記は驚くほど定型なので、
 * 8 割以上はパターンで取れる。取れなかった分だけ LLM に回す。
 */
object SizeExtractor {

    @Serializable
    data class Measurements(
        val shoulderCm: Double? = null,
        val chestWidthCm: Double? = null,
        val lengthCm: Double? = null,
        val sleeveCm: Double? = null,
    ) {
        val isEmpty: Boolean
            get() = shoulderCm == null && chestWidthCm == null && lengthCm == null && sleeveCm == null

        val filledCount: Int
            get() = listOfNotNull(shoulderCm, chestWidthCm, lengthCm, sleeveCm).size
    }

    // 「肩幅」「身幅」等のラベル。表記ゆれを全部ここに集約する。
    private val SHOULDER = listOf("肩幅", "肩巾", "かたはば")
    private val CHEST_WIDTH = listOf("身幅", "身巾", "みはば", "脇下", "胸幅")
    private val LENGTH = listOf("着丈", "身丈", "総丈", "きたけ")
    private val SLEEVE = listOf("袖丈", "そで丈", "裄丈", "ゆき丈", "裄")

    /**
     * 数値部分。「約42.5cm」「42〜43cm」「42センチ」「４２」全部拾う。
     * レンジ表記のときは下限を採る（大きめに見積もって失敗するより安全）。
     */
    private const val NUM = """約?\s*(\d{1,3}(?:[.．]\d)?)"""

    private fun patternFor(labels: List<String>): Regex {
        val alt = labels.joinToString("|") { Regex.escape(it) }
        // ラベル → 区切り(コロン/全角コロン/スペース/スラッシュ/イコール/なし) → 数値 → 単位(任意)
        return Regex("""(?:$alt)\s*[:：=＝/／\s]{0,3}$NUM\s*(?:cm|CM|ｃｍ|センチ|㎝)?""")
    }

    private val SHOULDER_RE = patternFor(SHOULDER)
    private val CHEST_RE = patternFor(CHEST_WIDTH)
    private val LENGTH_RE = patternFor(LENGTH)
    private val SLEEVE_RE = patternFor(SLEEVE)

    fun extract(rawText: String): Measurements {
        val text = normalize(rawText)
        return Measurements(
            shoulderCm = firstPlausible(SHOULDER_RE, text, 25.0, 70.0),
            chestWidthCm = firstPlausible(CHEST_RE, text, 30.0, 80.0),
            lengthCm = firstPlausible(LENGTH_RE, text, 35.0, 120.0),
            sleeveCm = firstPlausible(SLEEVE_RE, text, 15.0, 100.0),
        )
    }

    /**
     * 最初にマッチした「ありえる範囲の」値を返す。
     * 説明文には「身長170cm」「送料800円」のような無関係な数字が混ざるので、
     * 部位ごとの物理的な上下限でふるいにかける。
     */
    private fun firstPlausible(re: Regex, text: String, min: Double, max: Double): Double? =
        re.findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.replace('．', '.')?.toDoubleOrNull() }
            .firstOrNull { it in min..max }

    /** 全角数字・全角記号を半角に寄せ、改行を空白に潰す。 */
    private fun normalize(s: String): String = buildString(s.length) {
        for (ch in s) {
            val c = when (ch) {
                in '０'..'９' -> ch - 0xFEE0
                '　' -> ' '
                '\n', '\r', '\t' -> ' '
                else -> ch
            }
            append(c)
        }
    }
}
