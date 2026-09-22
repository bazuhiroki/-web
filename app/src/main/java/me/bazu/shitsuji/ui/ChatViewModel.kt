package me.bazu.shitsuji.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import me.bazu.shitsuji.Container
import me.bazu.shitsuji.agent.Agent
import me.bazu.shitsuji.container

sealed interface ChatLine {
    data class Me(val text: String) : ChatLine
    data class Agent(val text: String) : ChatLine
    data class Status(val text: String) : ChatLine
    data class Error(val text: String) : ChatLine
}

class ChatViewModel(private val container: Container) : ViewModel() {

    val lines = mutableStateListOf<ChatLine>()

    var busy by mutableStateOf(false)
        private set

    var budgetLabel by mutableStateOf("")
        private set

    private var budgetFractionState by mutableFloatStateOf(0f)
    val budgetFraction: Float get() = budgetFractionState

    private val agent = container.newAgent()

    init {
        refreshBudget()
        if (!container.store.hasApiKey()) {
            lines += ChatLine.Error("APIキーが未設定です。右上の設定から登録してください。")
        }
    }

    private fun refreshBudget() {
        viewModelScope.launch {
            val s = container.budget.state()
            budgetLabel = "今月 ¥${s.spentJpy} / ¥${s.capJpy}（${s.calls}回）"
            budgetFractionState = s.usedFraction
        }
    }

    fun send(text: String) {
        if (busy) return
        lines += ChatLine.Me(text)
        busy = true

        viewModelScope.launch {
            try {
                agent.run(text).collect { event ->
                    when (event) {
                        is Agent.Event.Say ->
                            lines += ChatLine.Agent(event.text)

                        is Agent.Event.ToolStart ->
                            lines += ChatLine.Status("… ${event.summary}")

                        is Agent.Event.ToolEnd ->
                            if (!event.ok) lines += ChatLine.Status("（${event.name} は失敗）")

                        is Agent.Event.Done -> {
                            budgetLabel = "今月 ¥${event.state.spentJpy} / ¥${event.state.capJpy}（${event.state.calls}回）"
                            budgetFractionState = event.state.usedFraction
                        }

                        is Agent.Event.Failed ->
                            lines += ChatLine.Error(event.message)
                    }
                }
            } catch (e: Exception) {
                lines += ChatLine.Error("想定外のエラー: ${e.message ?: e::class.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val c = context.container
            return viewModelFactory {
                initializer { ChatViewModel(c) }
            }
        }
    }
}
