package com.liumiaoqi.notificationkeep.data

/**
 * 一段「**没在监听**」的区间 —— 这就是"断档"
 *
 * @param fromCaptured 开始（收到 DISCONNECTED / SERVICE_DESTROY 的时刻，或"沉默"的起点）
 * @param toCaptured   结束（下一次 CONNECTED 的时刻，或"沉默"的终点）；[closed] = false 时无意义。
 *  区间语义是【左闭右开】`[fromCaptured, toCaptured)`。因为 `toCaptured` 那一刻**已经在监听了**
 * @param closed       是否已经恢复监听；`false` => **此刻仍处在断档中**
 * @param suspected   **是否是"疑似"** ——
 *   `false` = 有生命周期事件作证（**确定**）`true` = 只是"沉默太久"（**推断**）
 *   两者必须分开显示：能确定的说明确，不能确定的标"疑似"，**不冒充**
 */
data class Gap(
    val fromCaptured: Long,
    val toCaptured: Long,
    val closed: Boolean,
    val suspected: Boolean = false,
)

/**
 * **断档计算**（纯逻辑 不碰 Android API => 可在 JVM 里测）
 *
#   那 note 已移到 implemented/ => 这里写 proposed/ 是【过期路径】
 * 它要解决的问题（Agent Note：`.agents/notes/implemented/architecture/2026-09-28-offline-attestation-boundary.md`）：
 *   哈希链能证明"**已记录的内容没被改**"，但**证明不了"该记的都记了"** ——
 *   服务没在跑的那段时间，链**依然完全连续**，记录里看不出缺口。
 *
 ***两条路一起用（缺一不可，2026-09-28 实测后定）**：
 *   1 **确定断档**：靠 `LISTENER_DISCONNECTED` / `SERVICE_DESTROY` 开、`LISTENER_CONNECTED` 关
 *   2 **疑似断档**：实测发现"进程被 SIGKILL（装包/低内存）时 **`onDestroy` 不会被调用**"
 *      => 单靠事件会**漏掉整类断档** => 再补一手：**两条相邻记录"沉默"超过阈值**也算一段，
 *      但明确标成**疑似**（因为分不清"真断了"和"就是没人发消息"）
 */
object NotifGap {

    /** 生命周期事件名（`NotifListener` 写库时用同一批常量 => 一处定义） */
    const val SERVICE_CREATE = "SERVICE_CREATE"
    const val SERVICE_DESTROY = "SERVICE_DESTROY"
    const val CONNECTED = "LISTENER_CONNECTED"
    const val DISCONNECTED = "LISTENER_DISCONNECTED"

    /** 默认"沉默阈值"：30 分钟没有任何记录 => 值得标成疑似断档 */
    const val DEFAULT_SILENCE_MILLIS = 30L * 60 * 1000

    /** 这些事件意味着"从这里开始听不到了" */
    private val STOPS = setOf(DISCONNECTED, SERVICE_DESTROY)

    //2026-09-28 **撤回**（A3 的第一次修法被独立复核证伪）：
    //    我一度在这里加了 `RESTART_MIN_MILLIS`，并把"两个 `CONNECTED` 之间"
    //    当成一段**确定**断档。**那个前提是错的**：
    //      `CONNECTED` 会被**重复回调**（真机数据里有 0 s / 90 s / 251 s 的配对）
    //      而大间隔的窗口里**通知一直在进**（1438 行 POST 在那 12 小时里）
    //      我自己声称的"11 段 最长 43074 秒"是【相邻两个 `CONNECTED` 之差】，
    //        而**我实现的规则（前一条记录 -> reconnect）只会算出 4 段 最长 301.7 秒**
    //        => **注释里的数与代码会算出的数不是一回事**
    //    => 所以这一段**整体回退**，`certainGaps` 只认真正的停止事件
    //    而"重启期间的静默"由 `suspectedGaps`（相邻记录沉默 > 阈值）与
    //      A4 补的"最后一条 -> 此刻"覆盖 => **不需要在这里再判一次**
    //    回退是干净的：实测那几段的静默只有 171–302 秒，**远小于 30 分钟阈值**
    //    （记在 `2026-09-28-a1-a8-verifier-fixes.md` §十）

    /** 生命周期事件（即"不是通知"的那些行） */
    private val LIFECYCLE = setOf(SERVICE_CREATE, SERVICE_DESTROY, CONNECTED, DISCONNECTED)

    fun isLifecycle(event: String): Boolean = event in LIFECYCLE

    /**
     * **确定的**断档区间（有事件作证）。
     *
     * 输入不要求有序 —— 内部按 `captured` 排序后再算（稳健优先）
     * 只有 `CONNECTED` 能**结束**断档；`SERVICE_CREATE` **不算**恢复
     *    （服务被创建 ≠ 已接上通知流 —— 实测就是两条独立事件）
     */
    fun certainGaps(events: List<NotifEvent>): List<Gap> {
        val sorted = events.sortedBy { it.captured }
        val out = ArrayList<Gap>()
        var openFrom: Long? = null
        //2026-09-28 **撤回**（A3 的第一次修法被独立复核证伪 —— 见 §十）：
        //    我一度在这里把"两个 `CONNECTED` 之间"当成一段**确定**断档
        //    **那个前提是错的**：
        //      `CONNECTED` 会**重复回调**（真机数据里有 0 s / 90 s / 251 s 的配对）
        //      而大间隔窗口里**通知一直在进**（那 12 小时里有 **1438 行 POST**）
        //        => **监听一直在工作** => 那不是断档
        //      我声称的"11 段 最长 43074 秒"其实是【相邻两个 `CONNECTED` 之差】，
        //        而**我实现的规则只会算出 4 段 最长 301.7 秒**
        //        => 记一条：**注释里的数必须是代码会算出的数**
        //    => 所以整体回退：`certainGaps` **只认真正的停止事件**
        //    "重启期间的静默"由 `suspectedGaps`（相邻沉默 > 阈值）与
        //      A4 的"最后一条 -> 此刻"覆盖 => 不在这里再判一次
        //    回退干净：那几段的静默只有 171–302 秒，**远小于 30 分钟阈值** => 本就不该报
        for (e in sorted) {
            when (e.event) {
                in STOPS -> if (openFrom == null) openFrom = e.captured
                CONNECTED -> {
                    val f = openFrom
                    if (f != null) {
                        out.add(Gap(f, e.captured, closed = true, suspected = false))
                        openFrom = null
                    }
                }
            }
        }
        openFrom?.let {
            out.add(Gap(it, sorted.lastOrNull()?.captured ?: it, closed = false, suspected = false))
        }
        return out
    }

    /**
     * **疑似**断档：两条相邻记录之间"沉默"超过 [thresholdMillis]，
     * 且这段落在**确定断档之外**
     *
     * 为什么需要它：`SERVICE_DESTROY` **不可靠**（SIGKILL 不触发 `onDestroy` 实测）
     * 为什么标"疑似"：它**分不清**"真的没在听"和"只是那段时间没人发消息" => 不冒充确定
     */
    fun suspectedGaps(
        events: List<NotifEvent>,
        now: Long,
        thresholdMillis: Long = DEFAULT_SILENCE_MILLIS,
    ): List<Gap> {
        val sorted = events.sortedBy { it.captured }
        //2026-09-28 修（A4 根因1）：**去掉"少于 2 条就返回空"**
        //    改前 `if (sorted.size < 2) return emptyList()` =>
        //    记录只有 **0 或 1 条**时**永不报断档** —— 而那正是"服务被杀后再没回来"的样子
        //    => 现在只有**真的一条都没有**才返回空（因为那时没有锚点，不知道从何时起）
        if (sorted.isEmpty()) return emptyList()
        val certain = certainGaps(sorted)

        /** 落在某个确定断档区间内（含边界）=> 不再重复报为"疑似" */
        /**
     *段内判定 —— 区间是【左闭右开】`[from, to)`
     *
     *2026-09-28 修（B3 独立复核报的）：原来是**闭区间** `t <= to`
     *   而 `toCaptured` 的含义是「**下一次 `CONNECTED` 的时刻**」
     *   => **那一刻已经在监听了** => 它**不属于断档**
     *   => 后果：一条**恰好落在恢复时刻**的记录会被当成"在断档里" => 被 `suspectedGaps` 跳过
     *     =>**"恢复之后一直没消息"那一段报不出来**
     *   代价评估：影响只是**一个时间点**（概率低）—— 而判据是**它确实错**
     *     而且它高估了断档范围（把恢复那一刻算进去）=> 与"宁可少说"相反
     *
     * 已用一个**先失败**的测试演示过：
     *   `NotifGapTest.gapEnd_isExclusive_aRecordAtTheRecoveryInstantIsNotInside`
     *   （修前它 FAILED（`12 tests completed, 1 failed`）=> 修后过）
     */
    fun insideCertain(t: Long) = certain.any { g ->
        //`toCaptured` 有**两个含义**，端点归属相反（2026-09-28 量出来的）：
        //   1 **已闭合段**：它 = 「下一次 `CONNECTED` 的时刻」
        //      => **那一刻已经在监听了** => 它**不属于断档**
        //      => 所以右端开（`t < to`）
        //   2 **未闭合段**：它 = 「**最后一条记录**的 `captured`」
        //      => **那一刻我们确实还没在听** => 它**属于断档**
        //      => 所以右端闭（`t <= to`）
        //   一律用半开会破掉旧行为：未闭合段的右端就是最后一条记录
        //      => 那条记录不再被算在断档里 => 它之后又多出一段疑似
        //        （实测：`NotifGapSuspectedTest.openCertainGap_suppressesTheTrailingSuspectedSegment` 就是押这个的）
        g.fromCaptured <= t && (if (g.closed) t < g.toCaptured else t <= g.toCaptured)
    }

        val out = ArrayList<Gap>()
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            val span = b.captured - a.captured
            if (span <= thresholdMillis) continue
            if (insideCertain(a.captured) || insideCertain(b.captured)) continue
            out.add(Gap(a.captured, b.captured, closed = true, suspected = true))
        }
        //2026-09-28 修（A4 根因2）：**补上"最后一条记录 -> 此刻"那一段**
        //    改前循环只看 `i-1 -> i` => **最后一条之后到现在【从来没有被检查过】**
        //    => 后果：服务被杀**再也没回来**时（正是最该承认的那一段）
        //       既不报确定、也不报疑似 => 证据包写 `hasOpenGap = false`
        //       —— 交付出去的文件会**暗示"一直在听"**，而事实相反
        //    这一段是**未闭合**的（closed = false）仍然标"疑似"（分不清"断了"还是"没人发"）
        //    若最后一条**落在确定断档内** => 那段已被确定断档覆盖 => 不重复报
        val last = sorted.last()
        if (now - last.captured > thresholdMillis && !insideCertain(last.captured)) {
            out.add(Gap(last.captured, now, closed = false, suspected = true))
        }
        return out
    }

    /** 全部区间 = 确定的 + 疑似的（按开始时间排序）—— 导出与界面用这个 */
    fun allGaps(
        events: List<NotifEvent>,
        now: Long,
        thresholdMillis: Long = DEFAULT_SILENCE_MILLIS,
    ): List<Gap> =
        (certainGaps(events) + suspectedGaps(events, now, thresholdMillis)).sortedBy { it.fromCaptured }

    // 兼容旧调用（此前只有"确定断档"这一种）—— 保留名字，语义不变
    fun gaps(events: List<NotifEvent>): List<Gap> = certainGaps(events)

    /**
     * 此刻是否仍处在断档中（界面/导出要用）
     *
     *2026-09-28 修（A4 第三处）：**把"疑似（未闭合）"也算进来**
     *   改前只从 `certainGaps` 取 => **"服务被杀后再没回来"**那种断档
     *   **没有任何生命周期事件**（SIGKILL 不触发 `onDestroy`）
     *   => 它是**疑似的、且未闭合的** => 改前 `openGap` **报不出来**
     *   => 于是导出里写 `hasOpenGap = false` —— 而事实是"可能到现在都没在听"
     *   （判据：`openGap` 回答的是「**此刻还在断档吗**」=>
     *     那就不该只信"有事件作证的那一类"）
     */
    fun openGap(events: List<NotifEvent>, now: Long): Gap? =
        // 2026-09-28 修（A4 补）：**必须先按开始时间排序再取最后一段**
        //   改法原来是 `(certain + suspected).lastOrNull { !closed }` ——
        //   那个拼接**不保证有序**（certain 在前、suspected 在后）=>
        //   于是"既有确定断档、又有疑似段"时，它可能取到**较早的疑似段**
        //   => 而"此刻还在断档吗"这个问题的答案应该是**时间上最晚的那段**
        (certainGaps(events) + suspectedGaps(events, now))
            .sortedBy { it.fromCaptured }
            .lastOrNull { !it.closed }

    /** 断档总时长（毫秒）；未闭合的那段用 [now] 补（只算确定的） */
    fun totalGapMillis(events: List<NotifEvent>, now: Long): Long =
        certainGaps(events).sumOf { g -> (if (g.closed) g.toCaptured else now) - g.fromCaptured }
}
