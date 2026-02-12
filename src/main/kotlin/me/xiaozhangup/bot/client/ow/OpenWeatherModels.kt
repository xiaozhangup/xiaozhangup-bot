package me.xiaozhangup.bot.client.ow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WeatherResponse(
    val weather: List<WeatherDescription>,
    val main: MainData,
    val wind: WindData,
    val visibility: Int? = null, // 能见度（米）
    val sys: SysData? = null, // 系统信息（日出日落等）
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

@Serializable
data class SysData(
    val type: Int? = null,
    val id: Int? = null,
    val country: String? = null, // 国家代码
    val sunrise: Long, // 日出时间（Unix 时间戳）
    val sunset: Long   // 日落时间（Unix 时间戳）
)

