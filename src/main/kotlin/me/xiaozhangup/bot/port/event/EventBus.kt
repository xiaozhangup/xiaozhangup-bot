package me.xiaozhangup.bot.port.event

import me.xiaozhangup.bot.port.unit.EventUnit

object EventBus {
    private val events = mutableMapOf<String, EventUnit>()
    private val trigger = EventTrigger()

    fun register(eventUnit: EventUnit) {
        if (!events.containsKey(eventUnit.id)) {
            events[eventUnit.id] = eventUnit
        } else {
            throw IllegalArgumentException("EventUnit with id ${eventUnit.id} is already registered.")
        }
    }

    fun unregister(eventUnit: EventUnit) {
        unregister(eventUnit.id)
    }

    fun unregister(id: String) {
        events.remove(id)
    }

    fun getEvent(id: String): EventUnit? {
        return events[id]
    }

    fun getEvents(): Collection<EventUnit> {
        return events.values
    }

    fun getTrigger(): EventTrigger {
        return trigger
    }
}