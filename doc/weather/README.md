# 天气提醒功能使用说明

## 功能介绍

天气提醒功能会在每天早上 7:30 自动获取指定城市的天气信息，并发送到配置的 QQ 群中。

## 配置说明

### 1. 创建配置文件

在插件数据目录下创建 `weather_reminder.properties` 文件（可以参考 `weather_reminder.properties.example`）。

### 2. 配置项说明

```properties
# OpenWeather API Key
# 在这里填写你的 OpenWeatherMap API Key
# 获取地址: https://openweathermap.org/api
api.key=你的API密钥

# 要发送天气提醒的群号（多个群号使用逗号分隔）
# 例如: target.groups=123456789,987654321
target.groups=群号1,群号2,群号3

# 城市名称（使用英文，例如: Beijing, Shanghai, Guangzhou）
# 默认值: Beijing
city=Beijing
```

### 配置示例

```properties
api.key=abc123def456ghi789
target.groups=123456789,987654321
city=Shanghai
```

## 获取 OpenWeather API Key

1. 访问 [OpenWeatherMap](https://openweathermap.org/api)
2. 注册账号
3. 在 API keys 页面获取你的 API Key
4. 免费版 API 足够日常使用

## 天气播报格式

每天早上 7:30，机器人会发送如下格式的天气信息：

```
☀️ 早安！今日天气播报
━━━━━━━━━━━━━━━
📍 城市: Shanghai
🌡️ 温度: 15.5°C
🤔 体感: 14.2°C
📊 温度范围: 12.0°C ~ 18.0°C
💧 湿度: 65%
🌀 气压: 1013 hPa
💨 风速: 3.5 m/s
☁️ 天气: 多云
━━━━━━━━━━━━━━━
祝你有美好的一天！
```

## 技术细节

- 发送时间：每天早上 7:30
- 使用 OpenWeatherMap API 获取天气数据
- 支持多个群同时发送
- 自动计算下次发送时间
- 温度单位：摄氏度（°C）
- 语言：简体中文

## 注意事项

1. 确保 API Key 有效且未超出调用限制
2. 群号必须是机器人已加入的群
3. 城市名称必须使用英文
4. 如果 API 调用失败，会在日志中记录错误信息
5. 配置文件修改后需要重启插件才能生效

## 支持的城市名称示例

- Beijing（北京）
- Shanghai（上海）
- Guangzhou（广州）
- Shenzhen（深圳）
- Chengdu（成都）
- Hangzhou（杭州）
- Wuhan（武汉）

更多城市名称可以在 [OpenWeatherMap City List](https://openweathermap.org/find) 查询。

