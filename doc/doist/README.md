# Todoist 客户端

这是一个使用 Kotlin 和 kotlinx.serialization 编写的 Todoist API 客户端，使用 java.net 进行网络访问。

## 功能特性

- ✅ 项目管理（增、删、改、查）
- ✅ 板块管理（增、删、改、查）
- ✅ 任务管理（增、删、改、查）
- ✅ 使用 kotlinx.serialization 进行 JSON 序列化
- ✅ 使用 java.net 进行网络请求
- ✅ 完整的类型安全

## 快速开始

### 初始化客户端

```kotlin
val client = TodoistClient("your-api-token-here")
```

### 项目操作

#### 创建项目

```kotlin
// 简化版
val project = client.createProject(
    name = "我的项目",
    color = "blue",
    isFavorite = true,
    viewStyle = "list"
)

// 使用 Request 对象
val projectRequest = CreateProjectRequest(
    name = "我的项目",
    color = "blue",
    isFavorite = true,
    viewStyle = "board"
)
val project = client.createProject(projectRequest)
```

#### 获取项目列表

```kotlin
// 获取所有项目
val projects = client.getProjects()

// 获取单个项目
val project = client.getProject("project-id")
```

#### 更新项目

```kotlin
// 简化版
val updatedProject = client.updateProject(
    projectId = "project-id",
    name = "新的项目名称",
    color = "green"
)

// 使用 Request 对象
val updateRequest = UpdateProjectRequest(
    name = "新的项目名称",
    color = "green",
    isFavorite = false
)
val updatedProject = client.updateProject("project-id", updateRequest)
```

#### 删除项目

```kotlin
val success = client.deleteProject("project-id")
```

### 板块操作

#### 创建板块

```kotlin
val section = client.createSection(
    name = "工作任务",
    projectId = "your-project-id"
)
```

#### 获取板块列表

```kotlin
// 获取所有板块
val allSections = client.getSections()

// 获取特定项目的板块
val projectSections = client.getSections(projectId = "your-project-id")
```

#### 更新板块

```kotlin
val updatedSection = client.updateSection(
    sectionId = "section-id",
    name = "新的板块名称"
)
```

#### 删除板块

```kotlin
val success = client.deleteSection("section-id")
```

### 任务操作

#### 创建任务

```kotlin
// 简化版本
val task = client.createTask(
    content = "完成项目报告",
    description = "需要包含所有统计数据",
    projectId = "your-project-id",
    sectionId = "section-id",
    priority = 4, // 1-4，4为最高优先级
    dueString = "tomorrow at 18:00"
)

// 使用 Request 对象
val taskRequest = CreateTaskRequest(
    content = "代码审查",
    description = "审查新功能代码",
    projectId = "your-project-id",
    sectionId = "section-id",
    priority = 3,
    dueString = "next Monday",
    labels = listOf("code-review", "urgent")
)
val task = client.createTask(taskRequest)
```

#### 获取任务

```kotlin
// 获取所有任务
val allTasks = client.getTasks()

// 获取特定项目的任务
val projectTasks = client.getTasks(projectId = "your-project-id")

// 获取特定板块的任务
val sectionTasks = client.getTasks(sectionId = "section-id")

// 获取带有特定标签的任务
val labeledTasks = client.getTasks(label = "urgent")

// 获取单个任务
val task = client.getTask("task-id")
```

#### 更新任务

```kotlin
// 简化版本
val updatedTask = client.updateTask(
    taskId = "task-id",
    content = "更新后的任务标题",
    description = "更新后的描述",
    priority = 4
)

// 使用 Request 对象
val updateRequest = UpdateTaskRequest(
    content = "更新后的任务标题",
    description = "更新后的描述",
    priority = 4,
    dueString = "today at 20:00"
)
val updatedTask = client.updateTask("task-id", updateRequest)
```

#### 完成/重新打开任务

```kotlin
// 完成任务
val closed = client.closeTask("task-id")

// 重新打开任务
val reopened = client.reopenTask("task-id")
```

#### 删除任务

```kotlin
val success = client.deleteTask("task-id")
```

## 数据模型

### Project (项目)

```kotlin
@Serializable
data class Project(
    val id: String,
    val name: String,
    val color: String = "grey",
    val parentId: String? = null,
    val order: Int = 0,
    val commentCount: Int = 0,
    val isShared: Boolean = false,
    val isFavorite: Boolean = false,
    val isInboxProject: Boolean = false,
    val isTeamInbox: Boolean = false,
    val viewStyle: String = "list", // "list" 或 "board"
    val url: String = ""
)
```

### Section (板块)

```kotlin
@Serializable
data class Section(
    val id: String,
    val projectId: String,
    val order: Int = 0,
    val name: String
)
```

### Task (任务)

```kotlin
@Serializable
data class Task(
    val id: String,
    val projectId: String? = null,
    val sectionId: String? = null,
    val content: String,
    val description: String = "",
    val priority: Int = 1, // 1-4
    val order: Int = 0,
    val dueString: String? = null,
    val dueDate: String? = null,
    val labels: List<String> = emptyList(),
    val isCompleted: Boolean = false,
    val createdAt: String? = null
)
```

## 错误处理

所有 API 调用失败时会抛出 `TodoistException`：

```kotlin
try {
    val task = client.getTask("invalid-id")
} catch (e: TodoistException) {
    println("错误: ${e.message}")
}
```

## API 参考

### TodoistClient

#### 构造函数

- `TodoistClient(token: String)` - 使用 API token 初始化客户端

#### 项目方法

- `getProjects(): List<Project>` - 获取所有项目
- `getProject(projectId: String): Project` - 获取单个项目
- `createProject(request: CreateProjectRequest): Project` - 创建项目
- `createProject(name: String, parentId: String? = null, color: String? = null, isFavorite: Boolean? = null, viewStyle: String? = null): Project` - 创建项目（简化版）
- `updateProject(projectId: String, request: UpdateProjectRequest): Project` - 更新项目
- `updateProject(projectId: String, name: String? = null, color: String? = null, isFavorite: Boolean? = null, viewStyle: String? = null): Project` - 更新项目（简化版）
- `deleteProject(projectId: String): Boolean` - 删除项目

#### 板块方法

- `getSections(projectId: String? = null): List<Section>` - 获取板块列表
- `getSection(sectionId: String): Section` - 获取单个板块
- `createSection(name: String, projectId: String, order: Int? = null): Section` - 创建板块
- `updateSection(sectionId: String, name: String): Section` - 更新板块
- `deleteSection(sectionId: String): Boolean` - 删除板块

#### 任务方法

- `getTasks(projectId: String? = null, sectionId: String? = null, label: String? = null): List<Task>` - 获取任务列表
- `getTask(taskId: String): Task` - 获取单个任务
- `createTask(request: CreateTaskRequest): Task` - 创建任务
- `createTask(content: String, projectId: String? = null, sectionId: String? = null, description: String? = null, priority: Int? = null, dueString: String? = null): Task` - 创建任务（简化版）
- `updateTask(taskId: String, request: UpdateTaskRequest): Task` - 更新任务
- `updateTask(taskId: String, content: String? = null, description: String? = null, priority: Int? = null, dueString: String? = null): Task` - 更新任务（简化版）
- `closeTask(taskId: String): Boolean` - 完成任务
- `reopenTask(taskId: String): Boolean` - 重新打开任务
- `deleteTask(taskId: String): Boolean` - 删除任务

## 注意事项

1. **API Token**: 在使用前需要从 Todoist 获取 API token
2. **Project ID**: 创建板块和任务时需要提供有效的项目 ID
3. **优先级**: 任务优先级范围是 1-4，其中 4 是最高优先级
4. **时间格式**: `dueString` 支持自然语言，如 "tomorrow at 18:00", "next Monday" 等
5. **网络超时**: 默认连接超时和读取超时都是 10 秒

## API 文档

更多详细信息请参考 [Todoist REST API 文档](https://developer.todoist.com/rest/v2/)

