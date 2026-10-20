package com.example.myapplication.data

import java.security.MessageDigest

/**
 * **哈希链**：让"监测到的通知"能自证**没被改过、没被删过**（对应"三性两力"里的**真实性**）
 *
 * 为什么把它单独抽成一个**纯 Kotlin 对象**（不碰 Android API）：
 *   链条算法的正确性必须能**离机回归验证** ——
 *   "手工跑一遍看看"**证明不了**"篡改一定会被发现"
 *   => 抽出来 => 可以在 `src/test/` 里用 JUnit 直接测
 *   （这不是"架构"，只是**把不依赖平台的部分分开** —— 唯一的目的就是**可测**）
 *
 * 链条形式（每条都把前一条的 hash 混进自己的 hash）：
 * ```
 *   h1 = SHA256(GENESIS + SEP + payload1)
 *   h2 = SHA256(h1      + SEP + payload2)
 *   ...
 * ```
 * => **改中间任何一条**，它自己 + 后面每一条都算不出原值 => **断点可定位**
 * => **删掉中间一条**，后一条的 `prev` 对不上 => **照样能发现**
 */
object HashChain {

    /** 链的起点（64 个 0） */
    const val GENESIS: String =
        "0000000000000000000000000000000000000000000000000000000000000000"

    /**
     * 字段分隔符（U+0001）。
     * 用"内容里不太可能出现的控制字符"，而不是 `|` 这种普通字符 ——
     * 否则 `("a","b")` 与 `("ab","")` 会算出同一个指纹
     * 普通字符（不含分隔符）下的边界不歧义，有测试盯着
     *   （`HashChainTest.fieldBoundary_isNotAmbiguous`）
     *
     ***已知边界（2026-09-28 登记 来自 team 实验复核的 B1）**：
     *   上面那句「内容里**不可能**出现」是一条**假设**，不是机制
     *   若某字段**内容里含 `U+0001`** => **两个不同字段切分会拼出同一个 payload**
     *     => 同一个指纹 => 链区分不了那两条
     *     可执行证据：`HashChainTest.knownBoundary_separatorInContent_shiftsFieldBoundary`
     *   实测（真机库 3621 行）：**`text` 里 C0 控制字符确实会出现**
     *     （`U+0009` **1498 行** `U+000A` 4 `U+000D` 1）=> "控制字符不会出现"**是错的**
     *     而 **`U+0001` 本身恰好 0 行** => **今天没出过事**
     *   =>两条主张要分开：**(a) 含 `U+0001` 时会撞 = 已证明**；
     *      **(b) 本机数据上已发生 = 测得没有**
     *   **为什么先不修**：转义内容里的分隔符会**改变 payload**
     *     => **已有记录的 `self_hash` 全部失效**
     *     => 那是一次"链格式变更"（要开新链段 / 或者验证时按版本分派），
     *       必须单独评估，**不能顺手做**
     *   当前处置：**登记 + 写进发给第三方的说明书**（让接收方能判读）
     */
    private const val SEP = "\u0001"

    /** 一条记录的内容指纹（字段顺序固定 => 同样内容永远同样指纹） */
    fun payloadOf(e: NotifEvent): String = listOf(
        e.pkg,
        e.key,
        e.event,
        e.title,
        e.text,
        e.big,
        e.posted.toString(),
        e.reason.toString(),
        e.captured.toString(),
    ).joinToString(SEP)

    /**
     * SHA-256 -> 小写 hex（64 字符）
     *
     *2026-10-15（#4 根因修复）：**手写 hex，不走 `String.format`**
     *
     * **起因（有实锤）**：真机 ANR 的 bugreport 栈里就有 `String.format` ——
     *   `verifyChain -> HashChain.firstBroken -> link -> sha256 -> String.format`
     *   链校验要**逐条**算 hash => 每字节一次 `String.format` => 在大库上是**实打实的开销**
     *   （而"主线程全量校验"正是那次 ANR 的根因 => 这一处是**同一根因**的第二刀）
     *
     ***判据（银狼的审核重点2）：算法与输出一个字节都不许变**
     *   仍是 `SHA-256`（**同一个** `MessageDigest.getInstance("SHA-256")` 调用）
     *   仍是**小写、每字节 2 位**（与 `"%02x".format(b)` 逐字节等价）
     *   仍返回 **64 字符**（`HashChainTest` 本来就盯着 `length == 64` 与字符集）
     *   怎么验：**与旧写法直接对拍** + **独立已知向量**（见 `HashHexParityTest`）
     */
    fun sha256(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        // `and 0xFF` 是必须的：`Byte` 是有符号的 => 不清零会把 `-1` 算成负数 => 索引越界
        val hex = "0123456789abcdef"
        val sb = StringBuilder(64)
        for (b in d) {
            val v = b.toInt() and 0xFF
            sb.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return sb.toString()
    }

    /** 把"前一条的 hash"与"本条内容"混成"本条的 hash" */
    fun link(prevHash: String, payload: String): String = sha256(prevHash + SEP + payload)

    /**
     * 从链头开始逐条重算，返回**第一个对不上的 id**。
     *
     * @return `null` = 链条完整；否则 = 断在那条记录的 id（可定位）
     */
    fun firstBroken(events: List<NotifEvent>): Long? {
        var prev = GENESIS
        for (e in events) {
            val expect = link(prev, payloadOf(e))
            if (e.selfHash != expect) return e.id
            if (e.prevHash != prev) return e.id
            prev = expect
        }
        return null
    }
}
