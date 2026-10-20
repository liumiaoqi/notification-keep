package com.liumiaoqi.notificationkeep

import com.liumiaoqi.notificationkeep.data.OpsEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 操作事件与「导出文件自 hash」的回归测试（纯 JVM）
 *
 * 判据：
 *   1 自 hash 是**排除最后一行**算出来的（否则就是自指，算不出来）
 *   2 **改了正文任何一处 => 核对必须失败**（这正是它的用途）
 *   3 **改了声明的 hash => 也必须失败**
 *   4 没有自 hash 行的文件 => 核对返回 false（不猜、不当成"通过"）
 */
class OpsEventsTest {

    /** 造一个"像导出文件"的文本：正文若干行 + 最后一行是自 hash */
    private fun makeExport(bodyLines: List<String>): String {
        val body = bodyLines.joinToString("\n")
        val h = OpsEvents.selfHashOf(body)
        return body + "\n" + """{"${OpsEvents.SELF_HASH_KEY}":"$h","howToVerify":"去掉最后一行再算"}""" + "\n"
    }

    @Test
    fun freshExport_verifiesTrue() {
        val f = makeExport(listOf("""{"count":3}""", """{"gapCount":1}""", """{"id":1,"text":"hi"}"""))
        assertTrue(OpsEvents.verifyExportSelfHash(f))
    }

    /**核心：改正文一处 => 立刻查出来（这正是"这份文件没被改过"的证明） */
    @Test
    fun tamperedBody_failsVerification() {
        val f = makeExport(listOf("""{"count":3}""", """{"id":1,"text":"hi"}"""))
        val tampered = f.replace("\"text\":\"hi\"", "\"text\":\"he said otherwise\"")
        assertFalse(OpsEvents.verifyExportSelfHash(tampered))
    }

    /** 只改声明的 hash（装作重新签过）=> 也查出来 */
    @Test
    fun forgedHash_failsVerification() {
        val f = makeExport(listOf("""{"count":1}"""))
        // 2026-09-28 教训：不要在这里写正则
        //   我原来写的是 Regex("\"$" + KEY + "\":\"[0-9a-f]+\"") ——
        //   Kotlin 里 `$` 后跟 `"` 是字面 `$`，而 `$` 在【正则】里是行尾锚
        //   => 正则永远匹配不上 => 替换没生效 => 这条测试其实【形同虚设】（被它自己失败抓出来）
        // => 改成"把真 hash 直接换成假 hash"：不碰正则，意图更清楚
        val body = f.trimEnd('\n').split('\n').dropLast(1).joinToString("\n")
        val realHash = OpsEvents.selfHashOf(body)
        assertTrue("前置：真 hash 应当出现在文件里", f.contains(realHash))
        val forged = f.replace(realHash, "0".repeat(64))
        assertFalse("把声明的 hash 换成假值后，核对必须失败", OpsEvents.verifyExportSelfHash(forged))
    }

    /** 删掉一行（少了一条记录）=> 也查出来 */
    @Test
    fun deletedLine_failsVerification() {
        val f = makeExport(listOf("""{"a":1}""", """{"b":2}""", """{"c":3}"""))
        val lines = f.trimEnd('\n').split('\n').toMutableList()
        lines.removeAt(1)                       // 删掉中间一条
        assertFalse(OpsEvents.verifyExportSelfHash(lines.joinToString("\n")))
    }

    @Test
    fun noSelfHashLine_returnsFalse_notPass() {
        val f = "{\"count\":1}\n{\"id\":1}\n"
        assertFalse("没有自 hash 行时必须返回 false（不猜）", OpsEvents.verifyExportSelfHash(f))
    }

    @Test
    fun emptyClaimedHash_returnsFalse() {
        val f = "{\"count\":1}\n{\"$" + OpsEvents.SELF_HASH_KEY + "\":\"\"}\n"
        assertFalse(OpsEvents.verifyExportSelfHash(f))
    }

    @Test
    fun isOp_recognisesOnlyOpEvents() {
        assertTrue(OpsEvents.isOp(OpsEvents.APP_START))
        assertTrue(OpsEvents.isOp(OpsEvents.EXPORT))
        assertTrue(OpsEvents.isOp(OpsEvents.CLEAR))
        assertFalse(OpsEvents.isOp("POST"))
        assertFalse(OpsEvents.isOp("REMOVED"))
        assertFalse(OpsEvents.isOp("LISTENER_CONNECTED"))
    }

    @Test
    fun selfHash_is64Hex_andStable() {
        val h = OpsEvents.selfHashOf("abc")
        assertEquals(64, h.length)
        assertEquals(h, OpsEvents.selfHashOf("abc"))   // 确定性
    }
}
