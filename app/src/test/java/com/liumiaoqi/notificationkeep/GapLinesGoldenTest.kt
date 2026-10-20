package com.liumiaoqi.notificationkeep

import com.liumiaoqi.notificationkeep.data.Gap
import com.liumiaoqi.notificationkeep.data.gapLine
import com.liumiaoqi.notificationkeep.data.gapSummaryLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *断档那两行的 golden 测试（纯 JVM）
 *
 * 它押的是**真实性**那一族 —— "承认我不知道的时段"
 *
 *而核心是**P2 那次的语义**（那段注释里记着）：
 *   改前只有一个 `hasOpenGap = gaps.any { !it.closed }`
 *   而 A4 之后 `gaps` **含疑似尾段** => 任何"安静超过阈值"也会把它置 true
 *     => 核对器打出「**此刻仍处于断档中**」—— **最强措辞配最弱证据**
 *   => 所以分成 `openCertainGap` / `openSuspectedGap`，而 `hasOpenGap` **保留**（老核对器能读）
 *   **下面 `onlySuspected_isNotOpenCertain` 那条就是押它的**
 */
class GapLinesGoldenTest {

    private fun g(from: Long, to: Long, closed: Boolean, suspected: Boolean) =
        Gap(fromCaptured = from, toCaptured = to, closed = closed, suspected = suspected)

    @Test
    fun emptyGaps_allZeroAndFalse() {
        val out = gapSummaryLine(emptyList())
        assertTrue(out.contains("\"gapCount\":0"))
        assertTrue(out.contains("\"certainGaps\":0"))
        assertTrue(out.contains("\"suspectedGaps\":0"))
        for (k in listOf("hasOpenGap", "openCertainGap", "openSuspectedGap")) {
            assertTrue("空时应是 false：" + k, out.contains("\"" + k + "\":false"))
        }
    }

    @Test
    fun onlySuspected_isNotOpenCertain() {
        //这条押的就是 P2：只有【疑似】未闭合时，
        //   `openCertainGap` 必须是 false（否则就是"最强措辞配最弱证据"）
        val out = gapSummaryLine(listOf(g(100, 200, closed = false, suspected = true)))
        assertTrue("该有疑似未闭合", out.contains("\"openSuspectedGap\":true"))
        assertFalse("不该说成确定未闭合", out.contains("\"openCertainGap\":true"))
        assertTrue("总布尔该为 true（有未闭合的）", out.contains("\"hasOpenGap\":true"))
    }

    @Test
    fun onlyCertain_isNotOpenSuspected() {
        val out = gapSummaryLine(listOf(g(100, 200, closed = false, suspected = false)))
        assertTrue(out.contains("\"openCertainGap\":true"))
        assertFalse(out.contains("\"openSuspectedGap\":true"))
        assertTrue(out.contains("\"hasOpenGap\":true"))
    }

    @Test
    fun closedGaps_doNotOpenAnything() {
        // 都闭合了 => 三个布尔都该 false
        val out = gapSummaryLine(listOf(g(1, 2, closed = true, suspected = false),
                                        g(3, 4, closed = true, suspected = true)))
        assertTrue(out.contains("\"hasOpenGap\":false"))
        assertTrue(out.contains("\"openCertainGap\":false"))
        assertTrue(out.contains("\"openSuspectedGap\":false"))
    }

    @Test
    fun countsAddUpToTotal() {
        // 判据：certain + suspected == gapCount
        val gaps = listOf(
            g(1, 2, true, false), g(3, 4, true, true), g(5, 6, false, false), g(7, 8, false, true),
        )
        val out = gapSummaryLine(gaps)
        assertTrue("gapCount 该是 4", out.contains("\"gapCount\":4"))
        assertTrue("certainGaps 该是 2", out.contains("\"certainGaps\":2"))
        assertTrue("suspectedGaps 该是 2", out.contains("\"suspectedGaps\":2"))
    }

    @Test
    fun booleansAndNumbersAreNotQuoted() {
        val out = gapSummaryLine(listOf(g(1, 2, true, false)))
        assertFalse("布尔不带引号", out.contains("\"hasOpenGap\":\""))
        assertFalse("数字不带引号", out.contains("\"gapCount\":\""))
    }

    @Test
    fun detailLine_hasFourFieldsWithNestedGap() {
        val out = gapLine(g(1000, 2000, closed = false, suspected = true))
        assertTrue("该嵌套在 gap 下", out.startsWith("{\"gap\":{"))
        for (k in listOf("from", "to", "closed", "suspected")) {
            assertTrue("缺字段 " + k + "：" + out, out.contains("\"" + k + "\":"))
        }
        assertTrue("from 该是 1000", out.contains("\"from\":1000"))
        assertTrue("to 该是 2000", out.contains("\"to\":2000"))
        assertTrue("closed 该是 false", out.contains("\"closed\":false"))
        assertTrue("suspected 该是 true", out.contains("\"suspected\":true"))
    }

    @Test
    fun detailLine_isOneLine() {
        val out = gapLine(g(1, 2, true, false))
        assertFalse("不该含裸换行", out.contains('\n'))
        assertTrue("该以 } 收尾", out.endsWith("}"))
    }
}
