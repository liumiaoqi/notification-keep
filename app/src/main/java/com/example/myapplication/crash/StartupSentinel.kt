package com.example.myapplication.crash

import com.example.myapplication.fmtAgo

/**
 ***启动哨兵**：回答"**上一次启动，走到哪一步就没了**"（纯逻辑 => JVM 可测）
 *
 ***为什么必须有它**（这是本次真正要治的病）：
 *   真机上 NK "打开就崩"，而 系统**默认不弹崩溃框、也不留对我们可见的调试信息**
 *   => 那条路径**没有 Java 异常**（实测：退出原因全是 `ANR`，不是 `CRASH`）
 *     => **uncaught 异常处理器抓不到它**（那是给 Java 崩溃用的）
 *   => 所以补这一手：**每一步都留个记号** => 下次启动就能看出"上次卡在哪"
 *
 * 与 [ExitWatch]（读系统的退出记录）的分工：
 *   系统记录说"**系统怎么看这次退出**"（ANR / CRASH / …）
 *   本对象说"**我自己走到哪一步**"（进程起来了吗？界面渲染了吗？）
 *   => **两条互证** —— 且系统记录被 ring buffer 清掉时，这一条还在
 *
 * **它不是"兜底"**：它不改变任何行为、不吞任何东西
 *   它只是**把现场留下来** —— 与本任务的目标一致（可观测，不是静默）
 */
object StartupSentinel {

    /**
     * 启动链路的**阶段闭集**（顺序 = 启动链路的先后）
     *
     ***为什么是枚举、不是 `String`**（这是我自己复查时抓到的缺陷，改法记在这）：
     *   字符串版里，调用方写 `mark(cx, "content_shwon")`（**拼错**）=> **编译通过**
     *   => 而 [verdict] 会把不认识的名字当 [Verdict.Benign]（正常）
     *     =>**哨兵静默失效、且看不出任何迹象**
     *     —— 这**正是本模块要治的那类病**（"声明与实体不符"），不能自己再犯一个
     *   => 用枚举 => **拼错就编不过** => 这类失败从"静默"变成"不可能"
     *   而**存到 prefs 的仍是名字**（`name`）=> 所以 [verdict] 还得处理
     *     "**名字不认识**"这一格（例如老版本写下的名字）=> 见它的注释
     */
    enum class Stage { PROCESS_START, ACTIVITY_CREATE, GATE_SHOWN, AUTH_PROMPTED, CONTENT_SHOWN }

    /**
     ***只有停在这两个阶段，才算"上次启动没走完"**
     *
     * [Stage.AUTH_PROMPTED] **不在**其中，[Stage.GATE_SHOWN] **也不在** ——
     *   这两格都是**"用户在门口"**的**良性**状态：
     *     闸门渲染出来了、`BiometricPrompt` 也弹了，用户**没按就退了** => 用户行为
     *     甚至闸门刚渲染出来、认证还没弹，用户就退了（或系统认证 UI 抢了前台）=> 同样良性
     *   => 把它们算成异常 => **面板天天误报** => **人就再也不信这个面板了**
     *     —— 而"误报"比"漏报"更致命：它会把**真 ANR** 淹在噪声里
     *
     ***这条判据改过一次，改法值得留下**（判据：**"常见的良性路径"不许报警**）：
     *   初版把 [Stage.GATE_SHOWN] 当异常（理由写的是"卡在闸门自己的渲染里，值得报"）
     *   **独立复审指出那是个误报源**："停在闸门"恰恰是**最常见的路径**之一
     *     （我看过真机数据：用户反复打开、每次停在闸门 => 若每格都报，会把真 ANR 埋掉）
     *   => 现在只有 **`PROCESS_START` / `ACTIVITY_CREATE`** 算异常 ——
     *     它们的含义很硬：**"界面都还没起来"** => 那时**只可能是**崩了/卡死
     */
    private val STUCK_MEANS_INCOMPLETE: Set<Stage> =
        setOf(Stage.PROCESS_START, Stage.ACTIVITY_CREATE)

    /** 从没启动过 / 没有记号（`lastStartedAtMs <= 0`）=> 不判定 */
    const val NO_START_MS = 0L

    /** **上一次启动的结论** */
    sealed class Verdict {
        /** 首次（或记号被清）=> **界面什么都不显示** */
        object FirstRun : Verdict()

        /** 上次走到了内容 => 正常 */
        object Completed : Verdict()

        /**
         * 停在一个"**说明不了问题**"的阶段 => **不报警**
         *
         * **三种来源**（独立复审 P3-1 更正 —— 第一版只写了两种，漏了 `GATE_SHOWN`）：
         *   [Stage.GATE_SHOWN] —— **闸门渲染出来了、用户还没认证就走了** => 良性
         *   [Stage.AUTH_PROMPTED] —— 认证弹了、用户没按 => 良性
         *   **不认识的名字** —— 宁可漏报也不误报（见 [verdict]）
         *   => 与 [STUCK_MEANS_INCOMPLETE] **必须一致**：它只含 `PROCESS_START` / `ACTIVITY_CREATE`
         */
        object Benign : Verdict()

        /**上次没走完（就是要报警的那一格） */
        data class Incomplete(val stage: Stage, val startedAtMs: Long) : Verdict()
    }

    /**
     * 判定（**纯函数** —— `nowMs` 由调用方传 => 可测）。
     *
     * **参数是 prefs 里那个【名字】**（不是枚举 => 因为可能来自**老版本**）
     *
     * **名字不认识 => [Verdict.Benign]（不报警）** —— 这是**有意选的方向**：
     *   宁可**漏报**，也不**误报**
     *   （理由：误报会让人不再看这个面板 => 那比漏一条更糟）
     */
    fun verdict(lastStageName: String?, lastStartedAtMs: Long): Verdict {
        if (lastStageName.isNullOrEmpty() || lastStartedAtMs <= NO_START_MS) return Verdict.FirstRun
        val stage = Stage.values().firstOrNull { it.name == lastStageName } ?: return Verdict.Benign
        if (stage == Stage.CONTENT_SHOWN) return Verdict.Completed
        if (stage in STUCK_MEANS_INCOMPLETE) return Verdict.Incomplete(stage, lastStartedAtMs)
        return Verdict.Benign
    }

    /**
     * 给人看的那一行（`null` = **界面什么都不显示** —— 界面按这个判）
     *
     * 用了 `fmtAgo`（那个纯函数与"多久以前"的文案本项目已经统一过）
     *   => 它是**纯函数**（吃 `Long` 吐 `String`）=> 这里**没有破坏可测性**
     */
    fun describe(v: Verdict, nowMs: Long): String? = when (v) {
        is Verdict.FirstRun -> null
        is Verdict.Completed -> null
        is Verdict.Benign -> null
        is Verdict.Incomplete ->
            "上次启动未完成：卡在「${v.stage.name}」（${fmtAgo(v.startedAtMs, nowMs)}）" +
                " —— 可能是崩溃/无响应（系统怎么说见「崩溃记录…」）"
    }

    /**
     ***哨兵那条记录里要写的几行**（纯函数 => JVM 可测）
     *
     * **为什么要抽出来**（2026-10-15 #4 补令）：
     *   这几行**会原样落进 `crash.log`** => 那条"**记录文案不许有 markdown 装潢**"的判据
     *   **必须能 JVM 测** —— 原来它们内嵌在 `CrashLogIO.renderIncomplete`（要 `Context`）=> 测不到
     *   （同族判据：`AGENTS.md` §三#4「纯逻辑必须可测」）
     *
     * 这三行**无装潢**（无 `**` / 无 ``）—— 它进的是**取证记录**，不是给人看的说明文
     */
    fun recordLines(v: Verdict.Incomplete): List<String> = listOf(
        "上一次运行的起点：${CrashRecord.isoLocal(v.startedAtMs)}",
        "上一个进程最后记下的阶段：${v.stage.name}",
        "（这一条由哨兵写下，不依赖系统记录）",
    )
}
