package me.xiaozhangup.bot.func

import me.xiaozhangup.bot.client.ow.OpenWeatherClient
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.*

class WeatherReminder : EventUnit(
    "weather_reminder",
    "天气提醒工具",
    1
) {
    private val config by lazy { properties("weather_reminder") }
    private val weatherClient by lazy {
        val apiKey = config.getProperty("api.key")
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("OpenWeather API key is not set in weather_reminder.properties")
        }
        OpenWeatherClient(apiKey)
    }

    private val targetGroups by lazy {
        config.getProperty("target.groups")?.split(',')?.map { it.trim() } ?: listOf()
    }

    private val city by lazy {
        config.getProperty("city", "Beijing")
    }

    init {
        registerScheduled("weather_reminder_task", 7, 30) { sendWeatherReport() }
        info("[WeatherReminder] Weather reminder initialized. Target groups: $targetGroups, City: $city")
    }

    private fun sendWeatherReport() {
        try {
            info("[WeatherReminder] Fetching weather for $city...")
            val weather = weatherClient.getWeather(city)

            val message = buildString {
                append("☀️ 早安！今日天气播报\n")
                append("━━━━━━━━━━━━━━━\n")
                append("📍 城市: ${weather.name}\n")
                append("🌡️ 温度: ${weather.main.temp}°C\n")
                append("🤔 体感: ${weather.main.feelsLike}°C\n")
                append("📊 温度范围: ${weather.main.temp_min}°C ~ ${weather.main.temp_max}°C\n")
                append("💧 湿度: ${weather.main.humidity}%\n")
                append("🌀 气压: ${weather.main.pressure} hPa\n")
                append("💨 风速: ${weather.wind.speed} m/s\n")
                if (weather.weather.isNotEmpty()) {
                    val desc = weather.weather[0]
                    append("☁️ 天气: ${desc.description}\n")
                }
                append("━━━━━━━━━━━━━━━\n")
                append("祝你有美好的一天！")
            }

            // 向所有目标群发送天气信息
            targetGroups.forEach { groupId ->
                val group = getGroup(groupId)
                if (group != null) {
                    group.sendMessage(message)
                } else {
                    warning("[WeatherReminder] Group not found: $groupId")
                }
            }
        } catch (e: Exception) {
            warning("[WeatherReminder] Failed to send weather report: ${e.message}")
            e.printStackTrace()
        }
    }
}

