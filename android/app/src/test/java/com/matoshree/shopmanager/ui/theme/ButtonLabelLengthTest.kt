package com.matoshree.shopmanager.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test verifying that every registered button and action label
 * in AppLabels has at most 2 words (P2-H requirement).
 */
class ButtonLabelLengthTest {

    @Test
    fun testAllRegisteredLabelsHaveAtMostTwoWords() {
        val fields = AppLabels::class.java.declaredFields
        var checkedCount = 0
        for (f in fields) {
            f.isAccessible = true
            val value = f.get(null) as? String ?: continue
            val wordCount = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
            assertTrue(
                "Label '$value' (field ${f.name}) has $wordCount words; must be <= 2 words",
                wordCount in 1..2
            )
            checkedCount++
        }
        assertTrue("At least 20 labels must be checked", checkedCount >= 20)
    }
}
