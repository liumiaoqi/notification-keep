package com.example.myapplication.data

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 ***把"证据包 + 核对器 + 说明"打成一个 zip** —— 接收方拿到就能自己验
 *
 * 为什么必须带上核对器：
 *   证据包是**要交出去**的 => 只发一份 `.jsonl` 的话，接收方**无从核验**，
 *   只能听我们说"它没被改过" —— 那这份证据的价值就打了对折
 *   => 三份一起发：**数据 复算工具 怎么用**
 *
 * zip 里的条目名用 **ASCII**（`verify_export.py` / `HOW_TO_VERIFY.md`）——
 *   中文条目名在跨平台解压时容易乱码（内容当然是中文）
 *
 * 纯 IO（只用 `java.util.zip` => 不碰 Android API）=> 可以在 JVM 里用临时文件测
 */
object ExportBundle {

    /** 随 App 打包的核对器（真源在 `app/src/main/assets/verify_export.py`） */
    const val VERIFIER_NAME = "verify_export.py"

    /** 怎么用（ASCII 名 + 中文内容） */
    const val HOWTO_NAME = "HOW_TO_VERIFY.md"

    /**
     * 给接收方的三行说明 —— **别写长**：
     *   拿到 zip 的人只想问"我该怎么验、验出来什么算过"
     */
    fun howToText(jsonlName: String): String = """
        # 怎么核对这份证据包

        > **先看结论，再看边界**：怎么验在第 2 节 结论在第 4 节
        > **这份文件有 __LIM_N__ 条边界，它们集中在本说明的最后一节**
        > （写出来是为了不夸大 —— 不是提示"它有问题"）

        这个 zip 里有三样东西：

        | 文件 | 是什么 |
        |---|---|
        | `$jsonlName` | **证据包本体**（一行一条记录，JSON Lines）|
        | `$VERIFIER_NAME` | **独立核对器**（Python 3，不依赖本机任何东西）|
        | `HOW_TO_VERIFY.md` | 就是这份说明 |

        ## 一条命令

        ```
        python3 $VERIFIER_NAME $jsonlName
        ```

        ## 它会自己算一遍，回答四个问题

        1. **记录完整吗** —— 逐条重算哈希链，报出断在哪一条
        2. **这份文件被改过吗** —— **去掉最后一行【连同它前面的那个换行符】**，对剩余全文（UTF-8 字节）做 SHA-256，与文件里声明的 `exportSelfHash` 比
        3. **有没有"它不知道的时段"** —— 断档清单（`certain` = 有事件作证 `suspected` = 只是沉默超阈值）
        4. **它清空过吗** —— 清空留痕：何时、清掉多少、**被清掉那一段**的 `chainId`

        ## 结论怎么看

        - `这份文件自洽` => 链完整、且自身 hash 对得上（退出码 0）
        - `有问题` => 见它打出来的那一行 ``（退出码 1）

        ---

        ## 边界声明（最后这一节 必读 __LIM_N__ 条）

        > **它的边界集中在这里，一条都不藏** —— 写出来是为了**不夸大**，
        > **不是**说"这份文件有问题"
        > 如果你只想快速判断：**上面的结论 + 这一步**就够 ——
        > **在收到的时候就先把文件发一份给自己**（那是唯一需要你主动做的事）

        __LIM_LIST__

        说明文的每一行都**顶格**写：`trimIndent()` 只去掉【公共】缩进 => 续行缩进会在 Markdown 里变成意外的缩进块
        （这一条是 `ExportBundleTest` 里那条断言抓出来的）
    """.trimIndent()
        //2026-09-28 修：**局限那一段改成【渲染】**（不再在正文里手写）
        //    改前这里是手写 4 条，而 JSON 数据侧也手写了一份（2 条）
        //      => 两边漂开 => **跑核对器的人看到的局限比读这份说明的人少**
        //    现在正文里只留两个占位符，内容从 `OpsEvents.LIMITATIONS` 来
        //      => 与 JSON 数据**同源** => 不可能再漂
        //    用 `.replace` 而不是字符串模板：那让静态段落**不用拆开**，
        //      也不必担心续行缩进被 `trimIndent()` 处理错
        .replace("__LIM_N__", OpsEvents.LIMITATIONS.size.toString())
        .replace(
            "__LIM_LIST__",
            OpsEvents.LIMITATIONS.withIndex().joinToString("\n") { (i, t) -> "${i + 1}. $t" },
        ) + "\n"

    /**
     * 把三份内容写进一个 zip（写到 [out]，由调用方负责关闭）
     *
     * @param jsonlName 证据包文件名（会原样用作 zip 内的条目名）
     */
    fun writeTo(
        out: OutputStream,
        jsonlName: String,
        jsonlText: String,
        verifierText: String,
    ) {
        ZipOutputStream(out).use { zos ->
            fun put(name: String, text: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put(jsonlName, jsonlText)                       // 数据
            put(VERIFIER_NAME, verifierText)                // 复算工具
            put(HOWTO_NAME, howToText(jsonlName))           // 怎么用
        }
    }

    /** zip 的文件名（与 jsonl 同名，只换扩展名） */
    fun bundleName(jsonlName: String): String =
        jsonlName.substringBeforeLast('.') + ".zip"
}
