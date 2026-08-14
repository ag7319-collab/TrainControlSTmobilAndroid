package com.example.traincontrolstmobilandroid

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
    val lineTerminal: String? = null,
) {
    val hasAnyDelay: Boolean
        get() = hasDelay || rfiStatus == "Verspätung" || rfiStatus == "entfällt"

    val bestDelayInfo: String
        get() = when {
            hasDelay -> delay
            rfiStatus == "entfällt" -> "RFI: entfällt"
            rfiStatus == "Verspätung" -> "RFI: ${rfiDelay ?: "+?"}"
            else -> delay
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
