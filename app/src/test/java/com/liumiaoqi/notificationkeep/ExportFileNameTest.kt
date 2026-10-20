package com.liumiaoqi.notificationkeep

import com.liumiaoqi.notificationkeep.data.exportFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *`exportFileName()` 的 golden 测试（纯 JVM）
 *
 * 为什么它能纯 JVM 测（而这正是搬迁的收益）
 *   它原来是 `NotifStore` 的**实例方法**，而它**不引用任何实例状态**
 *   => JVM 里造不出 `NotifStore`（那要 `Context`）=> 所以它原本一条测试都没有
 *   2026-09-28 搬成 `data` 包的顶层函数 => 现在直接可测
 *
 * 判据的选择（值得说明）
 *   它输出里含**时间戳** => **不能断言具体值**
 *   => 所以断言的是**形状**：`nk-export-<8 位日期>-<6 位时间>.jsonl`
 *     而那**正是契约** —— 接收方按这个形状认文件
 */
class ExportFileNameTest {

    private val shape = Regex("""^nk-export-\d{8}-\d{6}\.jsonl$""")

    @Test
    fun matchesTheContractShape() {
        val n = exportFileName()
        assertTrue(
            "文件名必须匹配 nk-export-<yyyyMMdd>-<HHmmss>.jsonl，实际=$n",
            shape.matches(n),
        )
    }

    @Test
    fun endsWithJsonl() {
        //后缀是 `.jsonl` 而不是 `.json`
        //   理由（契约层面）：它的内容就是**一行一条** => 后缀要说清那件事
        //   （而 zip 里同名那份额外叫 `.zip` —— 那是打包产物，不是这个函数的输出）
        assertTrue("必须是 .jsonl 后缀", exportFileName().endsWith(".jsonl"))
    }

    @Test
    fun lengthIsStable() {
        // 形状固定 => 长度固定 => 可以用长度当一条廉价的健全性判据
        //   "nk-export-" (10) + 8 + "-" (1) + 6 + ".jsonl" (6) = 31
        assertEquals(31, exportFileName().length)
        assertEquals(31, exportFileName().length)
    }

    @Test
    fun dateAndTimePartsAreNumeric() {
        // 把两段拆出来看：它们必须都是纯数字（Locale.US => 不给别的数字系统）
        val n = exportFileName()
        val mid = n.removePrefix("nk-export-").removeSuffix(".jsonl")
        val parts = mid.split("-")
        assertEquals("该是【两段】：日期与时间", 2, parts.size)
        assertEquals("日期 8 位", 8, parts[0].length)
        assertEquals("时间 6 位", 6, parts[1].length)
        assertTrue("日期必须是数字", parts[0].all { it.isDigit() })
        assertTrue("时间必须是数字", parts[1].all { it.isDigit() })
    }

    @Test
    fun twoCallsAgreeOnShape() {
        // 两次调用可能落在同一秒 => **不能断言"不相等"**
        //   => 但可以断言"两次都合法"
        assertTrue(shape.matches(exportFileName()))
        assertTrue(shape.matches(exportFileName()))
    }
}
