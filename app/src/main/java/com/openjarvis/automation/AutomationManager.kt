package com.openjarvis.automation

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

class AutomationManager(private val context: Context) {
    private val dao = AutomationDB.getInstance(context).automationDao()
    private val _automationsFlow = MutableStateFlow<List<Automation>>(emptyList())
    val automationsFlow: StateFlow<List<Automation>> = _automationsFlow

    suspend fun loadAutomations() { _automationsFlow.value = dao.getAll() }
    suspend fun createAutomation(automation: Automation): String = withContext(Dispatchers.IO) {
        validateSchedule(automation.schedule)
        dao.insert(automation)
        if (automation.enabled) scheduleAutomation(automation)
        loadAutomations()
        automation.id
    }
    suspend fun updateAutomation(automation: Automation) = withContext(Dispatchers.IO) {
        validateSchedule(automation.schedule)
        dao.update(automation)
        cancelAutomation(automation.id)
        if (automation.enabled) scheduleAutomation(automation)
        loadAutomations()
    }
    suspend fun deleteAutomation(id: String) = withContext(Dispatchers.IO) {
        cancelAutomation(id); dao.delete(id); loadAutomations()
    }
    suspend fun toggleAutomation(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val automation = dao.getById(id) ?: return@withContext
        val updated = automation.copy(enabled = enabled)
        validateSchedule(updated.schedule)
        dao.update(updated)
        if (enabled) scheduleAutomation(updated) else cancelAutomation(id)
        loadAutomations()
    }
    suspend fun runNow(id: String) = withContext(Dispatchers.IO) {
        val automation = dao.getById(id) ?: return@withContext
        dao.update(automation.copy(lastResult = "Non exécuté : moteur d'automatisation non raccordé."))
        loadAutomations()
    }

    private fun scheduleAutomation(automation: Automation) {
        validateSchedule(automation.schedule)
        val input = workDataOf("automation_id" to automation.id, "automation_command" to automation.command)
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(false).build()
        val manager = WorkManager.getInstance(context)
        val schedule = automation.schedule
        if (schedule is AutomationSchedule.Once) {
            val delay = schedule.atMs - System.currentTimeMillis()
            require(delay > 0) { "La date doit être dans le futur." }
            val request = OneTimeWorkRequestBuilder<AutomationWorker>().setInputData(input)
                .setConstraints(constraints).setInitialDelay(delay, TimeUnit.MILLISECONDS).addTag(automation.id).build()
            manager.enqueueUniqueWork(automation.id, ExistingWorkPolicy.REPLACE, request)
            return
        }
        val interval = when (schedule) {
            is AutomationSchedule.Daily -> TimeUnit.DAYS.toMillis(1)
            is AutomationSchedule.Weekly -> TimeUnit.DAYS.toMillis(7)
            is AutomationSchedule.Interval -> schedule.intervalMs
            is AutomationSchedule.Once -> error("Handled above")
        }
        val initialDelay = when (schedule) {
            is AutomationSchedule.Daily -> calendarDelay(null, schedule.hour, schedule.minute)
            is AutomationSchedule.Weekly -> calendarDelay(schedule.dayOfWeek, schedule.hour, schedule.minute)
            else -> 0L
        }
        val request = PeriodicWorkRequestBuilder<AutomationWorker>(interval, TimeUnit.MILLISECONDS)
            .setInputData(input).setConstraints(constraints)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS).addTag(automation.id).build()
        manager.enqueueUniquePeriodicWork(automation.id, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun cancelAutomation(id: String) { WorkManager.getInstance(context).cancelAllWorkByTag(id) }
    private fun calendarDelay(dayOfWeek: Int?, hour: Int, minute: Int): Long {
        val now = System.currentTimeMillis()
        val target = Calendar.getInstance().apply {
            if (dayOfWeek != null) set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, if (dayOfWeek == null) 1 else 7)
        }
        return target.timeInMillis - now
    }
    private fun validateSchedule(schedule: AutomationSchedule) {
        when (schedule) {
            is AutomationSchedule.Daily -> require(schedule.hour in 0..23 && schedule.minute in 0..59)
            is AutomationSchedule.Weekly -> require(schedule.dayOfWeek in 1..7 && schedule.hour in 0..23 && schedule.minute in 0..59)
            is AutomationSchedule.Interval -> require(schedule.intervalMs >= TimeUnit.MINUTES.toMillis(15)) {
                "WorkManager exige au minimum 15 minutes entre deux exécutions."
            }
            is AutomationSchedule.Once -> require(schedule.atMs > 0)
        }
    }

    fun parseSchedule(input: String): AutomationSchedule? {
        val daily = Regex("""every day at (\d{1,2})(?::(\d{2}))?\s*(am|pm)?""", RegexOption.IGNORE_CASE).find(input)
        if (daily != null) {
            var hour = daily.groupValues[1].toIntOrNull() ?: return null
            val minute = daily.groupValues[2].toIntOrNull() ?: 0
            val suffix = daily.groupValues[3].lowercase()
            if (suffix.isNotEmpty()) {
                if (hour !in 1..12) return null
                hour = hour % 12 + if (suffix == "pm") 12 else 0
            }
            return if (hour in 0..23 && minute in 0..59) AutomationSchedule.Daily(hour, minute) else null
        }
        val interval = Regex("""every (\d+)\s*(minute|hour|day)s?""", RegexOption.IGNORE_CASE).find(input) ?: return null
        val value = interval.groupValues[1].toLongOrNull() ?: return null
        val multiplier = when (interval.groupValues[2].lowercase()) {
            "minute" -> 60_000L
            "hour" -> 3_600_000L
            else -> 86_400_000L
        }
        if (value > Long.MAX_VALUE / multiplier) return null
        val ms = value * multiplier
        return if (ms >= 900_000) AutomationSchedule.Interval(ms) else null
    }

    data class Automation(val id: String, val name: String, val command: String,
        val schedule: AutomationSchedule, val enabled: Boolean = true,
        val lastRun: Long? = null, val lastResult: String? = null, val runCount: Int = 0)
    sealed class AutomationSchedule {
        data class Daily(val hour: Int, val minute: Int) : AutomationSchedule()
        data class Weekly(val dayOfWeek: Int, val hour: Int, val minute: Int) : AutomationSchedule()
        data class Interval(val intervalMs: Long) : AutomationSchedule()
        data class Once(val atMs: Long) : AutomationSchedule()
    }
}
