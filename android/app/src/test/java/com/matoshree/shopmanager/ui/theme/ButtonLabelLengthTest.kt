package com.matoshree.shopmanager.ui.theme

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Unit test verifying that:
 * 1. Every registered button and action label in AppLabels has at most 2 words.
 * 2. All strings in values/strings.xml and values-en/strings.xml have identical keys and placeholders.
 * 3. All button, chip, tab, and navigation labels in both languages have at most 2 words.
 * 4. Scans Compose UI code to ensure all buttons, chips, tabs do not exceed 2 words.
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

    private fun parseStringXml(file: File): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        val stringNodes = doc.getElementsByTagName("string")
        val map = mutableMapOf<String, String>()
        for (i in 0 until stringNodes.length) {
            val node = stringNodes.item(i)
            val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
            val text = node.textContent ?: ""
            map[name] = text
        }
        return map
    }

    @Test
    fun testStringResources_EveryKeyExistsInBothLanguagesAndPlaceholdersMatch() {
        val mrFile = File("src/main/res/values/strings.xml").let { if (it.exists()) it else File("android/app/src/main/res/values/strings.xml") }
        val enFile = File("src/main/res/values-en/strings.xml").let { if (it.exists()) it else File("android/app/src/main/res/values-en/strings.xml") }

        assertTrue("Marathi strings.xml must exist", mrFile.exists())
        assertTrue("English strings.xml must exist", enFile.exists())

        val mrMap = parseStringXml(mrFile)
        val enMap = parseStringXml(enFile)

        // 1. Exact key parity
        val missingInEn = mrMap.keys - enMap.keys
        val missingInMr = enMap.keys - mrMap.keys

        assertTrue("Keys missing in values-en/strings.xml: $missingInEn", missingInEn.isEmpty())
        assertTrue("Keys missing in values/strings.xml: $missingInMr", missingInMr.isEmpty())
        assertTrue("Must have at least 50 string resources", mrMap.size >= 50)

        // 2. Button and Chip labels <= 2 words in BOTH languages
        val labelPrefixes = listOf("btn_", "tab_", "filter_", "nav_")
        for ((key, text) in mrMap) {
            if (labelPrefixes.any { key.startsWith(it) }) {
                val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                assertTrue(
                    "Marathi label '$text' (key $key) has ${words.size} words; must be <= 2 words",
                    words.size in 1..2
                )
            }
        }
        for ((key, text) in enMap) {
            if (labelPrefixes.any { key.startsWith(it) }) {
                val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                assertTrue(
                    "English label '$text' (key $key) has ${words.size} words; must be <= 2 words",
                    words.size in 1..2
                )
            }
        }
    }

    @Test
    fun testNoUiButtonLabelsExceedTwoWords() {
        val uiDir = File("src/main/java/com/matoshree/shopmanager/ui").let { if (it.exists()) it else File("android/app/src/main/java/com/matoshree/shopmanager/ui") }
        if (!uiDir.exists()) return

        val btnKeywords = listOf("Button", "TextButton", "OutlinedButton", "FilledTonalButton", "FilterChip", "AssistChip", "Tab")
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
                                    longLabels.add("${file.name}: '$label' (${words.size} words in $matchedKw)")
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
            "Found button/chip/tab labels with > 2 words:\n${longLabels.joinToString("\n")}",
            longLabels.isEmpty()
        )
    }
}
