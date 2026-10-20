package com.liumiaoqi.notificationkeep

import android.app.Application
import com.liumiaoqi.notificationkeep.crash.CrashLogIO

/**
 ***崩溃可观测性的生产接线点**（2026-10-15 加）。
 *
 ***本文件存在的唯一理由：`Thread.setDefaultUncaughtExceptionHandler` 必须有人装**
 *   `coding-discipline` §三.10 那条判据正是从这里来的：
 *   **"定义了没人调 = 静默失效"** —— 光把 `CrashLogIO.install()` 写好**等于没做**
 *   => 所以本类必须在 `AndroidManifest.xml` 的 `<application>` 上登记
 *     （`android:name=".NkApplication"`）—— **少了那一行，本类根本不会被创建**
 *
 * **`onCreate` 里刻意只做两件事**（别的都别往这儿加）：
 *   这是个**应用级**入口 => 它抛异常 = **整个 App 起不来**（比崩溃更糟）
 *   而这个 App 的老问题是"**启动时主线程被占住**"（真机 6 次 ANR）
 *   => 所以这里**不许**出现：文件 IO、binder、建库、任何循环
 *     （真正的重活都在 [CrashLogIO.onAppStart] 里丢给后台线程了）
 */
class NkApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 1 **最先**装捕获 —— 这样连"下面那一步抛异常"都能留下现场
        CrashLogIO.install(this)
        // 2 结算上一次 + 记这一次 + 后台读系统的退出记录（IO 全在后台线程）
        CrashLogIO.onAppStart(this)
    }
}
