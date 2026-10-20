package com.liumiaoqi.notificationkeep

import com.liumiaoqi.notificationkeep.data.InsertFailCounter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 *进程级写入失败计数器的测试（纯 JVM 不需要 Android）
 *
 * **它【不】押"一个读不到的缺陷"** —— 我初稿那么写过，而**那是错的**
 *   依据：`git show 8a1b823` 的 diff 上下文显示，那个计数器当时就在
 *   `companion object` 内 => **本来就在进程级共享**（由独立复核者推翻）
 *   => 判据：**"静态还是实例"要看【它在哪个块里】，不看修饰词**
 *
 * **它押的是这次改动【真实的】那三条**（每条都能被它或别的命令验）：
 *   1 **可测**：一个独立 `object` => **JVM 里能直接调**
 *     （改前它嵌在 `NotifStore`（继承 `SQLiteOpenHelper`）里 => JVM 造不出来）
 *   2 **它必须是【同一份】**（`object` 语义）
 *     => 若哪天有人把它改成 `class` => `counterIsProcessWide` 那条会失败
 *   3 **`@Volatile` 存在** —— 那**测不了**（那是可见性、不是语义）
 *     => 所以如实写在下面："本条测不到它"
 *
 * **测不到的部分要写明**（这是本仓一贯的纪律）：真正的
 *   "监听线程写、界面线程读"那条路径 => **JVM 单测覆盖不到** => 只能靠端到端
 */
class InsertFailCounterTest {

    @After
    fun tearDown() {
        // 全局状态 => 每条测试自己清理，免得互相影响
        InsertFailCounter.resetForTest()
    }

    @Test
    fun startsAtZero() {
        InsertFailCounter.resetForTest()
        assertEquals("初始应为 0", 0, InsertFailCounter.count())
    }

    @Test
    fun note_incrementsTheSameSingleCounter() {
        InsertFailCounter.resetForTest()
        InsertFailCounter.note()
        assertEquals("一次失败 => 1", 1, InsertFailCounter.count())
        InsertFailCounter.note()
        InsertFailCounter.note()
        assertEquals("三次失败 => 3", 3, InsertFailCounter.count())
    }

    @Test
    fun counterIsProcessWide() {
        //核心判据：取两次引用必须是【同一份】
        //   若被改成 `class` => 这条失败 => 那正是要防的退化
        InsertFailCounter.resetForTest()
        val a = InsertFailCounter
        val b = InsertFailCounter
        a.note()
        assertEquals("两个引用必须是同一份（那正是它能跨实例共享的原因）", 1, b.count())
    }

    // 本条测不到的（如实写）：`@Volatile` 管的是【跨线程可见性】
    //   而 那是运行时行为 => 单测里断言不了 => 靠端到端看界面那个数
}
