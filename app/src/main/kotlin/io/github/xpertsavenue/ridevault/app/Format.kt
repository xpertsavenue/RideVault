package io.github.xpertsavenue.ridevault.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Formatter werden bei jedem Aufruf mit der aktuellen Standard-Locale gebaut, damit ein Sprachwechsel greift.
fun dateTimeFormatter(): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())

fun dateFormatter(): DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

fun timeFormatter(): DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

fun Instant.formatLocal(f: DateTimeFormatter): String = f.format(atZone(ZoneId.systemDefault()))

fun fmt(v: Double, digits: Int = 1): String = String.format(Locale.getDefault(), "%.${digits}f", v)

fun formatDuration(seconds: Int): String = "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
