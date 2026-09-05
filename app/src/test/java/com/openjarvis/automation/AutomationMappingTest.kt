package com.openjarvis.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationMappingTest {
    @Test fun schedulesRoundTripWithoutLosingMetadata() {
        val schedules = listOf(
            AutomationManager.AutomationSchedule.Daily(7, 15),
            AutomationManager.AutomationSchedule.Weekly(2, 19, 30),
            AutomationManager.AutomationSchedule.Interval(900_000),
            AutomationManager.AutomationSchedule.Once(1_800_000_000_000)
        )
        for (schedule in schedules) {
            val original = AutomationManager.Automation("fixture", "test", "test command", schedule,
                enabled = false, lastRun = 123L, lastResult = "not executed", runCount = 4)
            assertEquals(original, original.toEntity().toAutomation())
        }
    }

    @Test(expected = IllegalStateException::class)
    fun unknownStoredScheduleIsNotSilentlyInterpreted() {
        AutomationEntity("fixture", "test", "test", "unknown").toAutomation()
    }
}
