package com.liumiaoqi.notificationkeep

import com.liumiaoqi.notificationkeep.crash.ExitReasonName
import com.liumiaoqi.notificationkeep.crash.GzipTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

/**
 *ANR trace 摘录的测试（纯 JVM —— `java.util.zip` 是 JVM 自带的 => 不是 Android API）
 *
 ***它押的是这条链能不能通**（而这条链是**本案的核心**）：
 *   系统把 ANR 现场留在 **gzip** 里、且只给**应用自己**读（adb shell 读那个目录是 Permission denied）
 *   => 所以"**把这个 gzip 解成'人看得懂的那几行'**"这一步必须有把握
 *
 * **用例覆盖的都是真会遇到的形状**：
 *   正常 trace => 要给出 `main` 线程那一段（答案就在这里）
 *   **没有 `main` 的 trace** => **必须把"没找到"说出来**，不许静默换一手假装找到了
 *   **不是 gzip**（拿错了）=> **抛出去**（由调用方出声，不吞）
 *   极长的一行 / 极多的行 => **输出必须有界**（否则会把日志额度吃光）
 *
 * **测不到的部分**："`getTraceInputStream()` 真给不给数据" => 要 Android => 真机验
 */
class GzipTraceTest {

    private fun gz(vararg lines: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { g -> g.write(lines.joinToString("\n").toByteArray(Charsets.UTF_8)) }
        return bos.toByteArray()
    }

    /** 一条**像真的** ANR trace（头部 + `main` 线程 + 别的线程） */
    private fun realLookingTrace() = arrayOf(
        "----- pid 1 at 2026-10-10 08:57:42 -----",
        "Cmd line: com.liumiaoqi.notificationkeep",
        "DALVIK THREADS:",
        "\"main\" prio=5 tid=1 Native",
        "  | sysTid=1 nice=-10",
        "  at android.os.MessageQueue.nativePollOnce(Native method)",
        "  at com.liumiaoqi.notificationkeep.MainActivity.onCreate(MainActivity.kt:129)",
        "  at android.app.Activity.performCreate(Activity.java:8000)",
        "\"Signal Catcher\" prio=5 tid=2",
        "  at java.lang.Object.wait(Native method)",
    )

    //正常路径

    @Test
    fun excerpGivesHeaderAndMainThreadSection() {
        val out = GzipTrace.excerpt(gz(*realLookingTrace()), headerLines = 2, mainLines = 4)
        assertEquals("头部 2 行", "----- pid 1 at 2026-10-10 08:57:42 -----", out[0])
        assertEquals("Cmd line: com.liumiaoqi.notificationkeep", out[1])
        assertTrue("要有一段'跳过了几行'的说明 => ${out[2]}", out[2].contains("跳过"))
        assertTrue("第 4 行起就是 main 线程 => ${out[3]}", out[3].startsWith("\"main\""))
        assertTrue("main 线程的栈行要在 => ${out[6]}", out[6].contains("MainActivity.kt:129"))
        assertFalse("mainLines=4 => 第 5 行就不该进来了", out.any { it.contains("Signal Catcher") })
    }

    @Test
    fun excerptWithDefaultsKeepsEnoughToSeeTheBlame() {
        // 默认参数下，那个"占住主线程的那一行"必须在输出里
        val out = GzipTrace.excerpt(gz(*realLookingTrace()))
        assertTrue("必须能一眼看到占用点", out.any { it.contains("MainActivity.kt:129") })
    }

    //没找到 main：说出声

    @Test
    fun missingMainSectionIsReportedNotHidden() {
        val noMain = arrayOf(
            "----- pid 1 -----", "Cmd line: x", "A", "B", "C", "D",
            "E", "F", "G", "H",
        )
        val out = GzipTrace.excerpt(gz(*noMain), headerLines = 2, mainLines = 3)
        assertTrue("必须明说'没找到 main' => 实际：$out", out.any { it.contains("没找到") })
        assertEquals("输出仍有界（头部 2 + 说明 1 + 3 行）", 6, out.size)
    }

    //输入不是 gzip：抛出去

    @Test
    fun nonGzipInputThrowsInsteadOfReturningGarbage() {
        val notGzip = "this is definitely not a gzip stream".toByteArray(Charsets.UTF_8)
        assertThrows(IOException::class.java) { GzipTrace.excerpt(notGzip) }
    }

    @Test
    fun emptyInputGivesEmptyList() {
        assertTrue("空输入 => 空结果（不抛、也不编内容）", GzipTrace.excerpt(ByteArray(0)).isEmpty())
    }

    //输出必须有界

    @Test
    fun veryLongLinesAreTruncatedWithAMarker() {
        val longLine = "x".repeat(1000)
        val out = GzipTrace.excerpt(gz(longLine), headerLines = 1, mainLines = 0, maxLineChars = 10)
        assertEquals("截到上限 + 一个省略号标记", 11, out[0].length)
        assertTrue("要有截断标记 => ${out[0]}", out[0].endsWith("…"))
    }

    @Test
    fun outputLineCountIsBoundedRegardlessOfTraceSize() {
        // 真 trace 很大（所有线程都 dump）=> 我们必须只取有界的一段
        val many = Array(500) { "line-$it" }
        val out = GzipTrace.excerpt(gz(*many), headerLines = 4, mainLines = 10)
        assertEquals("头部 4 + 说明 1 + mainLines 10", 15, out.size)
    }

    //decode：gzip / 未压缩兜底 / 读不懂 三态（P3-4 + 兜底升级）

    @Test
    fun decodeStillHandlesTheNormalGzipPath() {
        val d = GzipTrace.decode(gz(*realLookingTrace()), ExitReasonName.ANR)
        val g = d as GzipTrace.Decoded.Gzipped
        assertTrue("gzip 路径要能给出占用点 => ${g.lines}", g.lines.any { it.contains("MainActivity.kt:129") })
        assertFalse("gzip 路径不该带『未压缩』标记", g.lines.any { it.contains("未压缩") })
    }

    @Test
    fun decodeFallsBackToPlainTextWhenTheStreamIsNotGzip() {
        //这条就是"兜底升级"的核心：系统给**没压缩**的文本 => **照样把栈取出来**
        //   （真机实测：本机的 ANR 给的正是非 gzip => 改前这一格是"白扔"）
        val plain = realLookingTrace().joinToString("\n").toByteArray(Charsets.UTF_8)
        val d = GzipTrace.decode(plain, ExitReasonName.ANR)
        val u = d as GzipTrace.Decoded.Uncompressed
        assertTrue("必须真的取到 main 线程段 => ${u.lines}", u.lines.any { it.contains("MainActivity.kt:129") })
        assertTrue("必须标明『未压缩』=> ${u.lines.first()}", u.lines.first().contains("未压缩"))
    }

    @Test
    fun theUncompressedMarkerLandsInsideTheLinesSoItReachesTheRecord() {
        // 判据：标记必须在**返回的行里**（不是只写日志）—— 因为**只有记录会被拿走**
        val plain = realLookingTrace().joinToString("\n").toByteArray(Charsets.UTF_8)
        val u = GzipTrace.decode(plain, ExitReasonName.ANR) as GzipTrace.Decoded.Uncompressed
        assertTrue("标记要是**行**的一部分 => ${u.lines.first()}", u.lines.contains(GzipTrace.MARK_UNCOMPRESSED))
    }

    @Test
    fun junkWithoutTraceMarkersIsNotReadAsText() {
        // 第一道关：连 trace 标志都没有 => **不当作 trace**（别把普通文本硬读进来）
        val txt = "hello world\nthis is not a trace\n".toByteArray(Charsets.UTF_8)
        val d = GzipTrace.decode(txt, ExitReasonName.ANR)
        assertTrue("必须是 Unreadable => $d", d is GzipTrace.Decoded.Unreadable)
    }

    @Test
    fun markerAloneIsNotEnough_thePrintableRatioGateHolds() {
        // 第二道关：二进制里**偶尔会碰巧出现** `prio=` 这种片段
        //   => 所以还要过"可打印率" => 这条**直接构造**那个形状（有标志 + 满是 U+FFFD）
        val fake = ("prio=5 " + "\uFFFD".repeat(200)).toByteArray(Charsets.UTF_8)
        val d = GzipTrace.decode(fake, ExitReasonName.ANR)
        assertTrue("含标志但不可打印 => 仍须 Unreadable => $d", d is GzipTrace.Decoded.Unreadable)
    }

    @Test
    fun nativeCrashIsNotDecodedAtAll_itsATombstone() {
        //CRASH_NATIVE 的 trace 是 **tombstone（protobuf）** => **连试都不试**
        //   判据：把它写成「解压失败」会让人以为"trace 坏了/缺了" => **误导**
        val d = GzipTrace.decode(ByteArray(5000) { 7 }, ExitReasonName.CRASH_NATIVE)
        val note = (d as GzipTrace.Decoded.Unreadable).note
        assertTrue("必须点名 tombstone => $note", note.contains("tombstone"))
        assertFalse("不许写成『解压失败』=> $note", note.contains("解压失败"))
    }

    @Test
    fun nativeNeverFallsIntoTheSystemGaveNothingBranch() {
        //**这条押的就是 P2-A**（独立复审报的假声明）：
        //   改前：native => `decode` 先命中 `providedBytes <= 0` => 落进「系统没给」=> **不是它那一格**
        //   现在：native **永远**走 tombstone 说明（且如实带上**实际字节数**）
        //   而且**两种字节数都测**（银狼 §6-P3 之后，产线对 native **会真读** =>
        //     非空字节才是产线的真实形状 => 必须一起钉）
        val empty = (GzipTrace.decode(ByteArray(0), ExitReasonName.CRASH_NATIVE)
            as GzipTrace.Decoded.Unreadable).note
        val withBytes = (GzipTrace.decode(ByteArray(4096) { 7 }, ExitReasonName.CRASH_NATIVE)
            as GzipTrace.Decoded.Unreadable).note
        assertTrue("空字节也要点名 tombstone => $empty", empty.contains("tombstone"))
        assertTrue("产线形状（非空字节）也要点名 tombstone => $withBytes", withBytes.contains("tombstone"))
        // 判据：**分不清就别猜** => 把观测值（字节数）交出去
        assertTrue("空 => 要写「拿到 0 字节」=> $empty", empty.contains("0 字节"))
        assertTrue("有字节 => 要写清拿到了多少 => $withBytes", withBytes.contains("4096 字节"))
        //核心判据：与「真没给」那一格【必须不同】
        //   改前两者**是同一句话** => 这条断言**当时会红**
        //   而**不能**用 `contains("没给")` 去判：native 那句自己写着"也可能是系统没留，我们不猜" => 会误判
        val genuinelyAbsent = (GzipTrace.decode(ByteArray(0), ExitReasonName.ANR)
            as GzipTrace.Decoded.Unreadable).note
        assertNotEquals("native 的产出必须【不同于】真没给 => $empty", genuinelyAbsent, empty)
        assertNotEquals("非空字节的 native 也要不同 => $withBytes", genuinelyAbsent, withBytes)
    }

    @Test
    fun emptyInputIsReportedAsNotProvided() {
        val note = (GzipTrace.decode(ByteArray(0), ExitReasonName.ANR) as GzipTrace.Decoded.Unreadable).note
        assertTrue("要说是【系统没给】=> $note", note.contains("没给"))
    }

    @Test
    fun binaryJunkGetsAnHonestNote_namingBothMisses() {
        // 兜底也失败时 => 必须说清"**两样都试过**"（不许含糊成"失败"）
        //   措辞**只描述我们的动作**（"gzip 解不开"）—— 不对"生产者"下断言
        //   （判据：SDK 里非 native 是 `GZIPInputStream` 包着的 => "系统不给 gzip" 那是**假话**）
        val junk = ByteArray(3000) { (it * 7 % 256).toByte() }
        val note = (GzipTrace.decode(junk, ExitReasonName.ANR) as GzipTrace.Decoded.Unreadable).note
        assertTrue("要说清【gzip 解不开】=> $note", note.contains("gzip 解不开"))
        assertTrue("也要说清【不像文本】=> $note", note.contains("不像文本"))
        assertTrue("不许把 n 字节这类事实吞掉 => $note", note.contains("3000"))
        assertFalse("不许写成『解压失败』=> $note", note.contains("解压失败"))
    }

    @Test
    fun notesNeverAssertWhatTheProducerDid() {
        //判据（P3-2）：**只说自己试了什么，不说"系统给的不是 X"**
        //   因为我们**无法区分**"本来就不是 gzip" 与 "是 gzip 但坏了"
        val notes = listOf(
            (GzipTrace.decode(ByteArray(0), ExitReasonName.CRASH_NATIVE) as GzipTrace.Decoded.Unreadable).note,
            (GzipTrace.decode(ByteArray(3000) { (it * 7 % 256).toByte() }, ExitReasonName.ANR)
                as GzipTrace.Decoded.Unreadable).note,
        )
        for (n in notes) {
            assertFalse("不许对生产者下断言（『系统给的…不是』）=> $n", n.contains("系统给的"))
            assertFalse("不许写『不是 gzip』=> $n", n.contains("不是 gzip"))
        }
    }

    @Test
    fun formatTagKeepsWeDidNotReadApartFromTheSystemGaveNothing() {
        //**独立复审的验收第 2 条：字段与说明必须自洽**
        //   走的也是**生产真实组合**（空字节 + native / 空字节 + ANR）
        //   入参用 **reason**（不是 `isNative`）=> 与产线一致、且规则单点
        val nativeTag = GzipTrace.formatTag(
            GzipTrace.decode(ByteArray(0), ExitReasonName.CRASH_NATIVE), ExitReasonName.CRASH_NATIVE,
        )
        val absentTag = GzipTrace.formatTag(
            GzipTrace.decode(ByteArray(0), ExitReasonName.ANR), ExitReasonName.ANR,
        )
        assertEquals("native => 必须标明是 tombstone => $nativeTag", "native-tombstone", nativeTag)
        assertEquals("真没给 => none => $absentTag", "none", absentTag)
        assertNotEquals(
            "两者【必须不同】—— 否则字段本身就在把『native 不解析』说成『系统没给』", absentTag, nativeTag,
        )
    }

    @Test
    fun formatTagCoversTheTwoSuccessfulShapes() {
        val gz = GzipTrace.decode(gz(*realLookingTrace()), ExitReasonName.ANR)
        assertEquals("gzip", GzipTrace.formatTag(gz, ExitReasonName.ANR))
        val plain = GzipTrace.decode(
            realLookingTrace().joinToString("\n").toByteArray(Charsets.UTF_8), ExitReasonName.ANR,
        )
        assertEquals("plain", GzipTrace.formatTag(plain, ExitReasonName.ANR))
    }

    @Test
    fun nativeReasonRuleIsSingleSourced() {
        //银狼 §6-P3：**规则只在 `isNativeReason` 一处**
        //   判据：`decode` 的默认值与 `formatTag` **必须都从它取** => 两处漂开就会
        //     "产线走的路径 ≠ 被测的路径"（P2-A 的形态）
        assertTrue(GzipTrace.isNativeReason(ExitReasonName.CRASH_NATIVE))
        assertFalse(GzipTrace.isNativeReason(ExitReasonName.ANR))
        assertFalse(GzipTrace.isNativeReason(ExitReasonName.CRASH))
        // 默认值那条路：不传 `isNative` 也必须落到 native 格
        val viaDefault = GzipTrace.decode(ByteArray(4096) { 7 }, ExitReasonName.CRASH_NATIVE)
        assertTrue("不传 isNative 也要走 tombstone 格 => $viaDefault", viaDefault is GzipTrace.Decoded.Unreadable)
        assertTrue((viaDefault as GzipTrace.Decoded.Unreadable).note.contains("tombstone"))
    }

    @Test
    fun theThreeUnreadableNotesAreDistinct() {
        // 三种"没有"混成一句 => 读的人分不清该换哪条路
        val native = (GzipTrace.decode(ByteArray(100) { 1 }, ExitReasonName.CRASH_NATIVE)
            as GzipTrace.Decoded.Unreadable).note
        val absent = (GzipTrace.decode(ByteArray(0), ExitReasonName.ANR)
            as GzipTrace.Decoded.Unreadable).note
        val junk = (GzipTrace.decode(ByteArray(3000) { (it * 7 % 256).toByte() }, ExitReasonName.ANR)
            as GzipTrace.Decoded.Unreadable).note
        assertEquals("必须是三句不同的话", 3, setOf(native, absent, junk).size)
    }
}
