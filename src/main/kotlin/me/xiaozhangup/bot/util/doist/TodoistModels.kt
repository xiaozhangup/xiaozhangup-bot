package me.xiaozhangup.bot.util.doist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Todoist Project (项目)
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val color: String = "grey",
    @SerialName("parent_id")
    val parentId: String? = null,
    val order: Int = 0,
    val comment_count: Int = 0,
    @SerialName("is_shared")
    val isShared: Boolean = false,
    @SerialName("is_favorite")
    val isFavorite: Boolean = false,
    @SerialName("is_inbox_project")
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
    val order: Int = 0,
    @SerialName("due_string")
    val dueString: String? = null,
    @SerialName("due_date")
    val dueDate: String? = null,
    val labels: List<String> = emptyList(),
    @SerialName("is_completed")
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

