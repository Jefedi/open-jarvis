package com.openjarvis.agent
import android.content.Context
import com.openjarvis.murena.AssistantRuntime
import com.openjarvis.murena.IntentTools
import com.openjarvis.murena.Roles
import kotlinx.coroutines.flow.StateFlow

/** Legacy entry points use the same confirmed runtime, never the old unguarded executor. */
class AgentCore(private val context: Context) {
    private val runtime = AssistantRuntime.get(context)
    val state: StateFlow<AgentState> get() = runtime.state
    fun executeTask(command: String) = runtime.submit(command)
    fun getStateFlow(): StateFlow<AgentState> = state
    fun getCurrentProviderName(): String = runtime.provider.value.ifBlank { "Non configuré" }
    suspend fun testConnection(): Result<Long> = runCatching {
        val start = System.nanoTime()
        val profile = runtime.store.chosen(Roles.BRAIN) ?: error("Choisissez un profil principal.")
        runtime.test(profile)
        (System.nanoTime() - start) / 1000000
    }
    suspend fun getAnalyzedAppCount(): Int = IntentTools.apps(context).size
    suspend fun getAIAppCount(): Int = IntentTools.apps(context).count { it.first in setOf("com.openai.chatgpt", "com.anthropic.claude", "ai.mistral.chat") }
}
