package com.example.myapplication.crash

/**
 ***崩溃日志的轮转与上限**（纯逻辑 => JVM 可测）
 *
 ***为什么必须有上限**（这不是洁癖，是**本次缺陷的直接形态**）：
 *   真机实测：2026-10-08 那天 App **在 2 分钟内启动 4 次、4 次全 ANR**（同一签名）
 *   => 若日志无上限 => **崩一次写一条、把盘写满**
 *   => 上限 = `MAX_RECORDS` 条 => **崩溃循环自限**
 *   => 而且"20 条时间戳几乎一样"**本身就是崩溃循环的证据**（可读性）
 *
 * **为什么是"整个文件重写"而不是"追加"**：
 *   追加**没法淘汰旧的**（淘汰要从前面删）
 *   而文件有 256 KiB 上限 => 重写一次的代价可忽略
 *   => 写的时候用 `临时文件 + rename`（见 [CrashLogIO]）=> 写到一半崩了也**不会毁掉旧日志**
 *
 * **一条记录里出现"半个块"怎么办** —— 这里是有意选的方向：
 *   上一条写到一半断了 => **不删、不假装它不存在** =>
 *   把它**转成一条 [CrashRecord.KIND_TRUNCATED] 记录**留在原地
 *   （理由："进程死在写文件的过程中"本身就是要留的现场）
 */
object CrashRotate {

    /** 最多留几条记录（20 条 ≈ 覆盖"每次启动都崩"的循环） */
    const val MAX_RECORDS = 20

    /** 文件大小上限（256 KiB —— 一次 `cat` 读得完，且相对内部存储可忽略） */
    const val MAX_BYTES = 256 * 1024

    /**
     * 切分结果。
     *
     * @param records **完整的**块（只认以 [CrashRecord.END] 结尾的）
     * @param truncatedTail 最后一个**没写完**的块（空串 = 没有）
     */
    data class Split(val records: List<String>, val truncatedTail: String)

    /** 按 UTF-8 算字节数（写文件用的就是 UTF-8 => 这个数才是真的） */
    fun utf8Size(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /**
     * **按终止行切块**。
     *
     * **判据是 `line == END`（整行相等）**，不是 `contains` ——
     *   因为栈里可能**碰巧打印出**这一串（比如某条异常的消息里带了它）
     *   => 而 [CrashRecord.oneLine] 保证**单行字段**不可能整行等于它
     *   => 两层合起来才是"终止行唯一"
     * **记录之间的空行**（以及文件头空行）忽略；**ORPHAN 的终止行**也忽略
     */
    fun split(text: String): Split {
        if (text.isEmpty()) return Split(emptyList(), "")
        val records = ArrayList<String>()
        var buf: MutableList<String>? = null
        for (line in text.split('\n')) {
            if (buf == null) {
                if (line.isBlank()) continue
                if (line == CrashRecord.END) continue      // 孤儿终止行 => 忽略（不许造出空记录）
                buf = ArrayList()
            }
            buf.add(line)
            if (line == CrashRecord.END) {
                records.add(buf.joinToString("\n"))
                buf = null
            }
        }
        val tail = buf?.joinToString("\n")?.trim('\n').orEmpty()
        return Split(records, if (tail.isBlank()) "" else tail)
    }

    /**
     ***算出"整个文件该写成什么"**（`existing` = 现有全文，空串 = 还没有文件）。
     *
     * 淘汰顺序（两步都要，都不能把**新的**丢掉）：
     *   1 条数超 `maxRecords` => 丢最旧
     *   2 字节超 `maxBytes` => 丢最旧（至少留 1 条）
     *   3 单条**自己就超限** => **截断它**（绝不清空、绝不静默）
     *
     * 参数校验用 `require`（**响亮失败**，不静默产出一个畸形文件）——
     *   生产路径上永远传默认值 => 这条只在**有人误传**时响
     */
    fun plan(
        existing: String,
        incoming: String,
        maxBytes: Int = MAX_BYTES,
        maxRecords: Int = MAX_RECORDS,
    ): String {
        require(maxRecords >= 1) { "maxRecords 必须 ≥ 1（否则会把新记录也丢掉）" }
        require(maxBytes >= 1) { "maxBytes 必须 ≥ 1" }
        val sp = split(existing)
        val list = ArrayList<String>(sp.records.size + 2)
        // 残缺尾块 => 转成一条 TRUNCATED 记录（保住"上次写到一半断了"这个事实）
        if (sp.truncatedTail.isNotEmpty()) list.add(truncatedMarker(sp.truncatedTail))
        list.addAll(sp.records)
        list.add(incoming)

        val n = list.size
        // 每条后面各跟一个 '\n'（含最后一条）=> 与返回值的形状一致
        val sizes = IntArray(n) { utf8Size(list[it]) + 1 }
        var start = 0
        var total = sizes.sum()
        while (n - start > maxRecords) {
            total -= sizes[start]
            start++
        }
        while (n - start > 1 && total > maxBytes) {
            total -= sizes[start]
            start++
        }
        val kept = list.subList(start, n).toMutableList()
        // 3 单条就超限 => 截断（保留首尾片段 => 读的人仍能看出这是哪一类记录）
        if (kept.size == 1 && total > maxBytes) kept[0] = truncate(kept[0], maxBytes)
        return kept.joinToString("\n") + "\n"
    }

    /** 上一条写到一半断了 => 留一条**说明**，而不是把它删掉 */
    private fun truncatedMarker(tail: String): String =
        CrashRecord.render(
            CrashRecord.Fields(
                atMs = 0L, uptimeMs = 0L, stage = "-", pid = 0,
                kind = CrashRecord.KIND_TRUNCATED, threadName = "-",
                excClass = null,
                excMessage = "上一条记录写到一半就断了（进程在写文件时死了）",
                appVersion = "-", androidRelease = "-", sdkInt = 0, device = "-",
                stackLines = listOf("残缺字节数 = ${utf8Size(tail)}"),
                extra = listOf("partial" to "true"),
            )
        )

    /**
     * 单条就超上限 => **截断**（不是丢弃）。
     *
     * **为什么按"字符数"取 1/4 是安全的**：UTF-8 下**字符数 ≤ 字节数**
     *   => 取 `maxBytes / 4` 个字符 => 那一段的字节数**不会超过** `maxBytes / 4`
     *   => 头 + 尾 + 少量开销 < `maxBytes`（对 KiB 级的上限成立）
     */
    private fun truncate(record: String, maxBytes: Int): String {
        val orig = utf8Size(record)
        val budget = (maxBytes / 4).coerceAtLeast(16)
        return CrashRecord.render(
            CrashRecord.Fields(
                atMs = 0L, uptimeMs = 0L, stage = "-", pid = 0,
                kind = CrashRecord.KIND_TRUNCATED, threadName = "-",
                excClass = null,
                excMessage = "单条记录超过上限（$maxBytes 字节），只保留首尾片段",
                appVersion = "-", androidRelease = "-", sdkInt = 0, device = "-",
                stackLines = listOf(
                    record.take(budget),
                    "…（中间省略）…",
                    record.takeLast(budget),
                ),
                extra = listOf("origBytes" to orig.toString(), "kept" to "head+tail"),
            )
        )
    }
}
