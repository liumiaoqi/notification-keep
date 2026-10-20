package com.example.myapplication.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最朴素的持久化：SQLiteOpenHelper（零第三方依赖 —— 刻意不上 Room，避免版本坑）。
 *
 * 数据库落在**应用私有目录** => 其他 App 读不到（沙箱）
 * 本 App **没有任何网络权限** => 数据物理上无法外传
 * **只有一个 exported 的 Service，且被 BIND_NOTIFICATION_LISTENER_SERVICE 收口**
 */
class NotifStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "notif.db"
        const val DB_VERSION = 2
        const val TABLE = "events"

        /** 清空留痕的存放处（SharedPreferences => **不会被 clearAll 删掉**） */
        const val PREF = "nk_audit"
        private const val K_AT = "clear_at"
        private const val K_COUNT = "clear_count"
        private const val K_TAIL = "clear_tail"
        /** 被清掉的那一段链的身份（清空【前】它就属于谁 —— 见 NotifChain） */
        private const val K_CLEARED_CHAIN = "cleared_chain_id"
        private const val K_CLEARED_EPOCH = "cleared_epoch"
        /** **当前**这一段链的身份 */
        private const val K_CHAIN_ID = "chain_id"
        private const val K_EPOCH = "epoch"

        //2026-09-28 加（A5）：**写入失败计数** —— 让"丢过几条"可查
        //   2026-09-28 抽成一个独立的 `InsertFailCounter`（`object`）
        //
        //   **先撤回一条错误的说法**（初稿写过，被独立复核者推翻）：
        //      初稿说「原来它是**实例字段** => 监听服务记的、界面读不到」
        //      => **那是错的** —— 依据：`git show 8a1b823` 的 diff **上下文**
        //        显示它当时就在 `companion object` 内（上下文正是 `K_CHAIN_ID` / `K_EPOCH`）
        //      =>判据：**"静态还是实例"要看【它在哪个块里】，不看修饰词**
        //
        //    **那这次抽出来为什么仍然值得**（真实收益）：
        //      1 **可测** —— 原来它嵌在本类（继承 `SQLiteOpenHelper`）里 => JVM 造不出来
        //      2 **`@Volatile`** —— 原来**没有**，而写在监听线程、读在界面线程
        //      3 **意图显式** —— "进程级"现在写在类型上，不靠读缩进

        /** 记一次写入失败（由 `NotifListener.write()` 调用） */
        fun noteInsertFailed() {
            InsertFailCounter.note()
        }

        /**
         * **本次运行**丢了几条（0 = 没丢过）
         *
         * 改前读的是**本实例**的字段 => 界面看到的永远是 0
         */
        fun insertFailCount(): Int = InsertFailCounter.count()
    }

    /** 给 SharedPreferences 与版本信息用（applicationContext，避免持有 Activity） */
    private val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                pkg       TEXT NOT NULL,
                key       TEXT NOT NULL,
                event     TEXT NOT NULL,
                title     TEXT,
                text      TEXT,
                big       TEXT,
                posted    INTEGER,
                reason    INTEGER,
                captured  INTEGER,
                self_hash TEXT,
                prev_hash TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_captured ON $TABLE(captured DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 的记录没有链 => 让它们带"假的"链比清空更糟 => 升级即重建
        //
        //2026-09-28 修（A2）：**重建库也要开新的一段链**
        //    改前只有下面两行 => `chainId` / `epoch` **原封不动**
        //    => 于是"3621 条 -> 3 条"这种跳变，在导出里被读成**同一条链的延续**
        //       （而导出文件里明明写着"判断两批记录是否同一条链 => 比 chainId"）
        //    而 `NotifChain` 的文档明写它要覆盖「清空 / 重装 / **升级重建库**」这一种
        //       —— **声明了，但升级这条路一条都没做**
        //
        //   这里有两条硬约束（都不是猜的，是这段代码的位置决定的）：
        //      1 **不能调 `logOp()`** —— 它要写库，而库**正在重建中** =>
        //         会递归进 `writableDatabase` => 死锁/异常
        //      2 **不能调 `countAll()`** —— 表**马上要 DROP** => 只能在 DROP **之前**数
        //    => 所以这一段只做三件：**数一下** **读旧身份** **写新身份 + 留痕**（全走 prefs）
        //
        //    已知的未做（如实标）：它复用清空的留痕 key =>
        //       界面上的「上次清空」会把"升级重建"也读成清空
        //       => 那需要给 `ClearRecord` 加一个"这是重建还是清空"的字段（另一次改动）
        val n = runCatching {
            db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }.getOrDefault(0L)
        val tail = runCatching {
            db.rawQuery("SELECT self_hash FROM $TABLE ORDER BY id DESC LIMIT 1", null).use { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() else ""
            }
        }.getOrDefault("")
        val old = chainIdentity()
        val now = System.currentTimeMillis()
        val fresh = NotifChain.next(old, now)
        //2026-09-28 修（P6 来自独立复核）：**这里必须 `commit()`，不能用 `apply()`**
        //    为什么：`apply()` 是**异步**的（先改内存、后台落盘）=>
        //    而它**下一行就是 `DROP TABLE`** —— 一旦这次 flush 丢失（硬杀 / 断电），
        //    结果是：**新库已经建好，而 `chainId` 还是旧的**
        //      => 那正是 A2 要治的病（两段链共用同一个身份）**原样复发**
        //      => 而且"被删掉那一段"的条数与链尾**永久丢失**（留痕也没落盘）
        //    => `commit()` 是**同步**的：它返回时已经写进磁盘（升级只发生一次，代价可忽略）
        //    而 **返回 false 就抛** —— 判据：
        //      **宁可建库失败，也不要建出一个"假装同一条链"的库**
        //      （这里不能记 `logOp`：库正在重建 => 会递归进 `writableDatabase`）
        check(
            prefs.edit()
                .putLong(K_AT, now).putLong(K_COUNT, n).putString(K_TAIL, tail)
                .putLong(K_CLEARED_CHAIN, old.chainId).putInt(K_CLEARED_EPOCH, old.epoch)
                .putLong(K_CHAIN_ID, fresh.chainId).putInt(K_EPOCH, fresh.epoch)
                .commit()
        ) { "升级重建：链身份没能落盘 => 拒绝建库（否则两段链会共用一个身份）" }
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /**
     * **链的身份**（这一段链是谁）—— 见 [NotifChain]
     *
     *为什么需要它：链从 `GENESIS` 重来时（清空 / 重装 / 升级重建），
     *   新旧两段**各自完整但看不出是两段** => 有了 `chainId` 才**不会假装连续**
     */
    fun chainIdentity(): NotifChain.Id =
        NotifChain.Id(chainId = prefs.getLong(K_CHAIN_ID, NotifChain.NONE), epoch = prefs.getInt(K_EPOCH, 0))

    /** 第一次真的要写记录之前，把第一段链开出来 */
    @Synchronized
    private fun ensureChain() {
        if (chainIdentity().chainId == NotifChain.NONE) {
            val now = System.currentTimeMillis()
            prefs.edit().putLong(K_CHAIN_ID, now).putInt(K_EPOCH, 1).apply()
        }
    }

    /**
     * 写一条事件；返回行 id（-1 = 失败）。
     * **synchronized**：算链要"读上一条 hash + 写入"，两步必须原子
     */
    @Synchronized
    fun insert(e: NotifEvent): Long {
        ensureChain()                     // 第一段链在这一刻开始（写 prefs，不在事务里）
        val db = writableDatabase
        //2026-09-28 加（H13 来自独立复核）：**读上一条 + 写这一条必须原子**
        //   原来只靠 `@Synchronized` —— 而那是**实例锁**
        //     而本类有**两个实例**（`MainActivity` 与 `NotifListener` 各一个）
        //     => **两个写者可以交错** => 两条记录的 `prevHash` 指向同一条（链分叉）
        //   => **SQLite 的事务是【数据库级】的** => 它能跨实例互斥
        //     即：这一处同时补上了 H12 的一半
        //   判据：加固【不改语义】 => 所以仍然无条件 `setTransactionSuccessful()`
        //     （即使 `insert` 返回 -1 => 提交一个空事务，与改前等价）
        db.beginTransaction()
        try {
            val prev = latestHash(db)
            val self = HashChain.link(prev, HashChain.payloadOf(e))
            val v = ContentValues().apply {
                put("pkg", e.pkg); put("key", e.key); put("event", e.event)
                put("title", e.title); put("text", e.text); put("big", e.big)
                put("posted", e.posted); put("reason", e.reason); put("captured", e.captured)
                put("self_hash", self); put("prev_hash", prev)
            }
            val id = db.insert(TABLE, null, v)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    /**
     ***操作留痕**（对应"三性两力"的**合法性**）：
     * 记录"我做了什么"（启动 / 导出 / 清空），而不是"我收到了什么"
     */
    @Synchronized
    fun logOp(event: String, note: String = ""): Long {
        val now = System.currentTimeMillis()
        return insert(
            NotifEvent(
                pkg = appContext.packageName, key = event, event = event,
                title = "", text = note, big = "", posted = now, reason = 0, captured = now,
            )
        )
    }

    /** 链上最后一条的 hash（没有则 GENESIS） */
    /**
     * 链上最后一条的 hash（没有则 GENESIS）
     *
     * 这个重载**接受调用方的句柄** —— 事务必须在**同一个 db 句柄**上开
     *   => 事务里读用带 `db` 的那版，事务外用无参那版
     *   （2026-09-28 加 H13 来自独立复核）
     */
    private fun latestHash(db: android.database.sqlite.SQLiteDatabase): String {
        db.rawQuery(
            "SELECT self_hash FROM $TABLE ORDER BY id DESC LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) return c.getString(0) ?: HashChain.GENESIS }
        return HashChain.GENESIS
    }

    /** 链上最后一条的 hash（没有则 GENESIS）—— 事务外的调用点用这个 */
    private fun latestHash(): String = latestHash(readableDatabase)

    private val COLS =
        "id,pkg,key,event,title,text,big,posted,reason,captured,self_hash,prev_hash"

    /**
     ***按【列名】取值，不再按位置下标**（2026-09-28 修 H4 来自独立复核）
     *
     ***改前是什么样、为什么会坏**（复核者的原话，我核对过）：
     *   改前 `row()` 用的是**位置下标 0..11**（`c.getLong(0)` `c.getString(3)` …）
     *   而那个顺序必须与上面 `COLS` 的字符串**逐项一致**
     *   =>而**两处都是普通字符串** => **任一处被"顺手"重排 => 编译不报错**
     *     => **字段静默错位**（例如 `posted` 与 `captured` 对调）
     *     =>**更远**：那会改 `HashChain.payloadOf` 的输入
     *       => **整条链与核对器全体对不上**（而那是本工具最不能出的错）
     *
     * **为什么"按名字"就够**：
     *   `getColumnIndexOrThrow` 按**列名**去查 => **与 `COLS` 的书写顺序无关**
     *   => 顺序被重排也照样取对 而**名字打错会当场抛**
     *     =>即：把一类**静默错**变成**响亮失败** —— 正是本仓那条原则
     *
     * **如实说清这条改动的收益是【将来】的**（现在看不出差别）：
     *   今天 `COLS` 与下标是**一致的**（复核者逐项核对过）=> 行为**不变**
     *   它买的是"**下次有人重排时不会静默**" => 所以它是**加固**，不是修缺陷
     *   而它**测不了**：`Cursor` 是 Android 类 => JVM 单测里造不出来
     *     => 验证靠**真跑**（装包 + 导出 + 界面正常）
     */
    private fun row(c: android.database.Cursor) = NotifEvent(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        pkg = c.getString(c.getColumnIndexOrThrow("pkg")),
        key = c.getString(c.getColumnIndexOrThrow("key")),
        event = c.getString(c.getColumnIndexOrThrow("event")),
        title = c.getString(c.getColumnIndexOrThrow("title")).orEmpty(),
        text = c.getString(c.getColumnIndexOrThrow("text")).orEmpty(),
        big = c.getString(c.getColumnIndexOrThrow("big")).orEmpty(),
        posted = c.getLong(c.getColumnIndexOrThrow("posted")),
        reason = c.getInt(c.getColumnIndexOrThrow("reason")),
        captured = c.getLong(c.getColumnIndexOrThrow("captured")),
        selfHash = c.getString(c.getColumnIndexOrThrow("self_hash")).orEmpty(),
        prevHash = c.getString(c.getColumnIndexOrThrow("prev_hash")).orEmpty(),
    )

    /** 最近 N 条（**展示**用：倒序） */
    fun queryRecent(limit: Int = 1000): List<NotifEvent> {
        val out = ArrayList<NotifEvent>()
        readableDatabase.rawQuery(
            "SELECT $COLS FROM $TABLE ORDER BY captured DESC LIMIT ?", arrayOf(limit.toString())
        ).use { c -> while (c.moveToNext()) out.add(row(c)) }
        return out
    }

    /** 全部记录，**按 id 升序**（= 链的顺序，校验与导出都用它） */
    fun queryAll(): List<NotifEvent> {
        val out = ArrayList<NotifEvent>()
        readableDatabase.rawQuery("SELECT $COLS FROM $TABLE ORDER BY id ASC", null)
            .use { c -> while (c.moveToNext()) out.add(row(c)) }
        return out
    }

    /** 链校验：null = 完整；否则 = 断在那条记录的 id */
    fun verifyChain(): Long? = HashChain.firstBroken(queryAll())

    fun countAll(): Long = scalar("SELECT COUNT(*) FROM $TABLE")

    /**
     * 同上，但接受调用方的 db 句柄（事务里用）
     *
     * 为什么需要它：`clearAllWithRecord` 要在**事务内**数条数
     *   => 用 `readableDatabase` 会在**另一个句柄**上读
     *     （同一个 helper 通常给同一个连接，但**不该依赖那个实现细节**）
     */
    fun countAll(db: android.database.sqlite.SQLiteDatabase): Long =
        scalar(db, "SELECT COUNT(*) FROM $TABLE")

    /**
     * **疑似撤回**（2026-09-28 收紧判据）：
     *   1 `REMOVED` 且 `reason=8`（`REASON_APP_CANCEL` = App 自己取消了这条）
     *   2 正文里出现"撤回"字样
     * 旧判据 `event='REMOVED' OR text LIKE '%撤回%'` **太宽**
     *   （用户划掉 / 超时 / 系统通知被移除都会算 => 数字虚高）
     */
    fun countRecallLike(): Long = scalar(
        "SELECT COUNT(*) FROM $TABLE WHERE (event='REMOVED' AND reason=8) OR text LIKE '%撤回%'"
    )

    fun countToday(): Long =
        scalar("SELECT COUNT(*) FROM $TABLE WHERE captured >= ${System.currentTimeMillis() - 24L * 3600 * 1000}")

    /** **最近一条的抓取时间**（最实用的健康指示：一眼看出还在不在收） */
    //2026-09-28 修（A7）：**只算真通知**（`event IN ('POST','REMOVED')`）
    //    改前不分事件类型 —— 而 App 自己写的 `APP_START` / `LISTENER_*` / `EXPORT`
    //    也在同一张表里 => `MAX(captured)` 常常指向**我们自己刚写的那一行**
    //    => 后果（实测）：每次打开 App 的瞬间它都显示「刚刚」；
    //      更糟的是 **监听刚断开那一刻**（`onListenerDisconnected` 也写一行）
    //      它**照样显示「刚刚」**—— 那个"最实用的健康指示"在**最需要报警时给绿灯**
    //    （真机库实测：11 个 `APP_START` **11/11** 在自己写下的那一瞬间就是全表 MAX）
    fun latestCaptured(): Long = scalar(
        "SELECT IFNULL(MAX(captured), 0) FROM $TABLE WHERE event IN ('POST', 'REMOVED')"
    )

    //清空 + 留痕

    /**
     ***清空 + 留痕**（对应"三性两力"的**合法性**）。
     *
     * 难点：清空**会把自己也删掉** => 所以**先**把关键信息写进
     *   `SharedPreferences`（**不会被 clearAll 删掉**），**再**清库
     * => 效果：清空之后 App 仍能回答"**上次清空于何时、清掉多少条、当时链尾 hash 是什么**"
     */
    @Synchronized
    fun clearAllWithRecord(): ClearRecord {
        //2026-09-28 加（H13 来自独立复核）：**整个清空流程包一个事务**
        //   它原来是五步：`countAll`（读）-> `latestHash`（读）-> `prefs.commit`（写 prefs）
        //     -> `delete`（写 db）-> `return`
        //   => 而 `prefs` 与 db 是**两个存储** => 事务只能包 db 那部分（那是 H14）
        //     而「先写 prefs、再清库」那句顺序**必须保持** => 所以事务包住整个函数体
        //   => 效果：两个读与那个 delete 之间**不会被插进一条** `insert`
        //     （改前被插 => 那条记录**既不在这段链里、也不在留痕里** 静默丢证据）
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val n = countAll()
            val tail = latestHash()
            // 记下"被清掉的那一段链"是谁 => 同一段身份的条数/链尾 hash 才对得上
            val old = chainIdentity()
            val fresh = NotifChain.next(old, now)          // 开新一段（epoch 递增）
            //2026-09-28 修（P6 同族，复核只提了 `onUpgrade` 那处）：
            //    这里的注释一直写着"**先**把关键信息写进 prefs、**再**清库"
            //    而 `apply()` 是**异步**的 => **那个"先"并不成立**
            //    => 若 flush 丢失（硬杀 / 断电）：库**已经清空**，而留痕与新身份**都没落盘**
            //      => 用户看到"清空过"，但**查不到清空留痕**；且链身份仍是旧的
            //    => 改成 `commit()`（同步）=> "先写 prefs、再清库"这句话**才真的成立**
            check(
                prefs.edit()
                    .putLong(K_AT, now).putLong(K_COUNT, n).putString(K_TAIL, tail)
                    .putLong(K_CLEARED_CHAIN, old.chainId).putInt(K_CLEARED_EPOCH, old.epoch)
                    .putLong(K_CHAIN_ID, fresh.chainId).putInt(K_EPOCH, fresh.epoch)
                    .commit()
            ) { "清空：留痕与新链身份没能落盘 => 拒绝清库（否则清了却查不到留痕）" }
            writableDatabase.delete(TABLE, null, null)
            // 清空之后记一条 => 它是【新链】的第一条（prev = GENESIS）
            logOp(OpsEvents.CLEAR, "cleared=$n tailHash=${tail.take(16)}… oldChain=${old.chainId}")
            //2026-09-28 修（真点一次才暴露）：**两行原来是反的**
            //   原来：`return ClearRecord(...)` **在** `setTransactionSuccessful()` 之前
            //     => `return` 先跑 => 标成功永远不执行 => `endTransaction()` 回滚
            //     =>**`delete` 被回滚（行一条没删）而 `prefs.commit()` 成功**
            //       —— 因为 prefs **不在事务里** => 两处状态不一致（链段换了而数据还在）
            //   修法：**`setTransactionSuccessful()` 必须在每一个 return/throw 路径之前**
            //   而它证明了"必须真跑"：编译过 82 条单测全绿
            //     而**只在真点一次清空时暴露**（单测不碰这个函数 —— 它要 `Context`）
            db.setTransactionSuccessful()
            return ClearRecord(now, n, tail, old.chainId, old.epoch)
        } finally {
            db.endTransaction()
        }
    }

    /** 上次清空的留痕（没有则 null） */
    fun lastClear(): ClearRecord? {
        if (!prefs.contains(K_AT)) return null
        return ClearRecord(
            at = prefs.getLong(K_AT, 0),
            count = prefs.getLong(K_COUNT, 0),
            tailHash = prefs.getString(K_TAIL, "").orEmpty(),
            chainId = prefs.getLong(K_CLEARED_CHAIN, NotifChain.NONE),
            epoch = prefs.getInt(K_CLEARED_EPOCH, 0),
        )
    }

    /**
     * **导出 = 证据包**（对应"三性两力"里的**证据能力 证明力 关联性**）
     *
     * 每一项都对应一个"证据"用途：
     *   条数 / 链首尾 / 链是否完整 / 断点 id      -> **真实性**
     *   **断档清单**（含 suspected 标记）        -> **真实性**（承认"我不知道的时段"）
     *   **操作留痕**（含 `lastClear`）           -> **合法性**
     *   App 版本 + 系统 build                   -> **关联性**
     *   **字段释义 legend**                   -> **证明力**（读文件的人不一定懂 `reason=8`）
     *   **导出文件自身的 hash**（最后一行）    -> **证据能力**
     *   **两条局限声明**                     -> 不夸大（**写在文件里**，因为文件会交出去）
     */
    /**
     * 导出第一步（**同步，不签名**）的产物：定型好的输入 + 本次自身 hash。
     *
     * 2026-09-30（方案 C）：导出拆成两步 —— 因为签名要在 `BiometricPrompt` 的
     *   认证回调里做（异步 + 要 Activity），而"取数据 / 渲 body / 算自身 hash"不该跟着变异步
     */
    class Prepared internal constructor(
        internal val inputs: ExportInputs,
        internal val selfHash: String,
    )

    fun prepareExport(): Prepared {
        //2026-09-28（大改第十一步 形状）：**先把输入定型**
        //   这一步**一行渲染代码都不动** => 判据是"产物逐字节不变"（最硬）
        //   而 `appVersion` 在下面才用到 => 这里只是把取值**挪到同一个地方**
        val all = queryAll()
        val broken = HashChain.firstBroken(all)
        val cid = chainIdentity()
        val lc = lastClear()
        val appVersion = appVersionName(appContext)
        val now = System.currentTimeMillis()   // 两处用它（元信息一次、断档基准一次）
        //2026-09-28（大改第十一步 形状 2）：**把输入打成一个具名结构**
        //   上一轮只定义了它 => 那还是**死代码** => 这里让它真的被用
        //   而为了仍是小步，只让一处渲染走它：
        //     `metaRangeLine(all, now)` => `metaRangeLine(inputs.all, inputs.now)`
        //     其余照旧 => 判据仍是"产物逐字节不变"（最硬）
        //   而顺序要紧：`appVersion` / `now` 必须在**它之前**取
        //     （第一版我插反了 => 两个 Unresolved reference => 编译器当场指出）
        val inputs = ExportInputs(
            all = all,
            broken = broken,
            chainId = cid.chainId,
            epoch = cid.epoch,
            lastClear = lc,
            appVersion = appVersion,
            deviceManufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            buildFingerprint = Build.FINGERPRINT,
            // 2026-09-28：这一句【本该一直在这里】—— 它有副作用（懒生成密钥）
            //   而搬家时它跟着函数体进了 `render` => 测试一跑就露：
            //     `Method w in android.util.Log not mocked` （渲染里不许碰环境）
            signingLevel = ExportSigner.currentLevelName() ?: "unavailable",
            // 2026-09-28：上面四个从 `render` 里【搬到这里】—— 它们是环境依赖
            //   而 `Build.MANUFACTURER` 在 JVM 单测里是 `null` => 原来那个"纯函数"其实不纯
            //   是 `RenderContractTest` 把它逼出来的（那个测试是搬家后才可能写的）
            now = now,
        )
        //      而它之前那几个 val = "取输入" 的全部（碰 DB / prefs / Context）
        //2026-09-28（大改第十三步）：**签名搬回调用方**
        //   它原来跟着函数体进了 `render` => 而那让"纯函数"不纯、且测试会建密钥
        //   而 `selfHash` 依赖渲染的中间产物 => 所以先拿 body、再算它与签名
        val body = renderBody(inputs)
        return Prepared(inputs, OpsEvents.selfHashOf(body.trimEnd('\n')))
    }

    /**
     * 末行里"签名那一半"（或"没签成"那一半）的 JSON 片段。
     *
     * 2026-09-30（方案 C）从 [prepareExport] 里**提出来**了：签名现在发生在
     * `BiometricPrompt` 的认证回调里，所以"拿签名结果 -> 拼末行"必须能**单独调**
     */
    fun signingTailOf(r: ExportSigner.Result): String = when (r) {
        is ExportSigner.Result.Signed -> buildString {
            append(""","signature":"${r.signatureB64}"""")
            append(""","sigAlg":"SHA256withRSA"""")
            append(""","signerSecurityLevel":"${ExportSigner.levelName(r.level)}"""")
            append(""","publicKeyPem":"${esc(r.publicKeyPem)}"""")
            // 2026-09-30：一次导出一把密钥时会带一条 attestation 证书链。
            //   没有就不写这个键 —— 接收端按"没有这一层"处理，那条路不算失败。
            //   必须经 esc()：PEM 含换行，直接拼会产出非法 JSON（09-28 那三份就是这么坏的）。
            r.attestationChainPem?.let { append(""","attestationChain":"${esc(it)}"""") }
        }
        is ExportSigner.Result.Unavailable ->
            ""","signatureError":"${esc(r.reason)}""""
    }

    /**
     * 导出第二步（同步）：补上末行、交出完整文本。
     *
     * 渲染已经整个搬到 `data/ExportText.kt` 的 `render()` => 本函数只是转手
     *   （那条边界可核对：本函数里不该出现 `sb.appendLine` / 任何 `"""…"""`）
     */
    fun finishExport(p: Prepared, tail: String): String = render(p.inputs, tail)

    /**
     * 取一个标量（事务外调用点用这个）
     *
     * 下面是**带 db 句柄**的重载 —— 事务必须在**同一个句柄**上开
     *   （2026-09-28 加 H13 来自独立复核）
     */
    private fun scalar(sql: String): Long = scalar(readableDatabase, sql)

    private fun scalar(db: android.database.sqlite.SQLiteDatabase, sql: String): Long {
        db.rawQuery(sql, null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }
}
