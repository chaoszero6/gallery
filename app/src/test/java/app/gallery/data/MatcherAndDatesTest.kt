package app.gallery.data

import app.gallery.data.model.DayIds
import app.gallery.data.repo.Matcher
import app.gallery.search.DateQuery
import app.gallery.ui.components.Grouping
import app.gallery.ui.components.GridEntry
import app.gallery.ui.components.buildEntries
import app.gallery.data.model.MediaItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

class MatcherTest {
    @Test fun `upload record wins, then time+size, then name+size`() {
        val locals = listOf(
            Matcher.Local(1, 1000, 50, "a.jpg"),
            Matcher.Local(2, 2000, 60, "b.jpg"),
            Matcher.Local(3, 3000, 70, "c.jpg"),
        )
        val remotes = listOf(
            Matcher.Remote(10, 9999, 1, "zzz.jpg"),  // linked by upload record only
            Matcher.Remote(20, 2000, 60, "other.jpg"), // same time + size
            Matcher.Remote(30, 1, 70, "C.JPG"),        // same name + size (case-insensitive)
            Matcher.Remote(40, 5, 5, "nomatch.jpg"),
        )
        val m = Matcher.match(locals, remotes, uploaded = mapOf(1L to 10L))
        assertThat(m).containsExactly(10L, 1L, 20L, 2L, 30L, 3L)
    }

    @Test fun `each phone item matches at most one server item`() {
        val locals = listOf(Matcher.Local(1, 1000, 50, "a.jpg"))
        val remotes = listOf(Matcher.Remote(10, 1000, 50, "a.jpg"), Matcher.Remote(11, 1000, 50, "a.jpg"))
        assertThat(Matcher.match(locals, remotes)).hasSize(1)
    }

    @Test fun `zero sizes never match`() {
        val m = Matcher.match(listOf(Matcher.Local(1, 0, 0, "a.jpg")), listOf(Matcher.Remote(10, 0, 0, "a.jpg")))
        assertThat(m).isEmpty()
    }

    @Test fun `upload record pointing at a deleted server file is ignored`() {
        val m = Matcher.match(listOf(Matcher.Local(1, 5, 5, "a")), listOf(Matcher.Remote(11, 9, 9, "b")), mapOf(1L to 10L))
        assertThat(m).isEmpty()
    }
}

class DayIdsTest {
    @Test fun `dayId uses local wall clock like Memories`() {
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        // 2026-11-14T20:00Z is 2026-11-15 01:30 in India.
        val ms = java.time.Instant.parse("2026-11-14T20:00:00Z").toEpochMilli()
        assertThat(DayIds.fromUtcMillis(ms, ist)).isEqualTo(LocalDate.of(2026, 11, 15).toEpochDay().toInt())
        assertThat(DayIds.fromUtcMillis(ms, TimeZone.getTimeZone("UTC"))).isEqualTo(LocalDate.of(2026, 11, 14).toEpochDay().toInt())
    }

    @Test fun `on this day covers range around the date in past years`() {
        val ids = DayIds.onThisDay(LocalDate.of(2026, 11, 15), range = 1, years = 2)
        assertThat(ids).containsExactlyElementsIn(
            listOf("2025-11-14", "2025-11-15", "2025-11-16", "2024-11-14", "2024-11-15", "2024-11-16").map { LocalDate.parse(it).toEpochDay().toInt() },
        )
    }

    @Test fun `month and year of a day`() {
        val d = LocalDate.of(2026, 11, 15).toEpochDay().toInt()
        assertThat(DayIds.monthOf(d)).isEqualTo(LocalDate.of(2026, 11, 1).toEpochDay().toInt())
        assertThat(DayIds.yearOf(d)).isEqualTo(2026)
    }
}

class DateQueryTest {
    private val today = LocalDate.of(2026, 11, 15)
    private fun d(s: String) = LocalDate.parse(s).toEpochDay().toInt()

    @Test fun years_months_and_words() {
        assertThat(DateQuery.parse("2024", today)).isEqualTo(d("2024-01-01")..d("2024-12-31"))
        assertThat(DateQuery.parse("photos from nov 2023", today)).isEqualTo(d("2023-11-01")..d("2023-11-30"))
        assertThat(DateQuery.parse("february 2024", today)).isEqualTo(d("2024-02-01")..d("2024-02-29"))
        assertThat(DateQuery.parse("today", today)).isEqualTo(d("2026-11-15")..d("2026-11-15"))
        assertThat(DateQuery.parse("yesterday", today)).isEqualTo(d("2026-11-14")..d("2026-11-14"))
        assertThat(DateQuery.parse("beach", today)).isNull()
        assertThat(DateQuery.parse("november", today)).isNull()
    }

    @Test fun strip_date_words() {
        assertThat(DateQuery.strip("beach in nov 2023")).isEqualTo("beach")
        assertThat(DateQuery.strip("2024")).isEmpty()
        assertThat(DateQuery.strip("kids playing in sand")).isEqualTo("kids playing sand")
    }
}

class GridEntriesTest {
    private fun item(key: String, day: String, takenAtMs: Long = 0) = MediaItem(
        key, null, null, null, null, null, key, "image/jpeg", 1, takenAtMs, LocalDate.parse(day).toEpochDay().toInt(),
        1, 1, false, 0, false, null, null, null, null,
    )

    @Test fun `timeline order keeps each day together even when capture times interleave`() {
        // A Memories photo's day comes from the camera's local time, its timestamp is UTC:
        // "b" is on 7 Aug by its day but sorts between two 8 Aug photos by time.
        val items = listOf(
            item("a", "2026-08-08", 3_000), item("b", "2026-08-07", 2_500),
            item("c", "2026-08-08", 2_000), item("d", "2026-08-07", 1_000),
        ).sortedWith(MediaItem.TimelineOrder)
        assertThat(items.map { it.key }).containsExactly("a", "c", "b", "d").inOrder()
        val headers = buildEntries(items, Grouping.DAY).filterIsInstance<GridEntry.Header>()
        assertThat(headers.map { it.itemKeys }).containsExactly(listOf("a", "c"), listOf("b", "d")).inOrder()
    }

    @Test fun `a list that returns to an earlier day still gets unique keys`() {
        // Recent is sorted by date modified, so days can repeat; duplicate keys crashed the grid.
        val items = listOf(item("a", "2026-08-08"), item("b", "2026-08-07"), item("c", "2026-08-08"), item("d", "2026-08-07"))
        val keys = buildEntries(items, Grouping.DAY).map { it.key }
        assertThat(keys).containsNoDuplicates()
        assertThat(keys.filter { it.startsWith("h-") }).containsExactly("h-DAY-20673", "h-DAY-20672", "h-DAY-20673-2", "h-DAY-20672-2").inOrder()
    }

    @Test fun `headers group items by day and month`() {
        val items = listOf(item("a", "2026-11-15"), item("b", "2026-11-15"), item("c", "2026-11-02"), item("d", "2026-10-30"))
        val byDay = buildEntries(items, Grouping.DAY)
        assertThat(byDay.filterIsInstance<GridEntry.Header>().map { it.itemKeys }).containsExactly(listOf("a", "b"), listOf("c"), listOf("d")).inOrder()
        val byMonth = buildEntries(items, Grouping.MONTH)
        assertThat(byMonth.filterIsInstance<GridEntry.Header>().map { it.itemKeys }).containsExactly(listOf("a", "b", "c"), listOf("d")).inOrder()
        assertThat(byDay.size).isEqualTo(7)
    }
}
