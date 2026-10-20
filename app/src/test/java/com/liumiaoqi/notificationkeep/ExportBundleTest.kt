package com.liumiaoqi.notificationkeep

import com.liumiaoqi.notificationkeep.data.ExportBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * 「证据包 zip」的回归测试（纯 JVM —— 用临时目录，不需要 Android）
 *
 * 判据：
 *   1 zip 里**三份都在**（数据 + 核对器 + 说明）
 *   2**读回来与原文本逐字相同**（编码不能变 —— 这是最容易悄悄坏掉的地方）
 *   3 条目名是 ASCII（中文名跨平台解压易乱码）
 *   4 说明里**带上 jsonl 的实际文件名**（否则接收方不知道跑哪个文件）
 *   5 `bundleName` 只换扩展名，不动名字其余部分
 */
class ExportBundleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val jsonlName = "nk-export-20260928-220000.jsonl"
    private val jsonlText = "{\"a\":1}\n{\"b\":\"中文与 emoji 与 \\\"引号\\\"\"}\n"
    private val verifierText = "#!/usr/bin/env python3\nprint(\"核对器\")\n"

    private fun buildZip(): File {
        val f = tmp.newFile("bundle.zip")
        f.outputStream().use { os ->
            ExportBundle.writeTo(os, jsonlName, jsonlText, verifierText)
        }
        return f
    }

    @Test
    fun zip_containsAllThreeEntries() {
        val zf = ZipFile(buildZip())
        val names = zf.entries().toList().map { it.name }.toSet()
        assertTrue("要有数据", names.contains(jsonlName))
        assertTrue("要有核对器", names.contains(ExportBundle.VERIFIER_NAME))
        assertTrue("要有说明", names.contains(ExportBundle.HOWTO_NAME))
        assertEquals(3, names.size)
        zf.close()
    }

    /**核心：读回来必须【逐字相同】（编码/换行都不能变） */
    @Test
    fun contentsRoundTrip_exactly() {
        val zf = ZipFile(buildZip())
        fun read(name: String) = zf.getInputStream(zf.getEntry(name)).use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals("数据必须逐字相同", jsonlText, read(jsonlName))
        assertEquals("核对器必须逐字相同", verifierText, read(ExportBundle.VERIFIER_NAME))
        zf.close()
    }

    @Test
    fun entryNames_areAscii() {
        val zf = ZipFile(buildZip())
        zf.entries().toList().forEach { e ->
            assertTrue("条目名必须是 ASCII（中文名跨平台易乱码）：${e.name}",
                e.name.all { it.code < 128 })
        }
        zf.close()
    }

    @Test
    fun howto_mentionsActualJsonlName_andCommand() {
        val how = ExportBundle.howToText(jsonlName)
        assertTrue("要指出跑哪个文件", how.contains(jsonlName))
        assertTrue("要给出命令", how.contains("python3"))
        assertTrue("要点名核对器", how.contains(ExportBundle.VERIFIER_NAME))
        assertTrue("要写清局限", how.contains("不能") && how.contains("设备时钟"))
    }

    /** 说明的起点必须干净：不残留 trimIndent 之前的缩进 */
    @Test
    fun howto_startsWithHeading_noLeadingSpaces() {
        val how = ExportBundle.howToText(jsonlName)
        assertTrue("首行应是 # 标题且顶格", how.startsWith("# "))
        assertTrue("不应有行首多余空格", how.lineSequence().none { it.startsWith(" ") })
        assertTrue("应以换行收尾", how.endsWith("\n"))
    }

    /**
     ***不许有重复的长句**（2026-09-28 加）—— 这条守护是有来历的
     *
     * 事实：`howToText()` 的第 4 条局限**曾被拼成两遍**（同一句话的后半段出现两次）
     *   根因：一次**按行号**的替换只断言了范围的**起点**、终点是猜的
     *     => 那两行留了下来，又被后续的"合并续行"折进了同一行
     *   而当时盯着这个函数的测试**只查"没有行首空格"** => **看不见重复**
     *      我自己也读过那一行、**也没看出来**
     *   =>所以补这条：**把文本切成 n-gram，任一较长片段不得出现两次**
     *
     * 为什么取 24 个字：中文里 24 字的短句很少自然重复，
     *   而一旦重复就是**粘贴事故**（不是修辞）
     */
    @Test
    fun howto_hasNoDuplicatedLongPhrase() {
        // 先把【本来就会出现多次】的东西剔除 —— 导出文件名在
        //    「内容清单」与「命令示例」里各出现一次，那是**应该**的
        //    （我第一版没剔除 => 它报了文件名 => 判据太宽）
        val how = ExportBundle.howToText(jsonlName)
            .replace(jsonlName, "<FILE>")
        val n = 24
        val seen = HashMap<String, Int>()
        var i = 0
        while (i + n <= how.length) {
            val g = how.substring(i, i + n)
            val prev = seen.put(g, i)
            if (prev != null) {
                // a repeat must not be a pure coincidence of the same short run
                val ctx = how.substring(maxOf(0, prev - 8), minOf(how.length, prev + n + 8))
                throw AssertionError("说明书里有重复片段（长度 $n）:\n  「$g」\n  首次出现在：「$ctx」")
            }
            i++
        }
    }

    @Test
    fun bundleName_swapsExtensionOnly() {
        assertEquals("nk-export-20260928-220000.zip", ExportBundle.bundleName(jsonlName))
        assertEquals("weird.zip", ExportBundle.bundleName("weird"))
    }

    @Test
    fun howto_limitationCount_matchesTheSingleSource() {
        //2026-09-28 加（这条是被一个真缺陷逼出来的）
        //
        // 当时的形状：第 5 条末尾漏了一个逗号（写成 `"…" +` 而不是 `"…",`）
        //   => Kotlin 把【第 5 条与第 6 条拼成了一条】
        //   => 而**编译照样过** 单测**也没抓到**
        //     —— 因为没有任何断言在管"条数对不对"
        //   =>症状只在**产物**里看得见：标题写"5 条"、JSON 只有 limitation1..5，
        //      而其中一条内容长到 282 字符（两条文案挤在一行）
        //   =>所以这条测试盯的就是那个：**渲染出的条目数必须等于列表长度**
        //
        // 它同时守两件事：
        //   1 列表里每一项**各自独立**（再漏逗号就会暴露）
        //   2 渲染（`__LIM_LIST__`）与数据源**不漂**

        val text = ExportBundle.howToText("x.jsonl")
        val numbered = text.lines().filter { it.trim().matches(Regex("""^\d+\. .+""")) }
        // 说明书里除了边界清单，还有"回答四个问题"那 4 条 —— 要排除掉
        val limitationItems = numbered.filterNot {
            val b = it.trim()
            b.startsWith("1. **") || b.startsWith("2. **") ||
                b.startsWith("3. **") || b.startsWith("4. **")
        }

        assertEquals(
            "说明书的边界条目数应等于 OpsEvents.LIMITATIONS.size",
            com.liumiaoqi.notificationkeep.data.OpsEvents.LIMITATIONS.size,
            limitationItems.size,
        )
        //而两个判据同时用（第二个比长度更准）：
        //   1 长度上限 —— 2026-09-28 调：原来设 200，而单条真的会写到 231
        //      而两条被拼起来大约 500-600 => 上限设 400 仍能抓住
        //   2 每条必须以标点收尾 —— 拼接会把一句话**打断在中间**
        //      实测过的拼接就是这样：…出现。）外部锚定只覆盖… -> 前一条以"）"收尾后直接接上新句
        for (item in limitationItems) {
            assertTrue(
                "边界条目异常长（疑似两条被拼在一起，长度=${item.length}）：${item.take(50)}…",
                item.length < 400,
            )
            val tail = item.trim().takeLast(1)
            assertTrue(
                "边界条目不以标点收尾（疑似被拼接截断）：…${item.takeLast(30)}",
                tail in "。）！？",
            )
        }
    }
}
