package com.matoshree.shopmanager.security

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashReporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testCrashReportRecordingAndReading() {
        val crashFile = File(tempFolder.root, "last_crash.txt")
        val dummyThread = Thread.currentThread()
        val dummyException = IllegalStateException("Test crash message: pin=1234 and token=secretToken123")

        CrashReporter.recordCrashToFile(crashFile, dummyThread, dummyException, "com.matoshree.collection")

        assertTrue("Crash file should exist", crashFile.exists())
        val report = CrashReporter.getCrashFromFile(crashFile)
        assertNotNull("Report should be readable", report)

        assertTrue(report!!.contains("=== MATOSHREE COLLECTION CRASH REPORT ==="))
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("Test crash message"))

        // Verify sensitive data was redacted
        assertTrue("PIN should be redacted", report.contains("pin=[REDACTED]"))
        assertFalse("Raw PIN should not exist", report.contains("pin=1234"))
        assertTrue("Token should be redacted", report.contains("token=[REDACTED]"))
        assertFalse("Raw token should not exist", report.contains("secretToken123"))

        // Verify clearing crash file
        val cleared = CrashReporter.clearCrashFile(crashFile)
        assertTrue(cleared)
        assertNull(CrashReporter.getCrashFromFile(crashFile))
    }
}
