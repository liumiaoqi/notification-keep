package com.example.myapplication

import com.example.myapplication.data.ClearRecord
import com.example.myapplication.data.ExportInputs
import com.example.myapplication.data.HashChain
import com.example.myapplication.data.NotifEvent
import com.example.myapplication.data.OpsEvents
import com.example.myapplication.data.render
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 ***`render(inputs)` 的契约测试**（复核者 E1–E5 E12）
 *
 ***这个文件是"搬家"这一步的收益兑现**
 *   独立复核者的回执写着：那 6 条产物契约
 *    「**只写在注释里、没有任何类型或测试兜着**」
 *     而"最后一行必须最后"**只靠两条物理事实**（快照在 `appendLine` 之前、之后只剩 `return`）
 *   => 那些契约**靠"代码物理相邻"撑着**
 *     而搬成顶层纯函数之后 => **它们可 JVM 测** => **从注释变成断言**
 *
 * 每条契约一条测试（编号对应回执里的编号）：
 *   **E12** `selfHashMatches_theRestOfTheFile` —— 自 hash 口径（三类读者必须一致）
 *   **E1**  `lastLineIsTheSelfHashLine` —— 最后一行是自 hash 行
 *   **E2**  `signatureIsOnTheSameLine` —— 签名 / 公钥 / 档位与 hash 同行
 *   **E3**  `everyLineLooksLikeAJsonObject` —— 每行是"一行一个对象"的形状
 *   **E4**  `noBlankLines` —— 不许有空行
 *   **E5**  `recordLinesCarryTheNineChainInputs` —— 记录行带链的 9 个输入
 *   外加  `verifyExportSelfHash_acceptsWhatRenderProduces` —— **最强的那条**（见下）
 *   以及  `sameInputsGiveSameOutput` —— 渲染是确定的（没有时钟/环境依赖）
 */
class RenderContractTest {

    private fun rec(id: Long, captured: Long, self: String, prev: String) = NotifEvent(
        id = id, pkg = "com.a", key = "k", event = "POST", title = "标题", text = "正文",
        big = "", posted = captured, reason = 0, captured = captured,
        selfHash = self, prevHash = prev,
    )

    /** 两个记录、有清空留痕 —— 覆盖"留痕那一行也在"的情形 */
    private fun inputs(
        all: List<NotifEvent> = listOf(
            rec(1, 1000, "aa11", HashChain.GENESIS),
            rec(2, 2000, "bb22", "aa11"),
        ),
        lastClear: ClearRecord? = ClearRecord(at = 500, count = 3, tailHash = "cc33", chainId = 7, epoch = 1),
        broken: Long? = null,
    ) = ExportInputs(
        all = all,
        broken = broken,
        chainId = 4242L,
        epoch = 1,
        lastClear = lastClear,
        appVersion = "1.0",
        now = 9999L,
        // 四个环境值也当入参（它们原来在 render 里直接读 Build）
        deviceManufacturer = "Google",
        deviceModel = "sdk_gphone64_x86_64",
        androidRelease = "13",
        sdkInt = 33,
        buildFingerprint = "google/emu64x:13/userdebug/dev-keys",
        signingLevel = "software",
    )

    private fun linesOf(out: String) = out.trimEnd('\n').split('\n')

    //E12：自 hash 口径

    @Test
    fun selfHashMatches_theRestOfTheFile() {
        //三类读者必须同口径：写侧 Kotlin `trimEnd('\n')`
        //   读侧 Kotlin `trimEnd+split+dropLast` 读侧 Python `"\n".join(lines[:-1])`
        val out = render(inputs(), "")
        val ls = linesOf(out)
        val claimed = Regex("\"" + OpsEvents.SELF_HASH_KEY + "\":\"([0-9a-f]{64})\"")
            .find(ls.last())?.groupValues?.get(1)
        assertTrue("末行该有一个 64 位 hex 的自 hash：\n" + ls.last().take(160), claimed != null)
        val rest = ls.dropLast(1).joinToString("\n")
        assertEquals(
            "自 hash 该等于【去掉最后一行后】全文的 sha256（不含结尾换行符）",
            claimed, OpsEvents.selfHashOf(rest),
        )
        // 而这里再自己算一遍（不经过 OpsEvents）=> 那样连"它的实现"也一起验了
        val mine = MessageDigest.getInstance("SHA-256").digest(rest.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("手算的 sha256 该与自 hash 相同", claimed, mine)
    }

    @Test
    fun verifyExportSelfHash_acceptsWhatRenderProduces() {
        //最强的一条：用【读侧自己的核对函数】验写侧的产物
        //   而它一行同时押住 E1（位置）与 E12（口径）
        assertTrue(
            "render 的产物必须通过它自己的自 hash 核对",
            OpsEvents.verifyExportSelfHash(render(inputs(), "")),
        )
        // 反向：改一个字符 => 必须判 false（否则那个核对形同虚设）
        val out = render(inputs(), "")
        val tampered = out.replace("正文", "正文X")
        assertFalse("改了一个字就该判 false", OpsEvents.verifyExportSelfHash(tampered))
    }

    //E1

    @Test
    fun lastLineIsTheSelfHashLine() {
        val ls = linesOf(render(inputs(), ""))
        assertTrue(
            "最后一行必须以 {\"exportSelfHash\": 开头：\n" + ls.last().take(120),
            ls.last().startsWith("{\"" + OpsEvents.SELF_HASH_KEY + "\":"),
        )
        // 而"最后一行"意味着它只出现一次（上面的除外）
        assertEquals(
            "自 hash 键只该在最后一行出现",
            1, ls.count { it.contains("\"" + OpsEvents.SELF_HASH_KEY + "\":") },
        )
    }

    //E2

    /**
     * 手工拼一个 `sigTail` —— **形状与 `ExportSigner.Result.Signed` 那一支【逐字相同】**
     *   为什么不调真的 `ExportSigner.sign()`：它会**懒生成一把 RSA-2048 密钥**
     *     而那正是 E7 与这条测试要防的事（测试不该有副作用）
     */
    private val fakeSigTail =
        "\",\"signature\":\"RkFLRQ==\",\"sigAlg\":\"SHA256withRSA\"," +
            "\"signerSecurityLevel\":\"software\",\"publicKeyPem\":\"-----BEGIN PUBLIC KEY-----\"" +
            "\",\"howToVerify\":\"见 HOW_TO_VERIFY.md\"}"

    @Test
    fun signatureIsOnTheSameLine() {
        //这条押的就是 **E2**：签名 / 公钥 / 档位必须与 hash **同一行**
        //   若另起一行 => 读侧"去掉最后一行"去掉的是**签名行**
        //     => 它重算的 hash 会**包含** `exportSelfHash` 行 => **必然对不上**
        //       于是一个**没被动过的**证据包会被算成"被改过" —— 本工具最不能出的错
        val out = render(inputs(), fakeSigTail)
        val ls = linesOf(out)
        assertEquals("带签名时仍是同样的行数（签名【没有】多占一行）", ls.size, linesOf(render(inputs(), "")).size)
        assertTrue(
            "末行必须【同时】含自 hash 与 signature（那就是 E2 本身）：\n" + ls.last().take(200),
            ls.last().contains("\"" + OpsEvents.SELF_HASH_KEY + "\":") && ls.last().contains("\"signature\":"),
        )
        assertTrue("而公钥也在同一行", ls.last().contains("\"publicKeyPem\":"))
        assertTrue("档位也在同一行", ls.last().contains("\"signerSecurityLevel\":"))
        // 而签名是【最后】那一段 => 自 hash 该在 signature 之前
        assertTrue(
            "自 hash 该在 signature 之前（顺序：hash => 签名各字段）",
            ls.last().indexOf("\"" + OpsEvents.SELF_HASH_KEY + "\":") < ls.last().indexOf("\"signature\":"),
        )
        //而最强的一条：**带签名的产物也仍该通过自 hash 核对**
        //   因为"去掉最后一行"去掉的是**这一整行**（hash + 签名）
        assertTrue(
            "带签名的产物也必须通过自 hash 核对（否则签名一加就狼来了）",
            OpsEvents.verifyExportSelfHash(out),
        )
    }

    //E4

    @Test
    fun noBlankLines() {
        // 写侧把空行算进 hash、读侧丢掉 => 对不上 => 狼来了
        val ls = linesOf(render(inputs(), ""))
        assertEquals("不许有空行（空元素）", 0, ls.count { it.isBlank() })
    }

    //E3

    @Test
    fun everyLineLooksLikeAJsonObject() {
        val ls = linesOf(render(inputs(), ""))
        for ((i, ln) in ls.withIndex()) {
            assertTrue("第 " + (i + 1) + " 行该以 { 开头：\n" + ln.take(110), ln.startsWith("{"))
            assertTrue("第 " + (i + 1) + " 行该以 } 结尾：\n" + ln.take(110), ln.endsWith("}"))
            assertFalse("第 " + (i + 1) + " 行不该含裸换行", ln.contains('\n'))
        }
        // 而"能不能被 JSON 解析"由 Python 侧端到端验（check_artifact 的 7）
    }

    @Test
    fun firstLineIsTheHeader() {
        assertEquals(
            "第一行该是版本契约",
            "{\"_header\":\"NK-notification-export\",\"formatVersion\":2}",
            linesOf(render(inputs(), "")).first(),
        )
    }

    //E5

    @Test
    fun recordLinesCarryTheNineChainInputs() {
        val ls = linesOf(render(inputs(), ""))
        val recLines = ls.filter { it.startsWith("{\"id\":") }
        assertEquals("该有两条记录行", 2, recLines.size)
        for (ln in recLines) {
            for (name in listOf("pkg", "key", "event", "title", "text", "big",
                                "posted", "reason", "captured")) {
                assertTrue("链的输入字段 " + name + " 必须在记录行里", ln.contains("\"" + name + "\":"))
            }
            assertTrue("selfHash 该在", ln.contains("\"selfHash\":"))
            assertTrue("prevHash 该在", ln.contains("\"prevHash\":"))
        }
    }

    //而"渲染是确定的"（没有时钟与环境依赖）

    @Test
    fun sameInputsGiveSameOutput() {
        assertEquals("同一个 inputs 该给同一输出", render(inputs(), ""), render(inputs(), ""))
    }

    @Test
    fun clearRecordIsOptional_andStillProducesAValidFile() {
        // 没清空过 => 那一行【不产】（而不是产 null）=> 而自 hash 仍该自洽
        val out = render(inputs(lastClear = null), "")
        assertFalse("不该有 lastClear 那一行", out.contains("\"lastClear\":"))
        assertTrue("没有留痕时自 hash 仍该自洽", OpsEvents.verifyExportSelfHash(out))
        assertEquals("而它仍然是合法的一行一条", 0, linesOf(out).count { it.isBlank() })
    }

    @Test
    fun brokenChainIsReportedHonestly() {
        // 链断了就该说断在哪（不假装）
        val out = render(inputs(broken = 2L), "")
        assertTrue("该报 chainIntact:false", out.contains("\"chainIntact\":false"))
        assertTrue("该报首个断点 id", out.contains("\"firstBrokenId\":2"))
        assertTrue("链断了也仍该自 hash 自洽", OpsEvents.verifyExportSelfHash(out))
    }
}
