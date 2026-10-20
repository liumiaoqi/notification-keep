package com.liumiaoqi.notificationkeep.crash

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.liumiaoqi.notificationkeep.data.appVersionName
import java.io.File

/**
 ***崩溃可观测性的"Android 适配层"**（**全模块唯一碰 Android API 的文件**）
 *
 * **为什么要把这一层单独隔出来**（本项目的既有做法）：
 *   [CrashRecord] / [CrashRotate] / [StartupSentinel] / [ExitReasonName] / [GzipTrace]
 *   都是**纯逻辑** => **JVM 单测里能跑**
 *   => 而"读文件 / 读 prefs / 查 `ActivityManager`"这些**必须有 Android** => 全放这里
 *   （判据见 `AGENTS.md` §三#4「纯逻辑必须可测」）
 *
 ***三条铁律（本文件就是靠它们才叫"可观测"而不是"静默兜底"）**：
 *   1 **不吞异常** —— 未捕获异常的处理器里，**先落盘、再把异常原样交给原 handler**
 *      => 进程该杀还是杀、系统该弹框还是弹（[install] 里逐行可核）
 *   2 **崩溃路径不做重活** —— 处理器里只做：拼字符串 -> 打开文件 -> 写 -> 关
 *      **不开线程、不碰 SQLite、不做 binder 调用**（那条线程正在死）
 *   3 **失败要响** —— 任何一步写不成 => Logcat 里 `Log.e` 出声 + 返回值 false
 *      **绝不"写不成也当写成了"**（本项目在这类"假成功"上栽过多次）
 *
 * **为什么落盘不用数据库**（`notif.db` 那张事件表就在隔壁）：
 *   崩溃路径**不能依赖 SQLite**（库本身可能就是崩因；且铁律#2「数据一条都不删」）
 *   => 与证据链**物理隔离**：`files/crash.log`（应用内部存储 => **不申请任何权限**）
 */
object CrashLogIO {

    /** 全项目统一日志 tag（与 `NotifListener.TAG` 同一个值 => `adb logcat -s NK:V` 就看全） */
    const val TAG = "NK"

    /** 日志文件（放在内部存储 => 零权限） */
    const val FILE_NAME = "crash.log"

    /**
     * 哨兵用**独立的** prefs 文件（不塞进 `nk_audit`）。
     *   理由：`nk_audit` 是**证据留痕**（清空/链身份），语义不同、读者不同 => 不混
     *   而 `clearAllWithRecord()` **只动数据库与 `nk_audit`** => 这个文件不会被清空删掉
     */
    const val PREF = "nk_crash"

    private const val K_STAGE = "stage"
    private const val K_STAGE_AT = "stage_at"

    /** 去重游标：上次已经写进日志的系统退出记录（见 [ExitWatch] 的注释） */
    private const val K_EXIT_TS = "exit_ts"
    private const val K_EXIT_PID = "exit_pid"

    /** 每次最多写几条系统退出记录（免得"很久没开"时一次灌一堆） */
    private const val EXIT_MAX_KEEP = 5

    /** 一次最多读多少 trace 字节（1 MiB）—— **读取必须有界**（`readBytes()` 是无界的） */
    private const val MAX_TRACE_BYTES = 1 shl 20

    private fun prefs(cx: Context) = cx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun logFile(cx: Context): File = File(cx.applicationContext.filesDir, FILE_NAME)

    //崩溃路径的"现场快照"（启动时取一次 => 处理器里零 binder / 零 prefs）

    /**
     ***为什么要有它**（这是我自己复查时抓到的**违反自己的规矩**）：
     *   规矩是"崩溃处理器里**不许**有 binder / SQLite / 重 IO"（那条线程正在死）
     *   而第一版处理器里却调了：
     *     `appVersionName(cx)` => 那是 `packageManager.getPackageInfo(...)` => **binder 调用**
     *     `lastStage(cx)`      => 读 `SharedPreferences` => **可能是一次读盘 + 解 XML**
     *   => 两样都是"处理器被卡住 => 现场反而落不下去"的风险
     *   => 修法：**这些值在启动时取一次、缓存在内存里**，处理器只读内存
     */
    private class Env(
        val appVersion: String,
        val androidRelease: String,
        val sdkInt: Int,
        val device: String,
    )

    /** 快照（`null` = 还没取到 => 处理器**如实写 `-`**，不许为了填它去 binder） */
    @Volatile
    private var env: Env? = null

    /**
     * **本进程当前阶段**（只在内存里）。
     *
     * 为什么内存里也留一份：处理器要它，而**读 prefs 可能读盘**
     *   => [mark] 时顺手更新它 => 处理器直接读，**不碰盘**
     *   它与 prefs 里那份**用途不同**：prefs 那份给**下一次启动**判（[verdict]），
     *     内存这份给**这一次崩溃**写（同一次改动里两个读者，别混）
     */
    @Volatile
    private var currentStage: String = STAGE_UNKNOWN

    private const val STAGE_UNKNOWN = "unknown"

    /**
     ***进程级的那把写锁** —— **同一个日志文件的所有【写者】共用它**
     *
     * 为什么放在 `object`（= 进程一份）而不是各调用点各持一把：
     *   写者是**两个**（独立复审 P3-1 更正：**界面不是写者**）：
     *     崩溃处理器（主线程，[install] 里那个）
     *     `nk-crash-io` 后台线程（[onAppStart] 起的：哨兵记录 + 系统退出记录 **同一条线程**）
     *   => 各持一把 = **等于没锁**（这正是 P2-1 的成因）
     *
     * **读者（界面拉摘要）为什么不必进锁**：因为写者是 `临时文件 + rename` 落盘的
     *   => `rename` 原子 => 读者要么看到**旧的**、要么看到**新的**，**看不到半截**
     *   （这也正是选 `rename` 而不是"直接改原文件"的原因之一）
     */
    private val appendLock = Any()

    //接线1：未捕获异常

    /**
     *装未捕获异常处理器（在 `Application.onCreate` 里**最早**调）。
     *
     ***"不吞"这一条就在下面这 4 行里** —— 改它之前先想清楚：
     *    原始异常**必须**被交给原 handler => 否则我们就把"崩溃"改成了"不崩"
     *    （那与本任务的目标**正好相反**）
     */
    fun install(cx: Context) {
        val app = cx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // 1 先落盘（这一步失败也**不许**影响下面 2）
            writeUncaught(app, t, e)
            // 2 再把异常**原样交回** => 系统照常杀进程 / 照常弹"应用已停止"
            if (prev != null) {
                prev.uncaughtException(t, e)
            } else {
                // 这不是"兜底"，是**正确性**：默认行为就是"杀掉进程"
                //   少了它 => 进程会带着一个已经抛过异常的状态继续跑 => 那才是真"吞"
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        Log.i(TAG, "崩溃捕获已装（落盘 ${FILE_NAME} 不吞异常：仍交给原 handler）")
    }

    /**
     * 写一条未捕获异常。
     *
     * **这里的 `runCatching` 不是"兜底"**（与本项目的反模式不是一回事）：
     *   它**只包"写日志"这一步** => 一点异常都没被吞
     *   它存在的**唯一理由**：若这里抛出去 => **原 handler 就永远轮不到**
     *     => 那我们就**改变了崩溃语义**（那才是不可接受的）
     *   而"写失败"这件事本身**照样出声**：`Log.e` + 堆栈（双通道）
     */
    private fun writeUncaught(cx: Context, t: Thread, e: Throwable) {
        //**这一段里【只有】字符串操作 + 一次文件写** —— 逐项可核：
        //   不 binder（appVersion 从 `env` 读，不调 `packageManager`）
        //   不读 prefs（阶段从 `currentStage` 读）
        //   不开线程、不碰 SQLite
        //   `Build.*` 是**静态字段**（不是 binder）=> 快照没取到时用它兜底是安全的
        val snapshot = env
        runCatching {
            append(
                cx,
                CrashRecord.render(
                    CrashRecord.Fields(
                        atMs = System.currentTimeMillis(),
                        uptimeMs = SystemClock.elapsedRealtime(),
                        stage = currentStage,
                        pid = Process.myPid(),
                        kind = CrashRecord.KIND_UNCAUGHT,
                        threadName = t.name,
                        excClass = e.javaClass.name,
                        excMessage = e.message,
                        appVersion = snapshot?.appVersion ?: "-",
                        androidRelease = snapshot?.androidRelease ?: Build.VERSION.RELEASE,
                        sdkInt = snapshot?.sdkInt ?: Build.VERSION.SDK_INT,
                        device = snapshot?.device ?: deviceLine(),
                        // 只做字符串切分 —— 不碰任何 Android API
                        stackLines = e.stackTraceToString().split('\n').filter { it.isNotBlank() },
                    )
                )
            )
        }.onFailure { x ->
            // 双通道：文件写不成 => Logcat 必须响（不静默）
            Log.e(TAG, "崩溃记录**没能落盘** ${x.javaClass.simpleName}: ${x.message}", x)
        }
    }

    //接线2：启动哨兵 + 系统退出记录

    /**
     *每次 App 启动调一次（主语 = `Application.onCreate`）。
     *
     ***顺序是这条函数的全部内容，不能改**：
     *   1 **先结算上一次**（读它的记号 => 算出 verdict）
     *   2 **再记这一次**（写 `process_start`）
     *   => 反过来的话，上一次的记号**当场被覆盖** => "上次停在哪"永远查不出来
     *
     * **主线程只做前两步**（都是几个字节的 prefs 读写）：
     *   那个 App **已经因为主线程被占住 ANR 过 6 次**（真机实测）=>
     *   **我们自己的观测代码绝不能再往主线程上加文件 IO**
     *   => 所以"写哨兵记录 + 读系统退出记录"（都是 IO / binder）全丢给**一个后台线程**
     */
    fun onAppStart(cx: Context) {
        val app = cx.applicationContext
        val v = verdict(cx)                              // 1 读上一次
        // 2 记这一次 `mark()` 里是 **`commit()`（有意同步写）** —— 理由见 `mark` 的注释
        mark(cx, StartupSentinel.Stage.PROCESS_START)
        Thread {
            // 1 **先把现场快照取好**（这一步有 binder（`packageManager`）=> 只能在后台做）
            //   => 这样"崩溃处理器里的 binder"就被**彻底拿掉**了
            env = runCatching {
                Env(appVersionName(app), Build.VERSION.RELEASE, Build.VERSION.SDK_INT, deviceLine())
            }.getOrNull()
            if (v is StartupSentinel.Verdict.Incomplete) {
                runCatching { append(app, renderIncomplete(app, v)) }
                    .onFailure { Log.e(TAG, "哨兵记录写不成 ${it.javaClass.simpleName}: ${it.message}") }
            }
            runCatching { captureLastExit(app) }
                .onFailure { Log.e(TAG, "读系统退出记录失败 ${it.javaClass.simpleName}: ${it.message}") }
        }.apply { name = "nk-crash-io"; isDaemon = true }.start()
    }

    /**
     * 记一个阶段（由 `MainActivity` 在 `onCreate` 末尾 / `MainScreen` 里调）。
     *
     ***用 `commit()`（同步落盘），不是 `apply()`** —— 这里改过一版，**理由值得留下**：
     *   初版用 `apply()`，理由是"别在启动主线程上加 IO"（这个 App 因主线程慢 ANR 过）
     *   **一次真机上对不上的观测把它推翻了**（2026-10-15 两次进程启动的对照）：
     *     第 1 次启动**确实**写了记号，而第 2 次启动**没有**按预期报出"上次没走完"
     *     当时**没能把它归因到唯一原因**（`apply()` 的异步落盘 + `install -r` 的 SIGKILL 是头号嫌疑）
     *   =>判据：**这个洞可以直接堵死，不必先归因**
     *     `commit()` 返回时数据**已经在磁盘上** => "记号不见了"这种形态**不可能发生**
     *   **代价评估**（不是"随手加的 IO"）：
     *     写的是**一个几百字节的 XML、每次启动共 3 次** => **毫秒级**
     *     => 与那 **5000 ms** 的 ANR **不是一个量级**
     *     （"别在启动主线程加 IO"那条针对的是**重活** —— TEE 建密钥 / 重建库）
     *  而**丢记号的代价是双向的，两边都不能接受**：
     *     丢 `PROCESS_START` => **漏报**（静默）丢 `CONTENT_SHOWN` => **误报**
     *     （"上次没走完"，而它其实走完了 => 正是本项目最怕的那种假警报）
     */
    fun mark(cx: Context, stage: StartupSentinel.Stage) {
        // 内存里那份同时更新（它的读者是**崩溃处理器** => 那里不许读盘）
        currentStage = stage.name
        val p = prefs(cx)
        val now = System.currentTimeMillis()
        val ed = p.edit().putString(K_STAGE, stage.name)
        // **起点只在"新一次运行的第一个记号"时打** => 否则每次 mark 都把起点往后推
        if (stage == StartupSentinel.Stage.PROCESS_START || p.getLong(K_STAGE_AT, 0L) <= 0L) {
            ed.putLong(K_STAGE_AT, now)
        }
        // 失败要响（一个静默失败掉的记号 = 观测静默失效 => 那正是不许发生的事）
        if (!ed.commit()) Log.e(TAG, "哨兵记号**没能落盘** stage=${stage.name}（观测会因此失效）")
    }

    /** 上一次运行的结论（界面按它决定显不显示那一行） */
    fun verdict(cx: Context): StartupSentinel.Verdict {
        val p = prefs(cx)
        return StartupSentinel.verdict(p.getString(K_STAGE, null), p.getLong(K_STAGE_AT, 0L))
    }

    private fun renderIncomplete(cx: Context, v: StartupSentinel.Verdict.Incomplete): String =
        CrashRecord.render(
            CrashRecord.Fields(
                atMs = System.currentTimeMillis(),
                uptimeMs = 0L,
                stage = v.stage.name,
                pid = Process.myPid(),
                kind = CrashRecord.KIND_STARTUP_INCOMPLETE,
                threadName = "main",
                excClass = null,
                excMessage = "上一次启动没走完：停在「${v.stage.name}」（起点 ${v.startedAtMs}）" +
                    " —— 没有达到「${StartupSentinel.Stage.CONTENT_SHOWN.name}」",
                appVersion = appVersionName(cx),
                androidRelease = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                device = deviceLine(),
                stackLines = StartupSentinel.recordLines(v),
                extra = listOf("lastStage" to v.stage.name),
            )
        )

    /**
     * 读系统记下的退出（**本次的 ANR 就走这条**）。
     *
     * **不需要任何权限**：`getHistoricalProcessExitReasons` 读**自己 UID** 的记录
     *   => SDK 源码原话：只有"查**别的** UID"才要 `DUMP`
     *   （已在本机 `sources/android-37.0/android/app/ActivityManager.java` 核对）
     *
     * 它是 **binder 调用** + 返回一个 **ring buffer**（本机实测留 16 条）
     *   => 必须在**非主线程**调（[onAppStart] 已经保证了）
     */
    private fun captureLastExit(cx: Context) {
        val am = cx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val raw = am.getHistoricalProcessExitReasons(null, 0, 12).map { info ->
            RawExit(
                ExitItem(
                    tsMs = info.timestamp,
                    pid = info.pid,
                    reason = info.reason,
                    importance = info.importance,
                    pssKb = info.pss / 1024,
                    description = info.description.orEmpty(),
                ),
                info,
            )
        }
        val p = prefs(cx)
        val fresh = ExitWatch.pickNew(
            raw.map { it.item },
            lastSeenTsMs = p.getLong(K_EXIT_TS, 0L),
            lastSeenPid = p.getInt(K_EXIT_PID, 0),
            maxKeep = EXIT_MAX_KEEP,
        )
        if (fresh.isEmpty()) return
        val byKey = raw.associateBy { it.item.tsMs to it.item.pid }
        var written = 0
        for (item in fresh) {
            val r = byKey[item.tsMs to item.pid] ?: continue
            if (append(cx, renderExit(cx, r))) written++
        }
        //**只有全部写进去了才推进游标** —— 否则那条现场**永远丢了**
        if (written == fresh.size) {
            ExitWatch.cursor(fresh)?.let { (ts, pid) ->
                // 这一处也在**后台线程**上（[onAppStart] 起的那个）=> 同步写没有主线程代价
                //   而它**必须**落盘：丢了游标 => 下次把同一批退出记录**再抄一遍** =>
                //      那些副本会把真正的现场挤出去
                val ok = p.edit().putLong(K_EXIT_TS, ts).putInt(K_EXIT_PID, pid).commit()
                if (!ok) Log.e(TAG, "退出记录游标没能落盘（下次会把同一批再抄一遍）")
            }
        } else {
            Log.e(TAG, "系统退出记录只写进 $written/${fresh.size} 条 => **不推进游标**（下次再试）")
        }
    }

    private class RawExit(val item: ExitItem, val info: android.app.ApplicationExitInfo)

    /**
     * 把一条系统退出记录渲染成文本。
     *
     * **trace 的取法与"拿不到怎么办"**（按拍板：**拿到就存、拿不到不算失败**）：
     *   `getTraceInputStream()` 可能为 **null**（老记录 / 系统没留）=> 如实写一行说明
     *   **gzip 解不开** => 先**试纯文本兜底**（兜底成了就有栈）；兜底也不成 => 如实写一行
     *   **native** => 我们**不读**它（tombstone）=> 如实写"**我们没读**"，
     *     **不许写成"系统没给"**（独立复审 P2-A）
     *   => **几种"没有"都要写出来** —— 不写的话读的人会以为"ANR 就是没有栈"
     */
    private fun renderExit(cx: Context, r: RawExit): String {
        val info = r.info
        //**两处刻意的处置**（都来自真机实测 / SDK 查证）：
        //   1**这里【不再对 native 特判】**（银狼 §6-P3 采纳 1，2026-10-15）：
        //      原来 `if (reason == CRASH_NATIVE) null` => 产线**永远传空字节**给 `decode`
        //        => 于是"产线走的路径"与"被测的路径"**不是同一条**
        //        （那正是 P2-A 的形态：用例喂字节、产线喂空 => 用例绿而产线不走那格）
        //      => 现在**所有 reason 都照读**（有界）=> 产线走的就是 `decode` 的**默认路径**
        //      而"不解析 tombstone"这条**仍然成立** —— 是 `decode` 按 reason **短路**掉它
        //        （所以 protobuf **不会被硬读成文本** => 也没有"读成乱码"的风险）
        //   2 **读取有上限**：`readBytes()` 是**无界**的 => 一个几 MB 的流会把内存吃光
        //      => 用 [readBounded]（**自己写循环**，不用 `readNBytes` ——
        //        那是 Java 9 的 API，而本项目 `minSdk=31` => 老设备上会 `NoSuchMethodError`
        //        判据同 `AGENTS.md` §8.3 第 3 步「运行环境也支持吗」）
        val trace = runCatching { info.traceInputStream?.use { readBounded(it, MAX_TRACE_BYTES) } }
            .onFailure { Log.w(TAG, "取 trace 失败 ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        //解成三态（gzip / **未压缩兜底** / 读不懂）—— 后两态**都把"为什么"写进记录**
        //   `isNative` **不在这里传**（让 `decode` 用自己的默认值 => 规则只剩一处）
        val decoded = GzipTrace.decode(trace ?: ByteArray(0), info.reason)
        val stackLines: List<String> = when (decoded) {
            is GzipTrace.Decoded.Gzipped -> decoded.lines
            is GzipTrace.Decoded.Uncompressed -> decoded.lines
            is GzipTrace.Decoded.Unreadable -> listOf(decoded.note)
        }
        // `trace` 字段只留"**这一段怎么来的**" —— 取值规则在 [GzipTrace.formatTag] 里（**纯函数 => 可测**）
        //   native 用 `native-tombstone`（= **不解析它**）而不是 `none`（= 系统没给）=> **字段与说明自洽**
        val traceFormat = GzipTrace.formatTag(decoded, info.reason)
        return CrashRecord.render(
            CrashRecord.Fields(
                atMs = info.timestamp,
                uptimeMs = 0L,
                //**这里刻意写 `-`，不是"忘填"** —— 判据：**不许做一个假声明**
                //   游标只留**上一次运行**的阶段 => 而这条退出记录**未必**来自上一次运行
                //     （ring buffer 里可能有几天前的）
                //   => 把"上一次运行的阶段"贴到"某一次更早的退出"上 => **是错的**
                //   => "上次停在哪"由 [KIND_STARTUP_INCOMPLETE] 那条**准确地说**（它讲的就是上一次）
                stage = "-",
                pid = info.pid,
                kind = CrashRecord.KIND_SYSTEM_EXIT,
                threadName = "main",
                excClass = null,
                // 系统给的那句话放 `msg=`（它含空格 => 只能放这个字段）
                //   而它**正是最有用的一句**：本机的 ANR 全带着
                //   `Waited 5000ms for FocusEvent(hasFocus=true)`
                excMessage = info.description.orEmpty(),
                appVersion = appVersionName(cx),
                androidRelease = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                device = deviceLine(),
                stackLines = stackLines,
                extra = listOf(
                    "reason" to ExitReasonName.name(info.reason),
                    "reasonCode" to info.reason.toString(),
                    "importance" to info.importance.toString(),
                    "pssKb" to (info.pss / 1024).toString(),
                    // 这一段栈是**怎么来的**（gzip / 未压缩 / 没解出来）=> 读的人一眼能判
                    "traceFormat" to traceFormat,
                    "traceBytes" to (trace?.size ?: 0).toString(),
                    "traceLines" to stackLines.size.toString(),
                ),
            )
        )
    }

    /**
     * **有界读**（最多 [MAX_TRACE_BYTES]）。
     *
     * 不用 `InputStream.readNBytes(int)`：那是 **Java 9** 的 API，
     *   而本项目 `minSdk = 31` => 老设备上会 `NoSuchMethodError`
     *   => 自己循环读（判据同 `AGENTS.md` §8.3：**"编译过了" ≠ "目标环境能跑"**）
     *
     * 如实标：超过上限时返回的是**截断后**的字节 => 记录里那个 `traceBytes`
     *   与 native 说明里的"拿到 N 字节"**都是【我们读到的量】**，不声称"系统只给了这么多"
     */
    private fun readBounded(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    //读（给界面用）

    /**
     * 界面那一行用的一句话（`null` = **没有记录 => 一个字都不显示**）。
     *
     * 这一行是**有意的**：本项目在"丢过的东西要可查"上的判据是
     *   **"界面上有一个数"**，不是"代码里能读"（`insertFails` 那行就是同一条判据）
     */
    fun summary(cx: Context): String? {
        val text = read(cx)
        if (text.isBlank()) return null
        val sp = CrashRotate.split(text)
        val partial = if (sp.truncatedTail.isEmpty()) 0 else 1
        val n = sp.records.size + partial
        if (n == 0) return null
        val kib = CrashRotate.utf8Size(text) / 1024
        return buildString {
            append("崩溃/无响应记录：$n 条（${kib} KiB）")
            if (partial > 0) append(" 含 1 条残缺（上次写到一半断了）")
        }
    }

    private fun read(cx: Context): String {
        val f = logFile(cx)
        if (!f.exists()) return ""
        return runCatching { f.readText(Charsets.UTF_8) }
            .onFailure { Log.e(TAG, "读 $FILE_NAME 失败 ${it.javaClass.simpleName}: ${it.message}") }
            .getOrElse { "" }
    }

    //写（唯一的落盘点）

    /**
     ***把一条记录并进日志文件**（轮转见 [CrashRotate.plan]）。
     *
     ***三个"不许静默"的点，都在这里兑现**（独立复审 P2-1）：
     *   1 **并发**：两个写者（崩溃处理器 / `nk-crash-io` 后台线程 —— **界面只读不写**）
     *      会**互相覆盖**
     *      => 整段「读 -> plan -> 写」塞进 [appendLock] 一把锁（[CrashWriter.append]）
     *   2 **读现有内容失败** => **带原因返回 false 并出声**，
     *      **绝不当成"空文件"往下写**—— 那会**把已攒下的现场整个抹掉**
     *   3 **写完回头核产物**（存在 + 字节数）=> rename 失败 / 字节不符**都报 false**
     *      —— 判据：**判据落在产物上，不落在"调用没抛"上**
     *
     * 写盘用 `临时文件 + rename`（同目录内 rename 是**原子**的 => 写一半崩了**不毁旧日志**）；
     *   临时名带 **pid + nanoTime** => 两个写者**不可能撞同一个 tmp**
     *
     * @return true = **产物核对通过**（调用方要靠这个值决定推不推进游标）
     */
    private fun append(cx: Context, record: String): Boolean {
        val f = logFile(cx)
        val outcome = CrashWriter.append(
            lock = appendLock,
            read = { if (f.exists()) f.readText(Charsets.UTF_8) else "" },
            write = { text -> writeAtomically(f, text) },
            record = record,
        )
        return when (outcome) {
            is CrashWriter.Outcome.Ok -> {
                Log.i(TAG, "崩溃记录已落盘 ${f.absolutePath}（${f.length()} 字节）")
                true
            }
            is CrashWriter.Outcome.Failed -> {
                Log.e(TAG, "$FILE_NAME 写入失败 ${outcome.reason}")
                false
            }
        }
    }

    /**
     * 落盘那一步（只有它碰文件系统；**产物的核对也在这儿**）。
     *
     ***它由 [CrashWriter.append] 在【锁内】调用** —— 这一点是**功能性**的，不是风格：
     *   下面那句"清残留 tmp"**必须在锁内**
     *   否则：写者 A 刚建好 `crash.log.<pid>.<nano>.tmp`，写者 B 进来一扫描 =>
     *     **把 A 正在写的 tmp 删了** => A 的 rename 失败 => **A 那条记录静默丢了**
     *   => 在锁内 => 只可能看到**早已死掉**的 tmp（唯一的活写者就是我们自己）
     */
    private fun writeAtomically(f: File, text: String): CrashWriter.Outcome {
        // 上一次"写到一半被打断"留下的 tmp => **清掉并出声**（不静默删）
        //   它已经被本次的「现有内容 + 新记录」取代 => 留着只会堆垃圾
        val stale = f.parentFile?.listFiles { t -> t.name.startsWith("$FILE_NAME.") && t.name.endsWith(".tmp") }
        if (!stale.isNullOrEmpty()) {
            stale.forEach { it.delete() }
            Log.w(TAG, "清掉 ${stale.size} 个残留 tmp（上一次写入没走完 => 这次的记录已覆盖它的内容）")
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val tmp = File(
            f.parentFile,
            CrashWriter.tmpName(FILE_NAME, Process.myPid(), System.nanoTime()),
        )
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            tmp.delete()
            return CrashWriter.Outcome.Failed("rename 失败（${tmp.name} => ${f.name}）")
        }
        //回头核产物（不看这一步 => "写不成也报"就会回来）
        CrashWriter.checkWritten(bytes.size, f.exists(), f.length())?.let {
            return CrashWriter.Outcome.Failed(it)
        }
        return CrashWriter.Outcome.Ok
    }

    private fun deviceLine(): String = "${Build.MANUFACTURER}/${Build.MODEL}"
}
