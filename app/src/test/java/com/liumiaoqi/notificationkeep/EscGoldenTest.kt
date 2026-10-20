package com.liumiaoqi.notificationkeep

import com.liumiaoqi.notificationkeep.data.esc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *`esc` 的 golden 测试（纯 JVM）
 *
 * 为什么要它（判据来源）
 *   独立复核者的回执里给了两条 caveat，第一条是：
 *     **「同包搬迁 => 引用点零报错（第一刀 NotifEvent 就是这样）=> "编译期全暴露"不成立，
 *        安全靠【无共享状态 + golden 测试】」**
 *   =>而 `esc` 恰好**是那种"编译期不会暴露"的东西** ——
 *     它没有共享状态 => 所以搬错了编译器**不吭声**
 *     而实测：本仓**一条 `esc` 的测试都没有**
 *     =>所以先立这个 golden 再动它
 *
 * 第二条 caveat 更直接：
 *   **「`esc` 是跨语言契约的一半 => 原样搬、别改一个字符」**
 *   => 而"契约的一半"意思是：写侧 `esc` ⟷ 读侧 `verify_export.py` 的转义约定**成对**
 *     改一个字符 => **接收方的核对器会判"文件被改过"**（而那是本工具最不能出的假警报）
 *
 * 判据的选择（值得说明）
 *   我**没有**去测"转义后的 JSON 能被 Python 解回来" ——
 *   因为那要跑 Python，JVM 单测里做不到
 *   => 所以这里断言的是**字面输出**（精确到字符）
 *     而"能不能被 Python 解回来"由**端到端**验（核对器 rc=0）
 */
class EscGoldenTest {

    @Test
    fun quoteAndBackslash_areEscaped() {
        // JSON 里必须转义的两个字符
        assertEquals("a\\\"b", esc("a\"b"))
        assertEquals("a\\\\b", esc("a\\b"))
    }

    @Test
    fun controlCharacters_useShortForms() {
        // 这三个用短形式（\n \r \t）而不是 \uXXXX
        assertEquals("\\n", esc("\n"))
        assertEquals("\\r", esc("\r"))
        assertEquals("\\t", esc("\t"))
    }

    @Test
    fun lowControlCharacters_useUnicodeEscape() {
        // 0x00-0x1F 里除了上面三个，都走 \uXXXX
        assertEquals("\\u0000", esc("\u0000"))
        //而 U+0001 特别要紧：它是**哈希链的字段分隔符**
        //   （payloadOf 用 U+0001 拼 9 个字段 => 若它原样落进文件，接收方切分就会歧义）
        assertEquals("\\u0001", esc("\u0001"))
        assertEquals("\\u001f", esc("\u001f"))
    }

    @Test
    fun lineBreakingCharacters_areEscaped() {
        //A9 那条修的就是这三个：它们在 JSON 里**合法**、却会让 Python 的
        //   `str.splitlines()` **断行** => 核对器把一行读成两行 => 判"文件被改过"
        //   => 所以必须转义成 \uXXXX（那是 JSON 标准转义，解回来仍是原字符）
        assertEquals("\\u0085", esc("\u0085"))   // NEL
        assertEquals("\\u2028", esc("\u2028"))   // LINE SEPARATOR
        assertEquals("\\u2029", esc("\u2029"))   // PARAGRAPH SEPARATOR
    }

    @Test
    fun ordinaryCharacters_passThroughUnchanged() {
        // 中文 标点 数字都原样（别自作聪明去转义）
        val plain = "老张：在吗？123 abc-_/."
        assertEquals(plain, esc(plain))
    }

    @Test
    fun outputNeverContainsRawNewlineOrTheChainSeparator() {
        //这条是"契约"层面的判据，比逐字符断言更接近意图：
        //   转义之后的字符串里**不该**再出现裸的换行或 U+0001
        //   => 否则它落进 JSONL 就会破坏"一行一条"或"字段可切分"
        val nasty = "line1\nline2\r\n\u0001\u0085\u2028\u2029\ttab"
        val out = esc(nasty)
        assertTrue("转义后不该有裸 LF", !out.contains('\n'))
        assertTrue("转义后不该有裸 CR", !out.contains('\r'))
        assertTrue("转义后不该有裸 U+0001（链的分隔符）", !out.contains('\u0001'))
        assertTrue("转义后不该有裸 U+2028", !out.contains('\u2028'))
    }

    @Test
    fun singleCharacterInput_staysOneLogicalUnit() {
        // 边界：空串与单字符
        assertEquals("", esc(""))
        assertEquals("x", esc("x"))
    }
}
