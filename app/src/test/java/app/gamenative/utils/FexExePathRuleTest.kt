package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FexExePathRuleTest {

    @Test
    fun isAffectedVersion_coversSeries2601To2608() {
        for (version in listOf("2601", "2603", "2604", "2605", "2601-217d039-0", "2607-0", "2608-0")) {
            assertTrue(version, FexExePathRule.isAffectedVersion(version))
        }
        for (version in listOf("2507", "2508", "2511", "2512", "2609-0", "2610-gn-4", "", "fex", "26010")) {
            assertFalse(version, FexExePathRule.isAffectedVersion(version))
        }
    }

    @Test
    fun isAffectedPath_rootExeWithLongPath() {
        // Wolfenstein: The New Order crashed on FEX 2603 with this path (docs/fork-plan.md T12).
        assertTrue(FexExePathRule.isAffectedPath("A:\\WolfNewOrder_x64.exe"))
        assertTrue(FexExePathRule.isAffectedPath("A:\\Bin\\VeryLongGameName.exe"))
        // Disco Elysium ran on the same FEX: 20 bytes fit the inline string buffer.
        assertFalse(FexExePathRule.isAffectedPath("A:\\Disco Elysium.exe"))
        // 22 bytes: still inline.
        assertFalse(FexExePathRule.isAffectedPath("A:\\WolfNewOrder_x6.exe"))
        // File name starts at byte 8, past the bytes the allocator overwrites.
        assertFalse(FexExePathRule.isAffectedPath("A:\\Game\\WolfNewOrder_x64.exe"))
        assertFalse(FexExePathRule.isAffectedPath("C:\\Program Files (x86)\\Steam\\steamapps\\common\\G\\G.exe"))
    }

    @Test
    fun isAffectedPath_countsUtf8Bytes() {
        // 21 chars, 24 bytes: FEX converts the path to UTF-8 before taking the base name.
        assertTrue(FexExePathRule.isAffectedPath("A:\\Spiel_Größe_xé.exe"))
    }

    @Test
    fun gameDrivePath_matchesLauncher() {
        assertEquals(
            "A:\\WolfNewOrder_x64.exe",
            FexExePathRule.gameDrivePath("/data/Epic/games/a64d/WolfNewOrder_x64.exe", "/data/Epic/games/a64d"),
        )
        assertEquals("A:\\Bin\\Game.exe", FexExePathRule.gameDrivePath("Bin/Game.exe", "/data/Epic/games/x"))
        assertEquals("A:\\Bin\\Game.exe", FexExePathRule.gameDrivePath("Bin/Game.exe"))
        assertEquals("D:\\Games\\X.exe", FexExePathRule.gameDrivePath("D:\\Games\\X.exe", "/data"))
    }
}
