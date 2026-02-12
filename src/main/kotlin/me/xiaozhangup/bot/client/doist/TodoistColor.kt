package me.xiaozhangup.bot.client.doist

enum class TodoistColor(
    val id: Int,
    val nameLower: String,
    val hex: String
) {

    BERRY_RED(30, "berry_red", "#B8255F"),
    RED(31, "red", "#DC4C3E"),
    ORANGE(32, "orange", "#C77100"),
    YELLOW(33, "yellow", "#B29104"),
    OLIVE_GREEN(34, "olive_green", "#949C31"),
    LIME_GREEN(35, "lime_green", "#65A33A"),
    GREEN(36, "green", "#369307"),
    MINT_GREEN(37, "mint_green", "#42A393"),
    TEAL(38, "teal", "#148FAD"),
    SKY_BLUE(39, "sky_blue", "#319DC0"),

    LIGHT_BLUE(40, "light_blue", "#6988A4"),
    BLUE(41, "blue", "#4180FF"),
    GRAPE(42, "grape", "#692EC2"),
    VIOLET(43, "violet", "#CA3FEE"),
    LAVENDER(44, "lavender", "#A4698C"),
    MAGENTA(45, "magenta", "#E05095"),
    SALMON(46, "salmon", "#C9766F"),
    CHARCOAL(47, "charcoal", "#808080"),
    GREY(48, "grey", "#999999"),
    TAUPE(49, "taupe", "#8F7A69");

    companion object {
        fun fromId(id: Int): TodoistColor? =
            values().find { it.id == id }

        fun fromLowerName(name: String): TodoistColor? =
            values().find { it.nameLower == name }
    }
}
