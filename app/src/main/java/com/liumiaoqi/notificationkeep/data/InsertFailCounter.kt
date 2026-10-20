package com.liumiaoqi.notificationkeep.data

/**
 ***写入失败的进程级计数器**（2026-09-28 抽出来）
 *
 ***先撤回一条错误的说法**（2026-09-28 更正 由独立复核者推翻）：
 *   本条初稿写着「原来那个计数器是 `NotifStore` 的**实例字段** =>
 *     监听服务记的、界面读不到 => **UI 永远是 0**」
 *   => **那是错的** —— 依据：`git show 8a1b823` 的 diff **上下文**显示
 *     它当时就在 `companion object` 内（上下文正是 `K_CHAIN_ID` / `K_EPOCH`，
 *     而 `companion object {` 在它上面几行）=> **它本来就在进程级共享**
 *   =>**判据（值得记）**：**"静态还是实例"要看【它在哪个块里】，不看修饰词**
 *     `private var` 这个词**两种都有**；我按词面判断 => **错了**
 *   发现者：独立复核者 `audit-falsify`（它用 `git show` 查了那一版的上下文）
 *
 ***那这次改动【为什么仍然值得】—— 真实收益（不是"修了一个读不到的缺陷"）**：
 *   1 **可测**：原来它嵌在 `NotifStore`（继承 `SQLiteOpenHelper`）里 =>
 *     **JVM 里造不出来** => 只能靠端到端看
 *     => 现在是一个独立 `object` => **纯 JVM 可测**（`InsertFailCounterTest` 3 条）
 *   2 **`@Volatile`**：原来**没有** —— 而写在监听服务的线程、读在界面线程
 *     => 跨线程可见性**没有保证** => 现在有了
 *   3 **意图显式**："进程级"现在**写在类型上**（一个 `object`）
 *     而不是"要往上读几行才知道它在 companion 里"
 *
 * **为什么是 `object`**：语义就是"**本次运行**丢了几条" => **进程一份**
 *
 * **它在内存里**（进程重启归零）—— 那**是有意的**：
 *   它回答"**本次运行**"，不是"历史上" => 所以不该落库
 *
 * **它不是原子的**：`fails++` 在**单写者**下够用（写者只有监听服务）
 *   => 若将来有第二个写者 => 该换 `AtomicInteger`
 */
object InsertFailCounter {

    @Volatile
    private var fails: Int = 0

    /** 记一次写入失败（由 `NotifListener.write()` 调用） */
    fun note() {
        fails++
    }

    /** **本次运行**丢了几条（0 = 没丢过） */
    fun count(): Int = fails

    /** 只在测试里用（让每条测试从 0 开始，免得互相影响） */
    internal fun resetForTest() {
        fails = 0
    }
}
