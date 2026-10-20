package com.example.myapplication

import com.example.myapplication.data.deviceLine
import com.example.myapplication.data.signingLevelLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *两行固定 meta 的 golden（纯 JVM）
 *
 * 它押的是**关联性**（设备那一行）与**不假装**（档位那一行）
 */
class DeviceAndLevelLineTest {

    //档位那一行

    @Test
    fun levelIsEmittedVerbatim() {
        // 三种档位与"没拿到"都原样 —— 而这个函数【不做判断】
        //   判断在 `ExportSigner.levelName` 里（那一处已修："读不到"不再冒充 software）
        for (v in listOf("software", "tee", "strongbox", "unavailable")) {
            assertEquals("""{"signingSecurityLevel":"$v"}""", signingLevelLine(v))
        }
    }

    @Test
    fun levelLineHasOneKey_quoted() {
        val out = signingLevelLine("tee")
        assertTrue("该以 { 开头", out.startsWith("{"))
        assertTrue("该以 } 结尾", out.endsWith("}"))
        assertTrue("值该带引号（它是字符串）", out.contains("\"signingSecurityLevel\":\"tee\""))
        assertEquals("该只有一个键", 1, Regex("\":").findAll(out).count())
    }

    @Test
    fun levelLineIsOneLine() {
        assertFalse(signingLevelLine("software").contains('\n'))
    }

    //设备那一行

    @Test
    fun deviceIsManufacturerPlusModel() {
        // 契约："厂商 型号"（一个空格分隔）—— 读侧按这一个人读
        val out = deviceLine("Google", "sdk_gphone64_x86_64", "13", 33)
        assertTrue("该是 厂商 型号：$out", out.contains("\"device\":\"Google sdk_gphone64_x86_64\""))
    }

    @Test
    fun sdkIsANumber_notQuoted() {
        //判据：`sdk` 是 **Int** => 不带引号（读侧按数字解析）
        val out = deviceLine("X", "Y", "13", 33)
        assertTrue("sdk 该不带引号：$out", out.contains("\"sdk\":33}"))
        assertFalse("sdk 不许带引号", out.contains("\"sdk\":\""))
    }

    @Test
    fun hasAllFourKeys() {
        val out = deviceLine("A", "B", "12", 31)
        for (k in listOf("device", "android", "sdk")) {
            assertTrue("缺键 $k：$out", out.contains("\"$k\":"))
        }
    }

    @Test
    fun androidReleaseIsEmittedVerbatim() {
        val out = deviceLine("A", "B", "14", 34)
        assertTrue("android 该是 14：$out", out.contains("\"android\":\"14\""))
    }

    @Test
    fun deviceLineIsOneLineAndStartsEndsRight() {
        val out = deviceLine("Google", "Pixel", "13", 33)
        assertFalse("不该含裸换行", out.contains('\n'))
        assertTrue("该以 { 开头", out.startsWith("{"))
        assertTrue("该以 } 结尾", out.endsWith("}"))
    }

    @Test
    fun emptyStringsStillProduceValidShape() {
        // 边界：若某个字段为空（例如某些设备 `Build.MODEL` 为空）
        //   输出仍该是**合法的一行**（而不是崩或缺键）
        val out = deviceLine("", "", "", 0)
        assertTrue(out.startsWith("{"))
        assertTrue(out.endsWith("}"))
        assertTrue("空的 device 也要有这个键", out.contains("\"device\":\" \""))
        assertTrue("sdk 0 也要在", out.contains("\"sdk\":0}"))
    }
}
