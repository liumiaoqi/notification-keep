package com.example.myapplication

import com.example.myapplication.data.HashChain
import com.example.myapplication.data.NotifEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 哈希链的**真回归测试**（纯 JVM，不碰手机 —— `./gradlew testDebugUnitTest` 就能跑）
 *
 * 测的是**判据**，不是"代码长得对"：
 *   1 同样输入 => 同样 hash（确定性）
 *   2 改**任何一个字段** => hash 必变
 *   3 **字段边界不能糊掉**（`("a","b")` 与 `("ab","")` 必须不同）
 *   4 **篡改中间一条** => 断点能被定位出来
 *   5 **删掉中间一条** => 照样能发现
 */
class HashChainTest {

    private fun ev(
        id: Long,
        pkg: String = "com.tencent.mobileqq",
        key: String = "k$id",
        event: String = "POST",
        title: String = "老张",
        text: String = "在吗",
        posted: Long = 1000L + id,
        reason: Int = 0,
        captured: Long = 2000L + id,
        selfHash: String = "",
        prevHash: String = "",
    ) = NotifEvent(
        id = id, pkg = pkg, key = key, event = event, title = title, text = text,
        big = "", posted = posted, reason = reason, captured = captured,
        selfHash = selfHash, prevHash = prevHash,
    )

    /** 把一串记录按链条规则算好（模拟"当初真的这么写入的"） */
    private fun chained(list: List<NotifEvent>): List<NotifEvent> {
        var prev = HashChain.GENESIS
        return list.map { e ->
            val h = HashChain.link(prev, HashChain.payloadOf(e))
            val out = e.copy(selfHash = h, prevHash = prev)
            prev = h
            out
        }
    }

    @Test
    fun deterministic_sameInput_sameHash() {
        val a = ev(1)
        val b = ev(1)
        assertEquals(HashChain.payloadOf(a), HashChain.payloadOf(b))
        assertEquals(
            HashChain.sha256(HashChain.payloadOf(a)),
            HashChain.sha256(HashChain.payloadOf(b)),
        )
    }

    @Test
    fun hash_is64HexChars() {
        val h = HashChain.sha256("hello")
        assertEquals(64, h.length)
        assertEquals(true, h.all { it in "0123456789abcdef" })
    }

    @Test
    fun changingAnySingleField_changesHash() {
        val base = HashChain.payloadOf(ev(1))
        val variants = listOf(
            HashChain.payloadOf(ev(1, pkg = "com.tencent.mm")),
            HashChain.payloadOf(ev(1, key = "other")),
            HashChain.payloadOf(ev(1, event = "REMOVED")),
            HashChain.payloadOf(ev(1, title = "小李")),
            HashChain.payloadOf(ev(1, text = "在吗?")),
            HashChain.payloadOf(ev(1, posted = 9999L)),
            HashChain.payloadOf(ev(1, reason = 8)),
            HashChain.payloadOf(ev(1, captured = 8888L)),
        )
        variants.forEachIndexed { i, v ->
            assertNotEquals("第 $i 个变体应当改变指纹", base, v)
        }
    }

    /** 字段边界测试：分隔符选错的话，这两组会撞成同一个指纹 */
    @Test
    fun fieldBoundary_isNotAmbiguous() {
        val x = HashChain.payloadOf(ev(1, title = "a", text = "b"))
        val y = HashChain.payloadOf(ev(1, title = "ab", text = ""))
        assertNotEquals(x, y)
    }

    /**
     ***已知边界的证据**（2026-09-28 加）—— **这一条测的是"我们会撞"，不是"我们想撞"**
     *
     * 事实：`payloadOf` 用 `U+0001` 拼 9 个字段。若某个字段**内容里含 `U+0001`**，
     * 则**两个不同字段切分会拼出同一个字符串** => **同一个指纹**
     *
     * 为什么留着这条"会失败形状"的用例：它是**边界的可执行证据** ——
     * 把"我们假设 U+0001 不会出现在内容里"从**口头假设**变成**一条可复算的断言**
     *
     * 实测（真机库 3621 行）：
     *   `text` 里 **C0 控制字符确实会出现**（`U+0009` 1498 行 `U+000A` 4 `U+000D` 1）
     *     => 所以"控制字符不会出现在内容里"这句话**是错的**
     *   但 **`U+0001` 本身恰好 0 行** => **今天没出事**
     *   =>两条主张分开：**(a) 算法在字段含 `U+0001` 时会碰撞 = 已证明**；
     *      **(b) 它在本机数据上已经发生 = 测得没有**
     *
     * 处置（C 方案）：先**登记 + 说明书里写明**。
     *   真正的修法（转义内容里的分隔符）会**让已有记录的 `self_hash` 全部失效**
     *   => 那是一次"链格式变更"，要单独评估、不能顺手做
     */
    @Test
    fun knownBoundary_separatorInContent_shiftsFieldBoundary() {
        val sep = "\u0001"
        // 只有 title / text 两处不同，其余字段（含默认值）都相同 => 字段数相同
        val a = HashChain.payloadOf(ev(1, title = "a$sep" + "b", text = "c"))
        val b = HashChain.payloadOf(ev(1, title = "a", text = "b$sep" + "c"))
        assertEquals("两个不同的字段切分拼出了同一个 payload（这就是边界平移）", a, b)
        assertEquals("=> 于是指纹也相同 => 链无法区分这两条", HashChain.sha256(a), HashChain.sha256(b))
    }

    @Test
    fun intactChain_verifiesClean() {
        val chain = chained((1L..5L).map { ev(it) })
        assertNull(HashChain.firstBroken(chain))
    }

    @Test
    fun emptyChain_isTriviallyIntact() {
        assertNull(HashChain.firstBroken(emptyList()))
    }

    /**核心判据：改掉中间第 3 条的内容 => 断点必须是第 3 条 */
    @Test
    fun tamperingMiddleRecord_isDetectedAtThatId() {
        val chain = chained((1L..5L).map { ev(it) })
        val tampered = chain.toMutableList()
        tampered[2] = tampered[2].copy(text = "偷偷改成别的内容")
        assertEquals(3L, HashChain.firstBroken(tampered))
    }

    /**核心判据：删掉第 3 条 => 第 4 条的 prev 对不上 => 断点在第 4 条 */
    @Test
    fun deletingMiddleRecord_isDetected() {
        val chain = chained((1L..5L).map { ev(it) })
        val removed = chain.toMutableList().also { it.removeAt(2) }
        assertEquals(4L, HashChain.firstBroken(removed))
    }

    /** 只改 hash 本身（装作"重新签"一条）=> 也会被抓 */
    @Test
    fun rewritingStoredHash_isDetected() {
        val chain = chained((1L..5L).map { ev(it) })
        val faked = chain.toMutableList()
        faked[1] = faked[1].copy(selfHash = HashChain.GENESIS)
        assertEquals(2L, HashChain.firstBroken(faked))
    }
}
