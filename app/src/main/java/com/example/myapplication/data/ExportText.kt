package com.example.myapplication.data

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 ***导出文本那一族**（2026-09-28 起从 `NotifStore` 逐步搬出来）
 *
 * 为什么单独一个文件
 *   `tools/cohesion_matrix.py` 的输出显示：这一族方法**不引用任何实例字段**
 *   => 而它们的**外向依赖只有 3 个**（`chainIdentity` `lastClear` `queryAll`）
 *     —— 而 那 3 个**都要 `NotifStore` 实例**（它们碰 db / prefs）
 *   =>所以整族搬走的形状是**两段式**：
 *     1 调用方（`NotifStore`）把 3 个值算好
 *     2 传进这里的**纯渲染函数**
 *   而那是**大改**（`exportText` 136 行）=> 所以先搬**不依赖那 3 个**的部分
 *
 * 搬迁顺序（从零依赖到有依赖，每步都要能独立验）
 *   1 `exportFileName()` —— **真零依赖**（只用 JDK 类）<- **本次**
 *   2 `appVersionName()` / `verifierScript()` —— 只碰 `appContext`
 *      => 需要一个 `Context`（可以当入参传）
 *   3 `exportText()` —— 那个两段式大改
 */

/**
 * 导出的文件名（带时间戳，放应用私有目录）
 *
 * 它原来是 `NotifStore` 的实例方法，而它**不引用任何实例状态**
 *   => 所以 JVM 单测里本来造不出它（`NotifStore` 要 `Context`）
 *   => 搬成顶层函数之后**纯 JVM 可测**（见 `ExportFileNameTest`）
 *
 * 形状是**契约**（接收方按它认文件）：
 *   `nk-export-<yyyyMMdd>-<HHmmss>.jsonl`（`Locale.US` => 与时区无关的数字）
 *   而 `SimpleDateFormat` 是**可变**的 => 所以**每次新建**（不共享）
 */
internal fun exportFileName(): String =
    "nk-export-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jsonl"
/**
 * 本 App 的**版本名**（关联性："这条记录是哪个版本的 App 产生的"）
 *
 * 它原来是 `NotifStore` 的实例方法，而它只用 `appContext` => **现在当入参传**
 *   => 好处是具体的：这个文件里的函数**不再需要某个类的实例**
 *     （而那正是"能不能纯 JVM 测"的分界线）
 *   **而这不是"纯移动"**：入参变了 => 调用方要改
 *     => 而**编译器会报出来**（调用方跨界 + c实例调用 => 它必须改）
 *     如实标注：我没有用"存量字节相等"验它（入参一变，那个判据就不适用了）
 *       => 改用的判据是：**函数体除 `appContext` => `ctx` 之外逐字不动**
 */
@Suppress("DEPRECATION")
internal fun appVersionName(ctx: Context): String = runCatching {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
}.getOrDefault("?")

/**
 *读出**随 App 打包的核对器**（真源：`app/src/main/assets/verify_export.py`）
 *
 * 它会被放进导出的 zip => **接收方拿到就能自己验**，不必听我们说"没被改过"
 * 读不到返回**空串** —— 调用方要能看出"没带上"（别静默失败）
 *   而调用方确实看了：`MainActivity` 判 `verifier.isEmpty()` 并在分享文案里提醒
 *
 * 它与 `appVersionName` 同族：原来都只用 `appContext` => 都改成入参
 *   而它的调用方是 `MainActivity`（不是 `exportText`）——
 *     因为 zip 是在 `MainActivity` 里组装的，`exportText` 只产那份 jsonl 文本
 */
internal fun verifierScript(ctx: Context): String = runCatching {
    ctx.assets.open(ExportBundle.VERIFIER_NAME).bufferedReader().use { it.readText() }
}.getOrDefault("")


/**
 ***记录行**（导出里"一条记录一行"的那一行）
 *
 * 为什么把它抽成一个纯函数（2026-09-28 大改的第一步）
 *   独立复核者的回执里 **E5** 那条说：
 *    「记录行的 **9 个字段名是链的输入** —— 读侧 `payload_of_strict` **按名字**重建 payload；
 *        少写或改名任一字段（例如 `text` -> `body`）=> 重算出【不同】的 payload
 *        => **整条链判断（假警报）**」
 *   而在抽出来之前，那条契约**只写在注释里、没有任何测试兜着**
 *     （因为 `exportText` 要 `Context` => JVM 测不了）
 *   =>抽成顶层函数之后 **纯 JVM 可测** => 那条契约从注释变成了断言
 *
 ***它的形状【一个字符都不能动】**（E3）
 *   回执 E3 说：「逗号/花括号靠【拼接的物理形状】正确」——
 *     例如 `+ ""","pkg":…` 里，**行首那个逗号属于上一行对象**
 *   => 所以这里**照抄**原来那 4 行的拼法（不重排、不合并、不改用 `joinToString`）
 *     因为"重排一下"正是会让产物多一个或少一个逗号的那类改动
 *
 * 12 个键的分工（而链只吃其中 9 个）
 *   链的输入（9）：`pkg` `key` `event` `title` `text` `big` `posted` `reason` `captured`
 *     —— 与 `HashChain.payloadOf` 逐字对应
 *   `id`：读侧排序用（`verify_export.py` 的 `:135`）**不进 payload**
 *   `selfHash` / `prevHash`：链的产物（读侧拿它们复算）
 *
 ***为什么外面有一对括号**（2026-09-28 抽出来时踩的）
 *   在 `NotifStore` 里那 4 行是写在 `sb.appendLine(` 的**括号内**的 => **括号提供了续行**
 *   而抽成【顶层表达式体】之后括号没了
 *     =>**Kotlin 不会因为"下一行以 `+` 开头"就自动续行**
 *       => 那 3 行被当成**非法语句** => 119 个语法错
 *   => 所以这里给它**自己的括号**（也可以改成 `buildString`，但那更容易动到形状）
 *   判据：**搬"表达式体"时，原来靠外层括号/调用续行的写法会散架**
 *     => 要么补括号、要么把整个表达式收成一行 —— **而后者会动到形状**（E3）
 */
internal fun recordLine(e: NotifEvent): String = (
    """{"id":${e.id},"captured":${e.captured},"posted":${e.posted},"event":"${esc(e.event)}""""
        + ""","pkg":"${esc(e.pkg)}","key":"${esc(e.key)}","title":"${esc(e.title)}""""
        + ""","text":"${esc(e.text)}","big":"${esc(e.big)}","reason":${e.reason}"""
        + ""","selfHash":"${e.selfHash}","prevHash":"${e.prevHash}"}"""
)


/**
 ***局限声明那一族**（`{"limitation1": …}` 到 `{"limitationN": …}`）
 *
 * 为什么返回 `List<String>` 而不是直接往 `StringBuilder` 里塞
 *   因为"**一个元素一条、从 1 起连续编号**"这条契约需要一个**可测的形状**
 *   而原来它是：
 *       `for ((i, lim) in OpsEvents.LIMITATIONS.withIndex())`
 *       `    sb.appendLine("""{"limitation${i + 1}":"${esc(lim)}"}""")`
 *     => 那**也能产出对的键名**，而它**测不了**（因为 `exportText` 要 `Context`）
 *       => 而"漏一条""跳号""重复"都是**改这份代码时容易犯**的错
 *         其中**重复键名最凶**：JSON 里后一个会**覆盖**前一个
 *           => 接收方**少看到一条局限**，而 rc 不变 => **静默**
 *
 * 它消费的是**单一来源**：`OpsEvents.LIMITATIONS`
 *   那个列表上面的注释明写了它的用法（两个读者都从这一份渲染）：
 *     `NotifStore` 逐条写成 `{"limitation1": …}`
 *     `ExportBundle` 的 markdown 说明书按**同一个列表**渲染
 *     而 Python 侧的核对器**读所有 `limitation*` 键** => **它永不需要同步**
 *   而历史教训（那注释里也写着）：改前这里是**手写两句**，
 *     而 md 说明书里**手写四句** => **两处漂了** => **跑核对器的人看到的局限比读说明书的人少**
 *
 * 判据（都做成了断言，见 `LimitationLinesTest`）：
 *   1 每个元素产出一行（一条不落）
 *   2 键名从 `limitation1` 起**连续**（不跳号、不重复）
 *   3 文本过 `esc`（引号/换行/控制字符都被转义）
 *   4 空列表 => **一行都不产**（而不是产 `limitation0`）
 */
internal fun limitationLines(items: List<String>): List<String> =
    items.mapIndexed { i, lim -> """{"limitation${i + 1}":"${esc(lim)}"}""" }


/**
 ***链状态那一行**（`chainGenesis` / `chainTail` / `chainIntact` / `firstBrokenId`）
 *
 * 它对应"三性两力"里的**真实性** —— 一句"这几条记录连成一条链、中间没断"
 *   而它的判据是**可复算的**：读侧按同一套 9 字段重建 payload => 重算 sha256 => 逐条比
 *
 * **它的形状有坑**（独立复核者的 E3）
 *   那 4 个键**不是一行字面量**，而是两行靠着 `+ ""","chainIntact":…` 拼起来的
 *     第一行结尾是 `""""`（raw string 收尾 + 一个 `"`）第二行以 `+ """` 开头
 *   => 这里是**照抄**那个拼接形状的（不重排、不合并、不改成 `joinToString`）
 *     因为"重排一下"正是会让产物多一个或少一个逗号的那类改动
 *   而表达式体外面那对括号是**必须的** —— 与 `recordLine` 同一个坑：
 *     Kotlin 不会因为"下一行以 `+` 开头"就自动续行
 *
 * 两个入参的含义（那正是 E14 说的"两段式"：调用方算好、传进来）：
 *   `all` = 这次要导出的全部记录（已按 id 升序）=> 只用它取**最后一条的 `selfHash`**
 *   `broken` = `HashChain.firstBroken(all)` 的结果（首个断点的 id，完好则 `null`）
 *
 * 判据（见 `ChainLineGoldenTest`）：
 *   1 `chainGenesis` 恒为 **64 个 0**（`HashChain.GENESIS`）
 *   2 **空列表 => `chainTail` 也写 GENESIS**（而不是 `null` 或空串）
 *   3 `broken == null` => `chainIntact:true` + `firstBrokenId:-1`
 *   4 `broken = 5` => `chainIntact:false` + `firstBrokenId:5`
 */
internal fun chainLine(all: List<NotifEvent>, broken: Long?): String = (
    """{"chainGenesis":"${HashChain.GENESIS}","chainTail":"${all.lastOrNull()?.selfHash ?: HashChain.GENESIS}""""
        + ""","chainIntact":${broken == null},"firstBrokenId":${broken ?: -1}}"""
)


/**
 ***清空留痕那一行**（对应"三性两力"里的**合法性**）
 *
 * 它回答的是"**谁在什么时候清掉了多少条**" ——
 *   而那份留痕存在 `SharedPreferences`（不是数据库）
 *     => 所以**清空之后它仍然在**（否则"清空"这个动作会把自己唯一的记录一起删掉）
 *
 ***它的返回类型是 `String?` —— 那是刻意的**
 *   判据：**"没清空过" => 一行都不产**（而不是产 `"lastClear":null`）
 *   而这条与同一份产物里另一个"空值"的写法**不同**，值得记：
 *     `coveredFrom` / `coveredTo`（空库时）=> **写 `null`**
 *     `lastClear`（没清空过）=> **整行不写**
 *     =>**两种"空"并存** => **读侧必须两种都处理**
 *       （核对器那边确实两种都处理了 而这是"跨语言契约"那条 E12 的一部分）
 *   => 所以"空值怎么办"这件事**收在这个函数里**，而不是留在调用方
 *
 * **一个名字映射（最容易改错的地方）**
 *   `ClearRecord` 的字段叫 `chainId` / `epoch`
 *   而产物里的键叫 **`clearedChainId`** / **`clearedEpoch`**
 *     =>因为"那一段链"指的是**被清掉的那一段**（不是清空后新开的那段）
 *       => 若这里写成 `"chainId"` => 接收方会以为它说的是**当前**那段链
 *         而同一份产物里**另有一行** `{"chainId":…,"epoch":…}` 说的才是当前段
 *           =>**两个同名键会打架** => 必须用 `cleared*` 前缀
 *
 * 5 个键：`at`（清空时刻）`count`（清掉几条）`tailHash`（当时链尾）
 *   `clearedChainId` `clearedEpoch` —— 数值不带引号、`tailHash` 带引号
 *
 ***为什么这里写成 `if / else` 而不是 `c?.let { … }`**（2026-09-28 踩的第三次）
 *   那个 `+` 开头的续行**必须处在【表达式位置】**才合法
 *     `foo( … )` 的括号内 => 表达式位置
 *     `= ( … )` 的括号内 => 表达式位置
 *     `{ … }` 的 lambda 块内 => **语句位置** => `+ …` 那行会成非法语句
 *   => 我第一版写的是 `= c?.let {` + 缩进的拼接 => **报 `Unresolved reference`**
 *     （同一个坑第三次：前两次是 `recordLine` 与 `chainLine`，那两次是补括号）
 *   => `if / else` 的 `else` 分支**本身就是表达式位置** => 括号里可以续行
 *     而且它与"**没清空过 => 不产行**"那条语义更贴（一个显式的 null 分支）
 */
internal fun lastClearLine(c: ClearRecord?): String? =
    if (c == null) null
    else (
        """{"lastClear":{"at":${c.at},"count":${c.count},"tailHash":"${c.tailHash}""""
            + ""","clearedChainId":${c.chainId},"clearedEpoch":${c.epoch}}}"""
    )


/**
 ***断档汇总行**（对应"三性两力"里的**真实性** —— 它承认"我不知道的时段"）
 *
 * 六个键，而它们的关系是**可验的**（见 `check_artifact.py` 的 9）：
 *   `gapCount` == `certainGaps` + `suspectedGaps`
 *   `hasOpenGap` == (`openCertainGap` || `openSuspectedGap`)
 *
 ***为什么"未闭合"要分成两个键**（那是 P2 那次修复的成果，而它是"少撒谎"的典型）
 *   改前只有一个布尔 `hasOpenGap = gaps.any { !it.closed }`
 *   而 A4 之后 `gaps` **含疑似尾段** => **任何超过阈值的安静**（疑似尾段，`closed = false`）
 *     也会把 `hasOpenGap` 置 true
 *     => 核对器于是打出「**此刻仍处于断档中**」——
 *      **最强措辞配最弱证据**，与"少撒谎"的宗旨**反向**
 *   => 所以现在分两类写：
 *     `openCertainGap`   = **有事件作证**的那类还没闭合
 *     `openSuspectedGap` = 只是"沉默超阈值"的那类还没闭合
 *   而 `hasOpenGap` **保留**（= 两者之一）=> **老核对器照样能读**
 *     =>判据：**加字段时别删老字段**（那会打断"读旧文件的人"）
 */
internal fun gapSummaryLine(gaps: List<Gap>): String = (
    """{"gapCount":${gaps.size},"certainGaps":${gaps.count { !it.suspected }}"""
        + ""","suspectedGaps":${gaps.count { it.suspected }},"hasOpenGap":${gaps.any { !it.closed }}"""
        + ""","openCertainGap":${gaps.any { !it.closed && !it.suspected }}"""
        + ""","openSuspectedGap":${gaps.any { !it.closed && it.suspected }}}"""
)

/**
 * **一条断档**（明细行）
 *
 * 四个字段：`from` / `to`（起止时刻，取自**设备时钟**）
 *   `closed`（是否已经恢复监听）`suspected`（是"推断"还是"有事件作证"）
 *
 * 命名与语义的对应（与 `Gap` 那个 data class 的字段名一致）：
 *   `Gap.fromCaptured` => 产物键 `from` `Gap.toCaptured` => `to`
 *     => 而那是个**名字映射**（字段名与键名不同）=> 与 `clearedChainId` 同族，
 *       **改错就静默**（读侧按名字取值）
 *
 * 而 `closed` / `suspected` 是**布尔** => 不许带引号（读侧按 bool 解析）
 */
internal fun gapLine(g: Gap): String =
    """{"gap":{"from":${g.fromCaptured},"to":${g.toCaptured},"closed":${g.closed},"suspected":${g.suspected}}}"""

//三个【零入参】的固定行（所以它们是 `val`，不是 `fun`）
//
// 判据：**无入参 => 该是 `val`** —— 给它们包一层函数只是多一层调用，不会有新判据
//   而它们的"不会被改坏"由**两份比**那一条覆盖（固定 meta 行逐字节比）
//
//而下面两个多行常量**必须**写成 `= (` + 拼接 + `)`
//   理由与 `recordLine` / `chainLine` / `gapSummaryLine` 同族（那个坑踩过三次）：
//     那个 `+` 开头的续行**必须处在【表达式位置】**
//     `val X =` 换行之后**不在任何括号里** => `+ …` 那行成非法语句
//   => 所以 `= (` 那一对括号是**必须的**，不是风格问题
//
// 而这三段是【从源文件里读出来的】、不是手抄的
//   因为我手抄那次抄漏了（抄的是被 `[:150]` 截断过的打印输出）
//     => 判据：**凡"照抄"该从【文件】抄，不该从【打印输出】抄**

/**
 * **第一行**（版本契约）
 *
 * `_header` + `formatVersion` —— 核对器**不读它**（它给第三方看）
 *   => 而"不读"不等于"不重要"：它是**格式版本**，将来改格式时靠它分派
 */
internal val HEADER_LINE = """{"_header":"NK-notification-export","formatVersion":2}"""

/**
 * **链身份那一行**（防止"两段链假装成一条"）
 *
 * 它说的是一件**容易搞错的事**：判断两批记录是否同一条链，要看 `chainId`、
 *   **不要看 `epoch`** —— 因为重装/清空后 `epoch` 会**重新数** => 会误判
 *   => 所以这句话**写进产物**（因为文件会交给第三方，而他们不会读我们的文档）
 */
//2026-09-29 搬（F2）：这句从【末行】搬到【记录区】—— 末行只剩签名原语
//   起因：exportSelfHash 的口径是「除末行外全部字节」=> 末行整行不受保护
//     而末行里却装着一句【给人看的说明】 => 换掉它 => 接收方照错的说明去核对，
//     而工具仍报「自洽 + 签名验过」（爻光 probe2/Q4 实测 我已复现）
//   为什么不能把末行也包进 hash：会自指（hash 要算进它自己）
//     => 正解是「把说明与声明分开」：说明进记录区（受保护）、末行只留签名原语
//   而核对器【根本不读】这个键（grep 命中 0）=> 搬它不影响任何判断
                //2026-09-28 修（A10）：文案必须与算法**逐字一致**
            //   改前写「去掉最后一行后，对剩余全文做 SHA-256」——
            //   照字面 `head -n -1 f | sha256sum` 会把上一行结尾的换行符也算进去
            //   => 于是一个**没被动过**的证据包会被算成"被改过"（本工具最不能出的错）
            //   实际算法（三处一致：Kotlin trimEnd('\n') dropLast(1).joinToString("\n")
            //      Python "\n".join(lines[:-1])）算的是**不含结尾换行符**的那一段
internal val HOW_TO_VERIFY_LINE = """{"howToVerify":"去掉最后一行【连同它前面的那个换行符】，对剩余全文（UTF-8 字节）做 SHA-256，应等于本字段的值。等价说法：删掉最后一个换行符及其之后的所有内容，再对剩下的字节做 SHA-256。直接 head -n -1 会把结尾换行符算进去 => 会算出不一致（那是口径差，不是文件被改）"}"""
internal val CHAIN_IDENTITY_NOTE_LINE = (

"""{"chainIdentityNote":"判断两批记录是否属于【同一条链】，要看 chainId 是否相同；"""
    + """不要看 epoch —— 重装/清空后 epoch 会重新数，会误判。chainId = 这一段链【开始记录】那一刻的时间戳。"""
    + """而它可能【晚于】本文件里最早的记录 —— 因为链身份这个特性是【中途加上的】："""
    + """在它之前写入的老记录没有链身份，而它们仍会出现在导出里。所以别用 chainId 推断「这份文件覆盖了库里全部历史」。"}"""

)

/**
 ***字段释义（legend）** —— 对应"三性两力"里的**证明力**
 *
 * 为什么它要紧：读文件的人**不一定懂** `reason=8` 是什么意思
 *   => 不解释 => 他要么猜、要么来问我们
 *     => 所以他**自己就能判读**这件事，是"证明力"的一部分
 *
 * 四个键解释四类东西：`event`（事件类型）`reason`（移除原因）
 *   `selfHash`/`prevHash`（链的两个 hash）`gap`（断档的语义）
 *   而 `gap` 那条**特意写了"不能确定真断了"** —— 与 P2 那次同族：
 *     **不夸大**（`suspected=true` 只是沉默超阈值）
 *
 * 而 `event` 那一段里含 **`APP_START/EXPORT/CLEAR=本应用操作`** ——
 *   那三个是我们**自己写的**操作事件（不是通知）=> 读的人得知道
 */
internal val LEGEND_LINE = (

"""{"legend":{"event":"POST=收到通知 REMOVED=通知被移除 LISTENER_CONNECTED=开始监听 LISTENER_DISCONNECTED=监听断开 SERVICE_DESTROY=服务销毁 APP_START/EXPORT/CLEAR=本应用操作"}"""
    + ""","reason":"移除原因：8=REASON_APP_CANCEL（由该应用自己取消 —— 微信/QQ 撤回属于此类）1=用户点击 2=用户划掉 3=用户一键清空 19=超时""""
    + ""","selfHash":"本条记录内容的 SHA-256","prevHash":"前一条的 selfHash，首条为 64 个 0""""
    + ""","gap":"from->to 之间本应用【没有在监听】；suspected=true 表示只是沉默超过阈值，不能确定真断了"}"""

)


/**
 ***导出元信息那一行**（`exportedAt` / `count` / `coveredFrom` / `coveredTo`）
 *
 * 它回答两件事：**"我什么时候导的"**（`exportedAt`）与 **"这份覆盖了哪段时间"**
 *   （`coveredFrom` -> `coveredTo`）以及条数
 *
 * **`exportedAt` 是可被改的**（它是设备时钟）
 *   =>**它【不是锚】** —— 只是可读性（真正的时间锚只能靠外部锚定）
 *     而这一段边界**写在文件里的说明**（`HOW_TO_VERIFY.md`）里
 *
 ***`coveredFrom` / `coveredTo` 的写法有讲究**（复核者回执的 E6）
 *   它们取的是**全部记录 `captured` 的最小/最大**
 *   而**零记录时写 `null`**（**不是 0**）——
 *     写 0 的话，读侧会把它格式化成 **1970-01-01** => **接收方误读时间段**
 *     =>**那正是"空值该有自己的表示"**（而不是拿一个合法值去冒充）
 *
 * 判据（见 `check_artifact.py` 的 10）：
 *   10a `coveredFrom` == min(记录 `captured`) 10b `coveredTo` == max(…)
 *   10c 空记录 => 两个都是 `null`
 *
 * **两个入参**（两段式）：`all` = 要导出的记录 `now` = "此刻"（由调用方取）
 *   => 而 `now` 由外部传进来，是这个函数**可测**的前提
 *     （若在里面调 `System.currentTimeMillis()` => 输出不可复现 => golden 测试写不出来）
 *   而 `count` 用 `all.size` —— 它必须与记录行数一致（由 5 与 10 一起看着）
 */
internal fun metaRangeLine(all: List<NotifEvent>, now: Long): String = (
    """{"exportedAt":$now,"count":${all.size},"""
        + """"coveredFrom":${all.minOfOrNull { it.captured } ?: "null"}"""
        + ""","coveredTo":${all.maxOfOrNull { it.captured } ?: "null"}}"""
)


/**
 * **签名密钥档位那一行**（对应"不假装"那条）
 *
 * 它回答：**这把密钥实际落在哪个安全档** —— `software` / `tee` / `strongbox`
 *   而材料点名的那条常见缺陷是"**声称硬件背书却从不读回来**"
 *     => 所以本产物把**读回来的**档位写出来
 *
 ***入参是 `String` 而不是让它自己去读**（复核者回执的 E7）
 *   `ExportSigner.currentLevelName()` **有副作用** —— 它内部会 `generateIfMissing()`
 *     => **首次导出时真的会生成一把 RSA-2048 密钥**
 *   => 所以那个调用**必须留在调用方**（`NotifStore`）
 *     而这个函数只做字符串拼接 => **它是纯的、可测**
 *   若把那个调用搬进来 => golden 测试会**建一把密钥**（污染 + 不可复现）
 *
 * 而它的取值有一个**已修的缺陷**（见 `ExportSigner.levelName`）：
 *   "读不到"曾经与"真的是软件档"同形 => 现在读不到写 `"unavailable"`
 *     => 所以这一行见到的 `"unavailable"` 意思是"**没拿到**"，不是"软件档"
 */
internal fun signingLevelLine(level: String): String = """{"signingSecurityLevel":"$level"}"""

/**
 * **设备那一行**（对应"三性两力"里的**关联性**）
 *
 * 它回答：**这份文件出自哪台设备、什么系统**
 *   => 关联性要的是"能对上环境"，而不是"能定位到人"
 *     （所以只写厂商/型号/系统版本/SDK 级别 —— 不写序列号）
 *
 * **四个入参而不是直接读 `Build.*`**（与 `signingLevelLine` 同族）
 *   `Build.MANUFACTURER` 那些是**静态**的 => 技术上可以直接读
 *   => 而那样这个函数就**不可测**（输出随运行环境变）
 *     所以当入参传 => **纯函数、可测**
 *   而 `sdk` 是 **`Int`（不带引号）** => 读侧按数字解析
 */
internal fun deviceLine(manufacturer: String, model: String, release: String, sdk: Int): String = (
    """{"device":"$manufacturer $model","android":"$release""""
        + ""","sdk":$sdk}"""
)


/**
 * **App 版本那一行**（对应"三性两力"里的**关联性**）
 *
 * 它回答：**这份文件出自哪个版本的 App、哪个系统 build**
 *   => 关联性要的是"能对上环境"
 *
 * **两个入参都是字符串**（第二个由调用方从 `Build.FINGERPRINT` 取）
 *   与 `deviceLine` 同族：在函数里直接读 `Build.*` 会让它**不可测**
 *     => 而当入参传 => 纯函数、可测
 *
 * 而 `appVersion` 那个值**本身有一个易错处**（已处理，但值得记）：
 *   它是 `appVersionName(ctx)` 的返回值 => 而那个函数**取不到时会回退**
 *     所以这里见到的可能是回退值 => **"取不到"与"真的是那个版本"不同形**
 *       （与 `signingLevelLine` 那条同族：别让"读失败"冒充一个合法值）
 *
 * 两个字符串都过 `esc`（由调用方做）—— 因为 `Build.FINGERPRINT` 里可能有
 *   不寻常的字符（它由 ROM 决定）=> 不过 `esc` 就可能破坏那一行的 JSON 形状
 */
internal fun appVersionLine(appVersion: String, fingerprint: String): String =
    """{"appVersion":"$appVersion","buildFingerprint":"$fingerprint"}"""

/**
 ***链身份那一行**（`chainId` + `epoch`）
 *
 * 它的用途：**让"两段链"不会假装成一条**
 *   背景：清空之后链会**重新开一段** => 于是同一份导出里可能有两段
 *     而接收方若不看身份 => 会把"清空后的新链"与"清空前的旧链"当成一条连续链
 *
 ***两个键的含义容易搞反**（那一行下面的注释里也写着）：
 *   `chainId` = **该段链开始那一刻的时间戳** => **它是身份**
 *   `epoch`  = **第几段** => **重装 / 清空后它会重新数** => **不能拿它判身份**
 *     => 所以 `CHAIN_IDENTITY_NOTE_LINE` 那句"**不要看 epoch**"是写给接收方的
 *
 * 而这两个值**随清空变** => 所以"两份比"只在"两次之间没清空"时能比它们
 *   而清空真的发生过时 => 它们变了是**对的** => 那时的差异是真差异
 *
 * **入参是两个基本类型，不是 `NotifChain.Id`**（刻意的）
 *   那样这个函数不依赖 `NotifChain` 那个类型 => **更容易测、也更难被误用**
 */
internal fun chainIdLine(chainId: Long, epoch: Int): String =
    """{"chainId":$chainId,"epoch":$epoch}"""

/**
 ***自 hash 那一行（= 导出文件的【最后一行】）**
 *
 ***它为什么必须与签名在同一行**（复核者回执的 E1 / E2）
 *   核对器的判据是：**去掉最后一行，对剩余全文算 SHA-256，该等于本字段的值**
 *     => 若签名**另起一行** => 被去掉的就变成签名行
 *       => 它算出的 hash 会**包含** `exportSelfHash` 那行 => **必然对不上**
 *         于是一个**没被动过的**证据包会被算成「被改过」 —— 而那是本工具最不能出的错
 *     => 并进同一行 => 最后一行仍是这一行 => 核对器行为**不变**
 *   而签的是 `exportSelfHash` 那个值 => **它覆盖的是整份文件的内容**
 *     （因为那个值是「去掉最后一行后全文的 SHA-256」）
 *
 * **两个入参，而不是让它自己去算**（与 E7 同族）
 *   `selfHash` 由调用方算（它要读 `sb` 的当前内容）
 *   `sigTail` 也由调用方取（`ExportSigner.sign()` **有副作用** ——
 *     它会懒生成密钥 => 进纯函数会让 golden 测试**建一把密钥**）
 *   => 所以这个函数只做字符串拼接 => **它是纯的、可测**
 *
 * 而「**它在最后**」这件事**不由这个函数保证**
 *   它由**调用点的位置**保证（与抽之前完全一样）
 *     => 判据：**只搬「怎么拼」、不搬「什么时候拼」**
 *   而那两条由 `check_artifact.py` 的 1 2 直接盯着：
 *     1 末行含 `exportSelfHash` 2 末行含 `signature`/`signatureError`
 */
internal fun selfHashLine(selfHash: String, sigTail: String): String = (
    """{"${OpsEvents.SELF_HASH_KEY}":"$selfHash"$sigTail}"""
)


/**
 ***导出渲染的【全部输入】**（复核者 E14 说的"两段式"的那个交接面）
 *
 * 它把"渲染需要什么"从"渲染怎么做"里**分离出来**
 *   于是调用方（`NotifStore`）负责**取值**（碰 DB / prefs / Context）
 *   而渲染那一边**只接这一个结构** => **它可以是纯的**
 *
 ***它是纯数据 —— 刻意不放 `Context`**
 *   `appContext` 在渲染里只用来取**一个值**（`appVersionName(ctx)`）
 *     => 所以这里放**已经算好的** `appVersion: String`
 *       若放 `Context` => 这个结构就依赖 Android => 渲染不可能 JVM 测
 *   => 判据：**交接面上只放【值】，不放【怎么取值的工具】**
 *
 * 七个字段，而它们各自带一个"为什么需要它"（那些理由散在渲染的注释里）：
 *   `all`        —— 全部记录（供条数、范围、链尾、逐条渲染、断档计算）
 *   `broken`     —— `HashChain.firstBroken(all)`（链是否完好 + 首个断点 id）
 *   `chainId` / `epoch` —— 链身份（见 `chainIdLine` 那段：身份看 chainId、不看 epoch）
 *   `lastClear`  —— 清空留痕（可空：没清空过 => 不产那一行）
 *   `appVersion` —— App 版本（关联性）
 *   `now`        —— "此刻"（供 `exportedAt` 与 断档计算）
 *     而 `now` 是**入参而不是在这里调时钟** => 那是"输出可复现"的前提
 *
 * 而 `all` 与 `broken` 是**同一批数据的两面**（一个列表 + 它的一个结论）
 *   看起来可以现场算（`HashChain.firstBroken(all)`）
 *   => 而**不那样做**：因为那会让纯渲染去调一个**有逻辑的函数**
 *     而"结论由调用方算好"正是两段式的要义
 *     而它也避免"只改一处、另一处忘改"（本仓已有那条：同一事实读两次要看含义）
 */
internal data class ExportInputs(
    val all: List<NotifEvent>,
    val broken: Long?,
    val chainId: Long,
    val epoch: Int,
    val lastClear: ClearRecord?,
    val appVersion: String,
    // 下面四个是 **`Build.*` 的值**（而它们是【环境依赖】）
    //   2026-09-28：这个缺陷是【测试逼出来的】—— `render` 原来直接读 `Build.MANUFACTURER`，
    //     而它在 JVM 单测里是 `null` => `NullPointerException` => 那说明"纯函数"不纯
    //     而此前测不出来（渲染嵌在 NotifStore 里、要 Context）
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidRelease: String,
    val sdkInt: Int,
    // 而 `Build.FINGERPRINT` 也是环境依赖（它在 JVM 里也是 null）
    val buildFingerprint: String,
    // 而这个也是【取】来的（`ExportSigner.currentLevelName()` 内部会懒生成密钥）
    //   2026-09-28：同一类缺陷的第二处 —— 它原来跟着函数体搬进了 `render`
    //     而 E7 那条警告的正是它："进纯函数会让 golden 测试建一把密钥"
    val signingLevel: String,
    val now: Long,
)

/**
 ***渲染整份导出文本**（复核者 E14 说的"两段式"的第二段）
 *
 ***它必须保持那 6 条产物契约**（回执 E1–E5 E12），而那正是它搬过来的理由：
 *   搬到顶层之后它**可 JVM 测** => 那 6 条契约能从注释变成断言
 *     而在此之前，那些契约**只靠"代码物理相邻"撑着** ——
 *       例如"最后一行必须最后"只靠两条物理事实（快照在 appendLine 之前、之后只剩 return）
 *       而**没有任何断言 / 类型 / 测试**保证它
 *
 * 而它接的是 [ExportInputs] => **纯的、可 JVM 测**
 *   调用方（`NotifStore`）负责取值（碰 DB / prefs / Context）
 *   所以这里**不该出现** `queryAll` / `chainIdentity` / `lastClear` / `appContext`
 *     => 判据：**这个函数里没有"取"、只有"怎么拼"**
 */
internal fun renderBody(inputs: ExportInputs): String {
        //2026-09-28（大改第十二步 搬家）：**把输入接过来**
        //   而这里【加局部别名、不改编排】是刻意的：
        //     那样下面那 100 多行**一个字都不用改** => 风险降到只剩"搬"本身
        //       而"搬"是可证的（产物逐字节不变）=> 判据是最硬的那条
        //   而这批别名**不能漏**（漏一个就一处 `Unresolved reference`）：
        //     第一版我漏了 `appVersion` => 编译器当场报出 `565:42 Unresolved reference 'appVersion'`
        //       => 判据：能靠编译器兜住的别靠眼睛
        //   而 `Build` 要的是 **import**（不是别名）=> 见文件头的 `import android.os.Build`
        //     那也是编译器报出来的（12 处 `Unresolved reference 'Build'`）
        //       => 判据：**搬代码要连 import 一起搬**
        val all = inputs.all
        val broken = inputs.broken
        val lc = inputs.lastClear
        val now = inputs.now
        val appVersion = inputs.appVersion
        val sb = StringBuilder()
        sb.appendLine(HEADER_LINE)
        //2026-09-28 加（lmq 指派的 2）：**声明这份文件的【覆盖范围】**
        //   改前只声明 `exportedAt`（我何时导出）与 `count`（多少条）
        //     => 接收方**看不出"这份覆盖了哪段时间"**
        //       => 他只能自己去翻每一条的时间（能做，但费事）
        //   =>现在给出 `coveredFrom` / `coveredTo` = 全部记录 `captured` 的**最小/最大**
        //   零记录时写 `null`（而不是 0 —— 那会被误读成"1970 年"）
        //   而它**同样可被改**（它是设备时钟）=> 它不是锚，只是可读性
        // 2026-09-28 抽成纯函数（大改的第七步）
        //   入参是 `all` 与 `now` => **叫用方取、传进来**（两段式）
        //   而它必须在前面：`now` 后面还要给 `allGaps` 用一次（两处读“此刻”的含义不同）
        //     （判据：同一事实读两次要看【含义是否相同】）
        sb.appendLine(metaRangeLine(inputs.all, inputs.now))
        // 同上：`Build.*` 当**入参**传 => 纯函数、可测
        sb.appendLine(
            deviceLine(inputs.deviceManufacturer, inputs.deviceModel, inputs.androidRelease, inputs.sdkInt)
        )
        //2026-09-28 加（lmq 指派的 1 第一步）：**如实标注签名密钥的档位**
        //   为什么先做这一步：材料点名"声称硬件背书却从不读回来"是常见缺陷
        //     => 所以先让产物**说出实测档位**（software / tee / strongbox）
        //   `currentLevelName()` 会**懒生成**密钥（首次导出时建）=>
        //     失败时返回 null => 这里写 `"unavailable"`（**不假装**）
        // 2026-09-28 抽成纯函数（大改的第八步）
        //   而 `currentLevelName()` 的调用**留在这里**（它有副作用：首次会建密钥）
        sb.appendLine(signingLevelLine(inputs.signingLevel))

        // 关联性
        // 2026-09-28：`appVersionName()` 已搬到 data 包的顶层函数，而它现在**接受一个 Context 入参**
        //   （原来它用 `appContext` 字段 => 现在从外部传进来：两者是**同一个实例**）
        // 2026-09-28 抽成纯函数（大改的第九步）
        //   而 `appVersionName(appContext)` 与 `esc(...)` 的调用**留在这里**
        //     （前者需要 Context、后者是跨语言契约的一半）
        sb.appendLine(appVersionLine(esc(appVersion), esc(inputs.buildFingerprint)))
        // 真实性
        // 2026-09-28 抽成纯函数（大改的第三步）
        //   它接两个入参 => **那正是 E14 说的"两段式"的第一步**：
        //     调用方算好 => 传进来
        sb.appendLine(chainLine(all, broken))
        //2026-09-28 修（A4 导出侧）：**把"此刻"明确传下去**
        //    改前 `allGaps(all)` 没有 now => **"最后一条记录 -> 此刻"那一段从来没被检查过**
        //    => 而它正是「服务被杀后**再也没回来**」的样子（SIGKILL 不触发 onDestroy）
        //    => 于是证据包里写 `hasOpenGap = false`
        //       —— 交给第三方的文件会**暗示"一直在听"**，而事实可能相反
        val gaps = NotifGap.allGaps(all, now)
        // 2026-09-28 抽成纯函数（大改的第五步）
        sb.appendLine(gapSummaryLine(gaps))
        for (g in gaps) {
            // 同上：明细行也抽成纯函数
            sb.appendLine(gapLine(g))
        }
        // 合法性：清空留痕（SharedPreferences 里那份 => 清空之后仍在）
        // 2026-09-28 抽成纯函数（大改的第四步）
        //   它返回 `String?` => **"没清空过就不产行"那条契约收在函数里**
        lastClearLine(lc)?.let { sb.appendLine(it) }
        //这一段链的身份（见 NotifChain）：让"两段链"不会假装成一条
        // 2026-09-28：`chainIdentity()` 的调用已挪到函数开头（大改第十一步 形状）
        //   因为它的结果要进 `ExportInputs` => **取值集中在一处**
        sb.appendLine(chainIdLine(inputs.chainId, inputs.epoch))
        sb.appendLine(CHAIN_IDENTITY_NOTE_LINE)
        // 证明力：字段释义（让读文件的人能自己判读，不用问我们）
        sb.appendLine(LEGEND_LINE)
        // 局限声明：写在文件里（因为文件会被交出去，而文档不会）
        //2026-09-28 修：**改成从 `OpsEvents.LIMITATIONS` 渲染**（一处定义）
        //    改前这里是**手写两句**，而说明书 markdown 里手写了**四句**
        //      => 两处会漂 => 实测已经漂了（JSON 2 条 vs md 4 条）
        //      => 而后果是：**跑核对器的人看到的局限比读说明书的人少**
        //    键名从 `limitation1` 起编号（而 Python 侧读**所有** `limitation*` 键
        //      => 连老文件里的 `limitation`（无数字）也能读）
        // 2026-09-28 抽成纯函数（大改的第二步）
        //   它现在可 JVM 测 => "一个元素一条、从 1 起连续" 那条契约有了落脚点
        //   而重复键名是最凶的那一类：JSON 后一个会覆盖前一个
        //     => 接收方少看到一条局限，而 rc 不变 => 静默
        for (line in limitationLines(OpsEvents.LIMITATIONS)) sb.appendLine(line)
        //2026-09-29（F2）：从末行搬来的那句，现在在这里、受自 hash 保护
        sb.appendLine(HOW_TO_VERIFY_LINE)
        for (e in all) {
            // 2026-09-28 抽成纯函数（大改的第一步）
            //   它现在可 JVM 测 => **E5 那条契约从注释变成了断言**
            //   而两边字节一致 => 产物不变（用 `diff_snapshots before after` 验）
            sb.appendLine(recordLine(e))
        }
        return sb.toString()
}

/**
 ***把 body 与末行接起来**（而它【也是纯的】—— `sigTail` 是入参）
 *
 ***为什么签名不在这里做**（复核者回执的 E7）
 *   `ExportSigner.sign()` 有副作用（首次会**懒生成一把 RSA-2048 密钥**）
 *     => 若在这里调它 => 这个函数**不纯**、而 golden 测试会**建一把密钥**
 *   而它【原来就在这里】—— 那是搬家时跟着函数体一起搬进来的：
 *     我用"局部别名"策略让函数体一个字不改 => 搬迁可证（逐字节）
 *     **而那个策略会把"取"与"拼"之间的边界【一起搬走】**
 *       => 判据：**搬完之后必须再扫一遍"这个函数里有没有【取】"**
 *         而「能 JVM 跑」本身就是那条判据 => 一跑就露（实测：三处）
 *           `Build.MANUFACTURER/MODEL/VERSION.*` => 已改成入参
 *           `Build.FINGERPRINT` => 已改成入参
 *           `ExportSigner.currentLevelName()` => 已改成入参
 *           `ExportSigner.sign()` => 就是这一处（现在搬到调用方）
 *
 * 三段的分工（而"自 hash 必须是最后一行"由此保证）
 *   1 `renderBody` 产出全部内容、**以 `\n` 结尾**
 *   2 自 hash = 对 `body.trimEnd('\n')` 求 SHA-256 => **不含结尾换行符**
 *   3 末行 = `selfHashLine(hash, sigTail)` + 一个 `\n` => **它成为最后一行**
 *     那三处的口径必须与读侧一致（写侧 Kotlin 读侧 Kotlin 读侧 Python）
 *       而 `RenderContractTest.selfHashMatches_theRestOfTheFile` 就是押它的
 */
internal fun render(inputs: ExportInputs, sigTail: String): String {
        val body = renderBody(inputs)
        val hash = OpsEvents.selfHashOf(body.trimEnd('\n'))
        return body + selfHashLine(hash, sigTail) + "\n"
}
