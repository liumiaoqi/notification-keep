package com.example.myapplication

import com.example.myapplication.data.NotifEvent
import com.example.myapplication.data.metaRangeLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *`metaRangeLine()` 的 golden 测试（纯 JVM）
 *
 * 它押的是**覆盖范围**那一行 —— 而"覆盖了哪段时间"是接收方**判断相关性**的第一步
 *
 * 两个反直觉的判据各有一个测试：
 *   1 **零记录时 `coveredFrom`/`coveredTo` 写 `null`**（**不是 0**）
 *     —— 写 0 的话读侧会把它格式化成 **1970-01-01** => 接收方误读时间段（E6）
 *   2 **`now` 是入参**（函数里不调系统时钟）
 *     —— 那是它**可测**的前提：否则输出不可复现，golden 写不出来
 */
class MetaRangeLineGoldenTest {

    private fun rec(id: Long, captured: Long) = NotifEvent(
        id = id, pkg = "com.a", key = "k", event = "POST", title = "", text = "",
        big = "", posted = captured, reason = 0, captured = captured, selfHash = "h$id", prevHash = "p",
    )

    @Test
    fun emptyRecords_rangeIsNull_notZero() {
        //判据1：零记录 => 两个都是 null（不是 0）
        val out = metaRangeLine(emptyList(), now = 12345L)
        assertTrue("coveredFrom 该是 null：$out", out.contains("\"coveredFrom\":null"))
        assertTrue("coveredTo 该是 null：$out", out.contains("\"coveredTo\":null"))
        assertFalse("不许出现 coveredFrom:0（那会被读成 1970 年）", out.contains("\"coveredFrom\":0"))
        assertTrue("count 该是 0", out.contains("\"count\":0"))
    }

    @Test
    fun handledNowIsUsed_verbatim() {
        // 判据2：now 是从入参来的 => 同一个入参永远同一输出
        assertEquals(metaRangeLine(emptyList(), 999L), metaRangeLine(emptyList(), 999L))
        assertTrue(metaRangeLine(emptyList(), 999L).contains("\"exportedAt\":999"))
        assertTrue(metaRangeLine(emptyList(), 1L).contains("\"exportedAt\":1"))
    }

    @Test
    fun rangeIsMinAndMax_ofCaptured_notOfOrder() {
        // 取的是 **min/max**，而不是"第一条与最后一条"
        //   => 所以故意给一个乱序的列表
        val out = metaRangeLine(listOf(rec(1, 5000), rec(2, 1000), rec(3, 3000)), now = 0L)
        assertTrue("coveredFrom 该取最小 1000：$out", out.contains("\"coveredFrom\":1000"))
        assertTrue("coveredTo 该取最大 5000：$out", out.contains("\"coveredTo\":5000"))
        assertFalse("不该取第一条的 5000 当 from", out.contains("\"coveredFrom\":5000"))
    }

    @Test
    fun countEqualsHowManyRecords() {
        assertEquals(0, Regex("\"count\":(\\d+)").find(metaRangeLine(emptyList(), 0))!!.groupValues[1].toInt())
        val two = metaRangeLine(listOf(rec(1, 10), rec(2, 20)), 0L)
        assertEquals(2, Regex("\"count\":(\\d+)").find(two)!!.groupValues[1].toInt())
    }

    @Test
    fun singleRecord_rangeIsThatOne() {
        val out = metaRangeLine(listOf(rec(7, 4242)), now = 0L)
        assertTrue(out.contains("\"coveredFrom\":4242"))
        assertTrue(out.contains("\"coveredTo\":4242"))
        assertTrue(out.contains("\"count\":1"))
    }

    @Test
    fun isOneLine_andNumbersUnquoted() {
        val out = metaRangeLine(listOf(rec(1, 10)), 20L)
        assertFalse("不该含裸换行", out.contains('\n'))
        assertTrue("该以 { 开头", out.startsWith("{"))
        assertTrue("该以 } 结尾", out.endsWith("}"))
        assertFalse("数字不带引号", out.contains("\"exportedAt\":\""))
        assertFalse("count 不带引号", out.contains("\"count\":\""))
    }

    @Test
    fun fourKeysAllPresent() {
        val out = metaRangeLine(listOf(rec(1, 10)), 20L)
        for (k in listOf("exportedAt", "count", "coveredFrom", "coveredTo")) {
            assertTrue("缺键 $k：$out", out.contains("\"$k\":"))
        }
    }
}
