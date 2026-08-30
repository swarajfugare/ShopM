package com.matoshree.shopmanager.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DateUtils {
    private const val ISO_FORMAT = "yyyy-MM-dd HH:mm:ss"
    private const val DATE_ONLY_FORMAT = "yyyy-MM-dd"
    private const val DISPLAY_FORMAT = "dd MMM yyyy, hh:mm a"
    private const val TIME_ONLY_FORMAT = "HH:mm"

    val kolkataTimeZone: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    fun nowIso(): String {
        val sdf = SimpleDateFormat(ISO_FORMAT, Locale.ENGLISH).apply {
            timeZone = kolkataTimeZone
        }
        return sdf.format(Date())
    }

    fun todayDate(): String {
        val sdf = SimpleDateFormat(DATE_ONLY_FORMAT, Locale.ENGLISH).apply {
            timeZone = kolkataTimeZone
        }
        return sdf.format(Date())
    }

    fun formatForDisplay(isoString: String?): String {
        if (isoString.isNullOrBlank()) return ""
        return try {
            val parseSdf = SimpleDateFormat(ISO_FORMAT, Locale.ENGLISH).apply { timeZone = kolkataTimeZone }
            val date = parseSdf.parse(isoString) ?: return isoString
            val displaySdf = SimpleDateFormat(DISPLAY_FORMAT, Locale.ENGLISH).apply { timeZone = kolkataTimeZone }
            displaySdf.format(date)
        } catch (e: Exception) {
            isoString
        }
    }

    fun formatTimeOnly(isoString: String?): String {
        if (isoString.isNullOrBlank()) return ""
        return try {
            val parseSdf = SimpleDateFormat(ISO_FORMAT, Locale.ENGLISH).apply { timeZone = kolkataTimeZone }
            val date = parseSdf.parse(isoString) ?: return isoString
            val timeSdf = SimpleDateFormat(TIME_ONLY_FORMAT, Locale.ENGLISH).apply { timeZone = kolkataTimeZone }
            timeSdf.format(date)
        } catch (e: Exception) {
            isoString
        }
    }
}
