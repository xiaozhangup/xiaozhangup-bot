package me.xiaozhangup.bot.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ScheduledUtils {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scheduledTasks = ConcurrentHashMap<String, ScheduledTaskInfo>()
    private var checkerTask: TaskUtils.Task? = null
    private var checkIntervalSeconds: Long = 40

    data class ScheduledTaskInfo(
        val hour: Int,
        val minute: Int,
        val task: suspend () -> Unit,
        var lastExecutedDate: String? = null
    )

    fun start(checkIntervalSeconds: Long = 40) {
        this.checkIntervalSeconds = checkIntervalSeconds
        if (checkerTask?.isActive == true) {
            return
        }

        checkerTask = submitRepeat(checkIntervalSeconds, TimeUnit.SECONDS) {
            checkAndExecuteTasks()
        }
    }

    fun stop() {
        checkerTask?.cancel()
        checkerTask = null
    }

    fun registerTask(
        id: String,
        hour: Int,
        minute: Int,
        task: suspend () -> Unit
    ) {
        require(hour in 0..23) { "Hour must be between 0 and 23" }
        require(minute in 0..59) { "Minute must be between 0 and 59" }

        scheduledTasks[id] = ScheduledTaskInfo(hour, minute, task)
    }

    fun unregisterTask(id: String) {
        scheduledTasks.remove(id)
    }

    private fun checkAndExecuteTasks() {
        val now = LocalTime.now()
        val currentHour = now.hour
        val currentMinute = now.minute
        val currentDate = java.time.LocalDate.now().toString()

        scheduledTasks.forEach { (id, info) ->
            if (info.hour == currentHour && info.minute == currentMinute) {
                if (info.lastExecutedDate != currentDate) {
                    scope.launch {
                        try {
                            info.task()
                            info.lastExecutedDate = currentDate
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }
}

fun registerScheduled(
    id: String,
    hour: Int,
    minute: Int,
    task: suspend () -> Unit
) {
    ScheduledUtils.registerTask(id, hour, minute, task)
}

fun unregisterScheduled(id: String) {
    ScheduledUtils.unregisterTask(id)
}
