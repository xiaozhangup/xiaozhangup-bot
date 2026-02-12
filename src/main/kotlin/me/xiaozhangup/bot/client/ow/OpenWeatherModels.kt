package me.xiaozhangup.bot.client.ow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WeatherResponse(
    val weather: List<WeatherDescription>,
    val main: MainData,
    val wind: WindData,
    val name: String, // 城市名称
    val cod: Int
)

@Serializable
data class WeatherDescription(
    val id: Int,
    val main: String,
    val description: String,
    val icon: String
)

@Serializable
data class MainData(
    val temp: Double,      // 当前温度
    @SerialName("feels_like")
    val feelsLike: Double, // 体感温度
    val temp_min: Double,
    val temp_max: Double,
    val pressure: Int,
    val humidity: Int
)

@Serializable
data class WindData(
    val speed: Double,
    val deg: Int
)