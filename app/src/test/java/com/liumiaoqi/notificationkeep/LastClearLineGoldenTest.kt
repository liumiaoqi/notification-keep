package com.liumiaoqi.notificationkeep

import com.liumiaoqi.notificationkeep.data.ClearRecord
import com.liumiaoqi.notificationkeep.data.lastClearLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *`lastClearLine()` 的 golden 测试（纯 JVM）
 *
 * 它押的是**合法性**那一行（"谁在什么时候清掉了多少条"）
 *
 * 而两条判据是**反直觉的**，所以各有一个测试：
 *   1 **没清空过 => 不产行**（而不是产 `"lastClear":null`）
 *     —— 与同一份产物里 `coveredFrom`/`coveredTo` 的做法**不同**（那两处写 `null`）
 *       => 两种"空"并存 => **读侧必须两种都处理**
 *   2 **名字映射**：`ClearRecord.chainId` => 产物键是 `clearedChainId`
 *     —— 因为"那一段链"指的是**被清掉的那一段**
 *       而同一份产物里**另有一行** `{"chainId":…,"epoch":…}` 说的才是**当前**那段
 *         => 若这里写成 `"chainId"` => **两个同名键会打架**
 */
class LastClearLineGoldenTest {

    private val c = ClearRecord(
        at = 1000L, count = 135L, tailHash = "5d9a9b77",
        chainId = 1790617922253L, epoch = 3,
    )

    @Test
    fun nullMeansNoLineAtAll() {
        //判据1：没清空过 => 返回 null（调用方就不产那一行）
        assertNull("没清空过时该返回 null（不是 \"lastClear\":null）", lastClearLine(null))
    }

    @Test
    fun usesClearedPrefix_notBareChainId() {
        //判据2：那些键必须带 `cleared` 前缀
        val out = lastClearLine(c)!!
        assertTrue("该是 clearedChainId", out.contains("\"clearedChainId\":1790617922253"))
        assertTrue("该是 clearedEpoch", out.contains("\"clearedEpoch\":3"))
        assertFalse(
            "不许出现裸露的 \"chainId\" —— 同族里另有一行说的是【当前】那段链，两个会打架",
            out.contains("\"chainId\""),
        )
    }

    @Test
    fun hasAllFiveKeys() {
        val out = lastClearLine(c)!!
        for (k in listOf("at", "count", "tailHash", "clearedChainId", "clearedEpoch")) {
            assertTrue("缺键 " + k + "：" + out, out.contains("\"" + k + "\":"))
        }
    }

    @Test
    fun isNestedUnderLastClear() {
        // 它是个**嵌套对象**（`{"lastClear":{…}}`）而不是平铺的键
        val out = lastClearLine(c)!!
        assertTrue("该以 {\"lastClear\":{ 开头，实际：" + out.take(30), out.startsWith("{\"lastClear\":{"))
        assertTrue("该以 } 结尾", out.endsWith("}"))
        assertEquals("两层花括号 => 该以 }} 收尾", 2, out.takeLast(2).count { it == '}' })
    }

    @Test
    fun numbersUnquoted_tailHashQuoted() {
        val out = lastClearLine(c)!!
        assertTrue("at 该不带引号", out.contains("\"at\":1000"))
        assertTrue("count 该不带引号", out.contains("\"count\":135"))
        assertTrue("tailHash 该带引号（它是 hex 字符串）", out.contains("\"tailHash\":\"5d9a9b77\""))
    }

    @Test
    fun isOneLine_noRawNewline() {
        val out = lastClearLine(c)!!
        assertFalse("不该含裸换行", out.contains('\n'))
    }

    @Test
    fun tailHashIsEmittedVerbatim_notEscaped() {
        // 它是 hex => 不需要转义（与 `esc` 那族不同）
        val odd = c.copy(tailHash = "abc123")
        assertTrue(lastClearLine(odd)!!.contains("\"tailHash\":\"abc123\""))
    }
}
