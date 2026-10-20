package com.liumiaoqi.notificationkeep.data

//2026-09-28 从 NotifStore.kt 移出来的**数据模型**（工程化重构的第一刀）
//   为什么先动这一块：它**无锁 无 prefs 无初始化顺序**
//     => 是复核者与我都判定的「最安全的先动点」（见 C:\hub\reference\dsh\
//       refactor-notifstore-audit-falsify.md 的「最安全的先动点」一节）
//   而**引用点零改动** —— 两者仍在同一个 package 里 => Kotlin 同包不需要 import
//   这次是**纯移动**：一行逻辑都没改，连注释都原样搬
//     （而「移动」这件事本身记在这儿 => 后来人看 git blame 不会困惑）

/**
 * 一条**通知事件**（也承载"操作事件"—— 见 [OpsEvents]）。
 *
 * 设计要点（2026-09-28 实测后定）：**存事件流**，不做"同 key 合并"
 *   —— 实测"撤回"在通知层有**两条路**（同 key 改写 / 旧 key 移除 + 新 key 顶上）
 *   => 每次回调存一行 => 两条都覆盖、假设最少
 *
 * **哈希链**（`selfHash` / `prevHash`）：对应"三性两力"里的**真实性** ——
 *   事后能自证**没被改过、没被删过**
 *   但**不能**证明"对方真的说过这句话"（对方可伪造 第三方可改通知）
 */
data class NotifEvent(
    val id: Long = 0,
    val pkg: String,
    val key: String,
    /** POST / REMOVED / 生命周期 / 操作事件（见 [OpsEvents]） */
    val event: String,
    val title: String,
    val text: String,
    val big: String,
    /** 通知自身的时间戳（sbn.postTime，**系统给的**）；操作事件时 = 当时时刻 */
    val posted: Long,
    /** 仅 REMOVED 有意义（`reason=8` = App 自己取消 => 撤回的信号） */
    val reason: Int,
    /** 我们抓到它的时间 */
    val captured: Long,
    /** 本条内容的链式 hash */
    val selfHash: String = "",
    /** 前一条的 selfHash（首条为 GENESIS） */
    val prevHash: String = "",
) {
    /** 是否像"撤回相关"：REMOVED 事件，或正文里出现"撤回"字样（不推断，只标记） */
    val looksLikeRecall: Boolean
        get() = event == "REMOVED" || text.contains("撤回")
}

    /** 一次清空的留痕（存在 SharedPreferences => 清空之后仍然可查） */
    data class ClearRecord(
        val at: Long,
        val count: Long,
        val tailHash: String,
        /** **被清掉的那一段链**的身份（见 [NotifChain]）—— 不是清空后新段的 */
        val chainId: Long = NotifChain.NONE,
        val epoch: Int = 0,
    )
