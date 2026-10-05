package com.myhealth.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.CalendarDay
import com.myhealth.ui.theme.MyHealthTheme
import kotlinx.datetime.LocalDate

/**
 * The month grid of P3.4: ISO weeks, Monday first, always 6 rows × 7 cells so the height never
 * changes between months. Days outside [anchor]'s month are dimmed by [DayCell].
 */
@Composable
fun MonthGrid(
    anchor: LocalDate,
    days: Map<Long, CalendarDay>,
    today: Long,
    selectedDay: Long,
    onDayClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    cycleMarkers: Map<Long, CycleDayMarker> = emptyMap(),
) {
    val cells = monthGridDays(anchor)
    val anchorMonth = anchor.month
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        WeekdayHeader()
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    val epochDay = date.toEpochDays()
                    DayCell(
                        date = date,
                        day = days[epochDay],
                        isInAnchorMonth = date.month == anchorMonth && date.year == anchor.year,
                        isToday = epochDay == today,
                        isSelected = epochDay == selectedDay,
                        onClick = { onDayClick(epochDay) },
                        cycleMarker = cycleMarkers[epochDay],
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun WeekdayHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        WEEKDAY_INITIALS.forEach { initial ->
            Text(
                text = initial,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun MonthGridPreview() {
    val anchor = LocalDate(2026, 9, 1)
    val populated = LocalDate(2026, 9, 14).toEpochDays()
    MyHealthTheme(dynamicColor = false) {
        MonthGrid(
            anchor = anchor,
            days = mapOf(populated to previewCalendarDay(populated)),
            today = populated,
            selectedDay = populated + 1,
            onDayClick = {},
        )
    }
}
