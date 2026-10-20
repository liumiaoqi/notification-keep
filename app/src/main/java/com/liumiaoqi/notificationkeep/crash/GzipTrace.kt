package com.liumiaoqi.notificationkeep.crash

import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 ***从系统给的 ANR trace（gzip）里取"有用的那一段"**（纯逻辑 => JVM 可测）
 *
 ***为什么这条通道是本案的核心**（真机实测换来的结论）：
 *   症状："打开就崩，且别的软件崩了都有调试信息，它没有"。
 *   实测（`adb shell dumpsys activity exit-info`）：退出原因 **6 次全是 `ANR`、`CRASH` 0 次**
 *     => **没有 Java 异常** => uncaught 处理器**抓不到**
 *   而系统**确实**留了现场：`reason=6 (ANR) … trace=/data/system/procexitstore/anr_*.gz`
 *   但那个目录 **对 adb shell 是 Permission denied**（实测；要 root）
 *     =>**唯一免权限拿到它的路** = **应用自己**读
 *       `ApplicationExitInfo.getTraceInputStream()`
 *
 * **为什么不是"整份存下来"**：ANR trace 动辄上百 KiB（**所有线程**都 dump）
 *   => 存进去会把 [CrashRotate.MAX_BYTES] 一整个吃掉、把别的现场挤掉
 *   => 所以只取 **头部几行（认清是哪个进程）** + **`main` 线程那一段**
 *     —— 而"是谁占住了主线程"**正是我们要的答案**
 *
 * **`excerpt` 解不开时会抛 `IOException`**（那是**正确的失败** —— 它说明这不是一段能解的 gzip）
 *   => 而"解不开"**不等于**"没拿到栈" => **交给 [decode] 去兜底**（见它）
 *   => 调用方（[CrashLogIO]）必须**把它说出来**，不许吞
 */
object GzipTrace {

    /** trace 头部留几行（认清"哪个 pid / 哪台机 / 什么时候"） */
    const val HEADER_LINES = 6

    /** `main` 线程段留几行（栈通常在这个数以内） */
    const val MAIN_LINES = 60

    /** 单行最多留几个字符（有些 native 栈行极长 => 不截会撑爆） */
    const val MAX_LINE_CHARS = 300

    /** 读取时最多扫几行（防"文件异常大"=> 内存有界） */
    private const val SCAN_LINE_LIMIT = 3000

    /** `main` 线程段的起始行形状（`"main" prio=5 tid=1 …`） */
    private const val MAIN_PREFIX = "\"main\""

    //「拿不到 trace」要**说清是哪一种**（纯逻辑 => JVM 可测）

    /**
     ***"这个 reason 的 trace 是不是 tombstone 那一类"** —— **规则只在【这一处】**（银狼 §6-P3）
     *
     * 为什么要抽出来：它原来分散在两处（产线判一次 + `decode` 的默认参数判一次）
     *   => 两处一旦漂开 => "产线走的路径"与"被测的路径"就不是同一条
     *   => 抽成纯函数 => **一个真源 + 可单测**
     */
    fun isNativeReason(reason: Int): Boolean = reason == ExitReasonName.CRASH_NATIVE

    /**
     * **native 那一格的说明**（带**实际拿到的字节数** => 读的人能自己判）
     *
     ***为什么不写"系统没给"、也不写"我们没读"**（两句话**都可能不真**）：
     *   现在产线**会真的去读**（不再特判 => 见 `CrashLogIO.renderExit`）=> "我们没读"**已经过时**
     *   而"系统没给"也不一定：`getTraceInputStream()` 为 null 才算没给，
     *     而 native 那条路（tombstone）**通常是会给的**
     *   => 判据：**分不清就别猜** —— 把**观测到的字节数**交出去，让读的人自己判
     *
     ***本字符串会原样落进 `crash.log`** => **不许有 markdown 装潢**（2026-10-15 #4 补令）：
     *   已去掉 `**` 与 ``（取证记录是给人和 grep 看的，装潢只是噪声）；
     *   **正文一个字没动**（尤其那句"也可能是系统没留，我们不猜"是有意的）
     *   => 有 `RecordTextHygieneTest` 盯着（判据：文案不含 `**`、不含 ``）
     */
    private fun noteNativeTombstone(providedBytes: Int): String =
        "（native 的 trace 是 tombstone（protobuf，非文本），本工具不解析它，因此取不出栈正文；" +
            "本次拿到 $providedBytes 字节 —— 为 0 时也可能是系统没留，我们不猜" +
            "（但只要字节 >0，就说明数据是在的，换个解析方式就能拿到））"

    /** 2 系统压根没给（`getTraceInputStream()` 返回 `null`）本字符串**会落进记录** => 无装潢 */
    private const val NOTE_NOT_PROVIDED =
        "（系统没给 trace：getTraceInputStream() 为 null，不算失败）"

    /**
     ***把"没有栈"这件事说准**（独立复审 P3-4 / P2-A）
     *
     ***两条判据**（第一版两条都踩过）：
     *   1 **判定顺序：先看 native，再看字节数** ——
     *      第一版把 `providedBytes <= 0` 放前面 => native（字节为 0）会输出
     *      「系统没给」=> **假声明**（P2-A 就是它）
     *   2 **不对"生产者"下事实断言** —— 第一版写"系统给的**不是 gzip**"，
     *      而 `getTraceInputStream()` 对**非 native** 明明是用 `GZIPInputStream` 包着的
     *      => 本机看到的"解不开"是**本机实现偏离**，不是"系统不给 gzip"
     *      => 所以措辞只描述**我们做了什么**：「**gzip 解不开**」
     *
     * 2026-10-15（独立复审 P3）：**`reason` 参数已删** ——
     *   它当时是**死参**：native 判定已经**单点**收进 [isNativeReason]（由 `decode` / `formatTag` 取用）
     *   => 本函数只剩"按 `isNative` / 字节数选措辞" => 再留 `reason` 就是"声明与实现不符"
     *     （判据：签名里有、体内不用的参数，迟早被误当成"真的在参与判断"）
     *
     * 判据（`coding-discipline` §四.13）：**"没拿到"也要落在证据上** ——
     *   说清"拿到了多少字节、为什么没用"，而不是一句"失败"了事
     */
    private fun unavailableNote(providedBytes: Int, isNative: Boolean = false): String = when {
        //**native 必须前置**（见上面判据 1）
        //   已做**证伪对照**：把这两行调换 => `nativeWithNoBytes…` 那条用例**当场变红**
        //   （判据：用例必须**不是空跑** —— 改回缺陷形状则红，才说明它真的钉住了 P2-A）
        isNative -> noteNativeTombstone(providedBytes)
        providedBytes <= 0 -> NOTE_NOT_PROVIDED
        // 兜底也失败（gzip 解不开 **且**不像文本）=> 只有这一处会说这句话
        else -> NOTE_NOT_GZIP_NOR_TEXT.format(providedBytes)
    }

    fun excerpt(
        gz: ByteArray,
        headerLines: Int = HEADER_LINES,
        mainLines: Int = MAIN_LINES,
        maxLineChars: Int = MAX_LINE_CHARS,
    ): List<String> {
        if (gz.isEmpty()) return emptyList()
        return excerptFrom(
            readLines(GZIPInputStream(ByteArrayInputStream(gz)), maxLineChars),
            headerLines,
            mainLines,
        )
    }

    //兜底：gzip 解不开时也**尽量把栈捞出来**

    /**
     ***解 trace 的三种结果**（纯数据 => JVM 可测）
     *
     * **为什么要分三态、而不是"成功/失败"**：它们的**处置完全不同**
     *   [Gzipped]      => 常规形状，直接用
     *   [Uncompressed] => **兜底成功**：数据是**没压缩的文本** => 一样能把栈取出来
     *   [Unreadable]   => 真没法用（没给 / native 的 tombstone / 解不开也不像文本）
     *     => 如实记，**不许硬读成乱码**
     */
    sealed class Decoded {
        /** 正常：gzip 压缩的 trace（ANR 的常规形状） */
        data class Gzipped(val lines: List<String>) : Decoded()

        /****gzip 解不开、但内容读得懂** => 按纯文本取（记录里**自带"未压缩"标记**） */
        data class Uncompressed(val lines: List<String>) : Decoded()

        /** gzip 解不开、**也不像文本 trace** => **不硬解**（只留一句如实的说明） */
        data class Unreadable(val note: String) : Decoded()
    }

    /**
     * **"未压缩"标记**（它会**作为栈的第一行落进记录** => 读的人一眼知道这段是怎么来的）
     *
     * 它**必须落在记录里**，不能只写在日志里 —— 因为记录是**唯一会被拿走**的东西
     * 措辞只说**我们的动作**（"解不开"），不说"系统给的不是 gzip"（见 [unavailableNote] 判据 2）
     * 本字符串**会落进记录** => **无 markdown 装潢**（#4 补令；`RecordTextHygieneTest` 盯着）
     */
    const val MARK_UNCOMPRESSED =
        "（未压缩：这一段 gzip 解不开，已按纯文本取（内容仍是真的 trace））"

    /** gzip 解不开、也不像文本 => 说清"两样都试过"，别含糊成"失败"（会落进记录 => 无装潢）*/
    private const val NOTE_NOT_GZIP_NOR_TEXT =
        "这一段 gzip 解不开、也不像文本 trace（已拿到 %d 字节），不硬解（硬读会把二进制解成乱码、反而淹没真现场）"

    /** 一份真 trace 里**必然出现**的标志（用来判断"这是不是文本 trace"） */
    private val TRACE_MARKERS =
        listOf("----- pid", "Cmd line:", "DALVIK THREADS", MAIN_PREFIX, "prio=", "native: #")

    /**
     * 可打印率的下限 —— 随机二进制偶尔也会碰巧出现 `prio=` 之类的片段
     *   （真 trace 几乎全是可打印字符 => 实测该比例 >0.99；而二进制解成 UTF-8 会满是 U+FFFD）
     */
    private const val PRINTABLE_MIN_RATIO = 0.9

    /**
     * **这一段栈是"怎么来的"**（落进记录里的 `traceFormat` 字段）
     *
     ***判据（独立复审 P2-A 验收第 2 条：字段与说明必须自洽）**：
     *   **"我们没读"与"系统没给"必须落成【不同的值】** ——
     *   `gzip`             = 正常解出来的
     *   `plain`            = 兜底：gzip 解不开、按纯文本取到的
     *   `none`             = 系统**没给**（`getTraceInputStream()` 为 null）
     *   `native-tombstone` = **我们【有意】没读**（native 给的是 protobuf tombstone => 本工具不解）
     *   若把后两者都写成 `none` => 光看字段就会把"我们没读"读成"系统没给"
     *     => **假声明换了个地方藏**（这正是 P2-A 的病根形态）
     *
     * **为什么抽成纯函数**：它原本内嵌在 `CrashLogIO.renderExit` 里（要 `Context`）
     *   => 那条验收**只能靠真机看** => 抽出来 => **JVM 可测**（`AGENTS.md` §三#4）
     */
    fun formatTag(decoded: Decoded, reason: Int): String = when (decoded) {
        is Decoded.Gzipped -> "gzip"
        is Decoded.Uncompressed -> "plain"
        // 这里**也**用 [isNativeReason]（不是自己再判一次 `reason == …` => 规则仍然只有一处）
        is Decoded.Unreadable -> if (isNativeReason(reason)) "native-tombstone" else "none"
    }

    /**
     ***解一段 trace：先按 gzip 试，不行就按纯文本兜底**（本机实测的必要性见下）
     *
     * **判据（为什么兜底值得做）**：
     *   本机实测：ANR 那条 trace **按 gzip 解不开**（`ZipException`）
     *   而 `getTraceInputStream()` 对**非 native** 是**用 `GZIPInputStream` 包着的**（查过 SDK 源码）
     *   => 说明**本机（HyperOS）的实现偏离了 AOSP** —— 这类"偏离"**不能靠我们猜**
     *   => 所以：解不开就**再试纯文本** => **栈就在手上，别白扔**
     *
     * **兜底是有边界的**（不许"什么都当文本读"）：
     *   1 `isNative` => 是 **tombstone（protobuf）** => **连试都不试**
     *   2 文本路径要过两道关：**含 trace 标志** + **可打印率 ≥ [PRINTABLE_MIN_RATIO]**
     *      => 过不了就回 [Decoded.Unreadable]，**绝不把乱码写进记录**
     *   判据：**"宁可说不出，也不要乱说"** —— 乱码会挤掉真现场、还会误导读的人
     *
     * @param isNative **与字节数解耦**的 native 判定（独立复审 P2-A：产线上若靠"字节数为 0"去认它
     *   => 会先命中"系统没给"那一格 => **假声明**）默认值走 [isNativeReason]（规则单点）
     */
    fun decode(
        raw: ByteArray,
        reason: Int,
        isNative: Boolean = isNativeReason(reason),
        headerLines: Int = HEADER_LINES,
        mainLines: Int = MAIN_LINES,
        maxLineChars: Int = MAX_LINE_CHARS,
    ): Decoded {
        //**native 判定【前置】**，且**与 `raw` 是否为空无关**（order 就是这条修法的全部内容）
        if (isNative) return Decoded.Unreadable(unavailableNote(raw.size, isNative = true))
        if (raw.isEmpty()) return Decoded.Unreadable(unavailableNote(0))
        // 第 1 手：按 gzip 解（`excerpt` 解不开时**会抛** => 那正是我们要的分支信号）
        runCatching { excerpt(raw, headerLines, mainLines, maxLineChars) }
            .onSuccess { return Decoded.Gzipped(it) }
        // 第 2 手：当纯文本读（有界）
        val rawLines = runCatching { readLines(ByteArrayInputStream(raw), maxLineChars) }
            .getOrElse { return Decoded.Unreadable(unavailableNote(raw.size)) }
        if (!looksLikeTraceText(rawLines)) {
            // 兜底也没成 => 走**唯一那一处**措辞（`unavailableNote` => 不会两处漂）
            return Decoded.Unreadable(unavailableNote(raw.size))
        }
        return Decoded.Uncompressed(listOf(MARK_UNCOMPRESSED) + excerptFrom(rawLines, headerLines, mainLines))
    }

    /**
     * **这一段像不像"人写的 trace"**（纯函数）—— 两道关都要过：
     *   1 含至少一个 [TRACE_MARKERS] 标志
     *   2 可打印率 ≥ [PRINTABLE_MIN_RATIO]（U+FFFD 算**不可打印** => 它是"这是二进制"的信号）
     */
    private fun looksLikeTraceText(lines: List<String>): Boolean {
        if (lines.isEmpty()) return false
        if (lines.none { l -> TRACE_MARKERS.any { l.contains(it) } }) return false
        var printable = 0
        var total = 0
        for (l in lines) {
            for (c in l) {
                total++
                // U+FFFD = "按 UTF-8 解不开" => 它**不是**可打印字符
                if (c == '\t' || (!c.isISOControl() && c != '\uFFFD')) printable++
            }
            if (total >= 2000) break      // 只看前 2000 字符就够判（有界）
        }
        return total > 0 && printable.toDouble() / total >= PRINTABLE_MIN_RATIO
    }

    /** 取"头部几行 + `main` 线程段"（gzip 与纯文本**共用同一段逻辑** => 不会两处漂） */
    private fun excerptFrom(lines: List<String>, headerLines: Int, mainLines: Int): List<String> {
        val out = ArrayList<String>(headerLines + mainLines + 2)
        out.addAll(lines.take(headerLines))
        val i = lines.indexOfFirst { it.startsWith(MAIN_PREFIX) }
        if (i >= 0) {
            // 这几行**也会落进记录**（它们就是 stackLines 的一部分）=> 同一条"无装潢"不变式
            if (i > headerLines) out.add("…（中间跳过 ${i - headerLines} 行，直接给 main 线程）…")
            if (i < headerLines) out.add("…（main 线程出现在头部之内，上面已含）…")
            out.addAll(lines.subList(i, minOf(i + mainLines, lines.size)))
        } else {
            // 没找到就说出来（不静默换一手、也不假装这就是主线程）
            out.add("这份 trace 里没找到 main 线程段，原样给后面的 $mainLines 行")
            out.addAll(lines.drop(headerLines).take(mainLines))
        }
        return out
    }

    /**
     * 逐字符读（不调 `readLine()`）—— **因为要"每行有上限"**：
     *   `readLine()` 遇到一行 **100 MB** 会把内存吃光
     *   => 这里：每行最多 `maxLineChars` 个字符（超出补一个 `…`）+ 总数最多 [SCAN_LINE_LIMIT] 行
     */
    private fun readLines(input: java.io.InputStream, maxLineChars: Int): List<String> {
        val out = ArrayList<String>()
        input.use { raw ->
            InputStreamReader(raw, Charsets.UTF_8).use { r ->
                val sb = StringBuilder()
                while (out.size < SCAN_LINE_LIMIT) {
                    val c = r.read()
                    if (c < 0) {
                        if (sb.isNotEmpty()) out.add(sb.toString())
                        break
                    }
                    when {
                        c == '\n'.code -> { out.add(sb.toString()); sb.setLength(0) }
                        c == '\r'.code -> Unit            // CRLF 的 CR 丢掉（只按 LF 分行）
                        sb.length < maxLineChars -> sb.append(c.toChar())
                        sb.length == maxLineChars -> sb.append('…')   // 截断标记只加一次
                        else -> Unit
                    }
                }
            }
        }
        return out
    }
}
