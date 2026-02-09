package me.xiaozhangup.bot.util

import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

object TaskUtils {

    fun submitDelay(
        time: Long,
        unit: TimeUnit,
        block: suspend Task.() -> Unit
    ): Task {
        lateinit var job: Job
        lateinit var task: Task

        job = GlobalScope.launch {
            delay(unit.toMillis(time))
            task.block()
        }

        task = Task(job)
        return task
    }

    fun submitRepeat(
        time: Long,
        unit: TimeUnit,
        block: suspend Task.() -> Unit
    ): Task {
        lateinit var job: Job
        lateinit var task: Task

        job = GlobalScope.launch {
            val delayMillis = unit.toMillis(time)
            while (isActive) {
                task.block()
                delay(delayMillis)
            }
        }

        task = Task(job)
        return task
    }

    fun submit(
        block: suspend Task.() -> Unit
    ): Task {
        lateinit var job: Job
        lateinit var task: Task

        job = GlobalScope.launch {
            task.block()
        }

        task = Task(job)
        return task
    }

    class Task internal constructor(
        private val job: Job
    ) {
        fun cancel() {
            job.cancel()
        }

        val isActive: Boolean
            get() = job.isActive
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
    return TaskUtils.submit(block)
}