package com.liumiaoqi.notificationkeep

import com.liumiaoqi.notificationkeep.data.Gap
import com.liumiaoqi.notificationkeep.data.NotifEvent
import com.liumiaoqi.notificationkeep.data.NotifGap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断档计算的回归测试（纯 JVM）
 *
 * 判据（都是"必须成立的性质"，不是"代码长得对"）：
 *   1 断档 = `DISCONNECTED`/`SERVICE_DESTROY` 开、**只有 `CONNECTED` 能关**
 *   2 `SERVICE_CREATE` **不算**恢复（服务被创建 ≠ 已接上通知流 实测两者是两条事件）
 *   3 到最后仍开着的 => `closed=false`（"此刻仍在断档"是可表达的状态）
 *   4 输入乱序也要算对（按 captured 排序）
 *   5 普通通知（POST / REMOVED）**不产生**断档
 */
class NotifGapTest {

    private fun life(id: Long, event: String, at: Long) = NotifEvent(
        id = id, pkg = "com.liumiaoqi.notificationkeep", key = event, event = event,
        title = "", text = "", big = "", posted = at, reason = 0, captured = at,
    )

    private fun notif(id: Long, event: String = "POST", at: Long) = NotifEvent(
        id = id, pkg = "com.tencent.mobileqq", key = "k$id", event = event,
        title = "老张", text = "在吗", big = "", posted = at, reason = 0, captured = at,
    )

    @Test
    fun empty_isNoGaps() {
        assertEquals(0, NotifGap.gaps(emptyList()).size)
        assertNull(NotifGap.openGap(emptyList(), now = 0))
    }

    @Test
    fun normalNotifications_doNotCreateGaps() {
        val list = listOf(notif(1, at = 100), notif(2, "REMOVED", at = 200))
        assertEquals(0, NotifGap.gaps(list).size)
    }

    @Test
    fun connectedOnly_isNoGap() {
        val list = listOf(
            life(1, NotifGap.SERVICE_CREATE, 100),
            life(2, NotifGap.CONNECTED, 110),
        )
        assertEquals(0, NotifGap.gaps(list).size)
        // now = last record + exactly one threshold: silence is not yet over the line,
        // so the new "last record -> now" segment does not fire here.
        assertNull(NotifGap.openGap(list, now = 110 + NotifGap.DEFAULT_SILENCE_MILLIS))
    }

    @Test
    fun disconnectThenConnect_isOneClosedGap() {
        val list = listOf(
            life(1, NotifGap.CONNECTED, 100),
            life(2, NotifGap.DISCONNECTED, 200),
            life(3, NotifGap.CONNECTED, 500),
        )
        val g = NotifGap.gaps(list)
        assertEquals(1, g.size)
        assertEquals(Gap(200, 500, true), g[0])
        assertNull(NotifGap.openGap(list, now = 500 + NotifGap.DEFAULT_SILENCE_MILLIS))
    }

    /**核心：断开后没有恢复 => 必须报"此刻仍在断档"（而不是假装没有） */
    @Test
    fun disconnectWithoutReconnect_isOpenGap() {
        val list = listOf(
            life(1, NotifGap.CONNECTED, 100),
            life(2, NotifGap.DISCONNECTED, 200),
        )
        val g = NotifGap.gaps(list)
        assertEquals(1, g.size)
        assertFalse(g[0].closed)
        assertEquals(200L, g[0].fromCaptured)
        // This case keeps an open CERTAIN gap; now is again just inside the threshold so no
        // second (suspected) segment appears.  openGap must pick the LATEST open gap.
        assertEquals(200L, NotifGap.openGap(list, now = 200 + NotifGap.DEFAULT_SILENCE_MILLIS)!!.fromCaptured)
    }

    /** 关键：`SERVICE_CREATE` 不算恢复 —— 只有 `CONNECTED` 才算 */
    @Test
    fun serviceCreateAlone_doesNotCloseGap() {
        val list = listOf(
            life(1, NotifGap.DISCONNECTED, 100),
            life(2, NotifGap.SERVICE_CREATE, 200),
        )
        val g = NotifGap.gaps(list)
        assertEquals(1, g.size)
        assertFalse("SERVICE_CREATE 不该关掉断档", g[0].closed)
    }

    /** `SERVICE_DESTROY`（进程被杀/被更新）同样意味着听不到了 */
    @Test
    fun serviceDestroy_startsGap() {
        val list = listOf(
            life(1, NotifGap.CONNECTED, 100),
            life(2, NotifGap.SERVICE_DESTROY, 300),
            life(3, NotifGap.SERVICE_CREATE, 400),
            life(4, NotifGap.CONNECTED, 450),
        )
        val g = NotifGap.gaps(list)
        assertEquals(1, g.size)
        assertEquals(300L, g[0].fromCaptured)
        assertEquals(450L, g[0].toCaptured)
        assertTrue(g[0].closed)
    }

    @Test
    fun multipleGaps_areAllReportedInOrder() {
        val list = listOf(
            life(1, NotifGap.CONNECTED, 100),
            life(2, NotifGap.DISCONNECTED, 200),
            life(3, NotifGap.CONNECTED, 300),
            life(4, NotifGap.DISCONNECTED, 400),
            life(5, NotifGap.CONNECTED, 700),
        )
        val g = NotifGap.gaps(list)
        assertEquals(2, g.size)
        assertEquals(listOf(Gap(200, 300, true), Gap(400, 700, true)), g)
    }

    /** 乱序输入也要算对（用 captured 排序，不信任传入顺序） */
    @Test
    fun outOfOrderInput_isSortedByCaptured() {
        val list = listOf(
            life(3, NotifGap.CONNECTED, 500),
            life(1, NotifGap.DISCONNECTED, 200),
            life(2, NotifGap.SERVICE_CREATE, 300),
        )
        val g = NotifGap.gaps(list)
        assertEquals(1, g.size)
        assertEquals(Gap(200, 500, true), g[0])
    }

    @Test
    fun repeatedDisconnect_doesNotOpenTwice() {
        val list = listOf(
            life(1, NotifGap.DISCONNECTED, 100),
            life(2, NotifGap.DISCONNECTED, 150),
            life(3, NotifGap.CONNECTED, 400),
        )
        val g = NotifGap.gaps(list)
        assertEquals("连着两次断开只算一段", 1, g.size)
        assertEquals(100L, g[0].fromCaptured)
    }

    @Test
    fun totalGapMillis_countsOpenGapUpToNow() {
        val list = listOf(
            life(1, NotifGap.DISCONNECTED, 1_000),
            life(2, NotifGap.CONNECTED, 3_000),
            life(3, NotifGap.DISCONNECTED, 5_000),
        )
        // 闭合段 = 2000；未闭合段用 now=8000 补 => 3000；合计 5000
        assertEquals(5_000L, NotifGap.totalGapMillis(list, now = 8_000))
    }
    @Test
    fun gapEnd_isExclusive_aRecordAtTheRecoveryInstantIsNotInside() {
        //2026-09-28 加（B3 独立复核报的"闭区间"那条）
        //
        // 问题：`insideCertain(t)` 原来写的是 `t >= from && t <= to`（**闭区间**）
        //   而 `to` 是「下一次 `CONNECTED` 的时刻」=> **那一刻已经在监听了**
        //   => 所以一条**恰好落在恢复时刻**的记录会被判成"在断档里"
        //     => 后果：它会被 `suspectedGaps` 跳过 => **"恢复之后一直没消息"那一段报不出来**
        //
        // 判据（可观测的行为，不是内部函数）：
        //   造一段确定断档 [100, 200]，而最后一条记录恰好是 `captured = 200`
        //   => 那之后到 now 的"沉默"**应该**被报成一段确疑断档
        //     而闭区间会把它吞掉 => 这段测试在修前**必须失败**
        val list = listOf(
            life(1, "LISTENER_CONNECTED", 50),        // 先前就在听
            life(2, "LISTENER_DISCONNECTED", 100),    // 断档开始
            life(3, "LISTENER_CONNECTED", 200),       // 恢复（to = 200）
            notif(4, at = 200),                       // 恰好落在恢复那一刻
        )
        // 阈值为 60_000；now 设成 200 + 10 分钟 => 200 之后确实"沉默"了
        val gaps = NotifGap.allGaps(list, now = 200 + 600_000, thresholdMillis = 60_000)
        val suspected = gaps.filter { it.suspected }
        assertTrue(
            "恢复那一刻（captured=200）的记录不该被算进断档 => 它之后的沉默应报成一段疑似断档；" +
                "实际疑似段数=${suspected.size}",
            suspected.isNotEmpty(),
        )
        assertEquals(
            "疑似段的起点应是那条记录之后（200）",
            200L,
            suspected.first().fromCaptured,
        )

        // 反面对照：断档【内部】的记录仍然该被算进去
        val inside = listOf(
            life(1, "LISTENER_CONNECTED", 50),
            life(2, "LISTENER_DISCONNECTED", 100),
            life(3, "LISTENER_CONNECTED", 200),
            notif(4, at = 150),                       // 落在 [100,200) 里 => 确实在断档中
        )
        val gaps2 = NotifGap.allGaps(inside, now = 150 + 600_000, thresholdMillis = 60_000)
        // 150 在断档里 => 它不该成为"沉默的起点" => 疑似段应从 200 起（恢复只在 200）
        val s2 = gaps2.filter { it.suspected }
        assertEquals("疑似段仍应存在（且从 200 起）", 200L, s2.firstOrNull()?.fromCaptured)
    }
}
