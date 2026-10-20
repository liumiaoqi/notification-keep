package com.liumiaoqi.notificationkeep

import com.liumiaoqi.notificationkeep.crash.CrashRecord
import com.liumiaoqi.notificationkeep.crash.CrashRotate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *崩溃日志**轮转 / 上限**的测试（纯 JVM 不需要 Android）
 *
 ***它押的是一件真事**（不是"补覆盖率"）：
 *   真机实测 2026-10-08 那天 App **在 2 分钟内启动 4 次、4 次全 ANR**（同一签名）
 *   => 若无上限 => **崩一次写一条、把盘写满**
 *   => 所以"**崩溃循环下文件仍是有界的**"必须被验到
 *
 * **每条用例都对着一条具体行为的断言**（有动作就有断言）：
 *   上界两维（条数 / 字节）各一条
 *   **新记录永远不被丢掉**（最要紧 —— 丢了就等于这次白崩）
 *   残缺尾块**转成记录**而不是被删（不静默）
 *   单条超限**截断**而不是清空
 *
 * **测不到的部分**："写盘用的是 `临时文件 + rename`" => 那是 [com.liumiaoqi.notificationkeep.crash.CrashLogIO]
 *   的事（要 Context / 真文件系统）=> 只能真机验
 */
class CrashRotateTest {

    private fun rec(tag: String, pad: Int = 0): String =
        CrashRecord.render(
            CrashRecord.Fields(
                atMs = 1_760_000_000_000L, uptimeMs = 1L, stage = "activity_create", pid = 1,
                kind = CrashRecord.KIND_UNCAUGHT, threadName = "main",
                excClass = "java.lang.RuntimeException", excMessage = tag + "|" + "x".repeat(pad),
                appVersion = "1.0(1)", androidRelease = "16", sdkInt = 36, device = "d",
                stackLines = listOf("at $tag"),
            )
        )

    private fun recordsIn(text: String) = CrashRotate.split(text).records
    private fun bytesOf(text: String) = CrashRotate.utf8Size(text)

    //切块

    @Test
    fun splitKeepsOnlyCompleteBlocks() {
        val text = rec("A") + "\n" + rec("B") + "\n"
        val sp = CrashRotate.split(text)
        assertEquals("两块完整的", 2, sp.records.size)
        assertEquals("没有残缺尾块", "", sp.truncatedTail)
        assertTrue(sp.records.all { it.trimEnd('\n').endsWith(CrashRecord.END) })
    }

    @Test
    fun splitReportsTruncatedTailInsteadOfSwallowingIt() {
        val text = rec("A") + "\nhalf written line\n"
        val sp = CrashRotate.split(text)
        assertEquals("只有一块完整", 1, sp.records.size)
        assertEquals("残缺尾块必须被认出来（不许静默丢）", "half written line", sp.truncatedTail)
    }

    @Test
    fun splitToleratesBlankLinesBetweenRecords() {
        val text = "\n" + rec("A") + "\n\n" + rec("B") + "\n\n"
        val sp = CrashRotate.split(text)
        assertEquals("空行不算内容", 2, sp.records.size)
        assertEquals("", sp.truncatedTail)
    }

    @Test
    fun splitOfEmptyIsEmpty() {
        val sp = CrashRotate.split("")
        assertEquals(0, sp.records.size)
        assertEquals("", sp.truncatedTail)
    }

    //上限：条数

    @Test
    fun planAppendsWhileUnderTheRecordLimit() {
        var text = ""
        for (t in listOf("A", "B", "C")) text = CrashRotate.plan(text, rec(t))
        assertEquals(3, recordsIn(text).size)
        assertTrue("结果必须以换行收尾", text.endsWith("\n"))
    }

    @Test
    fun planDropsTheOldestWhenOverTheRecordLimit() {
        var text = ""
        for (t in listOf("A", "B", "C", "D")) text = CrashRotate.plan(text, rec(t), maxRecords = 2)
        val names = recordsIn(text).map { it.substringAfter("msg=").substringBefore("|") }
        assertEquals("只留最近两条", listOf("C", "D"), names)
    }

    @Test
    fun planNeverDropsTheNewestRecord() {
        //最要紧的一条：上限哪怕设成 1，也**必须留下刚发生的那次**
        val text = CrashRotate.plan(rec("OLD"), rec("NEW"), maxRecords = 1)
        val names = recordsIn(text).map { it.substringAfter("msg=").substringBefore("|") }
        assertEquals(listOf("NEW"), names)
    }

    //上限：字节

    @Test
    fun planDropsTheOldestWhenOverTheByteLimit() {
        val one = rec("A", pad = 400)
        // 上限刚好装不下两条 => 只该留新的那条
        val text = CrashRotate.plan(one + "\n", rec("B", pad = 400), maxBytes = bytesOf(one) * 3 / 2)
        assertTrue("字节超限 => 丢最旧", bytesOf(text) <= bytesOf(one) * 3 / 2)
        val names = recordsIn(text).map { it.substringAfter("msg=").substringBefore("|") }
        assertEquals("留下的是新的那条", listOf("B"), names)
    }

    @Test
    fun planKeepsTheFileBoundedAcrossACrashLoop() {
        //模拟"每次启动都崩"：写 60 次 => 文件**必须**被 20 条 / 256 KiB 卡住
        var text = ""
        repeat(60) { i -> text = CrashRotate.plan(text, rec("crash$i", pad = 200)) }
        assertTrue("条数必须被 MAX_RECORDS 卡住（实际 ${recordsIn(text).size}）",
            recordsIn(text).size <= CrashRotate.MAX_RECORDS)
        assertTrue("字节必须被 MAX_BYTES 卡住（实际 ${bytesOf(text)}）",
            bytesOf(text) <= CrashRotate.MAX_BYTES)
    }

    //单条超限：截断而不是清空

    @Test
    fun planTruncatesASingleRecordTooBigToFit() {
        val huge = rec("HUGE", pad = 20_000)
        val maxBytes = 4096
        val text = CrashRotate.plan("", huge, maxBytes = maxBytes)
        assertTrue("必须留下一条记录（不是清空）", recordsIn(text).size == 1)
        assertTrue("截断后的记录必须仍然是合法记录（头 / 尾都在）",
            text.trimEnd('\n').startsWith(CrashRecord.HEADER) && text.trimEnd('\n').endsWith(CrashRecord.END))
        assertTrue("必须标明它被截断了 => 实际 ${bytesOf(text)} 字节", text.contains(CrashRecord.KIND_TRUNCATED))
        assertTrue("截断后必须塞进上限（实际 ${bytesOf(text)} ≤ $maxBytes）", bytesOf(text) <= maxBytes)
    }

    //残缺尾块：转成记录，不静默删

    @Test
    fun planTurnsATruncatedTailIntoARecord() {
        val existing = rec("A") + "\n这是一条没写完的记录"
        val text = CrashRotate.plan(existing, rec("B"))
        val sp = CrashRotate.split(text)
        assertEquals("残缺尾块必须变成一条记录（A + TRUNCATED + B）", 3, sp.records.size)
        assertEquals("处理完之后不该再有残缺尾块", "", sp.truncatedTail)
        assertTrue("那条记录要标明是截断的", text.contains(CrashRecord.KIND_TRUNCATED))
        assertTrue("原来那一块也要还在", text.contains("at A"))
    }

    //参数校验：响亮失败

    @Test
    fun planRejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            CrashRotate.plan("", rec("A"), maxRecords = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CrashRotate.plan("", rec("A"), maxBytes = 0)
        }
    }
}
