package com.liumiaoqi.notificationkeep

import com.liumiaoqi.notificationkeep.crash.StartupSentinel
import com.liumiaoqi.notificationkeep.crash.StartupSentinel.Stage
import com.liumiaoqi.notificationkeep.crash.StartupSentinel.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *启动哨兵的测试（纯 JVM 不需要 Android）
 *
 ***最要紧的一条在 `stuckAtAuthPromptedIsBenign`**：
 *   用户打开 App、看到认证页、**没按就退了** => 那是**用户行为**，不是崩溃
 *   => 若把它算成异常 => **面板天天误报** => 人就再也不看这个面板了
 *   => 所以这一条**必须**被钉住（它是"报还是不报"的分界线）
 *
 * 另两条同样重要：
 *   `everyStageNameSurvivesTheStoredNameRoundTrip` —— 真正存进 prefs 的是**名字字符串**
 *     => "名字 ↔ 判定"这张表**每一格**都要对得上（改了枚举名却忘了判据 => 这条会红）
 *   `unknownStageNameIsBenign` —— 认不得的名字**宁可漏报也不误报**
 *
 * **测不到的部分（如实写）**：
 *   "记号**真的**落到了 `nk_crash` prefs 里" => 要 Context => 真机验
 *   "`commit()` 的**同步**落盘在硬杀前是否来得及" => 运行时行为 => 端到端验
 *     （改前这里写的是 `apply()` —— 那是**第一版**的做法，已按真机对照实验改回**有意同步写**
 *      理由见 `CrashLogIO.mark` 的注释）
 */
class StartupSentinelTest {

    private val t0 = 1_760_000_000_000L

    private fun describe(v: Verdict, now: Long = t0 + 120_000L) = StartupSentinel.describe(v, now)

    //不报的三种

    @Test
    fun firstRunIsSilent() {
        assertEquals(Verdict.FirstRun, StartupSentinel.verdict(null, t0))
        assertNull("首次运行 => 一个字都不显示", describe(Verdict.FirstRun))
    }

    @Test
    fun recordedStageWithZeroStartIsStillFirstRun() {
        // 有阶段名但**没有起点** => 说明记号不完整 => 当首次（不猜）
        assertEquals(Verdict.FirstRun, StartupSentinel.verdict(Stage.CONTENT_SHOWN.name, 0L))
    }

    @Test
    fun completedIsSilent() {
        val v = StartupSentinel.verdict(Stage.CONTENT_SHOWN.name, t0)
        assertEquals(Verdict.Completed, v)
        assertNull("上次走到内容了 => 正常 => 不显示", describe(v))
    }

    //报 / 不报 的分界线

    @Test
    fun stuckAtAuthPromptedIsBenign_notABug() {
        //用户没按认证就退了 => **不是崩溃** => **不许报警**
        val v = StartupSentinel.verdict(Stage.AUTH_PROMPTED.name, t0)
        assertEquals("停在认证页必须算【正常】", Verdict.Benign, v)
        assertNull("也不许显示任何东西", describe(v))
    }

    @Test
    fun stuckAtGateShownIsBenign_notABug() {
        //2026-10-15（独立复审 P2-3）：**"停在闸门"是最常见的良性路径之一**
        //   初版把它当异常 => **每格都报 => 真 ANR 会被淹没在噪声里**
        //   => 这条是"**不误报**"的正例：它必须**安静**
        val v = StartupSentinel.verdict(Stage.GATE_SHOWN.name, t0)
        assertEquals("停在闸门必须算【正常】", Verdict.Benign, v)
        assertNull("也不许显示任何东西", describe(v))
    }

    @Test
    fun unknownStageNameIsBenign_soWeNeverCryWolf() {
        val v = StartupSentinel.verdict("some_future_stage", t0)
        assertEquals("认不得的阶段 => 不报警（宁可漏报不误报）", Verdict.Benign, v)
        assertNull(describe(v))
    }

    @Test
    fun benignHappensExactlyForTheTwoDoorStages() {
        // 闭集里**只有"用户还站在门口"的两格**是"正常但没走完" —— 多一格就会天天误报
        val benign = Stage.values().filter { StartupSentinel.verdict(it.name, t0) is Verdict.Benign }
        assertEquals("实际：$benign", listOf(Stage.GATE_SHOWN, Stage.AUTH_PROMPTED), benign)
    }

    //报的三种（都在"出内容之前"）

    @Test
    fun stuckAtProcessStartIsIncomplete() {
        assertTrue(
            "进程起来了却什么都没建 => 报警",
            StartupSentinel.verdict(Stage.PROCESS_START.name, t0) is Verdict.Incomplete,
        )
    }

    @Test
    fun stuckAtActivityCreateIsIncomplete() {
        // 本次真机 ANR 的签名（`Waited 5000ms for FocusEvent`）正落在这里
        assertTrue(
            "Activity 建好了却没出内容 => 报警",
            StartupSentinel.verdict(Stage.ACTIVITY_CREATE.name, t0) is Verdict.Incomplete,
        )
    }

    // 曾有一条 `stuckAtGateShownIsIncomplete` —— **2026-10-15 删掉**（P2-3 的"反"那一半）：
    //   它的判据被推翻了 => 现在同一格由 `stuckAtGateShownIsBenign_notABug` 押**相反**的结论
    //   留着它会**两条用例互相打架** => 删掉比"改断言"更清楚（免得后人读到一句已废的理由）

    //跨进程边界：名字 ↔ 判定 每一格都要对得上

    @Test
    fun everyStageNameSurvivesTheStoredNameRoundTrip() {
        //真正存进 prefs 的是 `Stage.name`（字符串）=> 这条押的就是那张表
        for (s in Stage.values()) {
            assertNotEquals(
                "阶段 ${s.name} 的名字必须被认得（认不得 => 哨兵静默失效）",
                Verdict.FirstRun,
                StartupSentinel.verdict(s.name, t0),
            )
        }
    }

    //文案

    @Test
    fun describeNamesTheStageAndHowLongAgo() {
        val v = StartupSentinel.verdict(Stage.ACTIVITY_CREATE.name, t0)
        val s = describe(v, t0 + 120_000L)!!
        assertTrue("必须点名停在哪一步 => $s", s.contains(Stage.ACTIVITY_CREATE.name))
        assertTrue("必须带上'多久以前' => $s", s.contains("2 分钟前"))
    }

    @Test
    fun describeIsNullForEveryNonIncompleteVerdict() {
        val all = listOf(
            Verdict.FirstRun,
            Verdict.Completed,
            Verdict.Benign,
            // Incomplete 是唯一**该有**文案的那一格 => 它不该是 null
            Verdict.Incomplete(Stage.ACTIVITY_CREATE, t0),
        )
        for (v in all) {
            if (v is Verdict.Incomplete) assertTrue(describe(v) != null) else assertNull(describe(v))
        }
    }
}
