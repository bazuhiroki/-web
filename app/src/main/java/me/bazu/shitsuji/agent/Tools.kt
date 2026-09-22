package me.bazu.shitsuji.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.bazu.shitsuji.data.Decision
import me.bazu.shitsuji.data.Store
import me.bazu.shitsuji.tools.Mercari
import me.bazu.shitsuji.tools.WebScraper

/**
 * エージェントに渡す道具。
 *
 * 道具は少なく保つ。定義そのものが毎リクエストの入力トークンになるし、
 * 選択肢が多いほどモデルは迷う。今は 3 つで足りている。
 */
class Tools(
    private val store: Store,
    private val scraper: WebScraper,
) {
    private val mercari = Mercari(scraper)

    val definitions = buildJsonArray {
        add(buildJsonObject {
            put("name", "mercari_search")
            put(
                "description",
                """
                メルカリを検索し、本人の体格との適合度をつけて返す。

                出品説明文から実寸（肩幅・身幅・着丈・袖丈）を自動抽出し、
                本人の登録済み「当たり服」のレンジと突き合わせた fit_score(0-100) を付けて返す。
                売り切れと予算外は除外済み。適合度の高い順に並んでいる。

                実行には十数秒かかり実費もかかるので、条件が固まってから1回だけ呼ぶこと。
                同じ条件で呼び直さない。
                """.trimIndent(),
            )
            putJsonObject("input_schema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("keyword") {
                        put("type", "string")
                        put("description", "メルカリの検索語。日本語。例: カーディガン ウール")
                    }
                    putJsonObject("category") {
                        put("type", "string")
                        put(
                            "description",
                            "実寸レンジを引くためのカテゴリ名。プロフィールの基準服のカテゴリ名と揃えること。例: カーディガン",
                        )
                    }
                    putJsonObject("price_max") {
                        put("type", "integer")
                        put("description", "上限価格（円）")
                    }
                    putJsonObject("price_min") {
                        put("type", "integer")
                        put("description", "下限価格（円）。指定がなければ省略。")
                    }
                    putJsonObject("detail_limit") {
                        put("type", "integer")
                        put("description", "出品ページを開いて実寸を読む件数。既定12。増やすと遅く高くなる。")
                    }
                }
                putJsonArray("required") { add("keyword") }
            }
        })

        add(buildJsonObject {
            put("name", "remember")
            put(
                "description",
                """
                本人の判断や好みをプロフィールに追記する。次回以降の判定に効く。
                「これは無し」「この形は苦手」のような発言があったら呼ぶこと。
                """.trimIndent(),
            )
            putJsonObject("input_schema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("summary") {
                        put("type", "string")
                        put("description", "対象の短い説明。例: 無印 ネイビーカーディガン ¥3800")
                    }
                    putJsonObject("chose") {
                        put("type", "boolean")
                        put("description", "採用したなら true、見送ったなら false")
                    }
                    putJsonObject("reason") {
                        put("type", "string")
                        put("description", "理由。次回の判断に使える形で。例: 肩幅46cmは大きすぎた")
                    }
                }
                putJsonArray("required") { add("summary"); add("chose"); add("reason") }
            }
        })

        add(buildJsonObject {
            put("name", "scrape_diagnostics")
            put(
                "description",
                """
                mercari_search が0件を返したときだけ呼ぶ。
                実際に取得できたページの冒頭を返すので、ページ構造が変わったのか
                通信に失敗したのかを切り分けられる。
                """.trimIndent(),
            )
            putJsonObject("input_schema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("keyword") { put("type", "string") }
                }
                putJsonArray("required") { add("keyword") }
            }
        })
    }

    /** ツールを1つ実行して、モデルに返す文字列を作る。短く保つこと＝そのままコスト。 */
    suspend fun dispatch(name: String, input: JsonObject): String = try {
        when (name) {
            "mercari_search" -> runSearch(input)
            "remember" -> runRemember(input)
            "scrape_diagnostics" -> runDiagnostics(input)
            else -> "不明なツール: $name"
        }
    } catch (e: Exception) {
        "ツール実行に失敗: ${e.message ?: e::class.simpleName}"
    }

    private suspend fun runSearch(input: JsonObject): String {
        val keyword = input.str("keyword")?.takeIf { it.isNotBlank() }
            ?: return "keyword が空です。"
        val query = Mercari.SearchQuery(
            keyword = keyword,
            priceMin = input.intOrNull("price_min"),
            priceMax = input.intOrNull("price_max"),
            category = input.str("category").orEmpty(),
            detailLimit = (input.intOrNull("detail_limit") ?: 12).coerceIn(3, 20),
        )
        val profile = store.profile()

        val candidates = mercari.search(query, profile).getOrElse { e ->
            return "検索に失敗: ${e.message}"
        }

        if (candidates.isEmpty()) {
            return "条件に合う出品が0件でした（売り切れと予算外を除外した結果）。"
        }

        // モデルに渡すのは絞り込み済みの要約だけ。原文の説明文は渡さない。
        return buildString {
            appendLine("${candidates.size}件（適合度順）:")
            candidates.forEachIndexed { i, c ->
                val l = c.listing
                val price = l.priceYen?.let { "¥%,d".format(it) } ?: "価格不明"
                appendLine()
                appendLine("${i + 1}. $price ${l.title}")
                appendLine("   fit_score=${c.scored.fitScore} (${c.scored.confidence.label}) pref=${c.scored.prefScore}")
                if (c.scored.reasons.isNotEmpty()) {
                    appendLine("   " + c.scored.reasons.joinToString(" / "))
                }
                appendLine("   ${l.url}")
            }
        }.trim()
    }

    private suspend fun runRemember(input: JsonObject): String {
        val summary = input.str("summary").orEmpty()
        val reason = input.str("reason").orEmpty()
        val chose = input["chose"]?.jsonPrimitive?.content?.toBoolean() ?: false
        if (summary.isBlank()) return "summary が空です。"

        val profile = store.profile()
        store.saveProfile(
            profile.copy(
                decisions = (profile.decisions + Decision(
                    itemSummary = summary,
                    chose = chose,
                    reason = reason,
                    atEpochMs = System.currentTimeMillis(),
                )).takeLast(100),
            )
        )
        return "記録しました。"
    }

    private suspend fun runDiagnostics(input: JsonObject): String {
        val keyword = input.str("keyword").orEmpty().ifBlank { "カーディガン" }
        val url = mercari.searchUrl(Mercari.SearchQuery(keyword = keyword))
        val html = scraper.dumpHtml(url).getOrElse { return "ページ取得そのものに失敗: ${it.message}" }
        val anchorCount = Regex("""/item/m\d+""").findAll(html).count()
        return buildString {
            appendLine("URL: $url")
            appendLine("取得サイズ: ${html.length} 文字")
            appendLine("商品リンク(/item/m...)の出現数: $anchorCount")
            appendLine("ログイン要求の形跡: ${if (html.contains("ログイン")) "あり" else "なし"}")
            appendLine("---- 冒頭 600 文字 ----")
            appendLine(html.take(600))
        }
    }
}

private fun JsonObject.intOrNull(key: String): Int? =
    this[key]?.let { runCatching { it.jsonPrimitive.int }.getOrNull() }
