package com.example.myapplication.data

/**
 * 一条**展示项**：一个代表事件 + 它后面连续跟了多少条一模一样的。
 *
 * 注意：这**只用于展示** —— 数据层一条都不删、不合并
 *   原因（lmq 的判断，我认同）：**今天的"多此一举"，明天可能就是那个能帮我一把的记录**
 *   而且完整性正是"哈希链能自证"的前提
 */
data class FoldedEvent(val event: NotifEvent, val repeatCount: Int)

/**
 * **纯逻辑**（不碰 Android API）=> 可以在 JVM 里直接测
 *
 * 两个动作，都只影响"怎么显示"，**绝不影响存下来的数据**：
 *   1 [fold]   把**连续重复**的同一条通知折叠成"一条 ×N"（治"通知风暴"）
 *   2 [filterByPkg] 只显示指定 App（治"我希望它只监听个别软件"）
 */
object NotifFold {

    /** 默认视图候选：社交/通信类（可随时改，不影响数据） */
    val SOCIAL_PKGS: List<String> = listOf(
        "com.tencent.mm",         // 微信
        "com.tencent.mobileqq",   // QQ
        "com.tencent.tim",        // TIM
        "com.android.mms",        // 短信
        "org.telegram.messenger",
    )

    /** 折叠用的"同一性"判据：包名 + key + 事件类型 + 标题 + 正文 全同 */
    private fun sameAs(a: NotifEvent, b: NotifEvent): Boolean =
        a.pkg == b.pkg && a.key == b.key && a.event == b.event &&
            a.title == b.title && a.text == b.text

    /**
     * 把**连续**的相同通知折叠成一条（`repeatCount` = 实际条数）。
     *
     * 只折**连续**的：`A A B A` => `[A×2, B×1, A×1]`
     *   因为顺序本身是信息（取证要时序），不能把不连续的重排到一起
     */
    fun fold(list: List<NotifEvent>): List<FoldedEvent> {
        if (list.isEmpty()) return emptyList()
        val out = ArrayList<FoldedEvent>()
        var cur = list[0]
        var n = 1
        for (i in 1 until list.size) {
            val e = list[i]
            if (sameAs(cur, e)) {
                n++
            } else {
                out.add(FoldedEvent(cur, n))
                cur = e; n = 1
            }
        }
        out.add(FoldedEvent(cur, n))
        return out
    }

    /** 按包名筛选；`pkg == null` 或空串 => 全部 */
    fun filterByPkg(list: List<NotifEvent>, pkg: String?): List<NotifEvent> =
        if (pkg.isNullOrEmpty()) list else list.filter { it.pkg == pkg }

    /** 默认视图：只留社交类（只是视图） */
    fun filterSocial(list: List<NotifEvent>): List<NotifEvent> =
        list.filter { it.pkg in SOCIAL_PKGS }

    /** 某个包名在这批数据里的条数（用于给筛选按钮标数量） */
    fun countByPkg(list: List<NotifEvent>): Map<String, Int> =
        list.groupingBy { it.pkg }.eachCount()
}
