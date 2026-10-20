package com.liumiaoqi.notificationkeep

import com.liumiaoqi.notificationkeep.data.appVersionLine
import com.liumiaoqi.notificationkeep.data.chainIdLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *两行固定 meta 的 golden（纯 JVM）
 *
 * 它押的是**关联性**（App 版本 + 系统 build）与**链身份**（两段链别假装成一条）
 */
class AppVersionAndChainIdTest {

    //App 版本那一行

    @Test
    fun bothValuesEmittedVerbatim() {
        val out = appVersionLine("1.0", "google/emu64x:13/TE1A/userdebug/dev-keys")
        assertTrue(out.contains("\"appVersion\":\"1.0\""))
        assertTrue("fingerprint 里含斜杠也要原样", out.contains("\"buildFingerprint\":\"google/emu64x:13/TE1A/userdebug/dev-keys\""))
    }

    @Test
    fun hasTwoKeysAndIsOneLine() {
        val out = appVersionLine("1.0", "x")
        for (k in listOf("appVersion", "buildFingerprint")) {
            assertTrue("缺键 $k：$out", out.contains("\"$k\":"))
        }
        assertFalse("不该含裸换行", out.contains('\n'))
        assertTrue(out.startsWith("{"))
        assertTrue(out.endsWith("}"))
    }

    @Test
    fun doesNotInventEscaping() {
        // 判据：`esc` 由【调用方】做 —— 这个函数**不该再转义一次**
        //   转义两次会把 `\n` 变成 `\\n` => 读侧拿到字面的反斜杠
        assertEquals(
            """{"appVersion":"a\nb","buildFingerprint":"c"}""",
            appVersionLine("a\\nb", "c"),
        )
    }

    @Test
    fun emptyValuesStillProduceValidShape() {
        // `appVersionName` 取不到时会回退 => 这里的入参可能是回退值
        //   而无论是什么，输出都该是合法的一行
        val out = appVersionLine("", "")
        assertTrue(out.contains("\"appVersion\":\"\""))
        assertTrue(out.endsWith("}"))
    }

    //链身份那一行

    @Test
    fun bothValuesAreNumbers_notQuoted() {
        //判据：`chainId` 是 Long、`epoch` 是 Int => **都不带引号**
        //   => 读侧按数字解析（而它要拿 `chainId` **比较**）
        //     若带引号 => 类型错 => 比较可能变成字符串比较 => 误判"同一条链"
        val out = chainIdLine(1790617922253L, 3)
        assertTrue("chainId 该不带引号：$out", out.contains("\"chainId\":1790617922253"))
        assertTrue("epoch 该不带引号：$out", out.contains("\"epoch\":3"))
        assertFalse("chainId 不许带引号", out.contains("\"chainId\":\""))
        assertFalse("epoch 不许带引号", out.contains("\"epoch\":\""))
    }

    @Test
    fun epochAloneIsNotIdentity() {
        //这条把语义写进测试：**两段不同的链可以有相同的 `epoch`**
        //   因为重装/清空后 `epoch` 会**重新数** => 光看它会把两段不同的链判成同一条
        //   => 所以身份看 `chainId`（那句"不要看 epoch"就是写给这件事的）
        val segA = chainIdLine(1000L, 1)
        val segB = chainIdLine(2000L, 1)
        assertNotEquals("chainId 不同 => 两行该不同", segA, segB)
        assertTrue("而两者的 epoch 相同（正是那个陷阱）",
            segA.contains("\"epoch\":1") && segB.contains("\"epoch\":1"))
    }

    @Test
    fun chainIdIsNotQuoted_evenWhenLarge() {
        // 13 位时间戳（毫秒）超过 Int => 必须是 Long
        val big = 1790617922253L
        assertTrue(big > Int.MAX_VALUE)
        val out = chainIdLine(big, 0)
        assertTrue("该原样写 13 位数字：$out", out.contains("\"chainId\":1790617922253"))
    }

    @Test
    fun chainIdLineIsOneLineWithTwoKeys() {
        val out = chainIdLine(1L, 2)
        for (k in listOf("chainId", "epoch")) {
            assertTrue("缺键 $k：$out", out.contains("\"$k\":"))
        }
        assertFalse(out.contains('\n'))
        assertTrue(out.startsWith("{"))
        assertTrue(out.endsWith("}"))
        assertEquals("该只有两个键", 2, Regex("\":").findAll(out).count())
    }

    @Test
    fun negativeEpochStillEmitted() {
        // 边界：`NotifChain.NONE` 之类的哨兵值可能是负数
        //   输出仍该带它（而不是崩或缺键）
        val out = chainIdLine(0L, -1)
        assertTrue(out.contains("\"epoch\":-1"))
        assertTrue(out.contains("\"chainId\":0"))
    }
}
