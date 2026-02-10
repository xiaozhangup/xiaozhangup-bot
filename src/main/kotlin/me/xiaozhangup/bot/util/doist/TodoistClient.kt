package me.xiaozhangup.bot.util.doist

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Todoist API 客户端
 * 使用 java.net 进行网络访问
 *
 * @param token Todoist API token
 */
class TodoistClient(private val token: String) {

    companion object {
        private const val BASE_URL = "https://api.todoist.com/rest/v2"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    /**
     * 发送 HTTP 请求
     */
    private fun sendRequest(
        endpoint: String,
        method: String = "GET",
        body: String? = null
    ): String {
        val url = URL("$BASE_URL$endpoint")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            // 发送请求体
            if (body != null && (method == "POST" || method == "PUT" || method == "PATCH")) {
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(body)
                    writer.flush()
                }
            }

            // 读取响应
            val responseCode = connection.responseCode
            val inputStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: throw TodoistException("HTTP Error $responseCode")
            }

            val response = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                reader.readText()
            }

            if (responseCode !in 200..299) {
                throw TodoistException("HTTP Error $responseCode: $response")
            }

            return response
        } finally {
            connection.disconnect()
        }
    }

    // ========== 项目管理 (Project Management) ==========

    /**
     * 获取所有项目
     */
    fun getProjects(): List<Project> {
        val response = sendRequest("/projects")
        return json.decodeFromString(ListSerializer(Project.serializer()), response)
    }

    /**
     * 获取单个项目
     */
    fun getProject(projectId: String): Project {
        val response = sendRequest("/projects/$projectId")
        return json.decodeFromString(Project.serializer(), response)
    }

    /**
     * 创建项目
     */
    fun createProject(request: CreateProjectRequest): Project {
        val body = json.encodeToString(request)
        val response = sendRequest("/projects", "POST", body)
        return json.decodeFromString(Project.serializer(), response)
    }

    /**
     * 创建项目 (简化版)
     */
    fun createProject(
        name: String,
        parentId: String? = null,
        color: TodoistColor? = null,
        isFavorite: Boolean? = null,
        viewStyle: String? = null
    ): Project {
        val request = CreateProjectRequest(
            name = name,
            parentId = parentId,
            color = color?.nameLower,
            isFavorite = isFavorite,
            viewStyle = viewStyle
        )
        return createProject(request)
    }

    /**
     * 更新项目
     */
    fun updateProject(projectId: String, request: UpdateProjectRequest): Project {
        val body = json.encodeToString(request)
        val response = sendRequest("/projects/$projectId", "POST", body)
        return json.decodeFromString(Project.serializer(), response)
    }

    /**
     * 更新项目 (简化版)
     */
    fun updateProject(
        projectId: String,
        name: String? = null,
        color: TodoistColor? = null,
        isFavorite: Boolean? = null,
        viewStyle: String? = null
    ): Project {
        val request = UpdateProjectRequest(
            name = name,
            color = color?.nameLower,
            isFavorite = isFavorite,
            viewStyle = viewStyle
        )
        return updateProject(projectId, request)
    }

    /**
     * 删除项目
     */
    fun deleteProject(projectId: String): Boolean {
        return try {
            sendRequest("/projects/$projectId", "DELETE")
            true
        } catch (_: Exception) {
            false
        }
    }

    // ========== 板块管理 (Section Management) ==========

    /**
     * 获取项目的所有板块
     */
    fun getSections(projectId: String? = null): List<Section> {
        val endpoint = if (projectId != null) {
            "/sections?project_id=$projectId"
        } else {
            "/sections"
        }
        val response = sendRequest(endpoint)
        return json.decodeFromString(ListSerializer(Section.serializer()), response)
    }

    /**
     * 创建板块
     */
    fun createSection(name: String, projectId: String, order: Int? = null): Section {
        val request = CreateSectionRequest(name, projectId, order)
        val body = json.encodeToString(request)
        val response = sendRequest("/sections", "POST", body)
        return json.decodeFromString(Section.serializer(), response)
    }

    /**
     * 获取单个板块
     */
    fun getSection(sectionId: String): Section {
        val response = sendRequest("/sections/$sectionId")
        return json.decodeFromString(Section.serializer(), response)
    }

    /**
     * 更新板块
     */
    fun updateSection(sectionId: String, name: String): Section {
        val body = json.encodeToString(mapOf("name" to name))
        val response = sendRequest("/sections/$sectionId", "POST", body)
        return json.decodeFromString(Section.serializer(), response)
    }

    /**
     * 删除板块
     */
    fun deleteSection(sectionId: String): Boolean {
        return try {
            sendRequest("/sections/$sectionId", "DELETE")
            true
        } catch (_: Exception) {
            false
        }
    }

    // ========== 任务管理 (Task Management) ==========

    /**
     * 获取所有活跃任务
     */
    fun getTasks(
        projectId: String? = null,
        sectionId: String? = null,
        label: String? = null
    ): List<Task> {
        val params = mutableListOf<String>()
        if (projectId != null) params.add("project_id=$projectId")
        if (sectionId != null) params.add("section_id=$sectionId")
        if (label != null) params.add("label=$label")

        val endpoint = if (params.isEmpty()) {
            "/tasks"
        } else {
            "/tasks?" + params.joinToString("&")
        }

        val response = sendRequest(endpoint)
        return json.decodeFromString(ListSerializer(Task.serializer()), response)
    }

    /**
     * 获取单个任务
     */
    fun getTask(taskId: String): Task {
        val response = sendRequest("/tasks/$taskId")
        return json.decodeFromString(Task.serializer(), response)
    }

    /**
     * 创建任务
     */
    fun createTask(request: CreateTaskRequest): Task {
        val body = json.encodeToString(request)
        val response = sendRequest("/tasks", "POST", body)
        return json.decodeFromString(Task.serializer(), response)
    }

    /**
     * 创建任务 (简化版)
     */
    fun createTask(
        content: String,
        projectId: String? = null,
        sectionId: String? = null,
        description: String? = null,
        priority: Int? = null,
        dueString: String? = null,
        dueDate: String? = null,
        dueLang: String? = null
    ): Task {
        val request = CreateTaskRequest(
            content = content,
            projectId = projectId,
            sectionId = sectionId,
            description = description,
            priority = priority,
            dueString = dueString,
            dueDate = dueDate,
            dueLang = dueLang
        )
        return createTask(request)
    }

    /**
     * 更新任务
     */
    fun updateTask(taskId: String, request: UpdateTaskRequest): Task {
        val body = json.encodeToString(request)
        val response = sendRequest("/tasks/$taskId", "POST", body)
        return json.decodeFromString(Task.serializer(), response)
    }

    /**
     * 更新任务 (简化版)
     */
    fun updateTask(
        taskId: String,
        content: String? = null,
        description: String? = null,
        priority: Int? = null,
        dueString: String? = null
    ): Task {
        val request = UpdateTaskRequest(
            content = content,
            description = description,
            priority = priority,
            dueString = dueString
        )
        return updateTask(taskId, request)
    }

    /**
     * 关闭任务 (标记为完成)
     */
    fun closeTask(taskId: String): Boolean {
        return try {
            sendRequest("/tasks/$taskId/close", "POST")
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 重新打开任务
     */
    fun reopenTask(taskId: String): Boolean {
        return try {
            sendRequest("/tasks/$taskId/reopen", "POST")
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 删除任务
     */
    fun deleteTask(taskId: String): Boolean {
        return try {
            sendRequest("/tasks/$taskId", "DELETE")
            true
        } catch (_: Exception) {
            false
        }
    }
}











