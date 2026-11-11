package com.myhealth.ui.calendar

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.CalendarEvent
import com.myhealth.domain.model.EventType
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.SportType
import java.time.Clock
import org.junit.Test

/** The editor must hand back what it does not show: an imported event's tie to its calendar entry. */
class EventDraftImportedTest {

    private val imported = CalendarEvent(
        id = 4L, type = EventType.SOCCER_TRAINING, title = "Practice", startDay = 20_000L,
        startMinuteOfDay = 1110, durationMin = 60, location = null, sportType = SportType.SOCCER_TRAINING,
        targetDistanceMeters = null, isKeyEvent = false, recurrenceRule = null, recurrenceUntilDay = null,
        parentEventId = null, linkedActivityId = null, linkMethod = null, notes = null,
        createdAtMillis = 1L, updatedAtMillis = 1L,
        externalSource = "calendar:Team", externalId = "1|practice", intensity = Intensity.LOW,
    )

    @Test
    fun editing_an_imported_event_keeps_its_calendar_link_and_intensity() {
        val saved = eventDraftFrom(imported).copy(notes = "bring bibs").toCalendarEvent(Clock.systemUTC())

        assertThat(saved.externalSource).isEqualTo("calendar:Team")
        assertThat(saved.externalId).isEqualTo("1|practice")
        assertThat(saved.intensity).isEqualTo(Intensity.LOW)
        assertThat(saved.notes).isEqualTo("bring bibs")
    }

    @Test
    fun an_event_that_is_not_training_carries_no_intensity() {
        val saved = eventDraftFrom(imported).copy(type = EventType.APPOINTMENT).toCalendarEvent(Clock.systemUTC())

        assertThat(saved.intensity).isNull()
    }
}
