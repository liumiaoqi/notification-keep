package com.liumiaoqi.notificationkeep

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.liumiaoqi.notificationkeep.crash.CrashLogIO
import com.liumiaoqi.notificationkeep.crash.StartupSentinel
import com.liumiaoqi.notificationkeep.data.FoldedEvent
import com.liumiaoqi.notificationkeep.data.NotifFold
import com.liumiaoqi.notificationkeep.data.NotifStore
import com.liumiaoqi.notificationkeep.ui.theme.MyApplicationTheme
import java.io.File

/**
 * 主界面：**查看 + 导出证据包**
 *
 * 设计原则（三条，别违背）：
 *   1 **不删数据** —— 「清空…」是**灰色小字 + 二次确认**，且**不可逆**
 *      **不在这里写它的位置** —— 位置会随布局变（2026-09-28 就栽过：注释写着"右下角"，
 *         而实际上它被 `LazyColumn` 挤出了屏幕 => 用户反馈「清空找不到」）
 *      => 要看它在哪，**看那个 Composable 旁边的注释**，或**跑 uiautomator dump 量坐标**
 *   2 **可观测** —— 权限 / 计数 / 链完整性 / 最近一条 / 上次清空 都在第一屏
 *   3 **证据能出去** —— 导出后给两个出口：
 *        **「发送到…」** => 系统分享面板 => 邮件 / 微信 / 网盘（**这一步就是"外部锚定"**）
 *        **「另存为…」** => 系统文件选择器（**不需要任何存储权限**）
 *
 ***本 App 仍然不申请 INTERNET**
 *   "发送到…"是**把文件临时交给用户选的那个 App** => **联网的是它，不是我们**
 *   => "数据物理上无法外传"这条保证没被破坏
 */
/**
 *2026-09-30：**打开 App 也要认证**（lmq 提的）。
 *
 * 起因：方案 C 只把住了"**签名**"那一步，而设备落到别人手里时**最直接的一层不是签名，
 *   是"打开就看到全部通知记录"** —— 那条路此前一步都不拦。
 *
 * 三条边界（写清楚，免得被读成"万无一失"）：
 *  1 **只有设备本身设了安全锁屏时才拦**：没有锁屏就无从认证 => 那时不拦，
 *     但**明说**（见 [AppLockGate] 的另一个分支），不假装拦了
 *  2 离开前台**超过 [APP_LOCK_GRACE_MS]** 才要求重新认证 —— 切出去看一眼再回来不必重来
 *  3 认证失败/取消 => **留在闸门外**，给"重试"；**没有绕过按钮**
 *
 * 状态放**进程级**（不是 Activity 里）：转屏、Activity 重建不要求重来；
 *   而进程被杀 = 重新上锁（这正是想要的语义）。
 * 它与导出那一步的认证是**两次**、且**必须分开**：签名那把密钥是"每次使用都要认证"的，
 *   `CryptoObject` 绑的是那一次签名操作，跟"已解锁 App"不是同一个凭据
 */
private object AppLockState {
    /** true = 已解锁（可看记录）。用 `mutableStateOf` => Compose 能观察 */
    val unlocked = mutableStateOf(false)

    /** 上次离开前台的时刻（`onStop` 记）；0 = 从未离开或已处理过 */
    var leftAtMs = 0L

    /**
     *2026-09-30（真机轨迹抓到的缺陷）：**本次锁定期间是否已经自动弹过认证**。
     *
     * 起因：轨迹显示闸门在 7 毫秒内**渲染了两次、`authenticate()` 也发起了两次**，
     *   而第二遍把第一遍**取消**了（回调 `code=5 ERROR_CANCELED`）
     *   => 用户会看到一个莫名的"身份验证已取消"
     *   而代码里那句注释写的是"打开就弹一次" —— **注释与实现不符**（本项目的常客）。
     *
     * => 守卫放**进程级**（不放 `remember`）：闸门那个分支会被重新组合，
     *   `remember` 会跟着重置 => 那样守卫就失效了
     */
    var askIssued = false
}

/** 离开前台超过这个时长才要求重新认证（毫秒）。 */
private const val APP_LOCK_GRACE_MS = 60_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = NotifStore(this)
        // 操作留痕（合法性）：App 被打开这件事本身也记一笔
        runCatching { store.logOp(com.liumiaoqi.notificationkeep.data.OpsEvents.APP_START) }
        // 初始闸门状态：**设备有安全锁屏 => 先锁上**；没有 => 锁不上、也就不假装
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        AppLockState.unlocked.value = !km.isDeviceSecure
        //2026-09-30（lmq 提的"有的软件切后台会模糊"）：**任务切换器缩略图会原样拍下记录界面**。
        //   实测（真机 HyperOS 解锁状态下切后台）：缩略图里看得到「已记录 8706 条」与通知正文
        //   => API 33+ 关掉这一格的截图。它**只挡缩略图**，不挡用户自己截屏（那是有意留的）
        //   API 31/32 没有这个 API => 那两个版本上这一层不生效（真机是 36，覆盖到了）。
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        setContent { MyApplicationTheme { AppLockGate(this) { MainScreen(store, this) } } }
        //2026-10-15 加（崩溃/无响应可观测）：把"Activity 建好了"这一步记下来。
        //   这一行**只写一个几字节的记号**（prefs），**不改任何业务逻辑**
        //   => 它与 `MainScreen` 里那个 `content_shown` 记号合起来回答：
        //     "上次是**卡在出内容之前**，还是已经看到记录了"
        //   为什么这很重要：真机上那次 ANR 的签名正是
        //     `Waited 5000ms for FocusEvent(hasFocus=true)` => **窗口连焦点都没拿到**
        //     => 那正好落在"`activity_create` 之后、`content_shown` 之前"这一段
        CrashLogIO.mark(this, StartupSentinel.Stage.ACTIVITY_CREATE)
    }

    override fun onStop() {
        super.onStop()
        AppLockState.leftAtMs = android.os.SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        val left = AppLockState.leftAtMs
        if (left > 0 && android.os.SystemClock.elapsedRealtime() - left > APP_LOCK_GRACE_MS) {
            AppLockState.unlocked.value = false
            AppLockState.askIssued = false      // 新一次锁定 => 允许再自动弹一次
        }
        AppLockState.leftAtMs = 0
    }
}

/**
 * **链校验的界面状态**（三态，不是两态）
 *
 ***为什么必须三态**（#4 根因修复的**新增**部分）：
 *   校验挪到后台之后，界面要能表达"**还没算完**"
 *   若仍只用 `Long?`（`null` = 完整）=> **"还没算完"与"链完整"会撞成同一个显示**
 *     —— 那等于把"**未知**"显示成"**没问题**" => 恰恰是本批最不该犯的错
 */
private sealed class ChainCheckUi {
    /** 还没算完（绝不能显示成"完整"） */
    object Pending : ChainCheckUi()

    /** 算完了：`brokenAt == null` = 全链完整；否则 = 断在那条 id */
    data class Done(val brokenAt: Long?) : ChainCheckUi()

    /** 校验没跑成（说出来，不许静默停在 Pending） */
    data class Failed(val reason: String) : ChainCheckUi()
}

private const val VIEW_ALL = "__all__"

/**
 *2026-09-30：打开 App 的认证闸门。**未解锁时连 [MainScreen] 都不组合** =>
 * 记录此时根本没被读进内存（不是"显示了再遮住"）
 *
 * 两种分支都要**如实**：
 *  设备有安全锁屏 => 弹认证；失败/取消就**留在门外**（没有绕过按钮）
 *  设备没有锁屏 => 认证这条路走不通，于是**明说"这层没生效"**，让用户按一下继续
 *    —— 那是设备状态，不是我们给的例外
 */
@Composable
private fun AppLockGate(activity: ComponentActivity, content: @Composable () -> Unit) {
    if (AppLockState.unlocked.value) {
        content()
        return
    }
    val ctx = LocalContext.current
    val km = remember { ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager }
    val secure = remember { km.isDeviceSecure }
    var err by remember { mutableStateOf<String?>(null) }

    val prompt = remember {
        android.hardware.biometrics.BiometricPrompt.Builder(activity)
            .setTitle("解锁「通知留存（NK）」")
            .setSubtitle("记录里有你的通知内容")
            .setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
    }
    val mainExec = remember { ContextCompat.getMainExecutor(ctx) }
    val ask: () -> Unit = {
        err = null
        //2026-10-15 加（崩溃/无响应可观测）：**"认证弹出了"这一步也留个记号**
        //   **只加观测调用，闸门逻辑一行没动**
        //   => 于是哨兵能分清"用户停在闸门"（良性）与"卡在界面都没起来"（异常）
        //   它**每按一次「验证身份」都会写一次**（几字节 prefs）—— 这是有意的：
        //      `mark` 用的 `commit()` 是同步的，但代价是毫秒级（见 `CrashLogIO.mark` 的代价评估）
        CrashLogIO.mark(ctx, StartupSentinel.Stage.AUTH_PROMPTED)
        runCatching {
            prompt.authenticate(
                android.os.CancellationSignal(),
                mainExec,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult,
                    ) {
                        AppLockState.unlocked.value = true
                    }

                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        err = "没有认证 => 记录不显示\n$msg"
                    }
                },
            )
        }.onFailure { err = "发起认证失败：${it.javaClass.simpleName} ${it.message}" }
    }
    // 打开就**自动弹一次**（真正的"一次"：靠进程级守卫 `askIssued`，见它的注释）
    //   —— 失败/取消后**不**自动再弹（免得连环弹窗）；要再来就点「验证身份」
    // `LaunchedEffect(Unit)` 在这里可能因为分支重新组合而**再跑一次** => 光靠它不够，
    //   必须有那个守卫（轨迹实测：7 毫秒内跑了两次，第二遍把第一遍取消了）
    LaunchedEffect(Unit) {
        //2026-10-15 加（崩溃/无响应可观测）：**"闸门渲染出来了"** 记一笔
        //   放在 `LaunchedEffect(Unit)` 里 => **每次进入组合只写一次**（重组不会重复写）
        //   它**无条件**写（有锁屏 / 没锁屏两条分支都算"闸门已渲染"）
        //   **只加观测调用，闸门逻辑一行没动**
        //   它是 `ACTIVITY_CREATE` 与 `AUTH_PROMPTED` 之间的那一格：
        //      有它，哨兵才能区分"停在闸门（良性）"与"界面根本没起来（异常）"
        CrashLogIO.mark(ctx, StartupSentinel.Stage.GATE_SHOWN)
        if (secure && !AppLockState.askIssued) {
            AppLockState.askIssued = true
            ask()
        }
    }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (secure) "已锁定" else "本机没有安全锁屏",
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (secure) {
                        "记录里有你的通知内容，所以打开也要先验证身份。\n" +
                            "（导出时还会再验一次：签名那把密钥是「每次使用都要认证」的。）"
                    } else {
                        "本机没设锁屏/密码 => 「打开要认证」这层无法生效；" +
                            "导出那层同样不会要求认证。现在记录是不设防显示的。"
                    },
                    fontSize = 13.sp,
                )
                err?.let { Text(it, color = Color(0xFFC62828), fontSize = 12.sp) }
                if (secure) {
                    Button(onClick = { ask() }) { Text("验证身份") }
                } else {
                    Button(onClick = { AppLockState.unlocked.value = true }) { Text("知道了，继续") }
                }
            }
        }
    }
}

//2026-09-28 加（C3）：**"只看社交"的视图标记**
//    为什么需要另一个常量：`VIEW_ALL` 之外的取值会被当作**包名**传给 `filterByPkg`
//    => "只看社交"筛的是**一组包名**（`NotifFold.SOCIAL_PKGS`）
//      => 不能用单个包名表达 => 需要一个专用值
//    （而这个功能**本来就有**：`NotifFold.filterSocial` 当时是**零调用点**
//      —— 文档里写着"筛选芯片行：只看社交 / 全部 / 各 App 带计数"，
//      而界面上**只有"全部"** => 那正是"文档说有而代码没有"那一类）
private const val VIEW_SOCIAL = "__social__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(store: NotifStore, activity: android.app.Activity) {
    val ctx = LocalContext.current

    //2026-09-30（真机上抓到的 ANR）：导出那条路里有**三处重活**，
    //   全都不能在主线程上做 —— 真机 TEE 里生成一把带 attestation 的 RSA-2048 是**秒级**，
    //   而模拟器是软件 keystore、快到看不出来（所以模拟器上一直没暴露）。
    //   => 定型（读库 + 渲染）建密钥 签名 写文件 + 打 zip，一律走 IO。
    //   而**弹认证必须在主线程** => 用 `withContext(Dispatchers.Main)` 切回来。
    val scope = rememberCoroutineScope()
    val io = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }

    //2026-10-15 加（崩溃/无响应可观测）：`MainScreen` **只有解锁后才会被组合**
    //   => 所以"它跑到了"这件事本身 = "记录真的能显示了"
    //   必须包在 `LaunchedEffect` 里 —— 直接写在组合体里会**每次重组都写一遍 prefs**
    //     （界面上 `tick` 一变就重组 => 那就成了"每点一次刷新写一次盘"）
    LaunchedEffect(Unit) { CrashLogIO.mark(ctx, StartupSentinel.Stage.CONTENT_SHOWN) }

    var tick by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf(VIEW_ALL) }
    var foldDup by remember { mutableStateOf(true) }
    var askClear by remember { mutableStateOf(false) }

    /**
     *2026-09-30（lmq 提的「记录本身有没有防御手段」）：「清空」也要用户在场。
     *
     * 理由：清空是**唯一不可逆**的动作 —— 设备落到别人手里时，它能把记录直接弄没
     * （会留痕，但数据本身回不来）。打开闸门挡的是"看"，这一层挡的是"抹"。
     *
     * 与导出那次认证**也是分开的**：那是给签名密钥要的（`CryptoObject`），
     *   这里只要"人在场" => 不带 CryptoObject 的普通认证
     * 设备没有安全锁屏时认证走不通 => 直接清（那层闸门已经明说过"没生效"，不在这里假装）
     */
    val deviceSecure = remember {
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure
    }
    val clearPrompt = remember {
        android.hardware.biometrics.BiometricPrompt.Builder(activity)
            .setTitle("确认清空记录")
            .setSubtitle("清空不可逆：会留痕，但数据本身回不来")
            .setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
    }
    val clearNow: () -> Unit = {
        runCatching {
            store.clearAllWithRecord(); askClear = false; tick++
        }.onFailure { Toast.makeText(ctx, "清空失败：${it.message}", Toast.LENGTH_LONG).show() }
    }
    val askThenClear: () -> Unit = {
        if (!deviceSecure) {
            clearNow()
        } else {
            runCatching {
                clearPrompt.authenticate(
                    android.os.CancellationSignal(),
                    ContextCompat.getMainExecutor(ctx),
                    object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult,
                        ) {
                            clearNow()
                        }

                        override fun onAuthenticationError(code: Int, msg: CharSequence) {
                            Toast.makeText(
                                ctx, "没有认证 => 没有清空\n$msg", Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )
            }.onFailure {
                Toast.makeText(ctx, "发起认证失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    // 最近一次导出的结果（供"发送到/另存为"用）
    var exportFile by remember { mutableStateOf<File?>(null) }
    //2026-09-28 修（A6）：**删掉 `exportText` 这个假状态**
    //    它原来是 `var exportText by remember { mutableStateOf("") }` ——
    //    全项目**一个赋值点都没有** => 恒为 `""`
    //    而「另存为…」的 SAF 回调正是写它 => 用户得到 **0 字节** 文件，
    //    却看到「已保存到你选的位置」（本项目的"假成功"经典）
    //    => 现在回调直接读 `exportFile`（那个【真的有内容】的 zip）

    // 另存为：系统文件选择器（SAF）—— **不需要任何存储权限**
    //2026-09-28 修（D3 顺带）：MIME 从 `application/json` 改成 **`application/zip`**
    //    导出的其实是 **zip**（头是 `50 4b 03 04`：证据包 jsonl + 核对器 + 说明）
    //    => 声明成 json 会让接收方**按错类型打开**
    val saveLauncher = rememberLauncherForActivityResult(
        // 2026-09-28 修（P5 的第二半）：**这里改成 `*/*`**
        //    为什么不能写某个具体类型：这个 launcher 是**在导出【之前】**创建的
        //    => 它**拿不到**将来那个文件的名字（`.zip` 还是 `.jsonl`）
        //    => 而写死 `zip` 会在"没带上核对器"那条路上声明错类型
        //    `*/*` 正好是 SAF"另存为"的用法：类型交给**文件名**决定
        //      （我们在 `launch()` 时已经把名字给它了）
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri != null) {
            //2026-09-28 修（A6）：**写【真文件】的字节，不再写那个恒为空串的 state**
            //    1 先取那个真的有内容的文件（它由「导出」按钮生成）
            //    2 取不到 / 文件不在 => **明确报错**（不许再走"已保存"）
            //    3 写成功后把**字节数**报出来 —— 那样"0 字节"再也藏不住
            val src = exportFile
            if (src == null || !src.exists()) {
                Toast.makeText(
                    ctx,
                    "没有可保存的导出文件 —— 请先点「导出」再点「另存为…」",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                runCatching {
                    //`openOutputStream` 可能返回 **null**（系统没给流）
                    //    => 旧代码的 `?.use {}` 会**静默跳过写入**、然后照样报"已保存"
                    //    => 现在显式抛 => 落到 onFailure => **报失败**
                    val os = ctx.contentResolver.openOutputStream(uri)
                        ?: error("系统没有提供可写的输出流")
                    os.use { it.write(src.readBytes()) }
                }.onSuccess {
                    Toast.makeText(
                        ctx,
                        "已保存到你选的位置（${src.length()} 字节）",
                        Toast.LENGTH_LONG,
                    ).show()
                }.onFailure { t ->
                    Toast.makeText(ctx, "保存失败：${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val raw = remember(tick) { store.queryRecent(1000) }
    val total = remember(tick) { store.countAll() }
    val today = remember(tick) { store.countToday() }
    val recallLike = remember(tick) { store.countRecallLike() }
    //2026-10-15（#4 根因修复）：**链校验移出主线程**
    //  改前是 `remember(tick) { store.verifyChain() }` —— 它在**组合体内同步跑完**
    //     => 真机实测这就是那个 ANR 的根因：主线程在 UI 上**全量校验哈希链**
    //       （bugreport 的栈：`verifyChain -> firstBroken -> link -> sha256 -> String.format`）
    //       => 被"解释执行 + 编译风暴"拖过 5 秒 => 窗口 5 s 没拿到焦点
    //   => 手法与同批的 `summary`（P3-3）**完全一致**：`produceState + Dispatchers.IO`
    //   **语义一个字没改**（银狼的审核重点1）：仍是**同一个** `store.verifyChain()`
    //     => 覆盖（全链）/ 顺序（`queryAll()` 按 id 升序 = 从头）/ 结论（断点 id）**三项全同**
    //     变的只有【在哪个线程跑】与【UI 何时拿到结果】
    //   初值必须是 **Pending 而不是 `null`** —— 否则"还没算完"与"链完整"会**撞成同一个显示**
    val chainCheck by produceState<ChainCheckUi>(ChainCheckUi.Pending, tick) {
        value = withContext(Dispatchers.IO) {
            // 失败要**看得见**：若这里抛出去 => 协程被取消 => 界面**永远停在「校验中…」**
            //   （那正是"静默失效" => 所以**接住并说出来**，而不是让它静默卡住）
            runCatching { store.verifyChain() }
                .fold({ ChainCheckUi.Done(it) }, { ChainCheckUi.Failed("${it.javaClass.simpleName}: ${it.message}") })
        }
    }
    val lastAt = remember(tick) { store.latestCaptured() }
    val lastClear = remember(tick) { store.lastClear() }
    //2026-09-28 加（A5 的"可查"那一半）：**本次运行写入失败的条数**
    //    为什么需要它：`insert` 失败时返回 **-1** => 改前无条件打 => 静默丢记录
    //    => 光把日志改成 还不够 —— **用户不看 logcat** => 必须在界面上可见
    //    （判据：「让丢过的东西可查」= **界面上有一个数**，不是"代码里能读"）
    val insertFails = remember(tick) { NotifStore.insertFailCount() }

    //2026-09-28 修（C3）：**三种视图都接上**
    //    改前只认"全部 / 某个包名" => `filterSocial` **零调用点**
    //    => 而文档里写着界面有"只看社交"的芯片 => 那是"文档说有而代码没有"
    val filtered = when (view) {
        VIEW_ALL -> raw
        VIEW_SOCIAL -> NotifFold.filterSocial(raw)
        else -> NotifFold.filterByPkg(raw, view)
    }
    // 各 App 的条数（给筛选按钮标数量用 —— 而 `countByPkg` 此前也是零调用点）
    val pkgCounts = remember(tick) { NotifFold.countByPkg(raw).entries.sortedByDescending { it.value } }
    val shown = if (foldDup) NotifFold.fold(filtered) else filtered.map { FoldedEvent(it, 1) }

    val enabled = Settings.Secure.getString(
        ctx.contentResolver, "enabled_notification_listeners"
    ).orEmpty().contains(ctx.packageName)

    Scaffold(topBar = { TopAppBar(title = { Text("通知留存（NK）") }) }) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        if (enabled) "监听中" else "未获取通知使用权 => 请到系统设置打开",
                        color = if (enabled) Color(0xFF2E7D32) else Color(0xFFC62828),
                        fontWeight = FontWeight.Bold,
                    )
                    //2026-09-28 修（A7 配套）：`countAll`/`countToday` **含操作留痕**
                    //    —— 那是【合理的】（它们确实"记录"了）=> 不改 SQL，**改文案**说清
                    //    （判据：改哪里取决于"哪个语义是对的" ——
                    //       "已记录"包含监听事件是对的，而"最近一条"应该是通知，所以那条改了 SQL）
                    Text("已记录 $total 条（含监听事件）24h $today 条")
                    Text("疑似撤回：$recallLike 条", color = Color(0xFFB26A00))
                    Text(
                        "疑似撤回 = 1 App 自己撤掉的那条（reason=8）2 正文里带「撤回」的",
                        fontSize = 11.sp, color = Color.Gray,
                    )
                    // 链完整性那一行 —— 现在有**三个**状态（判据同 `brokenAt` 那处的注释）
                    val chainLine = when (val c = chainCheck) {
                        // 灰字：**还没算完**（与"完整"必须区分开）
                        is ChainCheckUi.Pending -> "校验中…" to Color.Gray
                        is ChainCheckUi.Done -> if (c.brokenAt == null) {
                            "记录完整" to Color(0xFF2E7D32)
                        } else {
                            "记录断在第 ${c.brokenAt} 条" to Color(0xFFC62828)
                        }
                        // 校验**失败了**也要说（不许永远停在"校验中…"）
                        is ChainCheckUi.Failed -> "链校验没跑成：${c.reason}" to Color(0xFFC62828)
                    }
                    Text(chainLine.first, color = chainLine.second)
                    Text(
                        //2026-09-28 修（A7）：这一条现在**只算真通知**（见 NotifStore.latestCaptured）
                        //    => 它不再被 App 自己写的 APP_START / LISTENER_* 顶上去了
                        //    （改前：每次打开 App 都显示"刚刚"；监听刚断开那一刻**也**显示"刚刚"）
                        if (lastAt == 0L) "最近一条：（还没有）" else "最近一条：${fmtAgo(lastAt)}",
                        color = Color.Gray, fontSize = 12.sp,
                    )
                    //2026-09-28 加（A5）：写入失败必须**看得见**（只在出过事时显示）
                    if (insertFails > 0) {
                        Text(
                            "本次运行有 $insertFails 条写入失败（记录丢了，不是没发生）",
                            color = Color(0xFFC62828), fontSize = 12.sp,
                        )
                    }

                    //2026-10-15 加：**崩溃/无响应也必须看得见** —— 与上面那行**同一条判据**
                    //   （判据：「让发生过的事可查」= **界面上有一行字**，不是"代码里能读"）
                    //   两行都**只在有事时显示** => 平常一个字都不占地方
                    //   `crashVerdict` 那一行说的是"**上一次**启动没走完"（不是这一次）
                    StartupSentinel.describe(
                        remember(tick) { CrashLogIO.verdict(ctx) },
                        System.currentTimeMillis(),
                    )?.let { Text(it, color = Color(0xFFC62828), fontSize = 12.sp) }
                    //2026-10-15（独立复审 P3-3）：**读 `crash.log` 挪到 IO 线程**
                    //   改前是 `remember(tick) { CrashLogIO.summary(ctx) }` =>
                    //     那会在**主线程**读盘（最大 256 KiB）+ 切块解析
                    //   => 判据：**这个 App 的老毛病就是"主线程被占住"** =>
                    //     观测代码**不该再往主线程添一笔**（与"标记用 commit()"是同一个判据的两次应用）
                    //   而 `verdict`（上面那行）**留在主线程**是**有意的**：
                    //     它只读 4 个 prefs 键（已在内存里）=> 纳秒级；读盘的那一份（summary）才要挪
                    val crashSummary by produceState<String?>(null, tick) {
                        value = withContext(Dispatchers.IO) { CrashLogIO.summary(ctx) }
                    }
                    crashSummary?.let { line ->
                        Text(line, fontSize = 11.sp, color = Color(0xFF9E9E9E))
                        TextButton(onClick = { shareCrashLog(ctx, CrashLogIO.logFile(ctx)) }) {
                            Text("崩溃记录…", fontSize = 11.sp, color = Color(0xFF1565C0))
                        }
                    }
                    if (lastClear != null) {
                        Text(
                            "上次清空：${fmtAgo(lastClear.at)}（清掉 ${lastClear.count} 条，" +
                                "当时链尾 ${lastClear.tailHash.take(10)}…，第 ${lastClear.epoch} 段）",
                            fontSize = 11.sp, color = Color(0xFF9E9E9E),
                        )
                    }
                    // 这一段链的身份：清空后会换新（两段不会被当成同一条）
                    val cid = remember(tick) { store.chainIdentity() }
                    if (cid.started) {
                        Text(
                            "本段链：第 ${cid.epoch} 段（chainId ${cid.chainId}）",
                            fontSize = 10.sp, color = Color(0xFF9E9E9E),
                        )
                    }

                    //清空入口 —— 2026-09-28 移到这里，原因见下面这段注释
                    //   原来它排在【列表下面】：LazyColumn 没有 weight => 吃掉全部剩余空间
                    //      => 那一行被**挤出屏幕** => lmq 反馈「清空找不到」
                    //   判据：**隐蔽要有下限** —— 可以"不显眼"，但必须
                    //      「**在第一屏** 且 **不随内容被挤走**」
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "清空不可逆 会留痕（可查）",
                            fontSize = 10.sp, color = Color(0xFFBDBDBD),
                        )
                        if (!askClear) {
                            TextButton(onClick = { askClear = true }) {
                                Text("清空…", fontSize = 11.sp, color = Color(0xFF9E9E9E))
                            }
                        } else {
                            TextButton(onClick = { askThenClear() }) {
                                Text("确认清空", fontSize = 11.sp,
                                    color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    //导出：一个主按钮 + 两个出口
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TextButton(onClick = { tick++ }) { Text("刷新") }
                        TextButton(onClick = {
                            //2026-09-30（方案 C）：导出分成两步 —— 先定型（同步），
                            //   然后在 `BiometricPrompt` 的认证回调里签名（密钥每次使用都要认证）。
                            //   所以原来那一整段"写文件 + 打 zip + 分享提示"要**能从头调** =>
                            //   提成 afterSign 这个 lambda，两条路径（要认证 / 退化）都走它。
                            //  而真机上它 ANR 过一次：TEE 建密钥是**秒级**，落在主线程就超过
                            //      输入分发的 5 秒上限 => 现在重活全在 IO（见上面 scope/io 那两行注释）。
                            scope.launch {
                            val prepared = withContext(Dispatchers.IO) {
                                runCatching { store.prepareExport() }.getOrNull()
                            }
                            if (prepared == null) {
                                Toast.makeText(ctx, "导出失败：读不到记录", Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            val afterSign: suspend (com.liumiaoqi.notificationkeep.data.ExportSigner.Result, String?) -> Unit = { r, notice ->
                            val res = withContext(Dispatchers.IO) {
                            runCatching {
                                // 2026-09-28：`exportFileName()` 已从 NotifStore 搬到 data 包的顶层函数
                                //   （那是重构第二刀：它不引用任何实例状态 => 不该是实例方法）
                                //   而这次【编译器报出来了】—— 与 `esc` 那次不同：
                                //     那次 7 个调用点全在 NotifStore 内（同包）=> 零报错
                                //     这次调用方【跨包 + 通过实例】=> 所以必须改两处
                                val name = com.liumiaoqi.notificationkeep.data.exportFileName()
                                val text = store.finishExport(prepared, store.signingTailOf(r))
                                ctx.openFileOutput(name, Context.MODE_PRIVATE)
                                    .use { it.write(text.toByteArray(Charsets.UTF_8)) }
                                //再打一个 zip：证据包 + 核对器 + 说明 => 接收方能自己验
                                val zipName = com.liumiaoqi.notificationkeep.data.ExportBundle.bundleName(name)
                                val verifier = com.liumiaoqi.notificationkeep.data.verifierScript(ctx)
                                val shareName: String
                                val shareFile: File
                                if (verifier.isNotEmpty()) {
                                    ctx.openFileOutput(zipName, Context.MODE_PRIVATE).use { os ->
                                        com.liumiaoqi.notificationkeep.data.ExportBundle
                                            .writeTo(os, name, text, verifier)
                                    }
                                    shareName = zipName
                                    shareFile = File(ctx.filesDir, zipName)
                                } else {
                                    // 没带上核对器就退回发 jsonl —— 但要说出来（不静默）
                                    shareName = name
                                    shareFile = File(ctx.filesDir, name)
                                }
                                store.logOp(com.liumiaoqi.notificationkeep.data.OpsEvents.EXPORT, shareName)
                                Triple(shareName, verifier.isEmpty(), shareFile)
                            }
                            }   // <- withContext(IO) 到此
                            res.onSuccess { (shareName, noVerifier, f) ->
                                exportFile = f
                                Toast.makeText(
                                    ctx,
                                    "已导出：$shareName\n里面有【证据包 + 核对器 + 怎么验】=> 对方拿到就能自己验\n" +
                                        (if (noVerifier) "这次没带上核对器（assets 读取失败）=> 只发了数据\n" else "") +
                                        "下一步点「发送到…」发到自己邮箱 = 第三方时间锚",
                                    Toast.LENGTH_LONG
                                ).show()
                                // 方案 C 的验收 e「失效要显式」：降级必须**看得见**。
                                //   不能把它拼进上面那条 —— Toast 被系统截成两行，
                                //      拼在末尾等于看不见（是**截屏**抓到的，不是推理出来的）。
                                //   => 单独发一条短的（Toast 会排队，各自完整显示）。
                                notice?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                            }.onFailure { t ->
                                Toast.makeText(ctx, "导出失败：${t.message}", Toast.LENGTH_LONG).show()
                            }
                            }   // <- afterSign 到此结束（它是 suspend）

                            // 第二步：签名。方案 C 要求密钥**每次使用都认证** =>
                            //   这一步必须由用户在场（`BiometricPrompt` + CryptoObject）。
                            //   建密钥是**重活**（真机 TEE 里秒级）=> 走 IO；
                            //      而**弹认证必须在主线程** => 切回来再弹。
                            when (val b = withContext(Dispatchers.IO) {
                                com.liumiaoqi.notificationkeep.data.ExportSigner.beginAuth(prepared.selfHash)
                            }) {
                                is com.liumiaoqi.notificationkeep.data.ExportSigner.Begin.NeedsAuth -> {
                                    val prompt = android.hardware.biometrics.BiometricPrompt
                                        .Builder(activity)
                                        .setTitle("验证身份后导出")
                                        .setSubtitle("导出会写成证据文件，所以要确认是你本人在操作")
                                        .setAllowedAuthenticators(
                                            android.hardware.biometrics.BiometricManager
                                                .Authenticators.BIOMETRIC_STRONG or
                                                android.hardware.biometrics.BiometricManager
                                                    .Authenticators.DEVICE_CREDENTIAL,
                                        )
                                        .build()
                                    prompt.authenticate(
                                        b.cryptoObject,
                                        android.os.CancellationSignal(),
                                        // 回调走 **IO 线程**（不是直接执行器）：`finish()` 里要真签一次
                                        //   TEE 签名 —— 放在主线程上又是一次 ANR 风险
                                        io,
                                        object :
                                            android.hardware.biometrics.BiometricPrompt
                                                .AuthenticationCallback() {
                                            override fun onAuthenticationSucceeded(
                                                result: android.hardware.biometrics.BiometricPrompt
                                                    .AuthenticationResult,
                                            ) {
                                                // 这个回调已经在 IO 线程上 => 直接签，再切回主线程收尾
                                                val r = b.finish()
                                                scope.launch { afterSign(r, null) }
                                            }

                                            override fun onAuthenticationError(
                                                code: Int,
                                                msg: CharSequence,
                                            ) {
                                                // 认证没通过 => **没有导出**。这不是失败，是这层的目的：
                                                //   它让"进程内有执行权的人静默签一份"这件事做不成（见决策记录）。
                                                scope.launch {
                                                    Toast.makeText(
                                                        activity,
                                                        "没有认证 => 没有导出" +
                                                            "（这正是不让它被静默签名的目的）\n$msg",
                                                        Toast.LENGTH_LONG,
                                                    ).show()
                                                }
                                            }
                                        },
                                    )
                                }
                                is com.liumiaoqi.notificationkeep.data.ExportSigner.Begin.Done ->
                                    afterSign(b.result, b.notice)
                                is com.liumiaoqi.notificationkeep.data.ExportSigner.Begin.Failed ->
                                    Toast.makeText(
                                        ctx, "导出失败：${b.reason}", Toast.LENGTH_LONG,
                                    ).show()
                            }
                            }   // <- scope.launch 到此
                        }) { Text("导出") }

                        if (exportFile != null) {
                            // 主出口：系统分享面板 => 邮件 / 微信 / 网盘（联网的是它们，不是我们）
                            TextButton(onClick = { shareExport(ctx, exportFile!!) }) {
                                Text("发送到…", fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                            }
                            // 备选：存到用户选的位置（不需要存储权限）—— 存的是**完整 zip**
                            TextButton(onClick = { saveLauncher.launch(exportFile?.name ?: com.liumiaoqi.notificationkeep.data.exportFileName()) }) {
                                Text("另存为…", color = Color(0xFF1565C0))
                            }
                        }
                    }
                    Text(
                        "无网络权限 数据只在本机 发送是交给别的 App 做",
                        fontSize = 11.sp, color = Color.Gray,
                    )
                }
            }

            //极简筛选
            //极简筛选（C3：把文档里承诺的三个入口都接上）
            //   用横向滚动而不是换行：App 数量不可控，
            //      换行会让筛选行占掉半屏 => 一行可横滑更克制
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                TextButton(onClick = { view = VIEW_ALL }) { Text(if (view == VIEW_ALL) "全部" else "全部") }
                TextButton(onClick = { view = VIEW_SOCIAL }) {
                    Text(if (view == VIEW_SOCIAL) "只看社交" else "只看社交")
                }
                // 各 App 带计数（按条数降序）—— 只列前 8 个，免得一行太长
                for ((pkg, n) in pkgCounts.take(8)) {
                    TextButton(onClick = { view = pkg }) {
                        Text(if (view == pkg) "${shortPkg(pkg)} $n" else "${shortPkg(pkg)} $n")
                    }
                }
                TextButton(onClick = { foldDup = !foldDup }) { Text(if (foldDup) "折叠" else "折叠") }
            }

            Column(Modifier.fillMaxSize()) {
                if (shown.isEmpty()) {
                    Text("还没有记录", color = Color.Gray)
                } else {
                    // 必须带 weight —— 否则列表会吃掉全部剩余空间，把它【下面】的东西挤出屏幕
                    //    （这就是「清空找不到」的根因，2026-09-28 实测）
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(shown.size) { i ->
                            val (e, n) = shown[i]
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "${if (e.event == "REMOVED") "<-" else ""} ${shortPkg(e.pkg)}",
                                    color = when {
                                        e.event == "REMOVED" -> Color(0xFF6A1B9A)
                                        e.looksLikeRecall -> Color(0xFFB26A00)
                                        else -> Color(0xFF00695C)
                                    },
                                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                )
                                Text("${e.title} ${e.text}".trim().take(90), fontSize = 13.sp)
                                if (n > 1) Text("×$n", fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                    }
                }

                // 这里【原来】是"清空…"的位置 —— 它在列表下方，会被挤出屏幕
                //    => 已移到上面的状态面板里（那里一定在第一屏）
            }
        }
    }
}

/**
 * **把证据包交给别的 App 发**（邮件 / 微信 / 网盘 …）。
 *
 * 三条安全边界（都在这一天里想清楚了才做）：
 *   1 provider `exported=false` => 别的 App 不能随便读
 *   2 只在**用户点了这一下**的时候授权（`FLAG_GRANT_READ_URI_PERMISSION`）
 *   3 只给**这一个文件**的临时读权限（数据库不在白名单里）
 * => 而**本 App 依旧不联网**（联网的是用户选的那个 App）
 */
private fun shareExport(ctx: Context, f: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            //2026-09-28 修（D3）：发出去的是 **zip** => 类型必须说 zip
            //    改前写 `application/json` => 接收方（邮件客户端等）会**按 JSON 处理它**
            //    而 zip 头是 `50 4b 03 04` => 类型与内容不符时，对方要么拒收、要么打不开
            //    与上面 SAF 那处（`CreateDocument`）是**同一个判据的两次应用**
            //2026-09-28 修（P5 来自独立复核）：**按实际文件名派生，不再硬编码 zip**
            //    改前这里写死 `application/zip`，而**没带上核对器时发的是 `.jsonl`**
            //    （见上面 `verifier.isEmpty()` 那条分支）
            //    => 那条路上类型与内容**不符** —— 方向与修前**正好相反**
            //    判据：声明要与**手里那个文件**一致，而不是与"通常是什么"一致
            type = if (f.name.endsWith(".zip")) "application/zip" else "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "通知留存（NK）导出：${f.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "把证据包发到…（选邮件 => 发给自己）"))
    }.onFailure { t ->
        Toast.makeText(ctx, "无法打开分享面板：${t.message}", Toast.LENGTH_LONG).show()
    }
}

/**
 *2026-10-15 加：把**崩溃记录**交给别的 App（发给自己 / 存网盘 => 顺带也是"外部时间锚"）。
 *
 ***为什么不复用 [shareExport]**（这是本项目的既有判据，不是洁癖）：
 *   `shareExport` 按**文件名后缀**推 MIME（`.zip` => zip，否则 `application/json`）——
 *   而 `crash.log` **既不是 zip 也不是 JSON** => 复用就会**声明错类型**
 *   而"声明与内容不符"**本项目已经栽过一次**（P5 那次：写死 zip、而实际发的是 jsonl）
 *   => 判据（`coding-discipline` §二）：**宁可新写一个平行函数，也不改已验证的老路径**
 *     —— 导出那条路是**证据链**，一个字都不该动
 *
 * 安全性照旧（与 `shareExport` 同一套边界）：provider `exported=false`
 *   只给**这一个文件**的临时读权限 **本 App 依旧不联网**（联网的是用户选的那个 App）
 */
private fun shareCrashLog(ctx: Context, f: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            // 类型与**手里那个文件**一致：它是纯文本（不是 zip、也不是 json）
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "通知留存（NK）崩溃记录：${f.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "把崩溃记录发到…（选邮件 => 发给自己）"))
    }.onFailure { t ->
        Toast.makeText(ctx, "无法打开分享面板：${t.message}", Toast.LENGTH_LONG).show()
    }
}

/** "多久以前"（纯函数：`now` 由调用方传 => 可测） */
internal fun fmtAgo(ms: Long, now: Long = System.currentTimeMillis()): String {
    val d = now - ms
    return when {
        d < 0 -> "（时间在未来？）"
        d < 60_000 -> "刚刚"
        d < 3_600_000 -> "${d / 60_000} 分钟前"
        d < 86_400_000 -> "${d / 3_600_000} 小时前"
        else -> "${d / 86_400_000} 天前"
    }
}

/** 绝对时间（与"多久前"配合看） */
internal fun fmtTime(ms: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(ms))

private fun shortPkg(p: String): String = when (p) {
    "com.tencent.mm" -> "微信"
    "com.tencent.mobileqq" -> "QQ"
    "com.tencent.tim" -> "TIM"
    "com.android.mms" -> "短信"
    "com.android.shell" -> "shell"
    "com.xiaomi.finddevice" -> "查找设备"
    "com.liumiaoqi.notificationkeep" -> "（本应用）"
    else -> p.substringAfterLast('.')
}
