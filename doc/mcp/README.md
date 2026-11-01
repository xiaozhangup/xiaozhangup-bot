# QQ MCP 服务

服务随 xiaozhangup-bot 插件启动和关闭，复用 Overflow 的当前机器人。默认地址为 `http://127.0.0.1:48247/mcp`，使用 Streamable HTTP。支持协议版本 `2025-11-25`、`2025-06-18`、`2025-03-26`。

## 配置与连接

首次启动在插件数据目录生成 `mcp.properties`。`token` 留空时自动生成随机值，配置文件权限设为 `600`，日志不会显示 token。

在 MCP 客户端填写服务 URL，并设置 HTTP 请求头 `Authorization: Bearer <mcp.properties 中的 token>`。本服务采用手动配置 token 的连接方式，不提供 OAuth 登录发现。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `host` | `127.0.0.1` | HTTP 监听地址 |
| `port` | `48247` | MCP 和 `/send` 共用的端口 |
| `token` | 自动生成 | Bearer token；配置修改后重启生效 |
| `allowed.origins` | 空 | 允许的浏览器 Origin，以分号分隔；空值拒绝带 Origin 的请求 |
| `share.roots` | 数据目录下 `mcp/share` | 允许发送文件的服务端目录，分号分隔 |
| `download.root` | 数据目录下 `mcp/downloads` | 下载文件可写入的根目录 |
| `max.file.bytes` | `20971520` | 单个附件或发送文件的字节上限，最高 100 MiB |
| `inline.bytes` | `1048576` | MCP 内嵌媒体字节上限 |
| `event.capacity` | `2000` | 内存事件、申请和消息归属的保留窗口 |
| `action.timeout.seconds` | `30` | 等待平台回执的超时 |
| `max.sessions` | `32` | MCP 会话上限，最高 128 |

HTTP POST 使用 `Content-Type: application/json` 和 `Accept: application/json, text/event-stream`。初始化请求例如：

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"my-client","version":"1.0"}}}
```

保存响应头的 `MCP-Session-Id`，后续请求携带此头与协商后的 `MCP-Protocol-Version`。然后发送不带 `id` 的 `notifications/initialized`，即可调用 `tools/list`、`tools/call` 等方法。会话空闲超过 30 分钟会过期，HTTP 404 表示需要重新初始化。GET `/mcp` 开启通知流，每个会话最多一个流；DELETE `/mcp` 结束会话。每个会话最多 8 个并发请求、每分钟 120 次工具调用。

## 工具

实际 `inputSchema`、`outputSchema` 和操作提示通过 `tools/list` 获取。QQ 号、群号、消息 ID、文件 ID 均用字符串。消息 ID 可以为负数，应按原值传递。

联系人（8）：`qq_get_status`、`qq_list_friends`、`qq_list_groups`、`qq_list_recent_conversations`、`qq_get_user`、`qq_get_group`、`qq_list_group_members`、`qq_get_group_member`。

消息（10）：`qq_send_message`、`qq_get_message`、`qq_list_messages`、`qq_search_messages`、`qq_get_forward_message`、`qq_forward_messages`、`qq_recall_message`、`qq_set_message_reaction`、`qq_mark_read`、`qq_read_attachment`。

文件（9）：`qq_send_file`、`qq_list_group_files`、`qq_get_group_file_url`、`qq_download_group_file`、`qq_create_group_folder`、`qq_move_group_file`、`qq_rename_group_file`、`qq_delete_group_file`、`qq_delete_group_folder`。

群管理（11）：`qq_set_group_name`、`qq_set_group_card`、`qq_set_group_special_title`、`qq_mute_member`、`qq_set_group_mute`、`qq_kick_member`、`qq_set_group_admin`、`qq_list_group_notices`、`qq_publish_group_notice`、`qq_delete_group_notice`、`qq_leave_group`。

事件与申请（4）：`qq_poll_events`、`qq_list_requests`、`qq_handle_friend_request`、`qq_handle_group_request`。

发送示例：

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"qq_send_message","arguments":{"target":{"type":"group","id":"123456"},"content":"消息文本"}}}
```

`content` 也可以是消息段数组，支持 `text`、`at`、`at_all`、`face`、`image`、`audio`、`video`。图片、音频、视频的 `source` 与文件发送相同：

```json
{"type":"image","source":{"type":"path","path":"/srv/qq-share/result.png"}}
```

```json
{"type":"image","source":{"type":"url","url":"https://example.com/image.png"}}
```

路径属于运行插件的主机，必须在 `share.roots` 内。本地内容转换为 Base64 后传给 NapCat，因此不要求宿主机与 NapCat 容器共享相同路径。URL 文件由插件下载并执行大小限制。

群文件列表返回文件、子目录、`busid` 和 `parent_folder_id`。移动文件必须同时提供 `parent_folder_id`、`destination_folder_id`；根目录 ID 为 `/`。下载默认生成唯一 `.bin` 文件名，传入 `name` 可保留原文件名，已存在的文件不会被覆盖。

历史查询按平台可读取的范围分页，搜索默认最多扫描 200 条，最大 1000 条。结果中的 `coverage` 明确范围，不承诺完整聊天历史。后端需配置数组消息格式才能转发和读取附件。机器人已发送的私聊消息若归属记录已失效，应先从该会话历史读取，以重新确认消息归属。

群文件列表最多读取一个目录的 10000 个条目；最近会话最多查询 1000 个条目。普通成员、管理员、群主的具体操作权限最终由平台校验。工具已实现不代表当前 NapCat 版本、QQ 账号或群身份允许执行所有操作。

## 资源与事件

支持 `resources/list`、`resources/templates/list`、`resources/read`、`resources/subscribe`、`resources/unsubscribe`。

| URI | 内容 |
|---|---|
| `qq://bot/status` | 机器人状态 |
| `qq://bot/capabilities` | 工具和文件限制 |
| `qq://events/recent` | 最近 100 个事件及读取游标 |
| `qq://groups/{group_id}/info` | 群资料 |
| `qq://conversations/{type}/{id}/recent` | 最近 20 条平台消息；type 为 group 或 private |
| `qq://attachments/{attachment_id}` | 小附件的二进制资源或大附件的 URL 元数据 |

订阅后通过 GET `/mcp` 收到 `notifications/resources/updated`，通知按 URI 合并，客户端重新读取资源以获取数据。通知本身不会触发 AI 回复。通知流不实现 SSE 事件重放；需要可靠跟进新事件时使用 `qq_poll_events` 的独立游标。

事件包含收到的消息摘要、MCP 发出的消息、撤回、表情回应、成员加入与退出、上线与离线、好友申请、入群申请和群邀请。消息事件包含平台消息 ID，完整消息与附件通过 `qq_get_message` 读取。`qq_poll_events` 可等待最多 30 秒，各客户端独立读取。服务重启或保留窗口滚动后，旧游标返回 `EVENT_CURSOR_EXPIRED`，不静默跳过。

申请列表仅包括本次服务启动后实际收到的申请，不补造历史申请。申请处理通过对应 Mirai/Overflow 事件执行，防止并发重复处理；原始申请 flag 不对外暴露。窗口移出或重启后申请引用失效。

## 返回结果与兼容接口

工具返回 `structuredContent: {"ok":true,"data":{...}}`，并附相同 JSON 文本供客户端读取。业务失败返回 `isError:true` 和 `{"ok":false,"error":{"code":"...","message":"...","backend_code":null}}`；未知工具或无效协议请求使用 JSON-RPC error。

发送和上传等待平台确认。`sent`、`uploaded` 表示平台确认，不表示对方已读。上传、公告等平台 API 没有返回 ID 时，只返回真实的平台结果。等待回执超时或连接失败时，写操作返回 `OUTCOME_UNKNOWN`，不自动重复发送。

`POST /send` 保留 `{"group":"123456","message":"文本"}` 或 `{"user":"123456","message":"文本"}` 的请求形式，但现在同样需要 Bearer token，确认发送后返回 HTTP 200 和 JSON 回执，替代旧的异步 HTTP 202。

协议参考：[MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[MCP Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)、[NapCat API](https://napneko.github.io/onebot/api)。
