package com.openjarvis.accessibility

import com.openjarvis.agent.Action
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ActionExecutor(private val service: JarvisAccessibilityService) {
    suspend fun execute(actions: List<Action>): ExecutionResult = withContext(Dispatchers.IO) {
        val results = mutableListOf<ActionResult>()
        for (action in actions) {
            val result = executeStep(action)
            results.add(result)
            if (!result.success) return@withContext ExecutionResult(results.toList(), false, result.errorMessage)
            delay(500)
        }
        ExecutionResult(results.toList(), true, null)
    }

    private fun executeStep(action: Action): ActionResult = when (action.action) {
        Action.OPEN_APP -> {
            val packageName = action.packageName
            val label = action.label
            if (packageName != null) {
                if (service.packageManager.getLaunchIntentForPackage(packageName) == null) {
                    ActionResult(action, false, "App not found: $packageName")
                } else {
                    service.openAppByPackage(packageName)
                    ActionResult(action, true, null)
                }
            } else if (label != null) {
                val success = service.openAppByLabel(label)
                ActionResult(action, success, if (success) null else "App not found: $label")
            } else ActionResult(action, false, "No package or label provided")
        }
        Action.TAP -> {
            val success = action.text?.let { service.tapByText(it) } ?: false
            ActionResult(action, success, if (success) null else "Tap failed")
        }
        Action.TYPE -> {
            val success = action.value?.let { service.typeText(it) } ?: false
            ActionResult(action, success, if (success) null else "Text input failed")
        }
        Action.PRESS_BACK -> globalResult(action, service.pressBack())
        Action.PRESS_HOME -> globalResult(action, service.pressHome())
        Action.PRESS_RECENTS -> globalResult(action, service.pressRecents())
        else -> ActionResult(action, false, "Unsupported action: ${action.action}")
    }

    private fun globalResult(action: Action, success: Boolean) =
        ActionResult(action, success, if (success) null else "Global action failed")

    data class ActionResult(val action: Action, val success: Boolean, val errorMessage: String?)
    data class ExecutionResult(val partialResults: List<ActionResult>, val success: Boolean, val errorMessage: String?)
}
