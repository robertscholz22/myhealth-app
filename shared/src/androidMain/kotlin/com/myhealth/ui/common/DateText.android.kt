package com.myhealth.ui.common

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter

actual fun formatDeviceDateTime(epochMillis: Long, zone: TimeZone, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern).format(Instant.ofEpochMilli(epochMillis).atZone(zone.toJavaZoneId()))
