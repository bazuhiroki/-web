package me.bazu.shitsuji.tools

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * 画面外 WebView でページを読み、JS で中身を取り出す。
 *
 * なぜサーバー側の headless ブラウザではなく端末の WebView なのか:
 *  1. サーバー代が 0 円。予算の全額を API トークンに回せる。
 *  2. 本人のログインセッションと Cookie がそのまま使える。
 *  3. データセンターの IP ではなく本人の回線・本人の UA から出るので、
 *     bot 判定に引っかかりにくい。
 *
 * 人間が操作するのと同程度の間隔を守ること（[politeDelayMs]）。
 */
class WebScraper(private val appContext: Context) {

    /** 直近に読んだページの生 HTML。セレクタが壊れたときの診断用に保持する。 */
    @Volatile
    var lastRawHtml: String? = null
        private set

    @Volatile
    var lastUrl: String? = null
        private set

    /**
     * [url] を読み込み、描画が落ち着いてから [js] を評価して結果の文字列を返す。
     *
     * @param settleMs onPageFinished からさらに待つ時間。メルカリは SPA なので
     *   DOM が埋まるまで少し要る。
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun evaluate(
        url: String,
        js: String,
        settleMs: Long = 2_500,
        timeoutMs: Long = 30_000,
    ): Result<String> = withContext(Dispatchers.Main) {
        var webView: WebView? = null
        try {
            withTimeout(timeoutMs) {
                val wv = WebView(appContext)
                webView = wv
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = false   // 画像は要らない。通信量と時間の節約。
                    blockNetworkImage = true
                    userAgentString = MOBILE_UA
                }

                suspendCancellableCoroutine { cont ->
                    wv.webViewClient = object : WebViewClient() {
                        private var finished = false
                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (finished) return
                            finished = true
                            view ?: return
                            view.postDelayed({
                                view.evaluateJavascript(js) { raw ->
                                    if (cont.isActive) cont.resume(unquote(raw))
                                }
                            }, settleMs)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ) = null
                    }
                    cont.invokeOnCancellation { wv.stopLoading() }
                    wv.loadUrl(url)
                }
            }.let { result ->
                lastUrl = url
                Result.success(result)
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("ページの読み込みが ${timeoutMs}ms で終わりませんでした: $url"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            webView?.let { wv ->
                wv.stopLoading()
                wv.destroy()
            }
        }
    }

    /** 診断用。ページ全体の HTML をそのまま取る。 */
    suspend fun dumpHtml(url: String, settleMs: Long = 3_000): Result<String> =
        evaluate(url, "document.documentElement.outerHTML", settleMs)
            .onSuccess { lastRawHtml = it }

    /** 連続アクセスの間に挟む待ち時間。人間の閲覧速度を超えないこと。 */
    suspend fun politeDelay() = delay(politeDelayMs)

    companion object {
        /** 出品ページを連続で開くときの最低間隔。 */
        const val politeDelayMs = 1_200L

        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"

        /**
         * evaluateJavascript は JSON エンコードされた文字列を返す。
         * "\"<html>...\"" → <html>...
         */
        fun unquote(raw: String?): String {
            if (raw == null || raw == "null") return ""
            if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
                return raw.substring(1, raw.length - 1)
                    .replace("\\u003C", "<")
                    .replace("\\u003E", ">")
                    .replace("\\u0026", "&")
                    .replace("\\u0027", "'")
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\\\", "\\")
            }
            return raw
        }
    }
}
