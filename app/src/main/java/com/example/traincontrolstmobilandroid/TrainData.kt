package com.example.traincontrolstmobilandroid

import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class TrainStop(
    val name: String,
    val scheduledTime: String,
    val actualTime: String,
    val delay: String,
    val isCancelled: Boolean = false,
) {
    fun getEffectiveTime(trainMaxDelay: Int): String {
        if (isCancelled) return scheduledTime
        val planned = try {
            LocalTime.parse(scheduledTime, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (_: Exception) {
            return actualTime
        }
        val efaMins = delay.filter { it.isDigit() }.toIntOrNull() ?: 0
        val effectiveMins = maxOf(efaMins, trainMaxDelay)
        return if (effectiveMins > 0) {
            planned.plusMinutes(effectiveMins.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
        } else {
            scheduledTime
        }
    }

    fun getEffectiveDelay(trainMaxDelay: Int): String {
        if (isCancelled) return "entfällt"
        val efaMins = delay.filter { it.isDigit() }.toIntOrNull() ?: 0
        val effectiveMins = maxOf(efaMins, trainMaxDelay)
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
    val lineTerminal: String? = null,
    val planDate: String? = null,
    val stops: List<TrainStop> = emptyList(),
) {
    val isCancelled: Boolean
        get() = (delay == "entfällt") || (rfiStatus == "entfällt") || (vtStatus == "entfällt")

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
        get() = "STA: $delay"

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
