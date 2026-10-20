package com.example.myapplication

import com.example.myapplication.data.NotifReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 移除原因的回归测试（纯 JVM）
 *
 * 判据来自 **本机 SDK 源码**（`NotificationListenerService.java` 的注释），不是我的理解：
 *   8 = `REASON_APP_CANCEL`      "canceled by the **app** canceling this specific notification"
 *   9 = `REASON_APP_CANCEL_ALL`  "canceled by the app cancelling **all** its notifications"
 *   1 = `REASON_CLICK`（用户点的）2 = `REASON_CANCEL`（用户划掉的）
 *   19 = `REASON_TIMEOUT`（超时自动消失）
 */
class NotifReasonTest {

    @Test
    fun reason8_isAppCancel_andLabelMentionsIt() {
        assertTrue(NotifReason.isAppCancel(8))
        val l = NotifReason.label(8)
        assertTrue("标签里要保留 SDK 常量名", l.contains("APP_CANCEL"))
        assertTrue("标签要说明是应用自己取消的", l.contains("应用自己取消"))
    }

    @Test
    fun reason9_isAppCancelAll() {
        assertTrue(NotifReason.isAppCancel(9))
        assertTrue(NotifReason.label(9).contains("APP_CANCEL_ALL"))
    }

    @Test
    fun userInitiatedReasons_areNotAppCancel() {
        // 用户点了(1) / 用户划掉(2) / 一键清空(3) / 超时(19) 都【不是】应用自己取消
        listOf(1, 2, 3, 19).forEach { r ->
            assertFalse("reason=$r 不应被判成 appCancel", NotifReason.isAppCancel(r))
        }
    }

    @Test
    fun labelsKeepSdkConstantNames_soTheyCanBeChecked() {
        assertEquals(true, NotifReason.label(1).startsWith("CLICK"))
        assertEquals(true, NotifReason.label(2).startsWith("CANCEL"))
        assertEquals(true, NotifReason.label(19).startsWith("TIMEOUT"))
        assertEquals(true, NotifReason.label(2) != NotifReason.label(3))
    }

    @Test
    fun unknownReason_isShownWithNumber_notGuessed() {
        // 不认识的别硬编：把数字原样显示出来（不猜）
        assertEquals("未知(99)", NotifReason.label(99))
        assertEquals("未知(-1)", NotifReason.label(-1))
        assertFalse(NotifReason.isAppCancel(99))
    }

    @Test
    fun everyMappedReason_hasDistinctLabel() {
        // 1..24 都有标签，且互不相同（防复制粘贴写重复）
        val labels = (1..24).map { NotifReason.label(it) }
        assertEquals(24, labels.toSet().size)
        assertTrue(labels.none { it.startsWith("未知") })
    }
}
