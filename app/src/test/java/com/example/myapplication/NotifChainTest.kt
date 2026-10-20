package com.example.myapplication

import com.example.myapplication.data.NotifChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链身份（epoch / chainId）的回归测试（纯 JVM）
 *
 * 判据（都是"必须成立的性质"）：
 *   1 `next` 让 epoch 递增，chainId 换成新的时刻
 *   2**不同时刻 => 不同 chainId**（这是"能区分两段"的前提）
 *   3**`NONE` 不与任何值算同一条链**（空值不能冒充身份）
 *   4**重装场景**：`epoch` 从 1 重新数，但 **chainId 变了** => 必须判成【不同段】
 *       （这正是"用 epoch 比会误判"的反例 —— 判据必须落在 chainId 上）
 */
class NotifChainTest {

    @Test
    fun next_incrementsEpoch_andTakesNewChainId() {
        val a = NotifChain.next(NotifChain.Id(), nowMillis = 1_000L)
        assertEquals(1, a.epoch)
        assertEquals(1_000L, a.chainId)
        assertTrue(a.started)

        val b = NotifChain.next(a, nowMillis = 2_000L)
        assertEquals(2, b.epoch)
        assertEquals(2_000L, b.chainId)
    }

    @Test
    fun freshId_isNotStarted() {
        val z = NotifChain.Id()
        assertFalse(z.started)
        assertEquals(NotifChain.NONE, z.chainId)
        assertEquals(0, z.epoch)
    }

    /** 不同时刻开段 => chainId 必不同（能区分两段的前提） */
    @Test
    fun differentStartTimes_yieldDifferentChainIds() {
        val a = NotifChain.next(NotifChain.Id(), 1_000L)
        val b = NotifChain.next(a, 1_001L)
        assertFalse(NotifChain.sameChain(a.chainId, b.chainId))
    }

    /**重装场景：epoch 都回到 1，但 chainId 不同 => 必须判成【不同段】*/
    @Test
    fun reinstallScenario_epochRestarts_butChainIdDiffers() {
        val before = NotifChain.Id(chainId = 100_000L, epoch = 3)   // 重装前已经是第 3 段
        val afterInstall = NotifChain.next(NotifChain.Id(), 900_000L) // 重装 => prefs 没了 => 从 0 开始

        assertEquals("重装后 epoch 会从 1 重新数（所以不能用它判同一段）", 1, afterInstall.epoch)
        assertFalse(
            "必须靠 chainId 判出这是【不同段】",
            NotifChain.sameChain(before.chainId, afterInstall.chainId)
        )
    }

    /**`NONE` 不能与任何值算同一条链（空值不冒充身份） */
    @Test
    fun none_neverMatchesAnything() {
        assertFalse(NotifChain.sameChain(NotifChain.NONE, NotifChain.NONE))
        assertFalse(NotifChain.sameChain(NotifChain.NONE, 123L))
        assertFalse(NotifChain.sameChain(123L, NotifChain.NONE))
    }

    @Test
    fun sameNonZeroChainId_matches() {
        assertTrue(NotifChain.sameChain(555L, 555L))
        assertFalse(NotifChain.sameChain(555L, 556L))
    }

    @Test
    fun describe_isHumanReadable_andHandlesUnstarted() {
        assertEquals("（这段链还没开始）", NotifChain.describe(NotifChain.Id()))
        val d = NotifChain.describe(NotifChain.Id(chainId = 1_700_000_000_000L, epoch = 2))
        assertTrue("要能看出第几段", d.contains("第 2 段"))
        assertTrue("要带上 chainId 便于核对", d.contains("1700000000000"))
    }
}
