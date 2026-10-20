package com.liumiaoqi.notificationkeep

import com.liumiaoqi.notificationkeep.data.NotifEvent
import com.liumiaoqi.notificationkeep.data.NotifFold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠 / 筛选的回归测试（纯 JVM `./gradlew testDebugUnitTest`）
 *
 * 测的都是**必须成立的性质**：
 *   1 折叠只动"连续的相同项"，**不动顺序语义**（A A B A => A×2, B×1, A×1）
 *   2 折叠**不改变原对象**（hash / key 原样 —— 数据层不许被展示层影响）
 *   3 筛选 null/空 = 全部；指定包名 = 只留那个
 *   4 社交过滤只挑社交包
 */
class NotifFoldTest {

    private fun ev(id: Long, pkg: String = "com.tencent.mobileqq", key: String = "k", text: String = "hi") =
        NotifEvent(
            id = id, pkg = pkg, key = key, event = "POST", title = "老张", text = text,
            big = "", posted = id, reason = 0, captured = id,
            selfHash = "hash$id", prevHash = "prev$id",
        )

    @Test
    fun fold_emptyList_returnsEmpty() {
        assertEquals(0, NotifFold.fold(emptyList()).size)
    }

    @Test
    fun fold_allDistinct_keepsAll() {
        val list = listOf(ev(1, text = "a"), ev(2, text = "b"), ev(3, text = "c"))
        val folded = NotifFold.fold(list)
        assertEquals(3, folded.size)
        assertTrue(folded.all { it.repeatCount == 1 })
    }

    /**核心：连续 3 条相同 => 1 条 ×3 */
    @Test
    fun fold_consecutiveDuplicates_collapseWithCount() {
        val list = listOf(ev(1, text = "正在运行"), ev(2, text = "正在运行"), ev(3, text = "正在运行"))
        val folded = NotifFold.fold(list)
        assertEquals(1, folded.size)
        assertEquals(3, folded[0].repeatCount)
    }

    /**核心：**不连续**的相同项**不能**被合并（顺序是信息） */
    @Test
    fun fold_nonConsecutiveDuplicates_areNotMerged() {
        val list = listOf(ev(1, text = "A"), ev(2, text = "B"), ev(3, text = "A"))
        val folded = NotifFold.fold(list)
        assertEquals(3, folded.size)
        assertEquals(listOf(1, 1, 1), folded.map { it.repeatCount })
        assertEquals(listOf("A", "B", "A"), folded.map { it.event.text })
    }

    @Test
    fun fold_mixedRun_producesExpectedRuns() {
        val list = listOf(
            ev(1, text = "A"), ev(2, text = "A"),
            ev(3, text = "B"),
            ev(4, text = "A"),
        )
        val folded = NotifFold.fold(list)
        assertEquals(listOf(2, 1, 1), folded.map { it.repeatCount })
        assertEquals(listOf("A", "B", "A"), folded.map { it.event.text })
    }

    /** 折叠**不改变**原事件（hash 必须原样 => 数据与展示分离） */
    @Test
    fun fold_doesNotMutateOrRewriteHashes() {
        val list = listOf(ev(1, text = "x"), ev(2, text = "x"))
        val folded = NotifFold.fold(list)
        assertEquals(1, folded.size)
        assertEquals("hash1", folded[0].event.selfHash)
        assertEquals("prev1", folded[0].event.prevHash)
        assertEquals(1L, folded[0].event.id)
    }

    /** 不同包名/不同 key 即使正文相同也**不算同一条** */
    @Test
    fun fold_differentPkgOrKey_areNotSame() {
        val list = listOf(ev(1, pkg = "com.tencent.mm"), ev(2, pkg = "com.tencent.mobileqq"))
        assertEquals(2, NotifFold.fold(list).size)

        val list2 = listOf(ev(1, key = "a"), ev(2, key = "b"))
        assertEquals(2, NotifFold.fold(list2).size)
    }

    @Test
    fun filterByPkg_nullOrEmpty_returnsAll() {
        val list = listOf(ev(1, pkg = "com.tencent.mm"), ev(2, pkg = "com.android.mms"))
        assertEquals(2, NotifFold.filterByPkg(list, null).size)
        assertEquals(2, NotifFold.filterByPkg(list, "").size)
    }

    @Test
    fun filterByPkg_keepsOnlyThatPackage() {
        val list = listOf(ev(1, pkg = "com.tencent.mm"), ev(2, pkg = "com.android.mms"))
        val out = NotifFold.filterByPkg(list, "com.tencent.mm")
        assertEquals(1, out.size)
        assertEquals("com.tencent.mm", out[0].pkg)
    }

    @Test
    fun filterSocial_keepsSocialOnly() {
        val list = listOf(
            ev(1, pkg = "com.tencent.mm"),
            ev(2, pkg = "com.android.mms"),
            ev(3, pkg = "com.xiaomi.finddevice"),
        )
        val out = NotifFold.filterSocial(list)
        assertEquals(2, out.size)
        assertTrue(out.none { it.pkg == "com.xiaomi.finddevice" })
    }

    @Test
    fun countByPkg_countsCorrectly() {
        val list = listOf(ev(1, pkg = "a"), ev(2, pkg = "a"), ev(3, pkg = "b"))
        val m = NotifFold.countByPkg(list)
        assertEquals(2, m["a"])
        assertEquals(1, m["b"])
    }
}
