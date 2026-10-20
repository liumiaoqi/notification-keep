package com.liumiaoqi.notificationkeep

import com.liumiaoqi.notificationkeep.data.HashChain
import com.liumiaoqi.notificationkeep.data.NotifEvent
import com.liumiaoqi.notificationkeep.data.chainLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *`chainLine()` 的 golden 测试（纯 JVM）
 *
 * 它押的是**真实性**那一行（链的 4 个状态字段）
 * 而它的价值在于：那 4 个字段的语义**有反直觉之处** ——
 *   例如"**空列表**时 `chainTail` 也要写 GENESIS 而不是 `null`"
 *     而那个若写错 => 读侧会拿到 `"null"` 字符串当 hash => 它复算必然对不上
 *       => **合法文件被判"被改过"**（狼来了）
 */
class ChainLineGoldenTest {

    private fun rec(id: Long, self: String) = NotifEvent(
        id = id, pkg = "com.a", key = "k", event = "POST", title = "", text = "",
        big = "", posted = id, reason = 0, captured = id, selfHash = self, prevHash = "x",
    )

    @Test
    fun genesisIsAlwaysSixtyFourZeros() {
        // 判据1：链首恒为 64 个 0
        val out = chainLine(listOf(rec(1, "aa")), null)
        assertTrue("该含 chainGenesis 的 64 个 0", out.contains("\"chainGenesis\":\"" + HashChain.GENESIS + "\""))
        assertEquals("GENESIS 该是 64 个 0", 64, HashChain.GENESIS.length)
        assertTrue(HashChain.GENESIS.all { it == '0' })
    }

    @Test
    fun emptyList_chainTailIsGenesis_notNull() {
        //判据2（反直觉的那条）：空列表 => `chainTail` 也写 GENESIS
        val out = chainLine(emptyList(), null)
        assertTrue(
            "空列表时 chainTail 该写 GENESIS（不是 null / 空串）：" + out,
            out.contains("\"chainTail\":\"" + HashChain.GENESIS + "\""),
        )
        assertFalse("不该出现字符串 null", out.contains(":null"))
    }

    @Test
    fun tailIsTheLastRecordSelfHash() {
        val out = chainLine(listOf(rec(1, "aa"), rec(2, "bb"), rec(3, "cc")), null)
        assertTrue("chainTail 该取【最后一条】的 selfHash", out.contains("\"chainTail\":\"cc\""))
        assertFalse("不该取第一条的", out.contains("\"chainTail\":\"aa\""))
    }

    @Test
    fun intactAndFirstBrokenId_agreeWithEachOther() {
        // 判据34：两个字段必须**互相一致**
        val ok = chainLine(listOf(rec(1, "aa")), null)
        assertTrue("完好时 chainIntact 该是 true", ok.contains("\"chainIntact\":true"))
        assertTrue("完好时 firstBrokenId 该是 -1", ok.contains("\"firstBrokenId\":-1"))

        val bad = chainLine(listOf(rec(1, "aa")), 5L)
        assertTrue("断了时 chainIntact 该是 false", bad.contains("\"chainIntact\":false"))
        assertTrue("断了时 firstBrokenId 该是那个 id", bad.contains("\"firstBrokenId\":5"))
        assertFalse("不该同时出现 true（那会自相矛盾）", bad.contains("\"chainIntact\":true"))
    }

    @Test
    fun booleansAndNumbersAreNotQuoted() {
        // 读侧按 bool / int 解析它们 => 不能带引号
        val out = chainLine(emptyList(), null)
        assertFalse("chainIntact 不该带引号", out.contains("\"chainIntact\":\""))
        assertFalse("firstBrokenId 不该带引号", out.contains("\"firstBrokenId\":\""))
    }

    @Test
    fun isOneLine_noRawNewline() {
        val out = chainLine(listOf(rec(1, "aa")), null)
        assertTrue("该以 { 开头", out.startsWith("{"))
        assertTrue("该以 } 结尾", out.endsWith("}"))
        assertFalse("不该含裸换行（那会破坏一行一条）", out.contains('\n'))
    }
}
