package com.example.myapplication

import com.example.myapplication.data.HashChain
import com.example.myapplication.data.NotifEvent
import com.example.myapplication.data.recordLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *`recordLine()` 的 golden 测试（纯 JVM）
 *
 * 为什么需要它（判据来源）
 *   独立复核者的回执里 **E5** 那条说：
 *    「记录行的 **9 个字段名是链的输入** —— 读侧 `payload_of_strict` **按名字**重建 payload；
 *        少写或改名任一字段（例如 `text` -> `body`）
 *        => 重算出**不同**的 payload => **整条链判断（假警报）**」
 *   而在 2026-09-28 把它抽出来之前，那条契约**只写在注释里、没有任何测试兜着**
 *     （因为 `exportText` 要 `Context` => JVM 测不了）
 *   =>所以这个文件存在的意义就是：**把那条契约从注释变成断言**
 *
 * 判据的选择（值得说明）
 *   我**没有**去断言"它输出的是合法 JSON" —— 那要一个 JSON 解析器，
 *     而 Kotlin 侧**没有**（也不该为一条测试就加依赖）
 *   => 所以断言**形状与键名**：以 `{"id":` 开头、以 `}` 结尾、12 个键都在
 *     而"能不能被 Python 解析"由**端到端**验（核对器 rc=0）
 */
class RecordLineGoldenTest {

    private val e = NotifEvent(
        id = 7, pkg = "com.a", key = "k", event = "POST", title = "老张",
        text = "在吗「好」", big = "", posted = 111L, reason = 0, captured = 222L,
        selfHash = "ab", prevHash = "cd",
    )

    /** 独立的期望：12 个键名（而那 9 个链输入必须都在） */
    private val twelveKeys = listOf(
        "id", "captured", "posted", "event", "pkg", "key", "title", "text", "big", "reason",
        "selfHash", "prevHash",
    )

    @Test
    fun hasAllTwelveKeys() {
        val out = recordLine(e)
        for (k in twelveKeys) {
            assertTrue(
                "缺键 " + k + " => 若是那 9 个链输入之一，读侧会重算出不同 payload：\n" + out,
                out.contains("\"" + k + "\":"),
            )
        }
    }

    @Test
    fun theNineChainInputsArePresent() {
        //这条把 E5 说透：链的输入是这 9 个名字，而它们必须【逐字】在记录行里
        val out = recordLine(e)
        for (name in listOf("pkg", "key", "event", "title", "text", "big",
                            "posted", "reason", "captured")) {
            assertTrue("链的输入字段 " + name + " 必须在记录行里", out.contains("\"" + name + "\":"))
        }
        // 而 selfHash / prevHash **不是** payload 的输入（它们是链的【产物】）
        //   => 所以那条断言【不该】把它们混进"链输入"里
    }

    @Test
    fun startsAndEndsAsOneObject() {
        val out = recordLine(e)
        assertTrue("该以 {\"id\":7, 开头，实际：" + out.take(40), out.startsWith("{\"id\":7,"))
        assertTrue("该以 } 结尾", out.endsWith("}"))
    }

    @Test
    fun stringsGoThroughEsc_noRawNewlineOrQuote() {
        // 记录里放一个真的换行与一个真引号 => 输出里不该出现裸的它们
        val nasty = e.copy(text = "第一行\n第二行\"带引号\"", title = "a\\b")
        val out = recordLine(nasty)
        assertFalse("不该有裸 LF", out.contains('\n'))
        assertFalse("不该有裸 CR", out.contains('\r'))
        assertTrue("换行该被转义成 \\n", out.contains("\\n"))
        assertTrue("引号该被转义成 \\\"", out.contains("\\\""))
        assertTrue("反斜杠该被转义成 \\\\", out.contains("\\\\"))
    }

    @Test
    fun emptyFieldsAreStillEmitted() {
        // `big` 是空串 => 但键必须在（"少一个字段"正是 E5 说的那种坏）
        assertTrue("空字段也要出现", recordLine(e).contains("\"big\":\"\""))
    }

    @Test
    fun numbersAreNotQuoted() {
        // 路径值必须是【不带引号】的数字 —— 读侧按 int 解析它们
        val out = recordLine(e)
        assertTrue(out.contains("\"id\":7,"))
        assertTrue(out.contains("\"posted\":111"))
        assertTrue(out.contains("\"reason\":0"))
        assertTrue(out.contains("\"captured\":222"))
    }

    @Test
    fun selfAndPrevHashAreEmittedVerbatim() {
        // 而它们是 hex 字符串 => 该【原样】出现（读侧拿它们复算）
        val out = recordLine(e)
        assertTrue(out.contains("\"selfHash\":\"ab\""))
        assertTrue(out.contains("\"prevHash\":\"cd\""))
    }
}
