package com.example.myapplication

import com.example.myapplication.data.Gap
import com.example.myapplication.data.NotifEvent
import com.example.myapplication.data.NotifGap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「疑似断档」的回归测试（纯 JVM）
 *
 * 为什么有这个功能（2026-09-28 实测教训）：
 *   装包时进程被 **SIGKILL** => **`onDestroy` 不执行** => `SERVICE_DESTROY` **没被写进库**
 *   => 只靠生命周期事件会**漏掉整类断档** => 补一手"沉默超过阈值"的推断，
 *     但**必须标成"疑似"**（它分不清"真断了"和"就是没人发消息"）
 *
 * 判据：1 阈值内不报 2 超阈值报且标 suspected 3 **确定断档区间内的沉默不重复报**
 *      4 生命周期事件本身不算"沉默"里的通知 5 排序稳健
 */
class NotifGapSuspectedTest {

    private fun n(id: Long, at: Long, pkg: String = "com.tencent.mobileqq") = NotifEvent(
        id = id, pkg = pkg, key = "k$id", event = "POST",
        title = "老张", text = "x", big = "", posted = at, reason = 0, captured = at,
    )

    private fun life(id: Long, event: String, at: Long) = NotifEvent(
        id = id, pkg = "com.example.myapplication", key = event, event = event,
        title = "", text = "", big = "", posted = at, reason = 0, captured = at,
    )

    private val T = NotifGap.DEFAULT_SILENCE_MILLIS

    @Test
    fun withinThreshold_isNotReported() {
        val list = listOf(n(1, 1_000), n(2, 1_000 + T))   // exactly threshold -> report nothing (uses <=)
        assertEquals(0, NotifGap.suspectedGaps(list, now = 1_000 + T + 1).size)
    }

    @Test
    fun beyondThreshold_isReportedAsSuspected() {
        val list = listOf(n(1, 1_000), n(2, 1_000 + T + 1))
        // now is one ms past the last record: only the PAIR gap can fire here
        val g = NotifGap.suspectedGaps(list, now = 1_000 + T + 2)
        assertEquals(1, g.size)
        assertTrue("必须标成疑似", g[0].suspected)
        assertTrue(g[0].closed)
        assertEquals(1_000L, g[0].fromCaptured)
        assertEquals(1_000L + T + 1, g[0].toCaptured)
    }

    /**核心：确定断档区间内的"沉默"不再报成疑似（避免同一段被算两次） */
    @Test
    fun silenceInsideCertainGap_isNotReportedAgain() {
        val list = listOf(
            n(1, 1_000),
            life(2, NotifGap.DISCONNECTED, 2_000),
            life(3, NotifGap.CONNECTED, 2_000 + T * 3),   // 长断档（有事件作证）
            n(4, 2_000 + T * 3 + 100),
        )
        assertEquals("确定断档 1 段", 1, NotifGap.certainGaps(list).size)
        assertEquals("this span is not reported again", 0, NotifGap.suspectedGaps(list, now = 2_000 + T * 3 + 200).size)
    }

    /** 确定断档之外的长沉默 => 照样报疑似 */
    @Test
    fun silenceOutsideCertainGap_isStillReported() {
        val list = listOf(
            life(1, NotifGap.CONNECTED, 500),
            n(2, 1_000),
            life(3, NotifGap.DISCONNECTED, 2_000),
            life(4, NotifGap.CONNECTED, 3_000),
            n(5, 4_000),
            n(6, 4_000 + T * 2),      // 这段是"正常监听中"的长沉默 => 疑似
        )
        val sus = NotifGap.suspectedGaps(list, now = 4_000 + T * 2 + 1)
        assertEquals(1, sus.size)
        assertEquals(4_000L, sus[0].fromCaptured)
    }

    /**A4 回归 根因1：**一条旧记录**也必须报"从那以后没在听"（改前恒返回 0） */
    @Test
    fun singleOldRecord_yieldsOpenSuspectedGap() {
        // 改前这里断言 0（那是把 bug 固化成了期望行为）——
        // `n(1, 1_000)` 是 1970-01-01 00:00:01，相对此刻是【56 年】的沉默
        val g = NotifGap.suspectedGaps(listOf(n(1, 1_000)), now = 1_000 + T * 2)
        assertEquals(1, g.size)
        assertTrue("必须标成疑似", g[0].suspected)
        assertFalse("必须是【未闭合】—— 此刻可能仍没在听", g[0].closed)
        assertEquals(1_000L, g[0].fromCaptured)
        assertEquals(1_000L + T * 2, g[0].toCaptured)
    }

    /**A4 回归 根因2：**最后一条 -> 此刻**那一段（改前从来没被检查过） */
    @Test
    fun lastRecordLongAgo_yieldsOpenSuspectedGap() {
        val list = listOf(n(1, 1_000), n(2, 2_000))
        // 两条之间只差 1 秒（不报）；但"第 2 条 -> 此刻"是 2 个阈值 => 必须报
        val g = NotifGap.suspectedGaps(list, now = 2_000 + T * 2)
        assertEquals("只有末尾那一段该报", 1, g.size)
        assertEquals(2_000L, g[0].fromCaptured)
        assertFalse(g[0].closed)
        assertTrue(g[0].suspected)
    }

    /**
     *A4 相关性质（不是"排序"那条 —— 那条测不出来，见下）：
     * **"确定断档未闭合"与"末尾疑似段"是互斥的**
     *   —— 因为确定区间的**右端点总是"最后一条记录"** =>
     *      最后一条永远落在确定区间里 => `insideCertain(last)` 恒为 true => 不报末尾疑似段
     */
    @Test
    fun openCertainGap_suppressesTheTrailingSuspectedSegment() {
        val list = listOf(
            life(1, NotifGap.DISCONNECTED, 10_000),
            n(2, 20_000),
        )
        val certain = NotifGap.certainGaps(list)
        assertEquals("确定断档 1 段（未闭合）", 1, certain.size)
        assertFalse(certain[0].closed)
        val now = 20_000 + T * 2
        // 末尾那段被确定区间吸收 => 疑似的为空
        assertEquals("确定段未闭合时，不再多出一个疑似段", 0, NotifGap.suspectedGaps(list, now).size)
        val g = NotifGap.openGap(list, now)
        assertEquals(10_000L, g!!.fromCaptured)
        assertFalse("取到的是确定段", g.suspected)
        // 诚实注记：`openGap` 里的 `.sortedBy { fromCaptured }` 是**防御性**的 ——
        //    上面这条性质保证"两段同时未闭合"不会出现 => **该排序测不出差别**
        //    （保留它是因为：若将来 `certainGaps` 的右端点语义改了，它就不会错）
    }

    @Test
    fun noEvents_yieldsNoGapsAtAll() {
        assertEquals(0, NotifGap.suspectedGaps(emptyList(), now = 5_000).size)
        // A4 FIX: a single record DOES produce a gap now -- "since then, nothing was heard".
        // Before the fix this returned 0, and a test pinned that bug (n(1, 1_000) is 1970,
        // i.e. 56 years of silence), so the assertion is inverted here on purpose.
        val one = NotifGap.suspectedGaps(listOf(n(1, 1_000)), now = 1_000 + T * 2)
        assertEquals(1, one.size)
        assertTrue(one[0].suspected)
        assertFalse(one[0].closed)
        assertEquals(1_000L, one[0].fromCaptured)
    }

    @Test
    fun allGaps_combinesAndSorts() {
        val list = listOf(
            n(1, 1_000),
            n(2, 1_000 + T * 2),            // 疑似（1_000 -> …）
            life(3, NotifGap.DISCONNECTED, 10_000_000),
            life(4, NotifGap.CONNECTED, 20_000_000),   // 确定
        )
        val all = NotifGap.allGaps(list, now = 20_000_000 + 1)
        assertTrue("至少含确定+疑似两类", all.any { it.suspected } && all.any { !it.suspected })
        assertEquals("按开始时间升序", all.sortedBy { it.fromCaptured }, all)
    }

    @Test
    fun lifecyclePredicate_isCorrect() {
        assertTrue(NotifGap.isLifecycle(NotifGap.CONNECTED))
        assertTrue(NotifGap.isLifecycle(NotifGap.DISCONNECTED))
        assertTrue(NotifGap.isLifecycle(NotifGap.SERVICE_CREATE))
        assertTrue(NotifGap.isLifecycle(NotifGap.SERVICE_DESTROY))
        assertFalse(NotifGap.isLifecycle("POST"))
        assertFalse(NotifGap.isLifecycle("REMOVED"))
    }

    @Test
    fun gapDataClass_defaultsToCertain() {
        val g = Gap(1, 2, true)
        assertFalse("默认不是疑似（向后兼容）", g.suspected)
    }
}
