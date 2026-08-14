package me.xiaozhangup.bot.port

import java.io.File

data class GroupFile(val id: String, val name: String, val uploadTime: Long)

abstract class Group(
    name: String,
    id: String
) : Source(name, id) {

    open fun getMemberName(memberId: String): String? = null

    open fun setBotNickname(nickname: String) {
        throw NotImplementedError()
    }

    open suspend fun sendFile(file: File) {
        throw NotImplementedError()
    }

    open suspend fun listFiles(): List<GroupFile> {
        throw NotImplementedError()
    }

    open suspend fun getFileUrl(fileId: String): String {
        throw NotImplementedError()
    }
}
