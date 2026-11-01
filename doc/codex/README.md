# Codex 会话

在 `codex.properties` 的 `enabled.groups` 中启用群聊，使用 `/x <任务>` 或 `/codex <任务>`。
可以在同一条消息中附带图片、转发、文件等内容，也可以引用原消息并发送 `/x 分析这条消息`。
引用本功能此前发出的消息时，可以省略命令前缀。普通群消息不会自动进入会话。
默认模型为 `gpt-6.1-terra`，推理强度为 `xhigh`。
在 `enabled.users` 中启用的私聊无需命令前缀，直接发送文字、图片或文件即可。Codex 放行以 `/` 开头的私聊消息，交给其他命令模块处理；会话管理命令 `/x new`、`/x resume`、`/x resume <编号>` 及对应 `/codex` 写法由 Codex 处理。私聊中的其他 `/x`、`/codex` 和 `/cli` 用法不触发 Codex。`/ping` 返回 `pong`。

群和私聊可通过 `session.group.<群号>`、`session.private.<QQ号>` 指向同一实例：

```properties
enabled.groups=959750158,1095963003,746508299,908196421
enabled.users=984177828
session.group.959750158=959750158
session.private.984177828=959750158
```

以上配置让群 `959750158` 和私聊 `984177828` 共用一个 Codex App Server、会话、容器和工作目录，其他群保持独立。共享只包含提交给 Codex 的内容。回复和文件发送到最后一次给该实例发消息的聊天，另一端可直接接着追加指令。群文件列表和下载使用该聊天对应的群，当前为私聊时会返回仅支持群聊。

未配置映射的群以群号作为实例名，私聊以 `private-<QQ号>` 作为实例名。实例名支持字母、数字、下划线和连字符，最长 64 字符。映射不替代 `enabled.groups` 和 `enabled.users` 白名单。
`/x new` 重置整个共享实例的会话，下一条任务开启新会话，容器文件保留。当前会话 ID 按实例保存到插件数据目录的 `codex_sessions/current.json`，重启机器人或 App Server 后继续上次会话。首次启用该机制时恢复当前实例最近使用的会话。`/x new` 标记下一条消息开启新会话，这一标记也会跨重启保留。

`/x resume` 按最后更新时间倒序列出当前实例最近 10 个会话，显示时间和最多 36 字的内容预览，并标记当前会话。预览去除聊天身份、发送者、消息 ID 和媒体路径，网址只显示域名。`/x resume 1` 切换到刚才列表中的第 1 个会话；编号按各聊天最后一次看到的列表解析。切换对共享组内所有聊天生效，正在执行任务时等待本轮完成再切换。

## 输入内容

- **引用**：用 OneBot `get_msg` 和引用的消息 ID 回查原消息，再递归展开文字、图片、转发和附件。循环引用和过深嵌套会明确标注。
- **合并转发**：保留消息顺序、发送者 QQ、昵称和时间；读取内嵌节点，没有节点时使用 `get_forward_msg` 回查。
- **图片**：JPEG、PNG、WebP 以 `localImage` 传入模型；GIF 抽样最多 12 帧，并保留原文件。图片和对应文字按顺序传入，包括引用和转发中的图片。
- **卡片**：JSON、XML 卡片传入完整原文，保留标题、链接等平台字段；JSON 卡片中 `preview`、`cover`、`image`、`pic`、`thumb` 的 HTTP(S) 预览图一并读取。
- **文件**：自动下载当前消息、引用及转发里的可访问附件，把本地路径传给 Codex，由模型按任务需要读取文件；已有群文件列表、下载及发送工具继续可用。
- **语音**：调用 QQ 的 `fetch_ptt_text` 转文字，使用 `get_record` 保存 WAV。当前 `gpt-6.1-terra` 仅支持文字和图片，不把原始音频当作模型已听到的内容；转写失败会明确标注。
- **视频**：保存原文件，使用 FFmpeg 均匀抽取最多 12 张画面，附上时间点并传入模型。当前不识别视频音轨，不保证抽样覆盖所有瞬间。
- **@ 和表情**：@ 保留群名片与 QQ 号，@全体成员保留语义；QQ 原生表情保留平台名称或表情 ID。图片表情按图片处理。
- **其他消息段**：分享、音乐、位置、联系人、骰子等保留平台返回的类型和完整字段。

只有 `/x` 通道使用这套展开逻辑，其他消息处理模块继续使用原有转换。
平台接口返回数组和 CQ 字符串两种格式均可解析。当前消息回查失败时使用收到的事件内容，并明确标注信息可能不完整；某个引用或附件失败不会冒充成功读取。

## 文件与限制

附件保存在 `<groups.directory>/<实例名>/workspace/inputs/qq/<group或private>-<聊天ID>/<消息ID>-<随机目录>/`，不会在本轮结束时删除，后续对话可以继续读取。需要释放磁盘空间时可按目录清理。

- 单张图片最多 20 MiB；单个语音、视频或文件最多 100 MiB。
- 每条输入下载的附件总量最多 400 MiB；最多直接传入 32 张图片，更多图片保留路径供模型按需读取。
- 嵌套最多 8 层，消息段最多 500 个；超出会明确标注。
- QQ 接口查询超时 30 秒；下载连接超时 15 秒、读取超时 30 秒；每次 FFmpeg/FFprobe 调用超时 60 秒。
- 执行镜像包含 `ffmpeg` 和 `ffprobe`。容器媒体使用 HTTP(S) 地址或接口返回的 base64，不把容器路径当作主机路径。
- 撤回、过期、无权限或平台已无法下载的内容会标注失败，无法凭消息 ID 恢复平台不存在的内容。

协议依据：[NapCat OneBot API](https://napneko.github.io/onebot/api)、[Codex App Server](https://learn.chatgpt.com/docs/app-server)。音频能力以运行时 `model/list` 返回的 `inputModalities` 为准。

## 实例与容器

`/cli`、`/x`、`/codex` 在当前聊天绑定的同一个 Rootless Podman 容器内运行，工作目录固定为 `/workspace`。同一实例共享 App Server、会话、进程和文件系统；不同实例分别运行。`/x new` 重置当前实例会话，不删除文件。

```
QBot/
  959750158/
    workspace/  # 原 QBot 全部内容
    home/       # /home/runner
    codex/      # CODEX_HOME，独立会话状态
  1095963003/
    workspace/
    home/
    codex/
  _runtime/     # 公共构建文件、迁移清单和备份，不挂载到群内
```

运行配置使用 `groups.directory=/home/xiaozhangup/文档/QBot`，不再使用 `working.directory` 或 `agent.path`。容器名为 `qbot-<实例名>`，首次使用时创建/启动。新增群需准备其 `workspace/AGENTS.md`（可从 `_runtime/AGENTS.md` 复制），然后更新白名单并重启机器人。容器随机器人关闭而停止，数据保留。

镜像定义在 [container/Containerfile](container/Containerfile)。构建上下文需提供宿主对应版本的 `codex`、`codex-code-mode-host`、`uv`，以及旧环境依赖的 Python 3.12 目录 `legacy-python/`；基镜像摘要见 `container/base-image.txt`。镜像和 Podman 引擎存储仍由本机 Rootless Podman 统一管理，群可写目录全部放在 QBot 下。

- 群任务为 UID 1000，无提权能力；根文件系统只读，不挂载宿主根目录、设备或容器管理 socket。只挂载当前群的三个目录。
- 启动入口在容器网络命名空间内设置防火墙，随后降权；群任务不能修改规则。限制访问宿主、内网和保留地址，只允许 DNS 与公网请求；不使用宿主网络。
- 默认每群 2 CPU、3 GiB 内存、256 个进程；`/cli` 10 秒超时。可通过 `container.cpus`、`container.memory`、`container.pids` 调整。内存支持按群覆盖，例如 `container.memory.746508299=12g`，其他群沿用 `container.memory`。已有容器可通过 `podman update --memory 12g --memory-swap 12g qbot-746508299` 立即调整；同时保存配置并加载新配置，保证以后重建也使用该额度。修改镜像或其他资源参数后需停掉并删除对应的容器实例（保留群目录），下一条命令会按新配置创建。磁盘未设每群配额，仍需管理空间。
- QQ 消息/文件接口保留在宿主机器人中。附件先暂存，再经固定的容器内文件工具导入；发送文件同样从当前群容器导出，路径校验在容器内进行。
- 宿主登录的刷新凭据不挂进群容器；机器人通过 App Server 的外部凭据协议注入短期 access token，刷新仍由宿主负责。各群共享同一账号的额度。
- 容器内旧路径 `/home/xiaozhangup/文档/QBot` 指向 `/workspace`，兼容旧脚本与 Python 环境。其他宿主程序、浏览器和私有服务不自动开放。

容器共享宿主内核。正常隔离下，危险命令只能破坏该群的可写数据，不能格式化宿主磁盘；这不等同于虚拟机级隔离，也不保证抵御内核漏洞。

## 群文件上传中转

`/x` 和 QQ MCP 的文件上传共用 `QqFileTransfer`。宿主把文件写入专用中转目录，每次上传使用独立随机子目录；OneBot 请求只传 NapCat 内可见的路径和文件名，不再把整个文件编码进 WebSocket 消息。MCP 原有共享目录校验和大小限制保留。

插件数据目录新增 `qq_transfer.properties`：

```properties
host.directory=/home/xiaozhangup/文档/QBot/_runtime/qq-transfer
napcat.directory=/qbot-transfer
```

NapCat 需只读挂载 `host.directory` 到 `/qbot-transfer`（启用 SELinux 时加 `Z`）；群容器不挂载此目录。上传成功返回后清理该次中转目录；异常、超时或断线时保留源文件并返回“上传未确认”，应先核对群文件，不要直接重传。保留文件位于 `upload-*` 子目录，核查后可由管理员清理。

机器人启动参数增加 `-Doverflow.timeout=300000`，避免 Overflow 默认 10 秒请求超时中断大文件等待；QQ MCP 文件上传等待 5 分钟，其他 MCP 操作保留原超时。NapCat 的 WebSocket 消息大小上限无需修改。

## 工具权限

`tools.default` 配置默认工具允许列表，`tools.<实例名>` 覆盖该实例的列表，空值关闭全部机器人扩展工具。注册和执行均检查此列表。

```properties
tools.default=send_qq_file,list_qq_group_files,download_qq_group_file
tools.959750158=send_qq_file,list_qq_group_files,download_qq_group_file,todoist,read_mail
```

- `todoist`：列出项目、板块和活跃任务，查看、创建、修改、完成、重新打开或删除任务。使用 `doist_task.properties` 的账号，查询返回真实任务 ID；任务列表通过 offset/limit 分页。
- `read_mail`：列出 `mail_summary.properties` 配置的邮箱，按带时区的时间范围读取收件箱，默认近 24 小时、每页 20 封、正文每封最多 12000 字符。返回发件人、主题、接收时间、正文及截断状态；翻页沿用返回的 since/until。邮件以只读方式打开，不修改已读状态，不读取附件。

新工具默认关闭，当前只在 `959750158` 实例启用。凭据留在宿主机器人中。工具清单在新建会话时保存，恢复历史会话时沿用其原有清单；新增工具在 `/x new` 后的新会话可见，已经禁用的工具在历史会话内也会被执行端拒绝。

`send_qq_file` 和 QQ MCP 共用路径中转，按流复制文件，不通过 WebSocket 传输 Base64；Codex 文件发送没有额外字节上限，最终大小受 QQ 平台限制。容器导出和平台上传分别最多等待 5 分钟。上传响应验证 status/retcode，失败或超时时保留中转文件并返回“上传未确认”，不自动重传。QQ MCP 自身的 max.file.bytes 配置继续只约束 MCP 文件来源读取。
