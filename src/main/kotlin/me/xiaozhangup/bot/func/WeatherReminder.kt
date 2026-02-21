package me.xiaozhangup.bot.func

import me.xiaozhangup.bot.client.ow.OpenWeatherClient
import me.xiaozhangup.bot.client.peapix.PeapixClient
import me.xiaozhangup.bot.port.msg.obj.ImageComponent
import me.xiaozhangup.bot.port.msg.obj.StringComponent
import me.xiaozhangup.bot.port.unit.EventUnit
import me.xiaozhangup.bot.util.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    private val peapixClient by lazy { PeapixClient() }

    private val targetGroups by lazy {
        config.getProperty("target.groups")?.split(',')?.map { it.trim() } ?: listOf()
    }

    private val city by lazy {
        config.getProperty("city", "Beijing")
    }

    init {
        registerScheduled("weather_reminder", 7, 30) { sendWeatherReport() }
        info("[WeatherReminder] Weather reminder initialized. Target groups: $targetGroups, City: $city")
    }

    private fun sendWeatherReport() {
        try {
            info("[WeatherReminder] Fetching weather for $city...")
            val weather = weatherClient.getWeather(city)
            val image = peapixClient.getBingImage("cn", 1).random()

            val message = buildString {
                val desc = weather.weather.firstOrNull()?.description ?: ""
                val temp = weather.main.temp
                val feelsLike = weather.main.feelsLike

                val advice = when {
                    desc.contains("雨") -> "有雨，出门请记得带伞"
                    desc.contains("雪") -> "路面可能湿滑，请注意出行安全"
                    feelsLike < 10 -> "天气较冷，请注意多穿衣保暖"
                    feelsLike > 32 -> "天气炎热，请注意防暑降温"
                    weather.wind.speed > 10 -> "室外风力较大，请注意防风"
                    else -> "体感舒适，适合活动"
                }

                append("早上好！现在天气${desc}，气温 ${temp}°C  (体感 ${feelsLike}°C)。")

                weather.visibility?.let { vis ->
                    val visKm = vis / 1000.0
                    append("能见度 %.1fkm，".format(visKm))
                }

                weather.sys?.let { sys ->
                    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
                    val zoneId = ZoneId.systemDefault()

                    val sunriseTime = Instant.ofEpochSecond(sys.sunrise)
                        .atZone(zoneId)
                        .format(timeFormatter)
                    val sunsetTime = Instant.ofEpochSecond(sys.sunset)
                        .atZone(zoneId)
                        .format(timeFormatter)

                    append("日出 ${sunriseTime}，日落 ${sunsetTime}。")
                }

                append("\n\n今日${advice}，祝你有美好的一天！\n\n")
            }

            targetGroups.forEach { groupId ->
                val group = getGroup(groupId)
                if (group != null) {
                    group.sendMessage(
                        StringComponent(message),
                        ImageComponent(image.thumbUrl),
                        StringComponent("—— ${image.title}")
                    )
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

