# xiaozhangup-bot

自用机器人，目的是方便处理我的各种消息通知。

### 目前支持的功能
1. 根据所填写的关键词和群号自动抓取并总结通知内容，然后发送到 Todoist
2. Doist 任务命令（添加任务、列出板块、查看板块任务、删除任务）

### Doist 命令

| 命令 | 功能 | 用法 |
| --- | --- | --- |
| `/task <任务内容>` | 快速添加任务（兼容旧用法） | `/task 完成周报` |
| `/task add <任务内容>` | 添加任务 | `/task add 复习离散数学` |
| `/task sections` | 列出所有板块（含板块ID） | `/task sections` |
| `/task tasks <板块ID>` | 列出某个板块的任务清单 | `/task tasks 1234567890` |
| `/task delete <任务ID>` | 删除指定任务 | `/task delete 9876543210` |
| `/task help` | 列出所有命令、功能与用法 | `/task help` |
