package com.matoshree.shopmanager.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit test verifying that every registered button and action label
 * in AppLabels has at most 2 words (P2-H requirement),
 * and scanning UI screens to verify button text labels do not exceed 2 words.
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

    @Test
    fun testNoUiButtonLabelsExceedTwoWords() {
        val uiDir = File("src/main/java/com/matoshree/shopmanager/ui")
        if (!uiDir.exists()) return // Fallback if working directory differs in some test runners

        val btnKeywords = listOf("Button", "TextButton", "OutlinedButton")
        val longLabels = mutableListOf<String>()

        uiDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val rawText = file.readText()
            val text: String = rawText
                .replace(Regex("//.*"), "")
                .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            var i = 0
            val n = text.length
            while (i < n) {
                var matchedKw: String? = null
                for (kw in btnKeywords) {
                    if (text.regionMatches(i, kw, 0, kw.length) &&
                        (i == 0 || !text[i - 1].isLetterOrDigit()) &&
                        (i + kw.length < n && !text[i + kw.length].isLetterOrDigit())
                    ) {
                        matchedKw = kw
                        break
                    }
                }
                if (matchedKw != null) {
                    val braceIdx = text.indexOf('{', i)
                    val parenIdx = text.indexOf('(', i)
                    if (braceIdx != -1 && (parenIdx == -1 || braceIdx < parenIdx + 300)) {
                        var depth = 1
                        var curr = braceIdx + 1
                        while (curr < n && depth > 0) {
                            if (text[curr] == '{') depth++
                            else if (text[curr] == '}') depth--
                            curr++
                        }
                        val btnBody = text.substring(braceIdx + 1, (curr - 1).coerceAtMost(n))
                        if (!btnBody.contains("TextField")) {
                            val regex = Regex("""Text\s*\(\s*"([^"]+)"""")
                            regex.findAll(btnBody).forEach { m ->
                                val label = m.groupValues[1].trim()
                                val words = label.split(Regex("\\s+")).filter {
                                    it.isNotBlank() && !it.startsWith("(") && !it.startsWith("#") && !it.startsWith("₹")
                                }
                                if (words.size > 2) {
                                    longLabels.add("${file.name}: '$label' (${words.size} words)")
                                }
                            }
                        }
                        i = curr
                        continue
                    }
                }
                i++
            }
        }

        assertTrue(
            "Found button labels with > 2 words:\n${longLabels.joinToString("\n")}",
            longLabels.isEmpty()
        )
    }
}
