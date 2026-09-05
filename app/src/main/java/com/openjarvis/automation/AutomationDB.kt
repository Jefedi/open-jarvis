package com.openjarvis.automation

import android.content.Context
import androidx.room.*
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException

@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val command: String,
    val scheduleType: String,
    val scheduleHour: Int = 0,
    val scheduleMinute: Int = 0,
    val scheduleDayOfWeek: Int = 0,
    val scheduleIntervalMs: Long = 0,
    val enabled: Boolean = true,
    val lastRun: Long? = null,
    val lastResult: String? = null,
    val runCount: Int = 0
)

// Explicit mapping keeps Room's stored entity distinct from the domain schedule.
internal fun AutomationEntity.toAutomation(): AutomationManager.Automation {
    val schedule = when (scheduleType) {
        "daily" -> AutomationManager.AutomationSchedule.Daily(scheduleHour, scheduleMinute)
        "weekly" -> AutomationManager.AutomationSchedule.Weekly(scheduleDayOfWeek, scheduleHour, scheduleMinute)
        "interval" -> AutomationManager.AutomationSchedule.Interval(scheduleIntervalMs)
        "once" -> AutomationManager.AutomationSchedule.Once(scheduleIntervalMs)
        else -> error("Type de planification inconnu : $scheduleType")
    }
    return AutomationManager.Automation(id, name, command, schedule, enabled, lastRun, lastResult, runCount)
}

internal fun AutomationManager.Automation.toEntity(): AutomationEntity {
    val common = AutomationEntity(id, name, command, "", enabled = enabled,
        lastRun = lastRun, lastResult = lastResult, runCount = runCount)
    return when (val value = schedule) {
        is AutomationManager.AutomationSchedule.Daily -> common.copy(scheduleType = "daily", scheduleHour = value.hour, scheduleMinute = value.minute)
        is AutomationManager.AutomationSchedule.Weekly -> common.copy(scheduleType = "weekly", scheduleDayOfWeek = value.dayOfWeek, scheduleHour = value.hour, scheduleMinute = value.minute)
        is AutomationManager.AutomationSchedule.Interval -> common.copy(scheduleType = "interval", scheduleIntervalMs = value.intervalMs)
        is AutomationManager.AutomationSchedule.Once -> common.copy(scheduleType = "once", scheduleIntervalMs = value.atMs)
    }
}

@Dao
abstract class AutomationDao {
    @Query("SELECT * FROM automations ORDER BY name")
    protected abstract suspend fun getAllEntities(): List<AutomationEntity>

    @Query("SELECT * FROM automations WHERE id = :id")
    protected abstract suspend fun getEntityById(id: String): AutomationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertEntity(automation: AutomationEntity)

    @Update
    protected abstract suspend fun updateEntity(automation: AutomationEntity)

    @Query("DELETE FROM automations WHERE id = :id")
    abstract suspend fun delete(id: String)

    suspend fun getAll(): List<AutomationManager.Automation> = getAllEntities().map { it.toAutomation() }
    suspend fun getById(id: String): AutomationManager.Automation? = getEntityById(id)?.toAutomation()
    suspend fun insert(automation: AutomationManager.Automation) = insertEntity(automation.toEntity())
    suspend fun update(automation: AutomationManager.Automation) = updateEntity(automation.toEntity())
}

@Database(entities = [AutomationEntity::class], version = 1, exportSchema = false)
abstract class AutomationDB : RoomDatabase() {
    abstract fun automationDao(): AutomationDao

    companion object {
        @Volatile private var INSTANCE: AutomationDB? = null
        fun getInstance(context: Context): AutomationDB = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AutomationDB::class.java,
                "automations.db").build().also { INSTANCE = it }
        }
    }
}

class AutomationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("automation_id") ?: return Result.failure()
        return try {
            val dao = AutomationDB.getInstance(applicationContext).automationDao()
            val automation = dao.getById(id) ?: return Result.failure()
            if (!automation.enabled) return Result.failure()
            // The upstream worker only slept and reported success; it never executed the command.
            // Do not pretend that a background action happened until an executor is wired and tested.
            val message = "Non exécuté : moteur d'automatisation non raccordé."
            dao.update(automation.copy(lastResult = message))
            Result.failure(workDataOf("error" to message))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure()
        }
    }
}
