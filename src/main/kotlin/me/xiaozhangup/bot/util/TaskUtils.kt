package me.xiaozhangup.bot.util

import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

object TaskUtils {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun submitDelay(
        time: Long,
        unit: TimeUnit,
        block: suspend Task.() -> Unit
    ): Task {
        lateinit var job: Job
        val task = Task { job }

        job = scope.launch {
            delay(unit.toMillis(time))
            task.block()
        }

        return task
    }

    fun submitRepeat(
        time: Long,
        unit: TimeUnit,
        block: suspend Task.() -> Unit
    ): Task {
        lateinit var job: Job
        val task = Task { job }

        job = scope.launch {
            val delayMillis = unit.toMillis(time)
            while (isActive) {
                task.block()
                delay(delayMillis)
            }
        }

        return task
    }

    class Task internal constructor(
        private val jobProvider: () -> Job
    ) {
        val isActive: Boolean
            get() = jobProvider().isActive

        fun cancel() {
            jobProvider().cancel()
        }
    }
}

fun submitDelay(
    time: Long,
    unit: TimeUnit,
    block: suspend TaskUtils.Task.() -> Unit
): TaskUtils.Task {
    return TaskUtils.submitDelay(time, unit, block)
}

fun submitRepeat(
    time: Long,
    unit: TimeUnit,
    block: suspend TaskUtils.Task.() -> Unit
): TaskUtils.Task {
    return TaskUtils.submitRepeat(time, unit, block)
}

fun submit(
    block: suspend TaskUtils.Task.() -> Unit
): TaskUtils.Task {
    return TaskUtils.submitDelay(0, TimeUnit.MICROSECONDS, block)
}