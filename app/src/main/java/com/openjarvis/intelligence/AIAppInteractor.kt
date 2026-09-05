package com.openjarvis.intelligence

import android.content.Context
import com.openjarvis.accessibility.JarvisAccessibilityService
import com.openjarvis.accessibility.ScreenReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class AIAppInteractor(private val context: Context) {
    private val screenReader = ScreenReader(context)
    private val workingMemory = TaskWorkingMemory()

    fun openAIApp(meta: AIAppMeta): Boolean {
        val service = JarvisAccessibilityService.instance ?: return false
        if (context.packageManager.getLaunchIntentForPackage(meta.packageName) == null) return false
        return try { service.openAppByPackage(meta.packageName); true }
        catch (_: Exception) { false }
    }

    suspend fun clearContext() {
        try {
            JarvisAccessibilityService.instance?.pressBack()
            delay(300)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }

    fun typePrompt(prompt: String): Boolean = JarvisAccessibilityService.instance?.typeText(prompt) ?: false

    suspend fun waitForResponse(timeoutMs: Long = 60_000): String {
        val start = System.currentTimeMillis()
        var lastText = ""
        var sameCount = 0
        while (System.currentTimeMillis() - start < timeoutMs) {
            delay(1000)
            val current = screenReader.extractAllText()
            if (current == lastText && current.isNotBlank()) {
                sameCount++
                if (sameCount >= 2) return current
            } else { sameCount = 0; lastText = current }
        }
        return lastText
    }

    fun extractResponse(meta: AIAppMeta): String = when (meta.responseExtraction) {
        ResponseExtraction.SCREEN_TEXT, ResponseExtraction.OCR_REQUIRED,
        ResponseExtraction.COPY_BUTTON, ResponseExtraction.SHARE_MENU -> screenReader.extractAllText()
    }
    fun getWorkingMemory(): TaskWorkingMemory = workingMemory
}
