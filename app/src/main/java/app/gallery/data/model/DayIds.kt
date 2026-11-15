package app.gallery.data.model

import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Memories groups photos by "dayid": the local calendar date of capture as days since 1970-01-01
 * (local wall-clock time treated as UTC). Phone items use the same scheme so both sources share
 * day groups.
 */
object DayIds {
    private const val DAY_MS = 86_400_000L

    fun fromUtcMillis(utcMs: Long, tz: TimeZone = TimeZone.getDefault()): Int =
        Math.floorDiv(utcMs + tz.getOffset(utcMs), DAY_MS).toInt()

    fun toLocalDate(dayId: Int): LocalDate = LocalDate.ofEpochDay(dayId.toLong())

    fun today(zone: ZoneId = ZoneId.systemDefault()): Int = LocalDate.now(zone).toEpochDay().toInt()

    /** Month id used to group the month view: the day id of the 1st of that month. */
    fun monthOf(dayId: Int): Int = toLocalDate(dayId).withDayOfMonth(1).toEpochDay().toInt()

    fun yearOf(dayId: Int): Int = toLocalDate(dayId).year

    /** Day ids for "On this day": today ± [range] days, for every past year back to [years]. */
    fun onThisDay(today: LocalDate, range: Int, years: Int = 60): List<Int> = buildList {
        for (y in 1..years) {
            val center = today.minusYears(y.toLong())
            for (d in -range..range) add(center.plusDays(d.toLong()).toEpochDay().toInt())
        }
    }
}
