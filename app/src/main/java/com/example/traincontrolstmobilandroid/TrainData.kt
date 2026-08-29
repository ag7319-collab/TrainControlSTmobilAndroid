package com.example.traincontrolstmobilandroid

data class TrainStop(
    val name: String,
    val scheduledTime: String,
    val actualTime: String,
    val delay: String,
    val isCancelled: Boolean = false,
)

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
    val hasAnyDelay: Boolean
        get() = hasDelay || 
                (rfiStatus == "Verspätung") || (rfiStatus == "entfällt") ||
                (vtStatus == "Verspätung") || (vtStatus == "entfällt")

    val bestDelayInfo: String
        get() = "STA: $delay"

    val extraDelayInfoLong: String?
        get() = buildExtraInfo(rfiLabel = "Tabellone", vtLabel = "Viaggiatreno")

    val extraDelayInfoShort: String?
        get() = buildExtraInfo(rfiLabel = "Tab", vtLabel = "VT")

    private fun buildExtraInfo(rfiLabel: String, vtLabel: String): String? = buildString {
        val rfiText = when (rfiStatus) {
            "entfällt" -> "entfällt"
            "Verspätung" -> rfiDelay ?: "+?"
            else -> rfiDelay
        }
        if (rfiText != null) {
            append("RFI: $rfiLabel ($rfiText)")
        }

        val vtText = when (vtStatus) {
            "entfällt" -> {
                // Nur ausblenden, wenn STA oder RFI bereits "entfällt" anzeigen
                if (delay == "entfällt" || rfiStatus == "entfällt") null 
                else "entfällt"
            }
            "Verspätung" -> vtDelay ?: "+?"
            else -> vtDelay
        }
        if (vtText != null) {
            if (isNotEmpty()) append(" | ")
            append("$vtLabel ($vtText)")
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
