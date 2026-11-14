package com.myhealth.ui.sleep

import com.myhealth.domain.model.SleepRecord
import com.myhealth.ui.body.SLEEP_BAR_NIGHTS
import com.myhealth.ui.common.zeroPad
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * State of [SleepScreen] (0.10.3): the Today "Sleep" tile used to open Body & Health, whose sleep
 * chart sits at the very bottom, so the tile seemed to lead nowhere. The screen shows last night,
 * the average against the sleep target, the 14-night bars and one row per night.
 */
data class SleepUiState(
    val isLoading: Boolean = true,
    val today: Long = 0L,
    /** The nights of the window, any order; see [nightsNewestFirst]. */
    val nights: List<SleepRecord> = emptyList(),
    val targetHours: Double = 8.0,
) {
    val fromNight: Long get() = today - SLEEP_BAR_NIGHTS + 1

    /** One row per night (the day the user woke up), newest first. */
    val nightsNewestFirst: List<SleepRecord> get() = nights.sortedByDescending { it.night }

    /** The newest night of the window, whatever its date; see [isLastNight] for its label. */
    val latest: SleepRecord? get() = nightsNewestFirst.firstOrNull()

    /** Mean sleep over the nights that have a record, in hours; `null` without any. */
    val averageHours: Double? get() = averageSleepHours(nights)
}

/** Mean of [SleepRecord.totalSleepMin] in hours over the records given; `null` when empty. */
fun averageSleepHours(nights: List<SleepRecord>): Double? =
    nights.takeIf { it.isNotEmpty() }?.let { list -> list.sumOf { it.totalSleepMin } / 60.0 / list.size }

/** "23:12 – 06:48" in [zone]. */
fun bedtimeLabel(record: SleepRecord, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    fun hm(millis: Long): String {
        val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
        return "${t.hour.zeroPad(2)}:${t.minute.zeroPad(2)}"
    }
    return "${hm(record.startAtMillis)} – ${hm(record.endAtMillis)}"
}

/**
 * True when [night] (the day the user woke up) counts as "last night" on [today]: this morning, or
 * yesterday's when the app is opened before a new night has synced. Older nights are shown with
 * their date instead, on the Today tile and on the Sleep screen alike.
 */
fun isLastNight(night: Long, today: Long): Boolean = night >= today - 1
