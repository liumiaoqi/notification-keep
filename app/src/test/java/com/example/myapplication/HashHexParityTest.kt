package com.example.myapplication

import com.example.myapplication.data.HashChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 *`HashChain.sha256` 手写 hex 与**旧写法**（`"%02x".format`）的对拍（纯 JVM）
 *
 ***为什么必须有它**（#4 的审核重点2：**不许改算法 / 不许改输出**）：
 *   本次改的是**实现**（手写 hex，替掉"每字节一次 `String.format`"）
 *   => 而**输出必须逐字节相同**
 *   判据：**同一输入 => 同一个 64 字符小写十六进制**
 *   否则 **全库已存的 `self_hash` 全部算不出来** => 断点会指到第一条
 *
 * **两条独立算路，缺一不可**：
 *   1 **与旧写法对拍** —— 把改前那一行**原样**搬进本文件当基准
 *   2 **公开标准向量** —— 只有1是**自证**（两处一起错也会同过）=> 所以要有2
 *
 * **本文件不声称的**："它在大库上真的更快" —— 那是**性能**，要另一套测量
 *   => 这里只押"**输出一个字节都不变**"（判据只覆盖声称的那一条）
 */
class HashHexParityTest {

    /** **旧写法**（就是改前那一行：`for (b in d) sb.append("%02x".format(b))`） */
    private fun legacySha256Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(64)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }

    @Test
    fun parityWithTheLegacyFormatter_overManyInputs() {
        val samples = listOf(
            "", "a", "abc", "abc\n", "中文", "=>", "\u0001", "x".repeat(4096),
            HashChain.GENESIS, "0", "-1", "true", "1791593873324",
        ) + (1..300).map { "sample-$it-中$it-${it * 7919}" }
        for (s in samples) {
            assertEquals("输入「${s.take(24)}」的对拍必须一致", legacySha256Hex(s), HashChain.sha256(s))
        }
    }

    @Test
    fun matchesPublishedTestVectors() {
        //**独立算路**：这两条是 SHA-256 的**公开标准向量**
        //   => 它们**不依赖我们的任何实现** => 才能证明"算法本身没被动过"
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            HashChain.sha256(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HashChain.sha256("abc"),
        )
    }

    @Test
    fun encodingShapeIsUnchanged() {
        // 与 `HashChainTest` 那两条**同族**，但本批动过这段代码 => 这里再钉一次
        val h = HashChain.sha256("shape")
        assertEquals("必须 64 字符", 64, h.length)
        assertTrue("必须全是小写十六进制 => $h", h.all { it in "0123456789abcdef" })
    }

    @Test
    fun everyByteValueSurvivesTheHexStep() {
        //**负字节是关键**：`Byte` 有符号 => `%02x` 与手写 hex 必须**都**按"无符号 2 位"处理
        //   怎么在不暴露新 API 的前提下覆盖 256 个取值：拿一批 hash 的**字节**当样本
        //     => 并且**断言样本够强**（覆盖满 256、且含 ≥0x80）——
        //        否则这条用例会"看着在测、其实没测到那条边界"
        val seen = HashSet<Int>()
        for (i in 0 until 400) {
            val input = "cover-$i"
            for (b in MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))) {
                seen.add(b.toInt() and 0xFF)
            }
            assertEquals("cover-$i 的对拍必须一致", legacySha256Hex(input), HashChain.sha256(input))
        }
        assertEquals("样本必须把 256 个字节值全盖到（实际 ${seen.size}）", 256, seen.size)
        assertTrue("必须含高位字节（≥0x80）—— 否则没测到负字节那条边界", seen.any { it >= 0x80 })
    }
}
