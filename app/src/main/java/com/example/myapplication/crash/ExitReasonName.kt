package com.example.myapplication.crash

/**
 * 系统退出原因（`ApplicationExitInfo.getReason()` 的 int）-> 稳定可读名 + 该不该报警。
 *
 * **这些常量值是【照抄】`android.app.ApplicationExitInfo.REASON_*`**
 *   权威来源：本机 SDK 源码 `sources/android-37.0/android/app/ApplicationExitInfo.java`
 *
 * **为什么照抄、不直接引 Android 常量**：
 *   本文件要能在 **JVM 单测**里跑 => 引 `android.app.*` 会 `not mocked`
 *   （本项目栽过同型的：`Method w in android.util.Log not mocked`）
 *   => **对接那一层**（`getReason()` -> 这个 int）放在 [CrashLogIO] —— 唯一碰 Android API 的文件
 *
 * **风险（如实标）**：这些值若哪天与系统对不上 => 会**静默错名**
 *   => 所以有一条用例把"每个值对应哪个名"钉死
 *   => 而"值与系统真的一致"**在 JVM 里验不了** => 只能靠真机对照
 *     （独立算路：`adb shell dumpsys activity exit-info <包名>` 会打印
 *       `reason=6 (ANR)` 这种**带名字**的文本 => 与 `name(6)` 对得上才算过）
 *
 * 为什么需要它：**"是什么原因退出的"必须一眼看懂** ——
 *   数字（`reason=6`）读文件的人不认识，而 本项目已经实测到本机会产生 `ANR`
 */
object ExitReasonName {

    // 值 = `ApplicationExitInfo.REASON_*`（别改 —— 改了与系统对不上）
    const val UNKNOWN = 0
    const val EXIT_SELF = 1
    const val SIGNALED = 2
    const val LOW_MEMORY = 3
    const val CRASH = 4
    const val CRASH_NATIVE = 5
    const val ANR = 6
    const val INITIALIZATION_FAILURE = 7
    const val PERMISSION_CHANGE = 8
    const val EXCESSIVE_RESOURCE_USAGE = 9
    const val USER_REQUESTED = 10
    const val USER_STOPPED = 11
    const val DEPENDENCY_DIED = 12
    const val OTHER = 13
    const val FREEZER = 14
    const val PACKAGE_STATE_CHANGE = 15
    const val PACKAGE_UPDATED = 16

    /**
     * 该不该把它当成"需要报警的退出"。
     *
     * 判据：**"这次退出是我们（或用户）主动要的吗？"** 不是 => 报警
     *   `ANR` / `CRASH` / `CRASH_NATIVE`            => 核心（本次缺陷就是第一种）
     *   `LOW_MEMORY`                                => 系统杀的，但它解释了"为什么断了"
     *   `INITIALIZATION_FAILURE` / `EXCESSIVE_RESOURCE_USAGE` => 罕见但要留痕
     *   `USER_REQUESTED` / `USER_STOPPED`（force-stop）
     *     / `PACKAGE_UPDATED`（装包）/ `OTHER`（系统清理）=> **不报警**
     *     —— 它们是**正常生命周期**，每次都写会把真现场挤掉
     */
    private val ABNORMAL: Set<Int> = setOf(
        ANR, CRASH, CRASH_NATIVE, LOW_MEMORY,
        INITIALIZATION_FAILURE, EXCESSIVE_RESOURCE_USAGE,
    )

    /** int -> 名（未知值**必须带原值** => 不许吞成 `UNKNOWN` 了事） */
    fun name(reason: Int): String = when (reason) {
        UNKNOWN -> "UNKNOWN"
        EXIT_SELF -> "EXIT_SELF"
        SIGNALED -> "SIGNALED"
        LOW_MEMORY -> "LOW_MEMORY"
        CRASH -> "CRASH"
        CRASH_NATIVE -> "CRASH_NATIVE"
        ANR -> "ANR"
        INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        USER_REQUESTED -> "USER_REQUESTED"
        USER_STOPPED -> "USER_STOPPED"
        DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        OTHER -> "OTHER"
        FREEZER -> "FREEZER"
        PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($reason)"
    }

    fun isAbnormal(reason: Int): Boolean = reason in ABNORMAL
}

/**
 * 一条**系统记下的退出**（值对象 纯逻辑 => JVM 可测）
 *
 * 它是 `ApplicationExitInfo` 的**纯数据摘录** ——
 *   取值的动作（那是个 Android 对象）在 [CrashLogIO]
 *
 * @param pssKb 退出时的物理内存（KB）`-1` = 系统没给
 */
data class ExitItem(
    val tsMs: Long,
    val pid: Int,
    val reason: Int,
    val importance: Int,
    val pssKb: Long,
    val description: String,
)

/**
 * **从系统的退出记录里挑出"新的、值得写下来的"那几条**（纯逻辑 => JVM 可测）
 *
 ***为什么必须有"去重"这一步**：
 *   `getHistoricalProcessExitReasons` 返回的是一个 **ring buffer**（本机实测：留 16 条）
 *   => 每次启动都会**再看到同一条** => 若原样落盘 =>
 *     **同一条 ANR 会被抄进日志 20 遍、把别的现场全挤掉**
 *   => 所以要看"上次记到哪一条了"（`lastSeenTsMs` + `lastSeenPid`）
 *
 * **判据是"时刻 + pid"两个一起**，不是只看时刻：
 *   同一秒里进程可能重启两次（本机实测就有：`06:10:25` 与 `06:11:13` 只差 48 秒；
 *   而更极端的情形是同一毫秒）=> 只看时刻会**漏掉其中一条**
 *
 * **排序按时刻升序**（最旧的先写）=> 日志里读起来是**时间递增**的
 *   （与"一次性日志"的直觉一致；而淘汰是按**块**、丢最旧的 => 新的更值钱）
 */
object ExitWatch {

    /**
     * **"游标之后"的判据 = 按 `(时刻, pid)` 这个**元组**比大小**
     *
     ***为什么不能只看时刻**（这是"注释与实现不符"的高发处，所以单独拆出来）：
     *   同一毫秒里进程可以重启两次（本机实测 06:10:25 / 06:11:13 已经只差 48 秒了）
     *   => 只看 `ts > lastTs` => 另一条**永远轮不到**（那条现场就丢了）
     *   => 所以**必须**把 `pid` 一起放进比较里
     */
    private fun isAfter(tsMs: Long, pid: Int, lastTsMs: Long, lastPid: Int): Boolean =
        tsMs > lastTsMs || (tsMs == lastTsMs && pid > lastPid)

    private val ORDER: Comparator<ExitItem> = compareBy({ it.tsMs }, { it.pid })

    fun pickNew(
        items: List<ExitItem>,
        lastSeenTsMs: Long,
        lastSeenPid: Int,
        maxKeep: Int,
    ): List<ExitItem> {
        val fresh = items
            .filter { ExitReasonName.isAbnormal(it.reason) }
            .filter { isAfter(it.tsMs, it.pid, lastSeenTsMs, lastSeenPid) }
            .sortedWith(ORDER)
        // 只保留最近的 maxKeep 条（免得"很久没开 App"时一次灌进几十条）
        return if (fresh.size <= maxKeep) fresh else fresh.takeLast(maxKeep)
    }

    /**
     * **记到哪一条了** —— 用来做下一次的去重游标。
     *
     * 与 `pickNew` 配对使用：**写成功了才推进游标**
     *   （若写失败还推进 => 那条现场**永远丢了**）
     * 取的是**按同一个元组顺序**的最大者 => 与 [isAfter] 严格配对（反了就会漏）
     */
    fun cursor(items: List<ExitItem>): Pair<Long, Int>? =
        items.maxWithOrNull(ORDER)?.let { it.tsMs to it.pid }
}
