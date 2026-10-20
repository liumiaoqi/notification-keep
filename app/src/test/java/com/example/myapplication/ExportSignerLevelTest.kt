package com.example.myapplication

import android.security.keystore.KeyProperties
import com.example.myapplication.data.ExportSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 *档位可读名的测试（纯 JVM 不需要 Android 设备）
 *
 * 它押的是一条**真缺陷**（2026-09-28 独立复核报的）：
 *   `levelName` 原来是 `… TEE -> "tee"` **`else -> "software"`**
 *   而 `levelOf()` 读失败时给的是 **`-1`**（它有意让"读不到"可表达）
 *   =>**`-1` 落进那个 `else` => 被说成 `"software"`**
 *     => **即："读不到"与"真的是软件档"在产物里【同形】**
 *       => 而那与 `ExportSigner` 里那句"**用读回来的数字、不假装**"**相反**
 *
 * 判据（每条都对着一个具体后果）：
 *   1 `levelName(-1)` **不能**是 `"software"` —— 那正是那条缺陷
 *   2 `levelName(-1)` 该是 `"unavailable"` —— 与 `NotifStore` 那个 `?: "unavailable"` 同名
 *   3 **软件档【仍然】叫 `"software"`** —— 修它不能把真信息也改掉
 *   4 三个已知档位必须**互不相同**（否则产物里分不开）
 */
class ExportSignerLevelTest {

    @Test
    fun readFailure_isNotReportedAsSoftware() {
        //这是核心判据：-1 是 levelOf() 的"读不到"，不是"软件档"
        assertNotEquals(
            "读不到（-1）不能说成 software —— 那正是这条缺陷",
            "software",
            ExportSigner.levelName(-1),
        )
    }

    @Test
    fun readFailure_usesTheSameNameAsMissingEntry() {
        // 同一个概念用同一个名字（NotifStore 里 entry 缺失时写的是 "unavailable"）
        assertEquals("unavailable", ExportSigner.levelName(-1))
    }

    @Test
    fun softwareLevel_stillSaysSoftware() {
        // 修那条缺陷【不能】把真的软件档也一起改掉
        assertEquals("software", ExportSigner.levelName(KeyProperties.SECURITY_LEVEL_SOFTWARE))
    }

    @Test
    fun teeAndStrongbox_areNamed() {
        assertEquals("tee", ExportSigner.levelName(KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT))
        assertEquals("strongbox", ExportSigner.levelName(KeyProperties.SECURITY_LEVEL_STRONGBOX))
    }

    @Test
    fun theFourNamesAreDistinct() {
        // 四个名字必须互不相同 => 否则产物里分不开
        val names = listOf(
            KeyProperties.SECURITY_LEVEL_SOFTWARE,
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
            KeyProperties.SECURITY_LEVEL_STRONGBOX,
            -1,
        ).map { ExportSigner.levelName(it) }
        assertEquals("四个档位名必须互不相同", 4, names.toSet().size)
    }

    @Test
    fun unknownPositiveValue_isAlsoUnavailable() {
        // 未来 Android 若加了新档位（例如 4）=> 该如实说"不认得"，而不是猜成 software
        assertEquals("unavailable", ExportSigner.levelName(4))
    }
}
