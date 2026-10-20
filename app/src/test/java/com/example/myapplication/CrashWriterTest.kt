package com.example.myapplication

import com.example.myapplication.crash.CrashRecord
import com.example.myapplication.crash.CrashRotate
import com.example.myapplication.crash.CrashWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 *崩溃日志「安全写入」的测试（纯 JVM —— **不碰文件系统**，用内存假替身）
 *
 ***它押的是独立复审报的 P2-1 那三个洞**（每条都能被它验）：
 *   1 **并发写会互相覆盖** => 用例 `concurrentAppendsLoseNothing`（**丢一条就红**）
 *   2 **临界区必须整段串行** => 用例 `theLockSerializesTheReadPlanWriteSection`（同时只许 1 个）
 *   3 **写失败必须带原因** => 三条（读失败 / 写返回失败 / 写抛异常）
 *   外加"写完核产物"与"临时名唯一"各一条
 *
 * **为什么这里能用确定性用例**（而不是"跑跑看"）：
 *   [CrashWriter.append] 的**端口是 lambda** => 本用例喂**内存里的字符串**就够，
 *   于是"并发下一条都不丢"是**必然结论**，不是"碰巧过了"
 *   （判据：有对照 / 有确定的代价模型 => 才敢说"修好了"）
 *
 * **测不到的部分（如实写）**：
 *   **真**文件系统上的 `rename` 原子性 => 那要靠真机（`files/crash.log`）
 *   "写完核产物"在**真实磁盘**上读到的是不是真产物 => 同上（本用例只喂三个入参）
 */
class CrashWriterTest {

    /** 一条**能被 [CrashRotate.split] 数出来**的记录（以终止行结尾） */
    private fun rec(tag: String) = "$tag\n${CrashRecord.END}"

    private fun countOf(text: String) = CrashRotate.split(text).records.size

    //1并发：一条都不许丢

    @Test
    fun concurrentAppendsLoseNothing() {
        val lock = Any()
        var content = ""
        val read = { content }
        val write = { t: String -> content = t; CrashWriter.Outcome.Ok }

        val threads = 8
        val perThread = 20
        val ts = (1..threads).map { i ->
            Thread {
                repeat(perThread) { j -> CrashWriter.append(lock, read, write, rec("R$i-$j"), maxRecords = 1000) }
            }
        }
        ts.forEach { it.start() }
        ts.forEach { it.join() }

        //判据：写入次数 == 记录条数 => **一条都没被覆盖掉**
        assertEquals(
            "并发写必须一条不丢（这是 P2-1 的核心承诺）",
            threads * perThread,
            countOf(content),
        )
    }

    @Test
    fun theLockSerializesTheReadPlanWriteSection() {
        //直接量"临界区里同时有几个写者" —— 确定性的"单写者"证据
        val lock = Any()
        val inside = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val read = {
            val c = inside.incrementAndGet()
            maxConcurrent.updateAndGet { m -> maxOf(m, c) }
            try {
                Thread.sleep(1)          // 故意让重叠**几乎必然发生**（不串行化就会 >1）
            } finally {
                inside.decrementAndGet()
            }
            ""
        }
        val write = { _: String -> CrashWriter.Outcome.Ok }

        val ts = (1..4).map { Thread { repeat(5) { CrashWriter.append(lock, read, write, rec("X")) } } }
        ts.forEach { it.start() }
        ts.forEach { it.join() }

        assertEquals("临界区里同时最多只能有一个写者", 1, maxConcurrent.get())
    }

    //2 三种失败都要带原因（不许静默）

    @Test
    fun readFailureIsReportedAndNeverTreatedAsEmpty() {
        val wrote = AtomicBoolean(false)
        val outcome = CrashWriter.append(
            lock = Any(),
            read = { throw IllegalStateException("磁盘读不了") },
            write = { wrote.set(true); CrashWriter.Outcome.Ok },
            record = rec("X"),
        )
        val f = outcome as CrashWriter.Outcome.Failed
        assertTrue("原因里要带异常名 => ${f.reason}", f.reason.contains("IllegalStateException"))
        assertTrue("原因要说得清是【读】那一步 => ${f.reason}", f.reason.contains("读现有日志失败"))
        assertEquals("读失败时**绝不许**继续写（否则会抹掉旧日志）", false, wrote.get())
    }

    @Test
    fun writeFailurePropagatesItsReason() {
        val outcome = CrashWriter.append(
            lock = Any(),
            read = { "" },
            write = { CrashWriter.Outcome.Failed("rename 失败") },
            record = rec("X"),
        )
        assertEquals("rename 失败", (outcome as CrashWriter.Outcome.Failed).reason)
    }

    @Test
    fun writeThrowingBecomesAFailureInsteadOfEscaping() {
        val outcome = CrashWriter.append(
            lock = Any(),
            read = { "" },
            write = { throw java.io.IOException("磁盘满") },
            record = rec("X"),
        )
        val f = outcome as CrashWriter.Outcome.Failed
        assertTrue("抛出来要变成「失败 + 原因」=> ${f.reason}", f.reason.contains("IOException"))
        assertTrue("原因里要带可读的说明 => ${f.reason}", f.reason.contains("写盘失败"))
    }

    //3 轮转仍然照旧生效（串行化没把 plan 挤掉）

    @Test
    fun rotationStillAppliesThroughTheWriter() {
        val lock = Any()
        var content = ""
        repeat(5) { i ->
            CrashWriter.append(
                lock = lock,
                read = { content },
                write = { t -> content = t; CrashWriter.Outcome.Ok },
                record = rec("R$i"),
                maxRecords = 2,
            )
        }
        assertEquals("上限 2 => 只留最近两条", 2, countOf(content))
        assertTrue("留下的该是最近的 => $content", content.contains("R4") && content.contains("R3"))
    }

    //4 临时名唯一 + 产物核对

    @Test
    fun tmpNameIsUniquePerPidAndNano() {
        val a = CrashWriter.tmpName("crash.log", 100, 1L)
        val b = CrashWriter.tmpName("crash.log", 100, 2L)
        val c = CrashWriter.tmpName("crash.log", 200, 1L)
        assertEquals("crash.log.100.1.tmp", a)
        assertNotEquals("pid 同、nano 不同 => 必须不同", a, b)
        assertNotEquals("nano 同、pid 不同 => 必须不同", a, c)
        assertTrue("名字必须带 .tmp（收尾时要认得出是自己的散落产物）", a.endsWith(".tmp"))
    }

    @Test
    fun checkWrittenPassesOnlyWhenTheArtifactMatches() {
        assertNull("存在且字节数相符 => 通过", CrashWriter.checkWritten(10, true, 10L))
        assertTrue("不存在 => 失败", CrashWriter.checkWritten(10, false, 0L)!!.contains("不存在"))
        assertTrue("字节数不符 => 失败", CrashWriter.checkWritten(10, true, 9L)!!.contains("字节数不符"))
        assertTrue("长度不符时**要把两个数都写出来** => 便于排查",
            CrashWriter.checkWritten(10, true, 9L)!!.contains("10") &&
                CrashWriter.checkWritten(10, true, 9L)!!.contains("9"))
    }
}
