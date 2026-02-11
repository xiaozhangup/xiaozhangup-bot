package me.xiaozhangup.bot.util.doist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Todoist 通用分页响应包装类 (v1)
 */
@Serializable
data class PaginatedResponse<T>(
    val results: List<T>,
    @SerialName("next_cursor")
    val nextCursor: String? = null,
    @SerialName("has_more")
    val hasMore: Boolean = false
)

/**
 * Todoist Project (项目)
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val color: String = "charcoal",
    @SerialName("parent_id")
    val parentId: String? = null,
    @SerialName("child_order")
    val order: Int = 0,
    @SerialName("shared")
    val isShared: Boolean = false,
    @SerialName("is_favorite")
    val isFavorite: Boolean = false,
    @SerialName("inbox_project")
    val isInboxProject: Boolean = false,
    @SerialName("is_team_inbox")
    val isTeamInbox: Boolean = false,
    @SerialName("view_style")
    val viewStyle: String = "list",
    val url: String = ""
)

/**
 * Create project request
 */
@Serializable
data class CreateProjectRequest(
    val name: String,
    @SerialName("parent_id")
    val parentId: String? = null,
    val color: String? = null,
    @SerialName("is_favorite")
    val isFavorite: Boolean? = null,
    @SerialName("view_style")
    val viewStyle: String? = null
)

/**
 * Update project request
 */
@Serializable
data class UpdateProjectRequest(
    val name: String? = null,
    val color: String? = null,
    @SerialName("is_favorite")
    val isFavorite: Boolean? = null,
    @SerialName("view_style")
    val viewStyle: String? = null
)

/**
 * Todoist Section (板块)
 */
@Serializable
data class Section(
    val id: String,
    @SerialName("project_id")
    val projectId: String,
    @SerialName("section_order")
    val order: Int = 0,
    val name: String
)

/**
 * Create section request
 */
@Serializable
data class CreateSectionRequest(
    val name: String,
    @SerialName("project_id")
    val projectId: String,
    val order: Int? = null
)

/**
 * Due date object (截止日期对象)
 */
@Serializable
data class Due(
    val string: String,
    val date: String,
    @SerialName("is_recurring")
    val isRecurring: Boolean = false,
    val datetime: String? = null,
    val timezone: String? = null
)

/**
 * Todoist Task (任务)
 */
@Serializable
data class Task(
    val id: String,
    @SerialName("project_id")
    val projectId: String? = null,
    @SerialName("section_id")
    val sectionId: String? = null,
    val content: String,
    val description: String = "",
    val priority: Int = 1,
    @SerialName("child_order")
    val order: Int = 0,
    val due: Due? = null,
    val labels: List<String> = emptyList(),
    @SerialName("checked")
    val isCompleted: Boolean = false,
    @SerialName("created_at")
    val createdAt: String? = null
)

/**
 * Create task request
 */
@Serializable
data class CreateTaskRequest(
    val content: String,
    val description: String? = null,
    @SerialName("project_id")
    val projectId: String? = null,
    @SerialName("section_id")
    val sectionId: String? = null,
    val priority: Int? = null,
    @SerialName("due_string")
    val dueString: String? = null,
    @SerialName("due_date")
    val dueDate: String? = null,
    @SerialName("due_lang")
    val dueLang: String? = null,
    val labels: List<String>? = null
)

/**
 * Update task request
 */
@Serializable
data class UpdateTaskRequest(
    val content: String? = null,
    val description: String? = null,
    @SerialName("section_id")
    val sectionId: String? = null,
    val priority: Int? = null,
    @SerialName("due_string")
    val dueString: String? = null,
    @SerialName("due_date")
    val dueDate: String? = null,
    val labels: List<String>? = null
)