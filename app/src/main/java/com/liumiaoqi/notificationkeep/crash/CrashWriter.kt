package com.liumiaoqi.notificationkeep.crash

/**
 ***崩溃日志的"安全写入"**：**串行化 + 唯一临时名 + 写完回头校验**（纯逻辑 => JVM 可测）
 *
 ***它治的是"写不成也报"这一类静默失效**（独立复审报的 P2-1）。三个洞各一条对策：
 *
 * | 洞 | 为什么会丢 | 对策 |
 * |---|---|---|
 * | **并发写** | 两个写者同时做「读现有 -> plan -> 写回」=> **后写的把先写的整份覆盖** | [append] 把整段放进**同一把锁** |
 * | **临时文件撞名** | 两个写者用同一个 `x.tmp` => 互相写坏 | [tmpName] 带 **pid + nanoTime** => 每次都不一样 |
 * | **写失败没人知道** | `renameTo` 返回 false 也算成功 => 静默丢 | [checkWritten] 回头**核产物**（存在 + 字节数）|
 *
 * **为什么"端口"用 lambda 而不是直接吃 File**：
 *   那样本文件就**不碰文件系统** => **JVM 里能用内存假替身跑** ——
 *   于是"并发下一条都不丢"这条**能被确定性地测出来**（见 `CrashWriterTest` 的并发用例）
 *   判据同 `AGENTS.md` §三#4「纯逻辑必须可测」
 *
 * **它与 [CrashRotate] 的分工**：轮转/上限是 [CrashRotate.plan]（纯算法），
 *   本对象管**"什么时候写、写得安不安全"**
 */
object CrashWriter {

    /**
     * 一次写入的结果。
     *
     * **刻意不用 `Boolean`** —— "没成"必须能**说出为什么**
     *   （判据："写不成也报"的反面不是"报"，而是"**报得出原因**"）
     */
    sealed class Outcome {
        object Ok : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /**
     ***串行化的「读 -> plan -> 写」** —— 整段临界区在**一把锁**里
     *
     * @param lock **进程级**的那把锁（调用方持有 => 同一个文件的所有写者共用同一把）
     * @param read 读现有全文（**读失败必须抛**，绝不许"当空文件"=> 那会抹掉旧日志）
     * @param write 写回（由调用方做 tmp + rename + 校验 => 见 `CrashLogIO`）
     *
     * 三个 `try` **不是"兜底"**：它们把"抛出来"变成"**带原因地返回**"（双通道：原因进日志），
     *   而**没有任何异常被吞掉**
     */
    fun append(
        lock: Any,
        read: () -> String,
        write: (String) -> Outcome,
        record: String,
        maxBytes: Int = CrashRotate.MAX_BYTES,
        maxRecords: Int = CrashRotate.MAX_RECORDS,
    ): Outcome = synchronized(lock) {
        val existing = try {
            read()
        } catch (t: Throwable) {
            return Outcome.Failed("读现有日志失败：${t.javaClass.simpleName} ${t.message}")
        }
        val text = try {
            CrashRotate.plan(existing, record, maxBytes, maxRecords)
        } catch (t: Throwable) {
            return Outcome.Failed("轮转/拼接失败：${t.javaClass.simpleName} ${t.message}")
        }
        try {
            write(text)
        } catch (t: Throwable) {
            Outcome.Failed("写盘失败：${t.javaClass.simpleName} ${t.message}")
        }
    }

    /**
     * **临时文件名** —— **必须每次都不一样**（否则两个写者会写同一个 tmp => 互相写坏）
     *
     * 用 `pid + nanoTime`：`nanoTime` 在**同一进程内**单调且纳秒级 =>
     *   同一个写者连续两次调用也不会撞；跨进程由 `pid` 分开
     */
    fun tmpName(base: String, pid: Int, nano: Long): String = "$base.$pid.$nano.tmp"

    /**
     ***写完回头核产物**（这是"不准再出现『写不成也报』"的落点）
     *
     * @return `null` = 通过；否则 = **可读的失败原因**
     *
     * 核的是**产物**（存在 + 字节数），不是"调用没抛"
     *   （判据同 `coding-discipline` §四.13：判据落在产物上，不落在工具自述）
     */
    fun checkWritten(expectedBytes: Int, exists: Boolean, actualBytes: Long): String? = when {
        !exists -> "写完后目标文件不存在"
        actualBytes != expectedBytes.toLong() ->
            "写完后字节数不符（期望 $expectedBytes，实际 $actualBytes）"
        else -> null
    }
}
