# 爱发电订单提醒

配置文件：运行目录下 `data/me.xiaozhangup.bot/afdian_order.properties`。修改后重启机器人。

```properties
enabled=true
user.id=你的爱发电用户ID
api.token=你的爱发电API Token
notification.group=959750158
poll.seconds=60
```

在 [爱发电开发者后台](https://afdian.com/dashboard/dev) 获取凭据，使用 [官方订单 API](https://guide.afdian.com/creator/developer)。无需 Cookie、公开端口或修改已有 Webhook。Token 只放在运行配置中，建议将文件权限设为 `600`，不要提交到仓库。

- 每轮遍历所有订单页，只处理 `status=2` 的成交订单，包括零元兑换订单。全量扫描可识别延迟成交，也会补发机器人停机期间的订单。
- 首次成功查询全部页面后，登记当时的历史成交订单，不发送历史提醒。初始化失败时下一轮重新查询。
- 后续按订单号去重，群消息包含昵称、方案、商品、实付金额及订单号，不发送订单留言、用户私有 ID 或收货信息。
- 等待 QQ 平台确认发送成功后，原子写入 `data/me.xiaozhangup.bot/afdian_order/<用户ID>-<群号>.json`。重启会加载记录；账号或目标群变化时重新初始化。不要删除状态文件，否则会重新登记历史订单。
- 网络或发送失败会记录日志并在下一轮继续；机器人离线时保留待发送订单。状态文件损坏时模块停止，保留文件以便修复。
- 平台已经收件但回执丢失，或发送成功后进程在保存状态前退出，下一轮可能重复提醒；QQ 发送与本地记账无法作为一次原子操作。

轮询间隔最少 10 秒，默认 60 秒。实际提醒延迟还包括接口查询与 QQ 发送耗时。插件停用时取消轮询任务。
