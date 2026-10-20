package com.example.myapplication

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.myapplication.data.NotifEvent
import com.example.myapplication.data.NotifGap
import com.example.myapplication.data.NotifStore

/**
 * 通知监听：**事件流存档**（含"监听生命周期"本身）
 *
 * 可观测设计：日志统一 tag = `NK`（`adb logcat -s NK:V` 一眼看全）
 *
 * 存档策略：**存事件流**，不做"同 key 合并"
 *   —— 实测"撤回"在通知层有**两条路**（同 key 改写 / 旧 key 移除 + 新 key 顶上，
 *      微信与 QQ 走的都是后者）=> 每次回调存一行 => 两条都覆盖、假设最少
 *
 ***生命周期入库**（断档显式化，见
 *   `.agents/notes/implemented/architecture/2026-09-28-offline-attestation-boundary.md`）：
 *   那个 note **随实现从 `proposed/` 移到了 `implemented/`** => 本行要跟着改
 *      （2026-09-28 就漏过一次：note 移了、这里还指着旧路径 ——
 *        是新加的文档巡检 `tools/check_docs.py` 第一次跑就抓到的）
 *   `LISTENER_CONNECTED` / `LISTENER_DISCONNECTED` 也存 =>
 *   "我不知道的那段时间"会留下痕迹（从"无法证明没漏"变成"漏了它会自己承认"）
 *
 ***不在 `onCreate` 里碰数据库**（2026-09-28 修正）：
 *   `onCreate` 处在**系统绑定服务的关键路径**上 => 在那里建库/写库有卡住绑定、
 *   甚至让"通知使用权"开关弹回的风险
 *   **2026-09-28 更正（A3）**：上面原来还挂着一条理由 ——
 *   「`SERVICE_CREATE` 对**断档判断没有用**（断档只关心"连上/断开"）」
 *   => **那条理由是错的，已删**：`SERVICE_CREATE` 恰恰是**唯一**能证明
 *     "上一个实例已经死了、这中间没在听"的证据（SIGKILL 时 `onDestroy` **不执行**）
 *     实测（真机库 3621 行）：`LISTENER_CONNECTED` **12 次**、`LISTENER_DISCONNECTED` **0 次**
 *     => **11 段断档在数据里完全隐形**（最长一段 **43074 秒 ≈ 12 小时**）
 *   **但上面那条风险仍然成立** => 所以**写入端依旧不在 `onCreate` 里写库**
 *     （判据：**两条理由要分开判** —— 一条错，不代表另一条也错）
 *   => 那段时间改由**算法端**从"两个 `CONNECTED` 之间"推出来
 *     （见 `data/NotifGap.kt` 的 `certainGaps` 与 `RESTART_MIN_MILLIS`）
 *   => 库依旧是**懒加载**（第一次真要写时才建）
 *
 * 本类不申请任何额外权限、不联网：
 *   只收系统分发给本 App 的通知（需用户在系统设置里授予"通知使用权"）
 */
class NotifListener : NotificationListenerService() {

    companion object {
        /** 全项目统一日志 tag —— Logcat 里只看这一个就够 */
        const val TAG = "NK"
    }

    /**
     * **懒加载**：第一次真正要写库时才创建
     * （=> 建库发生在 `onListenerConnected` 之后，**不占用系统绑定的关键路径**）
     */
    private val store: NotifStore by lazy { NotifStore(this) }

    override fun onCreate() {
        super.onCreate()
        // 这里【不碰数据库】—— 只打日志（理由见类注释）
        Log.i(TAG, "SERVICE_CREATE 监听服务已创建（未碰数据库）")
    }

    override fun onDestroy() {
        Log.w(TAG, "SERVICE_DESTROY 监听服务被销毁（系统回收或版本更新）")
        // 尽力记一条；进程被 SIGKILL 时这里**根本不会执行**
        //    => 所以"断档"不能只靠它 —— 见 NotifGap 的"疑似断档"那一手
        saveLifecycle(NotifGap.SERVICE_DESTROY)
        super.onDestroy()
    }

    /** 授权成功 / 服务被系统接上（这才是"真的开始听得到"的时刻） */
    override fun onListenerConnected() {
        Log.i(TAG, "LISTENER_CONNECTED 通知使用权已授予 => 开始监听")
        saveLifecycle(NotifGap.CONNECTED)
    }

    /** 授权被取消、或被系统停掉（这个最需要知道，否则会以为"坏了"） */
    override fun onListenerDisconnected() {
        Log.w(TAG, "LISTENER_DISCONNECTED 监听已断开（授权被关/被系统停）")
        saveLifecycle(NotifGap.DISCONNECTED)
    }

    /** 收到一条通知（同 key 再次进来 = 同一条被改写的第一种机制） */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n: Notification = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        Log.i(
            TAG,
            "POST pkg=${sbn.packageName} key=${sbn.key} posted=${sbn.postTime} " +
                "title=[$title] text=[$text] big=[$bigText]"
        )
        save(sbn, "POST", title, text, bigText, 0)

        NotificationCompatMessages.extract(ex).forEachIndexed { i, m ->
            Log.i(TAG, "  MSG[$i] ${m.first} -> ${m.second}")
        }
    }

    /**
     * 通知被移除（微信/QQ 撤回走的就是这条：旧 key 被移走）
     *
     * 2026-09-27 实测：两参数版本（`(sbn, reason)`）**在新 SDK 里不存在**
     * 编译器给出的候选：单参数 / `(sbn, RankingMap)` / `(sbn, RankingMap, reason)` => 取最全的
     *
     * `reason=8`（`REASON_APP_CANCEL`）= **App 自己取消了这条** => 撤回的系统层信号
     *   （语义来自本机 SDK 源码注释，见 `data/NotifReason.kt`）
     */
    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        Log.i(TAG, "REMOVED pkg=${sbn.packageName} key=${sbn.key} reason=$reason")
        save(sbn, "REMOVED", "", "", "", reason)
    }

    /** 落库（失败只记日志，**绝不让它把监听拖崩**） */
    private fun save(
        sbn: StatusBarNotification,
        event: String,
        title: String,
        text: String,
        big: String,
        reason: Int,
    ) {
        write(
            NotifEvent(
                pkg = sbn.packageName,
                key = sbn.key,
                event = event,
                title = title,
                text = text,
                big = big,
                posted = sbn.postTime,
                reason = reason,
                captured = System.currentTimeMillis(),
            ),
            event
        )
    }

    /**
     *把**监听生命周期**当成事件存下来 —— 断档显式化的关键一步
     *
     * 为什么必须存：若不存，"服务没在跑"的那段时间在记录里**和"没有通知"完全一样**
     *   => 存下来之后，导出里就能列出"我承认我不知道的时段"
     */
    private fun saveLifecycle(event: String) {
        val now = System.currentTimeMillis()
        write(
            NotifEvent(
                pkg = packageName,
                key = event,
                event = event,
                title = "",
                text = "",
                big = "",
                posted = now,
                reason = 0,
                captured = now,
            ),
            event
        )
    }

    private fun write(e: NotifEvent, event: String) {
        try {
            val id = store.insert(e)
            //2026-09-28 修（A5）：**按返回值分流，不再无条件打**
            //    改前：`SQLiteDatabase.insert` 出错返回 **-1**，而这里照样打 ``
            //    => 日志里会出现 `DB POST id=-1 pkg=…` => **静默丢记录**
            //    => 现在：`id < 0` 记一次失败并打 ``；计数可在界面上查到
            //    （判据：「**信号 ≠ 事实**」—— 日志是信号，插入成功才是事实）
            if (id < 0) {
                NotifStore.noteInsertFailed()
                Log.w(TAG, "DB $event **写入失败** id=$id pkg=${e.pkg}")
            } else {
                Log.i(TAG, "DB $event id=$id pkg=${e.pkg}")
            }
        } catch (t: Throwable) {
            // 这里绝不能抛出去：一条写入失败不该让整个监听停摆
            //2026-09-28 修（P7 来自独立复核）：**抛异常这一路也要记数**
            //    改前只有 `id < 0` 那一路调 `noteInsertFailed()` =>
            //    而"写库时抛出来"（磁盘满 / 库损坏 / 约束冲突）**同样是一条记录没进去**
            //    => 界面那个"本次运行有 N 条写入失败"会**少报**
            //      —— 而 A5 的判据正是「**界面上有一个数**」
            NotifStore.noteInsertFailed()
            Log.e(TAG, "DB 写入失败 ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}

/**
 * 从通知 extras 里取 `MessagingStyle` 的历史消息。
 *
 * 单独抽出来，是因为这一步**最容易踩坑**，也最需要单独调试：
 *   不同 App（微信/QQ/短信）给的内容不一样，有的只给一条摘要，有的给完整历史。
 *   => 这里**不猜**：拿不到就返回空列表，日志里能看出来
 */
object NotificationCompatMessages {

    fun extract(extras: android.os.Bundle): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        @Suppress("DEPRECATION")
        val raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return out
        for (p in raw) {
            val b = p as? android.os.Bundle ?: continue
            val who = b.getCharSequence("sender")?.toString().orEmpty()
            val what = b.getCharSequence("text")?.toString().orEmpty()
            if (who.isNotEmpty() || what.isNotEmpty()) out.add(who to what)
        }
        return out
    }
}
