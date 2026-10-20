package com.example.myapplication

import com.example.myapplication.crash.CrashRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 *崩溃记录格式的测试（纯 JVM 不需要 Android）
 *
 * **它押的是"记录一定读得懂"这件事**（每条都能被它或真机命令验）：
 *   1 **头尾齐**（以 `# NK-CRASH v1` 开头、以终止行结尾）
 *   2**`stack=N` 与数出来的行数一致** —— 这就是"记录没被写坏"的**机械判据**
 *   3 **终止行在一份记录里只出现一次** => 切块（[com.example.myapplication.crash.CrashRotate]）才不会切错
 *      而它**必须**挡得住"字段里碰巧带着终止行"这种输入
 *
 * **测不到的部分（如实写）**：
 *   "**真的**写进 `files/crash.log` 了" => 单测不碰文件系统与 Context
 *     => 那条靠真机：`adb exec-out run-as … cat files/crash.log`
 *   时区：这里**显式传固定时区** => 所以与设备时区无关地可重复
 */
class CrashRecordTest {

    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")

    private fun fields(
        atMs: Long = 1_760_000_000_000L,
        stage: String = "activity_create",
        kind: String = CrashRecord.KIND_UNCAUGHT,
        excClass: String? = "java.lang.IllegalStateException",
        excMessage: String? = "boom",
        device: String = "Xiaomi/22081212C",
        stack: List<String> = listOf("at a.b(C.kt:1)", "at d.e(F.kt:2)"),
        extra: List<Pair<String, String>> = emptyList(),
    ) = CrashRecord.Fields(
        atMs = atMs, uptimeMs = 1234L, stage = stage, pid = 4242,
        kind = kind, threadName = "main", excClass = excClass, excMessage = excMessage,
        appVersion = "1.0(1)", androidRelease = "16", sdkInt = 36, device = device,
        stackLines = stack, extra = extra,
    )

    private fun linesOf(text: String) = text.split('\n')

    //不变量 1：头尾齐

    @Test
    fun startsWithHeaderAndEndsWithTerminator() {
        val t = CrashRecord.render(fields(), shanghai)
        assertEquals("第一行必须是文件头", CrashRecord.HEADER, linesOf(t).first())
        assertEquals("最后一行必须是终止行", CrashRecord.END, linesOf(t).last())
    }

    //不变量 2：stack=N 与实到行数一致

    @Test
    fun stackCountMatchesActualStackLines() {
        val t = CrashRecord.render(fields(stack = listOf("a", "b", "c")), shanghai)
        assertTrue("必须写 stack=3", t.contains("stack=3"))
        val indented = linesOf(t).count { it.startsWith("  ") }
        assertEquals("数出来的栈行必须等于 3", 3, indented)
    }

    @Test
    fun emptyStackStillDeclaresZero() {
        val t = CrashRecord.render(fields(stack = emptyList()), shanghai)
        assertTrue("没有栈也要显式写 stack=0（别留空让人猜）", t.contains("stack=0"))
    }

    //不变量 3：终止行唯一（这才是切块的前提）

    @Test
    fun terminatorAppearsExactlyOnceEvenIfMessageIsTheTerminator() {
        // 恶意/巧合输入：异常消息**整好等于**终止行 => 必须被破掉
        val t = CrashRecord.render(fields(excMessage = CrashRecord.END), shanghai)
        val n = linesOf(t).count { it == CrashRecord.END }
        assertEquals("终止行必须只出现一次（否则切块会切错）", 1, n)
    }

    @Test
    fun terminatorAppearsExactlyOnceEvenIfStageIsTheTerminator() {
        val t = CrashRecord.render(fields(stage = CrashRecord.END), shanghai)
        val n = linesOf(t).count { it == CrashRecord.END }
        assertEquals("头字段带着终止行时也必须只有一条终止行", 1, n)
    }

    @Test
    fun oneLineBreaksTheHeaderStringToo() {
        // 头行同理 —— 若某个字段整好是 `# NK-CRASH v1` => 也会让"第一行"这个判据含糊
        assertEquals(" " + CrashRecord.HEADER, CrashRecord.oneLine(CrashRecord.HEADER))
    }

    //单行化：换行压掉

    @Test
    fun oneLineFlattensCrLfAndLf() {
        assertEquals("a\\nb", CrashRecord.oneLine("a\r\nb"))
        assertEquals("a\\nb", CrashRecord.oneLine("a\rb"))
        assertEquals("a\\nb", CrashRecord.oneLine("a\nb"))
    }

    @Test
    fun messageWithNewlinesDoesNotAddLinesToTheHeader() {
        val oneLineMsg = "# NK-CRASH v1\nat=x\nkind=y\nexc=z\nmsg=\napp=a\nstack=0\n--- END NK-CRASH ---"
        val t = CrashRecord.render(fields(excMessage = "line1\nline2\nline3", stack = emptyList()), shanghai)
        val msgLine = linesOf(t).first { it.startsWith("msg=") }
        // 断言的是"msg 那一行里没有真换行" => 它仍是**一行**
        assertEquals("msg 必须留在同一行里（换行被压成字面 \\n）", "msg=line1\\nline2\\nline3", msgLine)
        assertEquals("整体行数不该因消息里的换行而变", linesOf(oneLineMsg).size, linesOf(t).size)
    }

    @Test
    fun messageKeepsSpaces() {
        // `msg` 是自由文本 => 空格必须留住（它与头字段的判据不同）
        val t = CrashRecord.render(fields(excMessage = "waited 5000ms for focus"), shanghai)
        assertTrue(t.contains("msg=waited 5000ms for focus"))
    }

    //头字段：不许有空格（否则字段会被"劈成两半"）

    @Test
    fun headerFieldsHaveNoSpaces() {
        val t = CrashRecord.render(fields(device = "Xiaomi Redmi Note 12"), shanghai)
        val line = linesOf(t).first { it.startsWith("app=") }
        assertTrue("头字段里的空格必须被换成 _ => $line", line.contains("dev=Xiaomi_Redmi_Note_12"))
        assertFalse("不许把设备名原样带空格写进头行", line.contains("Xiaomi Redmi"))
    }

    //未知值要显式（不静默留空）

    @Test
    fun unknownTimeRendersAsDash() {
        val t = CrashRecord.render(fields(atMs = 0L), shanghai)
        assertTrue("时刻未知 => at=-（显式，不写 1970 骗人）", t.contains("at=- "))
    }

    @Test
    fun nullExcClassRendersAsDash() {
        val t = CrashRecord.render(fields(excClass = null), shanghai)
        assertTrue("没有异常类 => exc=-", t.contains("exc=-"))
    }

    //时间：固定时区 => 确定结果

    @Test
    fun isoLocalIsDeterministicAndOffsetAware() {
        assertEquals("epoch 0 在东八区就是 08:00", "1970-01-01T08:00:00.000+0800",
            CrashRecord.isoLocal(0L, shanghai))
        val a = CrashRecord.render(fields(), shanghai)
        val b = CrashRecord.render(fields(), shanghai)
        assertEquals("同一输入两次渲染必须逐字符相同", a, b)
    }

    //附加字段（reason=ANR 这种）也要能读

    @Test
    fun extraFieldsAreRenderedOnTheKindLine() {
        val t = CrashRecord.render(
            fields(
                kind = CrashRecord.KIND_SYSTEM_EXIT,
                extra = listOf("reason" to "ANR", "reasonCode" to "6"),
            ),
            shanghai,
        )
        val line = linesOf(t).first { it.startsWith("kind=") }
        assertTrue("kind 行要带 reason=ANR => $line", line.contains("reason=ANR"))
        assertTrue("kind 行要带 reasonCode=6 => $line", line.contains("reasonCode=6"))
    }
}
