package com.example.traincontrolstmobilandroid

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class TrainStop(
    val name: String,
    val scheduledTime: String,
    val actualTime: String,
    val delay: String,
    val isCancelled: Boolean = false,
) {
    fun getEffectiveTime(isPassed: Boolean = false, trainMaxDelay: Int = 0): String {
        if (isCancelled) return scheduledTime
        if ((actualTime.isNotEmpty()) && (actualTime != scheduledTime)) {
            return actualTime
        }
        val planned = try {
            LocalTime.parse(scheduledTime, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (_: Exception) {
            return actualTime.ifEmpty { scheduledTime }
        }
        val stopMins = delay.filter { it.isDigit() }.toIntOrNull() ?: 0
        val effectiveMins = if (isPassed) {
            stopMins
        } else {
            maxOf(stopMins, trainMaxDelay)
        }
        return if (effectiveMins > 0) {
            planned.plusMinutes(effectiveMins.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
        } else {
            scheduledTime
        }
    }

    fun getEffectiveDelay(isPassed: Boolean = false, trainMaxDelay: Int = 0): String {
        if (isCancelled) return "entfällt"
        val stopMins = delay.filter { it.isDigit() }.toIntOrNull() ?: 0
        val effectiveMins = if (isPassed) {
            stopMins
        } else {
            maxOf(stopMins, trainMaxDelay)
        }
        return if (effectiveMins > 0) "+$effectiveMins Min." else "pünktlich"
    }
}

data class TrainInfo(
    val categoryNumber: String,
    val destination: String,
    val time: String,
    val delay: String,
    val platform: String,
    val hasDelay: Boolean,
    val isBus: Boolean = false,
    val stopsAtTarget: Boolean? = null,
    val rfiDelay: String? = null,
    val rfiStatus: String? = null,
    val vtDelay: String? = null,
    val vtStatus: String? = null,
    val lineOrigin: String? = null,
    val lineTerminal: String? = null,
    val planDate: String? = null,
    val uniqueRID: String? = null,
    val stops: List<TrainStop> = emptyList(),
) {
    val cleanCategoryNumber: String
        get() = categoryNumber
            .split(" von ").first()
            .replace("Regional-Express", "", ignoreCase = true)
            .replace("Regionalexpress", "", ignoreCase = true)
            .replace("Regionale Veloce", "", ignoreCase = true)
            .replace("Regionalzug", "", ignoreCase = true)
            .replace("Regionale", "", ignoreCase = true)
            .replace("Zug", "", ignoreCase = true)
            .trim()

    val extractedLineOrigin: String?
        get() = if (categoryNumber.contains(" von ", ignoreCase = true)) {
            categoryNumber.split(Regex(" von ", RegexOption.IGNORE_CASE)).last().trim()
        } else null

    val isCancelled: Boolean
        get() = (delay == "entfällt") || (rfiStatus == "entfällt") || (vtStatus == "entfällt")

    val hasAnyDelay: Boolean
        get() = isCancelled || maxDelayMinutes > 0

    val hasNotificationDelay: Boolean
        get() = isCancelled || maxDelayMinutes >= 6

    val isRfiDelayed: Boolean
        get() = (rfiStatus == "Verspätung") || (rfiStatus == "entfällt")

    val isVtDelayed: Boolean
        get() = (vtStatus == "Verspätung") || (vtStatus == "entfällt")

    val maxDelayMinutes: Int
        get() {
            fun parse(s: String?): Int {
                if (s == null || s.contains("-")) return 0
                return s.filter { it.isDigit() }.toIntOrNull() ?: 0
            }
            return maxOf(parse(delay), maxOf(parse(rfiDelay), parse(vtDelay)))
        }

    val bestDelayInfo: String
        get() {
            if (isCancelled) return "fällt aus"
            val mins = maxDelayMinutes
            return if (mins > 0) "+$mins Min." else "pünktlich"
        }

    fun getRfiDisplay(label: String): String? {
        val text = when (rfiStatus) {
            "entfällt" -> "entfällt"
            "Verspätung" -> rfiDelay ?: "+?"
            else -> rfiDelay
        }
        return text?.let { "RFI: $label ($it)" }
    }

    fun getVtDisplay(label: String): String? {
        val text = when (vtStatus) {
            "entfällt" -> {
                if (delay == "entfällt" || rfiStatus == "entfällt") null 
                else "entfällt"
            }
            "Verspätung" -> vtDelay ?: "+?"
            else -> vtDelay
        }
        return text?.let { "$label ($it)" }
    }

    val extraDelayInfoShort: String?
        get() = buildString {
            val rfiText = when (rfiStatus) {
                "entfällt" -> "entfällt"
                "Verspätung" -> rfiDelay ?: "+?"
                else -> rfiDelay
            }
            if (rfiText != null) {
                append("RFI: Tab ($rfiText)")
            }

            val vtText = when (vtStatus) {
                "entfällt" -> {
                    if (delay == "entfällt" || rfiStatus == "entfällt") null 
                    else "entfällt"
                }
                "Verspätung" -> vtDelay ?: "+?"
                else -> vtDelay
            }
            if (vtText != null) {
                if (isNotEmpty()) append(" | ")
                append("VT ($vtText)")
            }
        }.takeIf { it.isNotEmpty() }

    fun getActualDateTimeForStop(stop: TrainStop): LocalDateTime {
        return calculateActualDateTime(planDate ?: "", stop.scheduledTime, stop.actualTime)
    }

    companion object {
        fun calculateActualDateTime(
            planDate: String,
            planTime: String,
            realTime: String?,
        ): LocalDateTime {
            val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
            val date = try {
                LocalDate.parse(planDate, dateFormatter)
            } catch (_: Exception) {
                LocalDate.now()
            }

            val plannedTime = parseLocalTime(planTime) ?: LocalTime.MIDNIGHT
            val actualTime = parseLocalTime(realTime ?: planTime) ?: plannedTime

            var actualDate = date
            val plannedMinutes = (plannedTime.hour * 60) + plannedTime.minute
            val actualMinutes = (actualTime.hour * 60) + actualTime.minute

            if ((actualMinutes < plannedMinutes) && ((plannedMinutes - actualMinutes) > 720)) {
                actualDate = actualDate.plusDays(1)
            }
            return LocalDateTime.of(actualDate, actualTime)
        }

        fun parseLocalTime(timeStr: String): LocalTime? {
            return try {
                LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm"))
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class StationData(
    val name: String,
    val placeId: String,
    val efaId: String? = null,
    val lat: Double,
    val lon: Double,
    val aliases: List<String>,
)

data class CategoryFilter(
    val prefKey: String,
    val label: String,
    val searchTerms: List<String>,
    val defaultState: Boolean,
)
