package me.bazu.shitsuji

import android.app.Application
import android.content.Context
import me.bazu.shitsuji.agent.Agent
import me.bazu.shitsuji.agent.Anthropic
import me.bazu.shitsuji.agent.Budget
import me.bazu.shitsuji.agent.Tools
import me.bazu.shitsuji.data.Store
import me.bazu.shitsuji.tools.WebScraper

/**
 * 配線。DI ライブラリは入れていない ―― 依存が 6 個しかないので、
 * 手で組んだ方が短く、ビルドも速い。
 */
class Container(appContext: Context) {
    val store = Store(appContext)
    val scraper = WebScraper(appContext)
    val anthropic = Anthropic { store.apiKey() }
    val budget = Budget(store.budgetStore)
    val tools = Tools(store, scraper)

    /** 会話ごとに新しく作る。履歴を持つため使い回さない。 */
    fun newAgent() = Agent(store, anthropic, tools, budget)
}

class ShitsujiApp : Application() {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
    }
}

val Context.container: Container
    get() = (applicationContext as ShitsujiApp).container
