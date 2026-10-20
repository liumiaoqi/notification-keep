package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "多久以前"的回归测试（纯 JVM —— `now` 由调用方传，所以能测）
 *
 * 判据：**边界要对**（60 秒 / 1 小时 / 1 天 是三档分界），
 * 而且 **未来的时间不能算成"刚刚"**（那会掩盖时钟被改的情况）
 */
class TimeAgoTest {

    private val base = 1_700_000_000_000L

    @Test
    fun secondsAgo_isJustNow() {
        assertEquals("刚刚", fmtAgo(base - 5_000, base))
        assertEquals("刚刚", fmtAgo(base - 59_999, base))
    }

    @Test
    fun minutesAndHoursAndDays() {
        assertEquals("1 分钟前", fmtAgo(base - 60_000, base))
        assertEquals("59 分钟前", fmtAgo(base - 59 * 60_000, base))
        assertEquals("1 小时前", fmtAgo(base - 3_600_000, base))
        assertEquals("23 小时前", fmtAgo(base - 23 * 3_600_000, base))
        assertEquals("1 天前", fmtAgo(base - 86_400_000, base))
        assertEquals("3 天前", fmtAgo(base - 3 * 86_400_000, base))
    }

    @Test
    fun boundary_isExclusiveAtEachStep() {
        // 59:59 还算"分钟"，刚好 60 分就进"小时"
        assertTrue(fmtAgo(base - 3_599_999, base).endsWith("分钟前"))
        assertTrue(fmtAgo(base - 3_600_000, base).endsWith("小时前"))
        assertTrue(fmtAgo(base - 86_399_999, base).endsWith("小时前"))
        assertTrue(fmtAgo(base - 86_400_000, base).endsWith("天前"))
    }

    @Test
    fun futureTime_isFlagged_notHidden() {
        // 未来时间（设备时钟被改过）不能显示成"刚刚" —— 那会掩盖问题
        val s = fmtAgo(base + 60_000, base)
        assertTrue(s.contains("未来"))
    }

    @Test
    fun fmtTime_isNonEmptyAndStableForFixedInput() {
        // 格式化结果只取决于传入的时间（同一输入两次结果相同）
        val a = fmtTime(base)
        val b = fmtTime(base)
        assertEquals(a, b)
        assertTrue(a.length >= 14)
    }
}
