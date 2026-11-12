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

    fun isToday(dateString: String?): Boolean {
        if (dateString.isNullOrBlank()) return false
        val today = todayDate()
        return dateString.startsWith(today)
    }

    fun isThisWeek(dateString: String?): Boolean {
        if (dateString.isNullOrBlank()) return false
        return try {
            val dateStr = if (dateString.length >= 10) dateString.substring(0, 10) else dateString
            val sdf = SimpleDateFormat(DATE_ONLY_FORMAT, Locale.ENGLISH).apply { timeZone = kolkataTimeZone }
            val billDate = sdf.parse(dateStr) ?: return false
            val todayDateObj = sdf.parse(todayDate()) ?: return false

            val diffMillis = todayDateObj.time - billDate.time
            // Within last 7 days and not in the future (with 1 day buffer for timezone differences)
            diffMillis in -(24L * 60 * 60 * 1000)..(7L * 24 * 60 * 60 * 1000)
        } catch (e: Exception) {
            false
        }
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

    fun addDaysToDate(dateString: String, days: Int): String {
        return try {
            val sdf = SimpleDateFormat(DATE_ONLY_FORMAT, Locale.ENGLISH).apply { timeZone = kolkataTimeZone }
            val date = sdf.parse(dateString) ?: Date()
            val cal = java.util.Calendar.getInstance(kolkataTimeZone).apply {
                time = date
                add(java.util.Calendar.DAY_OF_YEAR, days)
            }
            sdf.format(cal.time)
        } catch (e: Exception) {
            dateString
        }
    }

    fun isDatePassed(dueDateString: String?): Boolean {
        if (dueDateString.isNullOrBlank()) return false
        return try {
            val sdf = SimpleDateFormat(DATE_ONLY_FORMAT, Locale.ENGLISH).apply { timeZone = kolkataTimeZone }
            val dueDate = sdf.parse(dueDateString) ?: return false
            val today = sdf.parse(todayDate()) ?: return false
            dueDate.before(today)
        } catch (e: Exception) {
            false
        }
    }

    fun todayFormattedMarathi(): String {
        return try {
            val sdf = SimpleDateFormat("dd MMMM yyyy", Locale("mr", "IN")).apply {
                timeZone = kolkataTimeZone
            }
            sdf.format(Date())
        } catch (e: Exception) {
            SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).format(Date())
        }
    }
}
