package com.liumiaoqi.notificationkeep

import com.liumiaoqi.notificationkeep.crash.CrashRecord
import com.liumiaoqi.notificationkeep.crash.CrashRotate
import com.liumiaoqi.notificationkeep.crash.ExitReasonName
import com.liumiaoqi.notificationkeep.crash.GzipTrace
import com.liumiaoqi.notificationkeep.crash.StartupSentinel
import com.liumiaoqi.notificationkeep.crash.StartupSentinel.Stage
import com.liumiaoqi.notificationkeep.crash.StartupSentinel.Verdict
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * ⭐⭐ **不变式：凡会落进 `crash.log` 的文字 = 自然中文**（纯 JVM ✓）
 *
 * ⚠️⚠️ **为什么要有它**（⭐ 2026-10-15 #4 记录面收严 ✓）：
 *   `crash.log` 是**取证文件** —— ⭐ 会被 `cat` 出来、被贴进报告、被第三方读 ✓
 *   ⇒ ⚠️ 那些标记符（`⭐` / `**` / `⇒` / `【】` / `✓✗` / `⚠️` / 反引号 / `→`）**都是给源码注释用的**，
 *      ⭐ 落进记录正文只是**噪声** ✗
 *   ⭐ 判据已经**升格为一条不变式**（⭐ 不再逐符打补丁）⇒ ⭐ 本文件就是它的**执行点** ✓
 *
 * ⭐ **两条判据（一正一反）**：
 *   ① **禁用集**（[BANNED]）在记录文字里**一处都不许出现** ✓
 *   ② ⭐ **反向**：⭐ 中文 / 标点（`（）` `：` `——` `…`）/ 数字 / 字节数 **必须原样还在** ✓
 *      —— ⚠️ 否则"收严"很容易变成"顺手把话也改了" ✗（⭐ 复审官提醒过的那个坑 ✓）
 *
 * ⚠️ **覆盖范围（会落进记录的全部我们这边的字面量）**：
 *   · `GzipTrace`：4 段说明 + ⭐ **`excerptFrom` 的三条行**（⭐ 它们也是 `stackLines` 的一部分 ✓）
 *   · `StartupSentinel.recordLines`（哨兵那三行）
 *   · ⭐ `CrashRotate`：截断记录 / 残缺标记（⭐ 经 `plan()` 走公共入口 ✓）
 * ⚠️ **不在范围内**（⭐ 按令）：⭐ 源码注释里的 `⭐`/`**`（那是源码层，⭐ 材料端由 `strip` 管）、
 *   ⭐ 界面/Toast 文案、⭐ `Log.*` 串（它们**不进记录** ✓）
 */
class RecordTextHygieneTest {

    /** ⭐ **禁用集**（⭐ 升格后的不变式 —— 记录正文里一个都不许有 ✓） */
    private val BANNED = listOf("**", "⭐", "⚠️", "⇒", "【", "】", "✓", "✗", "→", "`")

    private fun assertClean(what: String, text: String) {
        assertTrue("$what 不能是空的 ⇒ 「$text」", text.isNotBlank())
        for (b in BANNED) {
            assertFalse("$what 不许含「$b」⇒ 「$text」", text.contains(b))
        }
    }

    /** ⭐ 逐行查（⭐ 这样"行首装饰"也躲不掉 ✓） */
    private fun assertAllLinesClean(what: String, lines: List<String>) {
        assertTrue("$what 不能是空的", lines.isNotEmpty())
        for (l in lines) assertClean(what, l)
    }

    private fun gz(vararg lines: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { g -> g.write(lines.joinToString("\n").toByteArray(Charsets.UTF_8)) }
        return bos.toByteArray()
    }

    /** ⭐ 一份像真的 trace（头部 + main 段） */
    private fun trace() = arrayOf(
        "----- pid 1 at 2026-10-10 08:57:42 -----",
        "Cmd line: com.liumiaoqi.notificationkeep",
        "DALVIK THREADS:",
        "\"main\" prio=5 tid=1 Native",
        "  at com.liumiaoqi.notificationkeep.MainActivity.onCreate(MainActivity.kt:129)",
        "\"Signal Catcher\" prio=5 tid=2",
    )

    // ── ① GzipTrace 的四段说明 ───────────────────────────────────────

    @Test
    fun gzipTraceNotesAreClean() {
        val nativeEmpty = (GzipTrace.decode(ByteArray(0), ExitReasonName.CRASH_NATIVE)
            as GzipTrace.Decoded.Unreadable).note
        val nativeWithBytes = (GzipTrace.decode(ByteArray(4096) { 7 }, ExitReasonName.CRASH_NATIVE)
            as GzipTrace.Decoded.Unreadable).note
        val absent = (GzipTrace.decode(ByteArray(0), ExitReasonName.ANR)
            as GzipTrace.Decoded.Unreadable).note
        val junk = (GzipTrace.decode(ByteArray(3000) { (it * 7 % 256).toByte() }, ExitReasonName.ANR)
            as GzipTrace.Decoded.Unreadable).note

        assertClean("native 说明（空字节）", nativeEmpty)
        assertClean("native 说明（有字节）", nativeWithBytes)
        assertClean("未压缩标记", GzipTrace.MARK_UNCOMPRESSED)
        assertClean("系统没给", absent)
        assertClean("解不开也不像文本", junk)
    }

    // ── ② ⭐ `excerptFrom` 的三条行（⭐ 也是 stackLines）────────────────

    @Test
    fun traceExcerptLinesAreClean() {
        // ⭐ 三种形状都要走（⭐ 否则容易"看着在查、其实只走到一支" ✗）：
        //   (a) `main` 在头部之外 ⇒ 「中间跳过」
        //   (b) `main` 落在头部之内（⭐ 默认 `headerLines=6`、而 main 在第 4 行 ⇒ 就是这一支）
        //   (c) 完全没有 `main` ⇒ 「没找到」
        val skipMain = (GzipTrace.decode(gz(*trace()), ExitReasonName.ANR, headerLines = 2, mainLines = 2)
            as GzipTrace.Decoded.Gzipped).lines
        val mainInHead = (GzipTrace.decode(gz(*trace()), ExitReasonName.ANR)
            as GzipTrace.Decoded.Gzipped).lines
        val noMain = (GzipTrace.decode(gz("----- pid 1 -----", "Cmd line: x", "A", "B", "C", "D", "E"),
            ExitReasonName.ANR) as GzipTrace.Decoded.Gzipped).lines

        assertAllLinesClean("trace 摘录（main 在头部之外）", skipMain)
        assertAllLinesClean("trace 摘录（main 在头部之内）", mainInHead)
        assertAllLinesClean("trace 摘录（无 main）", noMain)

        // ⭐ 确认三支**真的各自被走到**（⭐ 这几条就是"别空跑"的守门人 —— 它们当场抓到过我一次 ✓）
        assertTrue("必须真的产生『中间跳过』那一条 ⇒ $skipMain", skipMain.any { it.contains("跳过") })
        assertTrue("必须真的产生『头部之内』那一条 ⇒ $mainInHead", mainInHead.any { it.contains("头部之内") })
        assertTrue("必须真的产生『没找到』那一条 ⇒ $noMain", noMain.any { it.contains("没找到") })
    }

    // ── ③ 哨兵那三行 ────────────────────────────────────────────────

    @Test
    fun sentinelRecordLinesAreClean() {
        assertAllLinesClean(
            "哨兵记录行",
            StartupSentinel.recordLines(Verdict.Incomplete(Stage.ACTIVITY_CREATE, 1_760_000_000_000L)),
        )
    }

    // ── ④ ⭐ CrashRotate：截断 / 残缺（⭐ 走公共入口 plan ✓）─────────────

    @Test
    fun rotationProducedRecordsAreClean() {
        val huge = CrashRecord.render(
            CrashRecord.Fields(
                atMs = 1_760_000_000_000L, uptimeMs = 1L, stage = "activity_create", pid = 1,
                kind = CrashRecord.KIND_UNCAUGHT, threadName = "main",
                excClass = "java.lang.RuntimeException", excMessage = "x".repeat(20_000),
                appVersion = "1.0(1)", androidRelease = "16", sdkInt = 36, device = "d",
                stackLines = listOf("at x"),
            )
        )
        // ⭐ 单条超限 ⇒ 走截断那支（它有 excMessage 文案 ✓）
        val truncated = CrashRotate.plan("", huge, maxBytes = 4096)
        // ⚠️ `plan()` 的返回值**以 `\n` 结尾** ⇒ ⭐ `split('\n')` 会留一个**尾部空串**
        //   （⭐ 我第一次就把它当"空行"报了 ✗）⇒ ⭐ 先 `trimEnd` 再切 ✓
        assertAllLinesClean("截断后的记录", truncated.trimEnd('\n').split('\n'))
        // ⭐ 残缺尾块 ⇒ 走 truncatedMarker 那支 ✓
        val withPartial = CrashRotate.plan("半截没写完的记录", huge, maxBytes = 1 shl 20, maxRecords = 5)
        assertAllLinesClean("残缺标记记录", withPartial.trimEnd('\n').split('\n'))
        // ⚠️ 确认这两支**真的被走到**了（⭐ 否则断言是空跑 ✓）
        assertTrue("截断支要真的触发", truncated.contains("只保留首尾片段"))
        assertTrue("残缺支要真的触发", withPartial.contains("写到一半"))
    }

    // ── ⑤ ⭐ 反向：收严**不许把正文改掉** ──────────────────────────────

    @Test
    fun cleaningDidNotEatTheActualContent() {
        // ⭐⭐ 判据：⭐ 只删标记符，⭐ 正文一字不动 ✓
        //   ⚠️ 且要证明"改准了"：⭐ 那句"分不清就别猜"的语义**必须还在** ✓
        val native = (GzipTrace.decode(ByteArray(0), ExitReasonName.CRASH_NATIVE)
            as GzipTrace.Decoded.Unreadable).note
        assertTrue("中文必须原样 ⇒ $native", native.contains("本工具不解析"))
        assertTrue("取不出栈正文这个说法必须明确 ⇒ $native", native.contains("取不出栈正文"))
        assertTrue("那句有意的自否定必须还在 ⇒ $native", native.contains("也可能是系统没留"))
        assertTrue("标点必须原样 ⇒ $native", native.contains("（") && native.contains("）"))
        assertTrue("对照量（字节数）必须还在 ⇒ $native", native.contains("0 字节"))

        assertTrue("未压缩标记的核心词必须还在",
            GzipTrace.MARK_UNCOMPRESSED.contains("未压缩") &&
                GzipTrace.MARK_UNCOMPRESSED.contains("gzip 解不开"))

        val junk = (GzipTrace.decode(ByteArray(3000) { (it * 7 % 256).toByte() }, ExitReasonName.ANR)
            as GzipTrace.Decoded.Unreadable).note
        assertTrue("两样都试过的说法必须还在 ⇒ $junk",
            junk.contains("gzip 解不开") && junk.contains("不像文本"))
        assertTrue("字节数必须还在 ⇒ $junk", junk.contains("3000"))
    }
}
