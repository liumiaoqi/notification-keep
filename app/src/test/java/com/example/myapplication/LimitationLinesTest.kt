package com.example.myapplication

import com.example.myapplication.data.OpsEvents
import com.example.myapplication.data.limitationLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *`limitationLines()` 的 golden 测试（纯 JVM）
 *
 * 为什么需要它
 *   局限声明那一族（`{"limitation1": …}` 到 `{"limitationN": …}`）消费的是
 *     **单一来源** `OpsEvents.LIMITATIONS` —— 而它上面那段注释明写过历史教训：
 *       改前 `NotifStore` 里是**手写两句**、md 说明书里**手写四句** => **两处漂了**
 *         => **跑核对器的人看到的局限比读说明书的人少**
 *   => 所以"一份来源"这件事必须有人看着
 *
 * 而最凶的一类错是**重复键名**：JSON 里后一个会**覆盖**前一个
 *   => 接收方少看到一条局限，而核对器 rc **不变** => **静默**
 *   => 所以下面有一条专门押它
 */
class LimitationLinesTest {

    @Test
    fun oneLinePerItem() {
        // 判据1：每个元素产出一行（一条不落）
        val items = listOf("甲", "乙", "丙")
        assertEquals("3 条入参该产 3 行", 3, limitationLines(items).size)
        assertEquals("0 条该产 0 行（而不是产 limitation0）", 0, limitationLines(emptyList()).size)
    }

    @Test
    fun keysAreNumberedFromOneContiguously() {
        //判据2：从 1 起、连续、不跳号、不重复
        val n = 7
        val out = limitationLines(List(n) { "第" + it + "条" })
        val keys = out.map { Regex("""^\{"(limitation\d+)":""").find(it)?.groupValues?.get(1) }
        assertEquals("每行都该能被解析出键名", n, keys.count { it != null })
        assertEquals(
            "键名该是 limitation1..limitation" + n + "，实际=" + keys,
            (1..n).map { "limitation" + it },
            keys,
        )
        assertEquals("键名不许重复", n, keys.toSet().size)
    }

    @Test
    fun textGoesThroughEsc() {
        // 判据3：文本过 esc
        val out = limitationLines(listOf("含\"引号\"与\n换行"))
        assertEquals(1, out.size)
        assertFalse("不该有裸 LF", out[0].contains('\n'))
        assertTrue("引号该被转义", out[0].contains("\\\""))
        assertTrue("换行该被转义", out[0].contains("\\n"))
    }

    @Test
    fun everyLineIsASingleFlattenedObject() {
        val out = limitationLines(listOf("a", "b"))
        for (ln in out) {
            assertTrue("该以 { 开头", ln.startsWith("{"))
            assertTrue("该以 } 结尾", ln.endsWith("}"))
            assertFalse("不该含裸换行", ln.contains('\n'))
        }
    }

    @Test
    fun theRealListProducesExactlyItsSize() {
        //判据4：**真实的单一来源**产出的行数 = 它自己的条数
        //   这条最有用：以后往 `LIMITATIONS` 里加/删一条，
        //     若渲染那侧漏了（例如改回手写两句）=> 这条立刻红
        val real = OpsEvents.LIMITATIONS
        assertTrue("单一来源不该是空的", real.isNotEmpty())
        assertEquals(
            "渲染出的行数必须等于 LIMITATIONS 的条数（" + real.size + "）",
            real.size,
            limitationLines(real).size,
        )
    }

    @Test
    fun realKeysAreContiguousToo() {
        // 同一条判据押在**真实数据**上（而不只是造的例子）
        val keys = limitationLines(OpsEvents.LIMITATIONS)
            .map { Regex("""^\{"(limitation\d+)":""").find(it)?.groupValues?.get(1) }
        assertEquals(
            "真实列表的键名也必须连续",
            (1..OpsEvents.LIMITATIONS.size).map { "limitation" + it },
            keys,
        )
    }
}
