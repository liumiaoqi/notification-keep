package com.example.myapplication.crash

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 ***一条崩溃现场的文本格式**（纯逻辑 => JVM 可测）
 *
 ***为什么不用 JSON**（本项目在 JSON 上栽过三次）：
 *   拼 JSON 要走 `esc()`（转义）=> **崩溃路径上不该有会抛的转义逻辑**
 *     （本项目真出过：PEM 里的换行没转义 => 产出**非法 JSON**）
 *   而这份文件的读者是**人和 grep**，不是链 => 不需要 JSON
 *   => 用"**头行 key=value + 栈行 + 终止行**"
 *
 * **三条不变量**（都有用例钉着）：
 *   1 每条记录**以 [HEADER] 开头、以 [END] 结尾**
 *   2 `stack=N` 里的 N **必须等于**后面实际的行数
 *      => 于是"记录被写坏"这件事**能被机械认出来**（有动作就有断言）
 *   3 **`END` 在一份文件里只能出现在"记录末尾"** =>
 *      所以单行字段（`msg` / `stage` / …）里若整好等于 `END` => 前面补一个空格破掉它
 *      （栈行天然带两格缩进 => 不可能等于 `END` 不用额外处理）
 *
 * **时间戳**：用 `SimpleDateFormat` 带**显式时区** => 单测里传固定时区 => 结果确定
 *   （它是 `java.text`（JVM 就有）=> **不是** Android API => 单测里能跑）
 */
object CrashRecord {

    /** 文件头（每条记录第一行） */
    const val HEADER = "# NK-CRASH v1"

    /** 终止行（每条记录最后一行）—— **同时被 [CrashRotate] 用来切块** */
    const val END = "--- END NK-CRASH ---"

    //`kind` 的取值（闭集）
    /** 未捕获的 Java/Kotlin 异常（`Thread.setDefaultUncaughtExceptionHandler`） */
    const val KIND_UNCAUGHT = "UNCAUGHT"

    /** 系统记下的退出（`ApplicationExitInfo`）—— **本次的 ANR 走这条** */
    const val KIND_SYSTEM_EXIT = "SYSTEM_EXIT"

    /** 哨兵发现"上次启动没走完" */
    const val KIND_STARTUP_INCOMPLETE = "STARTUP_INCOMPLETE"

    /** 上一条记录**写到一半断了**（这本身就是证据：进程死在写文件的过程中） */
    const val KIND_TRUNCATED = "TRUNCATED"

    /** 空值占位（显式写出来，不发善心地留空 => 读的人不必猜） */
    private const val NONE = "-"

    /** 栈行缩进 —— **顺便保证栈行永远不可能等于 [END]** */
    private const val INDENT = "  "

    /**
     * 一条记录的**全部输入**（纯数据）。
     *
     * `excClass` / `excMessage` 可空 => 渲染时写成 [NONE] / 空串
     *   （**格式永远是同一套** => 好解析）
     * `extra` = 挂在第二行后面的附加键值（如 `reason=ANR` / `traceLines=40`）
     */
    data class Fields(
        /** 时刻（epoch ms）`<= 0` => 渲染成 `-`（未知）*/
        val atMs: Long,
        val uptimeMs: Long,
        val stage: String,
        val pid: Int,
        val kind: String,
        val threadName: String,
        val excClass: String?,
        val excMessage: String?,
        val appVersion: String,
        val androidRelease: String,
        val sdkInt: Int,
        val device: String,
        val stackLines: List<String>,
        val extra: List<Pair<String, String>> = emptyList(),
    )

    /** 带**显式时区**的本地时间（`+0800` 形状）—— 传固定时区就得到确定结果 */
    fun isoLocal(atMs: Long, tz: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
            .apply { timeZone = tz }
            .format(Date(atMs))

    /**
     ***压成一行**（不变量 3 就靠它）
     *
     * 1 `CRLF` / `CR` / `LF` 一律变成**两个字面字符** `\n` => 字段里再也带不了真换行
     * 2 结果若整好等于 [END] 或 [HEADER] => **前面补一个空格**
     *    （否则"终止行唯一"这条不变量破了 => 切块会切错）
     */
    fun oneLine(raw: String): String {
        val flat = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\\n")
        return if (flat == END || flat == HEADER) " $flat" else flat
    }

    /**
     ***头字段专用**（在 [oneLine] 之上再加一条：**空格换成 `_`**）
     *
     ***为什么头字段必须没有空格**：
     *   头行是 `key=value` 用**单个空格**分隔的一串
     *   => 值里若混进空格 => **字段从一个变成两个** => 读文件的人（和 grep）
     *     会读到**错的值**（比如 `dev=Xiaomi Redmi Note` => 尾巴上多出两个假字段）
     *   而 `msg=` 是**独占一行**的自由文本 => 那里**保留空格**（判据不同 => 处理不同）
     */
    fun headerValue(raw: String): String = oneLine(raw).replace(' ', '_')

    /** 渲染一整条记录（**不带**结尾换行 => 记录之间的换行由 [CrashRotate] 拼） */
    fun render(f: Fields, tz: TimeZone = TimeZone.getDefault()): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        sb.append("at=").append(if (f.atMs > 0L) isoLocal(f.atMs, tz) else NONE)
            .append(" uptimeMs=").append(f.uptimeMs)
            .append(" stage=").append(headerValue(f.stage))
            .append(" pid=").append(f.pid).append('\n')
        sb.append("kind=").append(headerValue(f.kind))
            .append(" thread=").append(headerValue(f.threadName))
        for ((k, v) in f.extra) sb.append(' ').append(headerValue(k)).append('=').append(headerValue(v))
        sb.append('\n')
        sb.append("exc=").append(f.excClass?.let { headerValue(it) } ?: NONE).append('\n')
        // `msg` = 自由文本 => **保留空格**，只把换行压掉
        sb.append("msg=").append(f.excMessage?.let { oneLine(it) } ?: "").append('\n')
        sb.append("app=").append(headerValue(f.appVersion))
            .append(" android=").append(headerValue(f.androidRelease))
            .append(" sdk=").append(f.sdkInt)
            .append(" dev=").append(headerValue(f.device)).append('\n')
        // 不变量 2：N 直接取实际条数 => **造不出"声明与实体不符"的记录**
        sb.append("stack=").append(f.stackLines.size).append('\n')
        for (l in f.stackLines) sb.append(INDENT).append(oneLine(l)).append('\n')
        sb.append(END)
        return sb.toString()
    }
}
