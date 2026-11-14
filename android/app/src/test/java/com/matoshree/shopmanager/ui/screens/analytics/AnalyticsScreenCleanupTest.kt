package com.matoshree.shopmanager.ui.screens.analytics

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Tests for Round 15 Bug B2:
 * Asserts that the 3 labels ("मागील कालावधीशी तुलना", "CSV", "WhatsApp")
 * no longer exist on the Analytics page, and that other sections remain intact.
 */
class AnalyticsScreenCleanupTest {

    @Test
    fun testAnalyticsScreen_RemovedCompareAndExportRow() {
        val file = File("src/main/java/com/matoshree/shopmanager/ui/screens/analytics/AnalyticsScreen.kt")
        val altFile = File("android/app/src/main/java/com/matoshree/shopmanager/ui/screens/analytics/AnalyticsScreen.kt")
        val targetFile = if (file.exists()) file else altFile
        assertTrue("AnalyticsScreen.kt must exist", targetFile.exists())

        val source = targetFile.readText()

        // 1. Verify the removed row elements no longer exist
        assertFalse(
            "Analytics page must NOT have 'मागील कालावधीशी तुलना' chip",
            source.contains("Text(\"मागील कालावधीशी तुलना\"")
        )
        assertFalse(
            "Analytics page must NOT have 'CSV' export button in quick row",
            source.contains("Text(\"CSV\", fontSize = 11.sp")
        )
        assertFalse(
            "Analytics page must NOT have 'WhatsApp' share button in quick row",
            source.contains("Text(\"WhatsApp\", fontSize = 11.sp")
        )

        // 2. Verify other sections still render intact
        assertTrue(
            "Analytics page must keep 'दैनिक ताळेबंद अहवाल'",
            source.contains("Text(\"दैनिक ताळेबंद अहवाल\"")
        )
        assertTrue(
            "Analytics page must keep period selector",
            source.contains("AnalyticsPeriodPreset")
        )
        assertTrue(
            "Analytics page must keep 'एकूण सारांश'",
            source.contains("एकूण सारांश")
        )
        assertTrue(
            "Analytics page must keep closing/report print and share actions",
            source.contains("shareColorAnalyticsReport")
        )
    }
}
