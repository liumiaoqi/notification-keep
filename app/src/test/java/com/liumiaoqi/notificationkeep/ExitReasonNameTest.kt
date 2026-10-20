package com.liumiaoqi.notificationkeep

import com.liumiaoqi.notificationkeep.crash.ExitItem
import com.liumiaoqi.notificationkeep.crash.ExitReasonName
import com.liumiaoqi.notificationkeep.crash.ExitWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *系统退出原因（解码 + 该不该报警 + 去重游标）的测试（纯 JVM）
 *
 ***本文件里有一条"跨系统"的钉子**：`theRealAnrCodeIsSix`
 *   这些 int 是**照抄** Android 的常量 => 抄错了会**静默错名**
 *   => 所以把"**本机真机实测见过的那个值**"钉死：
 *     `adb shell dumpsys activity exit-info` 打出来的是 `reason=6 (ANR)`
 *     => 与 `name(6) == "ANR"` **必须一致**
 *     这就是"两个独立算路对上"（数字来自系统、名字来自我们）
 *
 * **为什么去重那几条也在这里**：[ExitWatch] 与 [ExitReasonName] 是同一件事的两半
 *   （"哪条值得写" + "写过没有"）=> 放一起读得顺
 *
 * **测不到的部分**："`getHistoricalProcessExitReasons` 真能拿到记录" => 要 Android
 *   => 真机验（命令见 `AGENTS.md` §五 那一节）
 */
class ExitReasonNameTest {

    //解码

    @Test
    fun theRealAnrCodeIsSix() {
        //与真机对照过：`dumpsys activity exit-info` 打的是 `reason=6 (ANR)`
        assertEquals(6, ExitReasonName.ANR)
        assertEquals("ANR", ExitReasonName.name(6))
    }

    @Test
    fun knownReasonsMapToStableNames() {
        val expected = mapOf(
            ExitReasonName.UNKNOWN to "UNKNOWN",
            ExitReasonName.EXIT_SELF to "EXIT_SELF",
            ExitReasonName.SIGNALED to "SIGNALED",
            ExitReasonName.LOW_MEMORY to "LOW_MEMORY",
            ExitReasonName.CRASH to "CRASH",
            ExitReasonName.CRASH_NATIVE to "CRASH_NATIVE",
            ExitReasonName.ANR to "ANR",
            ExitReasonName.INITIALIZATION_FAILURE to "INITIALIZATION_FAILURE",
            ExitReasonName.PERMISSION_CHANGE to "PERMISSION_CHANGE",
            ExitReasonName.EXCESSIVE_RESOURCE_USAGE to "EXCESSIVE_RESOURCE_USAGE",
            ExitReasonName.USER_REQUESTED to "USER_REQUESTED",
            ExitReasonName.USER_STOPPED to "USER_STOPPED",
            ExitReasonName.DEPENDENCY_DIED to "DEPENDENCY_DIED",
            ExitReasonName.OTHER to "OTHER",
            ExitReasonName.FREEZER to "FREEZER",
            ExitReasonName.PACKAGE_STATE_CHANGE to "PACKAGE_STATE_CHANGE",
            ExitReasonName.PACKAGE_UPDATED to "PACKAGE_UPDATED",
        )
        for ((code, nm) in expected) assertEquals("值 $code 的名字必须是 $nm", nm, ExitReasonName.name(code))
    }

    @Test
    fun unknownReasonKeepsItsCode() {
        // 未知值 => **必须带原值**（吞成 "UNKNOWN" 就等于丢信息）
        assertEquals("UNKNOWN(99)", ExitReasonName.name(99))
        assertEquals("UNKNOWN(-1)", ExitReasonName.name(-1))
    }

    @Test
    fun abnormalCoversTheKillersOnly() {
        // 要报警的
        for (r in listOf(ExitReasonName.ANR, ExitReasonName.CRASH, ExitReasonName.CRASH_NATIVE,
            ExitReasonName.LOW_MEMORY, ExitReasonName.INITIALIZATION_FAILURE,
            ExitReasonName.EXCESSIVE_RESOURCE_USAGE)) {
            assertTrue("${ExitReasonName.name(r)} 应当报警", ExitReasonName.isAbnormal(r))
        }
        // 不报警的（主动退出 / 装包 / 系统清理 => 每次都写会把真现场挤掉）
        for (r in listOf(ExitReasonName.EXIT_SELF, ExitReasonName.USER_REQUESTED,
            ExitReasonName.USER_STOPPED, ExitReasonName.PACKAGE_UPDATED,
            ExitReasonName.OTHER, ExitReasonName.UNKNOWN)) {
            assertFalse("${ExitReasonName.name(r)} 不该报警", ExitReasonName.isAbnormal(r))
        }
    }

    //去重：挑"新的、值得写的"

    private fun item(ts: Long, pid: Int, reason: Int) =
        ExitItem(tsMs = ts, pid = pid, reason = reason, importance = 100, pssKb = 1000, description = "d")

    @Test
    fun pickNewKeepsOnlyAbnormal() {
        val items = listOf(
            item(10, 1, ExitReasonName.ANR),
            item(11, 2, ExitReasonName.PACKAGE_UPDATED),
            item(12, 3, ExitReasonName.ANR),
        )
        val got = ExitWatch.pickNew(items, lastSeenTsMs = 0, lastSeenPid = 0, maxKeep = 5)
        assertEquals("只留两条 ANR", 2, got.size)
        assertTrue(got.all { it.reason == ExitReasonName.ANR })
    }

    @Test
    fun pickNewSkipsWhatWeAlreadyRecorded() {
        val items = listOf(item(10, 1, ExitReasonName.ANR), item(20, 2, ExitReasonName.ANR))
        val got = ExitWatch.pickNew(items, lastSeenTsMs = 10, lastSeenPid = 1, maxKeep = 5)
        assertEquals("已经写过的那条不许再写一遍", listOf(20L), got.map { it.tsMs })
    }

    @Test
    fun pickNewKeepsTheSameInstantWithADifferentPid() {
        //就是这条把"只看时刻"的写法挡住（只看时刻会漏掉 pid=3 那条）
        val items = listOf(item(10, 3, ExitReasonName.ANR))
        val got = ExitWatch.pickNew(items, lastSeenTsMs = 10, lastSeenPid = 1, maxKeep = 5)
        assertEquals("同一毫秒但不同 pid => 必须还能被写出来", listOf(3), got.map { it.pid })

        // 反向对照：**同一条**（时刻与 pid 都一样）不许重复写
        val again = ExitWatch.pickNew(items, lastSeenTsMs = 10, lastSeenPid = 3, maxKeep = 5)
        assertTrue("同一条不许重复写", again.isEmpty())
    }

    @Test
    fun pickNewSortsAscendingAndCapsAtMaxKeep() {
        val items = (1..8).map { item(it.toLong() * 10, it, ExitReasonName.ANR) }
        val got = ExitWatch.pickNew(items, lastSeenTsMs = 0, lastSeenPid = 0, maxKeep = 3)
        assertEquals("只留最近 3 条", 3, got.size)
        assertEquals("按时刻升序（最旧的先写）", got.map { it.tsMs }, got.map { it.tsMs }.sorted())
        assertEquals("留的是最新的那 3 条", listOf(60L, 70L, 80L), got.map { it.tsMs })
    }

    @Test
    fun cursorPicksTheLatestByTimestampAndPid() {
        val items = listOf(
            item(10, 1, ExitReasonName.ANR),
            item(30, 2, ExitReasonName.ANR),
            item(30, 7, ExitReasonName.ANR),
        )
        assertEquals("游标要取 (时刻, pid) 元组下的最大者", 30L to 7, ExitWatch.cursor(items))
        assertEquals("空列表 => 没有游标", null, ExitWatch.cursor(emptyList()))
    }
}
