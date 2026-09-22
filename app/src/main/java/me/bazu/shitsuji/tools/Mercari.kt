package me.bazu.shitsuji.tools

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.bazu.shitsuji.data.Profile

/**
 * メルカリ検索。
 *
 * 方針: 自動購入も自動オファーもしない。候補を出して並べるところまで。
 * 買うかどうかのボタンは必ず人間が押す。
 *
 * 取得はアプリの画面自動操作ではなく Web 版の読み取りで行う。
 * アプリを自動操作するより速く、壊れにくく、本人のセッションで動く。
 */
class Mercari(private val scraper: WebScraper) {

    @Serializable
    data class SearchQuery(
        val keyword: String,
        val priceMin: Int? = null,
        val priceMax: Int? = null,
        /** 突き合わせに使うカテゴリ名。"カーディガン" など。 */
        val category: String = "",
        val onSaleOnly: Boolean = true,
        /** 出品ページまで開いて実寸を読む上限件数。多いほど遅く、通信量も増える。 */
        val detailLimit: Int = 12,
    )

    @Serializable
    data class Listing(
        val id: String,
        val title: String,
        val priceYen: Int?,
        val url: String,
        val sold: Boolean = false,
        val description: String = "",
        val measurements: SizeExtractor.Measurements = SizeExtractor.Measurements(),
    )

    data class Candidate(
        val listing: Listing,
        val scored: FitScorer.Scored,
    )

    fun searchUrl(q: SearchQuery): String {
        val b = Uri.parse("https://jp.mercari.com/search").buildUpon()
            .appendQueryParameter("keyword", q.keyword)
        q.priceMin?.let { b.appendQueryParameter("price_min", it.toString()) }
        q.priceMax?.let { b.appendQueryParameter("price_max", it.toString()) }
        if (q.onSaleOnly) b.appendQueryParameter("status", "on_sale")
        return b.build().toString()
    }

    /**
     * 検索 → 実寸抽出 → 体格との突き合わせ → 並べ替え。
     * この一連は LLM を一切呼ばない。モデルに渡すのは絞り込んだ後の上位だけ。
     */
    suspend fun search(q: SearchQuery, profile: Profile): Result<List<Candidate>> {
        val listPage = scraper.evaluate(searchUrl(q), LIST_JS, settleMs = 3_000)
            .getOrElse { return Result.failure(it) }

        val found = parseListings(listPage)
        if (found.isEmpty()) {
            return Result.failure(
                ScrapeFailure("検索結果を1件も取得できませんでした。メルカリ側のページ構造が変わった可能性があります。")
            )
        }

        val range = profile.fitRangeFor(q.category.ifBlank { q.keyword })

        // 先に決定的な条件（売切・価格）でふるいにかけてから、
        // 残った上位だけ出品ページを開く。ここで通信量とトークンの両方が決まる。
        val shortlist = found
            .asSequence()
            .filter { !it.sold }
            .filter { l -> q.priceMax == null || (l.priceYen ?: Int.MAX_VALUE) <= q.priceMax }
            .filter { l -> q.priceMin == null || (l.priceYen ?: 0) >= q.priceMin }
            .take(q.detailLimit)
            .toList()

        val enriched = shortlist.map { listing ->
            scraper.politeDelay()
            val desc = scraper.evaluate(listing.url, DETAIL_JS, settleMs = 2_000)
                .getOrDefault("")
                .take(MAX_DESCRIPTION_CHARS)
            val withDesc = listing.copy(
                description = desc,
                measurements = SizeExtractor.extract("${listing.title} $desc"),
            )
            Candidate(
                listing = withDesc,
                scored = FitScorer.score(
                    m = withDesc.measurements,
                    range = range,
                    preferences = profile.preferences,
                    title = withDesc.title,
                    description = desc,
                ),
            )
        }

        return Result.success(enriched.sortedByDescending { it.scored.total })
    }

    private fun parseListings(json: String): List<Listing> = runCatching {
        JSON.decodeFromString<List<RawItem>>(json).map {
            Listing(
                id = it.id,
                title = it.name,
                priceYen = it.price,
                url = "https://jp.mercari.com/item/${it.id}",
                sold = it.sold,
            )
        }
    }.getOrDefault(emptyList())

    @Serializable
    private data class RawItem(
        val id: String,
        val name: String = "",
        val price: Int? = null,
        val sold: Boolean = false,
    )

    class ScrapeFailure(message: String) : Exception(message)

    companion object {
        private const val MAX_DESCRIPTION_CHARS = 1500
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * 検索結果ページから商品を拾う JS。
         *
         * CSS クラスや data-testid ではなく「/item/mXXXX という URL 形式」を
         * 手がかりにしている。クラス名はリニューアルで毎回変わるが、
         * URL の形はメルカリの外部仕様なのでめったに変わらない。
         */
        const val LIST_JS = """
(function () {
  var seen = {}, out = [];
  var anchors = document.querySelectorAll('a[href*="/item/m"]');
  for (var i = 0; i < anchors.length; i++) {
    var a = anchors[i];
    var href = a.getAttribute('href') || '';
    var idm = href.match(/\/item\/(m\d+)/);
    if (!idm) continue;
    var id = idm[1];
    if (seen[id]) continue;
    seen[id] = 1;

    var label = a.getAttribute('aria-label') || '';
    var text = (a.innerText || '') + ' ' + label;
    var img = a.querySelector('img');
    var alt = img ? (img.getAttribute('alt') || '') : '';

    var priceMatch = text.replace(/,/g, '').match(/(\d{3,8})\s*円/);
    if (!priceMatch) priceMatch = text.replace(/,/g, '').match(/[¥￥]\s*(\d{3,8})/);
    var price = priceMatch ? parseInt(priceMatch[1], 10) : null;

    var sold = /売り切れ|SOLD/i.test(text);

    var name = alt || label || (a.innerText || '');
    name = name.replace(/\s+/g, ' ').trim().slice(0, 120);

    out.push({ id: id, name: name, price: price, sold: sold });
  }
  return JSON.stringify(out);
})();
"""

        /** 出品ページの本文。実寸はほぼここに書いてある。 */
        const val DETAIL_JS = """
(function () {
  var sels = [
    'div[data-testid="description"]',
    'pre[data-testid="description"]',
    'mer-show-more',
    'article'
  ];
  for (var i = 0; i < sels.length; i++) {
    var el = document.querySelector(sels[i]);
    if (el && (el.innerText || '').trim().length > 20) {
      return (el.innerText || '').trim();
    }
  }
  return (document.body.innerText || '').trim();
})();
"""
    }
}
