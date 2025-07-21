package com.protective.ebillyocr
import java.text.SimpleDateFormat
import java.util.*

object DateUtils {
    fun isWithinDateRange(startDate: String?, endDate: String?): Boolean {
        if (startDate.isNullOrEmpty() || endDate.isNullOrEmpty()) return false

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        return try {
            val start = dateFormat.parse(startDate)
            val end = dateFormat.parse(endDate)
            val today = getTodayWithoutTime()

            if (start == null || end == null) return false

            // Check if today is within the range (start <= today <= end)
            !today.before(start) && !today.after(end)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun getTodayWithoutTime(): Date {
        val calendar = Calendar.getInstance()
        calendar[Calendar.HOUR_OF_DAY] = 0
        calendar[Calendar.MINUTE] = 0
        calendar[Calendar.SECOND] = 0
        calendar[Calendar.MILLISECOND] = 0
        return calendar.time
    }
}
