package com.liumiaoqi.notificationkeep.data

/**
 * **链的身份**：让【不同段的链】不会假装成同一条 —— 对应"三性两力"里的**真实性**
 *
 * 要解决的问题：
 *   链从 `GENESIS` 重新开始时（**清空** / **重装** / **升级重建库**），
 *   新旧两段**各自都是完整的**，但**看不出是两段** =>
 *   把它们放在一起看，**像是"一条连续不断的链"** —— 这就是"假装连续"
 *
 * 解法（两个字段，各管一件事）：
 *   **`chainId`** = 这段链**开始那一刻**的时间戳 => **它是"段的身份"**
 *   **`epoch`** = 这是第几段 => 给人看的序号
 *
 ***判据（关键）**：判断两段记录是否属于同一条链，**要比 `chainId`，不能比 `epoch`** ——
 *   因为 **重装之后 `epoch` 会从 1 重新数** => 用 epoch 比会把"新一段"误判成"老一段"
 *   （`sameChain()` 就是为这条判据准备的）
 */
object NotifChain {

    /** 还没开始时用 0（与 `HashChain.GENESIS` 同族：把"什么都没有"显式写出来） */
    const val NONE = 0L

    /** 一段链的身份 */
    data class Id(val chainId: Long = NONE, val epoch: Int = 0) {
        /** 这段链是否已经开始过 */
        val started: Boolean get() = chainId != NONE
    }

    /**
     * **开新的一段链**：`chainId` = 开始时刻，`epoch` = 上一段 + 1
     *
     * 用"时刻"当 chainId 的前提：**同一毫秒内不会开两段链**
     *   （开段的动作只有"清空"和"库里还没有任何链"两种 => 人工触发 => 不可能同毫秒）
     */
    fun next(prev: Id, nowMillis: Long): Id =
        Id(chainId = nowMillis, epoch = prev.epoch + 1)

    /**
     ***判据**：两段记录是不是同一条链。
     *
     * `NONE` 与任何值都**不算同一条** —— **空值不能冒充身份**
     */
    fun sameChain(a: Long, b: Long): Boolean = a != NONE && a == b

    /** 给人看的说法（界面与导出用） */
    fun describe(id: Id): String =
        if (!id.started) "（这段链还没开始）"
        else "第 ${id.epoch} 段 始于 ${id.chainId}"
}
