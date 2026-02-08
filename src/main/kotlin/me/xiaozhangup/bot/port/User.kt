package me.xiaozhangup.bot.port

abstract class User(
    val name: String,
    val id: String
) : Source()