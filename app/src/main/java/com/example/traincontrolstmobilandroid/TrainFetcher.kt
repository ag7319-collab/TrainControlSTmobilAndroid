package com.example.traincontrolstmobilandroid

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

class TrainFetcher(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("TrainControlSTmobilPrefs", Context.MODE_PRIVATE)

    suspend fun fetchAndParseTrains(
        fromStation: StationData,
        targetStation: StationData,
        onProgress: (String) -> Unit = {},
    ): List<TrainInfo> {
        val rawTrainList = mutableListOf<TrainInfo>()
        val limit = 10
        val internalLimit = 25

        val allowBus = prefs.getBoolean("cat_bus", true)
        val allowReg = prefs.getBoolean("cat_reg", true)
        val allowRv = prefs.getBoolean("cat_rv", true)
        val allowTrenord = prefs.getBoolean("cat_tn_rj", false)
        val allowFreccia = prefs.getBoolean("cat_fv_freccia", false)
        val allowItalo = prefs.getBoolean("cat_fv_italo", false)
        val allowIC = prefs.getBoolean("cat_fv_ic", false)

        val now = LocalDateTime.now()

        for (attempt in 1..2) {
            rawTrainList.clear()

            try {
                val efaFromId = fromStation.efaId ?: resolveEfaId(fromStation.name)
                val efaToId = targetStation.efaId ?: resolveEfaId(targetStation.name)

                val queryOffsets = listOf(60L, 0L)

                for (offset in queryOffsets) {
                    onProgress("Abfrage EFA (STA)")
                    val queryStart = now.minusMinutes(offset)
                    val dateStr = queryStart.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    val timeStr = queryStart.format(DateTimeFormatter.ofPattern("HHmm"))

                    val url = "https://efa.sta.bz.it/web/XML_TRIP_REQUEST2" +
                            "?sessionID=0&requestID=0" +
                            "&name_origin=$efaFromId&type_origin=stop" +
                            "&name_destination=$efaToId&type_destination=stop" +
                            "&itdDate=$dateStr&itdTime=$timeStr" +
                            "&useRealtime=1&outputFormat=JSON&language=de" +
                            "&odvMacro=true&ptOptionsActive=1&itOptionsActive=1" +
                            "&inclMOT_0=1&inclMOT_1=1&inclMOT_2=1&inclMOT_3=1" +
                            "&inclMOT_4=0&inclMOT_5=0&inclMOT_6=0&inclMOT_7=0" +
                            "&inclMOT_8=0&inclMOT_9=0&inclMOT_10=0&inclMOT_11=0" +
                            "&calcNumberOfTrips=10"

                    val responseStr = withContext(Dispatchers.IO) {
                        try {
                            Jsoup.connect(url)
                                .ignoreContentType(true)
                                .timeout(10000)
                                .execute()
                                .body()
                        } catch (_: Exception) {
                            null
                        }
                    } ?: continue

                    val root = JSONObject(responseStr)
                    val tripResponse = root.optJSONObject("tripResponse")

                    val tripsArray = (tripResponse ?: root).optJSONArray("tripList")
                        ?: (tripResponse ?: root).optJSONArray("trips")
                        ?: (tripResponse ?: root).optJSONArray("trip")
                        ?: root.optJSONArray("journey")
                        ?: root.optJSONArray("journeys")
                        ?: JSONArray()

                    val trips = List(tripsArray.length()) { tripsArray.getJSONObject(it) }

                    for (trip in trips) {
                        val legsArray = trip.optJSONArray("legList") ?: trip.optJSONArray("legs") ?: trip.optJSONArray("leg") ?: JSONArray()
                        val legs = List(legsArray.length()) { legsArray.getJSONObject(it) }

                        var tripBanned = false
                        for (legObj in legs) {
                            val transp = legObj.optJSONObject("transportation") ?: legObj.optJSONObject("mode")
                            val tName = transp?.optString("name") ?: ""
                            val isWalk = tName.contains("Fußweg", ignoreCase = true) || legObj.optBoolean("isWalk", false)
                            if (isWalk) continue

                            val upper = tName.uppercase()
                            
                            // Regex sucht nach "BUS ", gefolgt von 1 bis 3 Ziffern und einer Wortgrenze (\b).
                            // Trifft z.B. auf "BUS 310" oder "BUS 1" zu, aber nicht auf "BUS 17270" (was oft ein Zug ist).
                            val isTarnBus = Regex(""".*BUS\s\d{1,3}\b.*""").matches(upper)
                            
                            val isRegularBus = upper.contains("SAD") || upper.contains("SASA") || upper.contains("LINIE") || isTarnBus
                            val isErsatzBus = (upper.contains("BUS") || upper.contains("SEV") || upper.contains("SOSTITUTIVO")) && !isRegularBus

                            val isTrenordOrRJ = upper.contains("RJ") || upper.contains("RAILJET") || upper.contains("EC")
                            val isFreccia = upper.contains("FRECCIA") || upper.contains("FR ")
                            val isItalo = upper.contains("ITALO")
                            val isIC = upper.contains("INTERCITY") || upper.contains("IC ")
                            val isRv = upper.contains("RV") || upper.contains("REGIONALE VELOCE")
                            val isReg = !isRegularBus && !isErsatzBus && !isRv && !isTrenordOrRJ && !isFreccia && !isItalo && !isIC

                            if (isRegularBus || (isErsatzBus && !allowBus) || (isTrenordOrRJ && !allowTrenord) || (isFreccia && !allowFreccia) || (isItalo && !allowItalo) || (isIC && !allowIC) || (isRv && !allowRv) || (isReg && !allowReg)) {
                                tripBanned = true
                                break
                            }
                        }
                        if (tripBanned) continue

                        val vehicleLegs = legs.filter { leg ->
                            val legTransp = leg.optJSONObject("transportation") ?: leg.optJSONObject("mode")
                            val legName = legTransp?.optString("name") ?: ""
                            val isWalk = legName.contains("Fußweg", ignoreCase = true) || leg.optBoolean("isWalk", false)
                            !isWalk
                        }

                        if (vehicleLegs.size > 1) continue

                        val lastLeg = legs.lastOrNull()
                        val tripDestNode = lastLeg?.optJSONObject("destination")
                        val tripDestName = tripDestNode?.optString("name") ?: ""

                        val cleanTarget = targetStation.name.split("/").first().trim()
                        val matchesTargetName = tripDestName.contains(cleanTarget, ignoreCase = true) ||
                                targetStation.name.contains(tripDestName.split("/").first().trim(), ignoreCase = true)

                        if (!matchesTargetName) {
                             // Check ID match as fallback
                             val tripDestId = tripDestNode?.optString("id") ?: ""
                             val targetIdSuffix = if (efaToId.length >= 4) efaToId.takeLast(4) else efaToId
                             if (targetIdSuffix.isEmpty() || !tripDestId.contains(targetIdSuffix)) continue
                        }

                        val mainLeg = vehicleLegs.firstOrNull() ?: continue
                        val pointsArray = mainLeg.optJSONArray("stopList") ?: mainLeg.optJSONArray("point") ?: mainLeg.optJSONArray("points") ?: JSONArray()
                        val points = List(pointsArray.length()) { pointsArray.getJSONObject(it) }
                        
                        val stops = points.map { point ->
                            val stopName = point.optString("name")
                            val sTime = extractTime(point, listOf("itdTime", "dateTime", "departureTimePlanned", "time", "arrivalTimePlanned")) ?: ""
                            val aTime = extractTime(point, listOf("itdRTTime", "realDateTime", "departureTimeEstimated", "rtTime", "arrivalTimeEstimated")) ?: sTime
                            val cancelled = (point.optString("isCancelled") == "1") || point.optBoolean("isCancelled", false)
                            
                            var dStr = "pünktlich"
                            if ((aTime != sTime) && sTime.isNotEmpty()) {
                                val pLT = parseLocalTime(sTime)
                                val aLT = parseLocalTime(aTime)
                                if ((pLT != null) && (aLT != null)) {
                                    val pT = (pLT.hour * 60) + pLT.minute
                                    var rT = (aLT.hour * 60) + aLT.minute
                                    if ((rT < pT) && ((pT - rT) > 720)) rT += 1440
                                    val dM = rT - pT
                                    if (dM > 0) dStr = "+$dM Min."
                                }
                            }
                            if (cancelled) dStr = "entfällt"
                            TrainStop(stopName, sTime, aTime, dStr, cancelled)
                        }

                        val originNode = mainLeg.optJSONObject("origin") ?: points.firstOrNull { it.optString("usage") == "departure" } ?: points.firstOrNull()
                        val transpNode = mainLeg.optJSONObject("transportation") ?: mainLeg.optJSONObject("mode")

                        if ((originNode == null) || (transpNode == null)) continue

                        val transpName = transpNode.optString("name").takeIf { it.isNotEmpty() }
                            ?: transpNode.optString("disassembledName").takeIf { it.isNotEmpty() } ?: "Zug"

                        val lineTerminal = transpNode.optJSONObject("destination")?.optString("name")
                            ?: transpNode.optString("destination").takeIf { it.isNotEmpty() }
                            ?: targetStation.name

                        val upperCat = transpName.uppercase()
                        val isErsatzBusMain = upperCat.contains("BUS") || upperCat.contains("SEV") || upperCat.contains("SOSTITUTIVO")

                        val planDate = extractDate(originNode, listOf("itdTime", "dateTime", "departureTimePlanned", "date")) ?: now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                        val planTime = extractTime(originNode, listOf("itdTime", "dateTime", "departureTimePlanned", "time")) ?: continue

                        val realTime = extractTime(originNode, listOf("itdRTTime", "realDateTime", "departureTimeEstimated", "rtTime")) ?: planTime

                        val actualDeparture = calculateActualDepartureDateTime(planDate, planTime, realTime)
                        // Be more lenient: keep trains from the last 75 minutes for cross-checking
                        if (!actualDeparture.isAfter(now.minusMinutes(75))) continue
                        if (actualDeparture.isAfter(now.plusHours(5))) continue

                        if (rawTrainList.any { (it.categoryNumber == transpName) && (it.time == planTime) }) continue

                        val trainInfo = TrainInfo(
                            categoryNumber = transpName,
                            destination = tripDestName.ifBlank { targetStation.name },
                            time = planTime,
                            delay = "pünktlich",
                            platform = originNode.optString("platformName", "-"),
                            hasDelay = false,
                            isBus = isErsatzBusMain,
                            stopsAtTarget = true,
                            lineTerminal = lineTerminal,
                            planDate = planDate,
                            stops = stops,
                        )

                        val idx = rawTrainList.size
                        rawTrainList.add(trainInfo)

                        if (realTime != planTime) {
                            val plannedLocalTime = parseLocalTime(planTime)
                            val actualLocalTime = parseLocalTime(realTime)
                            if ((plannedLocalTime != null) && (actualLocalTime != null)) {
                                val pTotal = (plannedLocalTime.hour * 60) + plannedLocalTime.minute
                                var rTotal = (actualLocalTime.hour * 60) + actualLocalTime.minute
                                if ((rTotal < pTotal) && ((pTotal - rTotal) > 720)) rTotal += 1440
                                val delayMins = rTotal - pTotal
                                if (delayMins > 0) {
                                    rawTrainList[idx] = rawTrainList[idx].copy(delay = "+$delayMins Min.", hasDelay = true)
                                }
                            }
                        }

                        val isCancelled = (originNode.optString("isCancelled") == "1") || originNode.optBoolean("isCancelled", false)
                        if (isCancelled) {
                            rawTrainList[idx] = rawTrainList[idx].copy(delay = "entfällt", hasDelay = true)
                        }

                        if (rawTrainList.size >= internalLimit) break
                    }
                }

                try {
                    onProgress("Abfrage RFI Tabellone")
                    val rfiUrl = "https://iechub.rfi.it/ArriviPartenze/arrivalsdepartures/Monitor?placeId=${fromStation.placeId}&arrivals=False"
                    val rfiDoc = withContext(Dispatchers.IO) {
                        try {
                            Jsoup.connect(rfiUrl)
                                .timeout(10000)
                                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
                                .get()
                        } catch (_: Exception) {
                            null
                        }
                    }

                    if (rfiDoc != null) {
                        val rfiRows = rfiDoc.select("tr")
                        for ((i, train) in rawTrainList.withIndex()) {
                            val efaNum = train.categoryNumber.filter { it.isDigit() }
                            if (efaNum.isBlank()) continue

                            // Robuster Abgleich: Erst nach Nummer, dann nach Zeit + Typ/Ziel
                            var matchedRow = rfiRows.firstOrNull { it.text().contains(efaNum) }
                            if (matchedRow == null) {
                                // Fallback: Suche nach Zeile mit exakt der gleichen Abfahrtszeit
                                matchedRow = rfiRows.firstOrNull { row ->
                                    val rowText = row.text()
                                    val hasTime = rowText.contains(train.time)
                                    val hasType = rowText.contains("REG", ignoreCase = true) || 
                                                  rowText.contains("RV", ignoreCase = true) ||
                                                  row.select("img[alt*=REG], img[alt*=RV], img[alt*=Regionale]").isNotEmpty()
                                    val hasDest = rowText.contains(train.destination.split("/").first().trim(), ignoreCase = true)
                                    
                                    hasTime && (hasType || hasDest)
                                }
                            }

                            if (matchedRow != null) {
                                val cols = matchedRow.select("td")
                                if (cols.size >= 5) {
                                    val timeRegex = Regex("""\b\d{2}:\d{2}\b""")
                                    val colTexts = cols.map { it.text().trim() }
                                    val timeIdx = colTexts.indexOfFirst { timeRegex.containsMatchIn(it) }

                                    if ((timeIdx != -1) && (colTexts.size > (timeIdx + 1))) {
                                        val rawDelay = colTexts[timeIdx + 1]
                                        val isCancelled = rawDelay.contains("SOP", ignoreCase = true) || rawDelay.contains("CANC", ignoreCase = true) || matchedRow.text().contains("SOPPRESSO", ignoreCase = true)

                                        val statusText = when {
                                            isCancelled -> "entfällt"
                                            (rawDelay.isBlank()) || (rawDelay == "0") || (rawDelay == "pünktlich") -> "pünktlich"
                                            else -> "Verspätung"
                                        }

                                        val delayDisplay = when {
                                            isCancelled -> ""
                                            (rawDelay.isBlank()) || (rawDelay == "0") || (rawDelay == "pünktlich") -> "+0"
                                            rawDelay.all { it.isDigit() } -> "+$rawDelay"
                                            else -> rawDelay
                                        }

                                        rawTrainList[i] = train.copy(rfiDelay = delayDisplay, rfiStatus = statusText)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("DEBUG: RFI-Monitor Cross-Check failed: ${e.message}")
                }

                try {
                    onProgress("Abfrage viaggiatreno.it")
                    coroutineScope {
                        val updatedTrains = rawTrainList.map { train ->
                            async(Dispatchers.IO) {
                                fetchViaggiaTrenoUpdate(train)
                            }
                        }.awaitAll()
                        rawTrainList.clear()
                        rawTrainList.addAll(updatedTrains)
                    }
                } catch (e: Exception) {
                    println("DEBUG: VT Cross-Check failed: ${e.message}")
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (rawTrainList.isNotEmpty()) break
            if (attempt == 1) delay(1.seconds)
        }

        // Final filtering: remove trains that have truly departed based on updated delay info
        return rawTrainList.asSequence().filter { train ->
            val bestRealTime = getBestRealTime(train) ?: train.time
            val actual = calculateActualDepartureDateTime(
                train.planDate ?: now.format(DateTimeFormatter.ofPattern("yyyyMMdd")),
                train.time,
                bestRealTime,
            )
            // Show trains until 2 minutes after their (possibly delayed) departure
            actual.isAfter(now.minusMinutes(2))
        }.sortedWith(compareBy({ it.planDate }, { it.time })).take(limit).toList()
    }

    private fun getBestRealTime(train: TrainInfo): String? {
        // Find maximum delay from all sources
        val vtMins = train.vtDelay?.filter { it.isDigit() }?.toIntOrNull() ?: -1
        val rfiMins = train.rfiDelay?.filter { it.isDigit() }?.toIntOrNull() ?: -1
        val efaMins = if (train.delay.startsWith("+")) train.delay.filter { it.isDigit() }.toIntOrNull() ?: -1 else -1

        val maxDelay = maxOf(vtMins, rfiMins, efaMins)
        if (maxDelay < 0) return null

        val planned = parseLocalTime(train.time) ?: return null
        return planned.plusMinutes(maxDelay.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    private fun fetchViaggiaTrenoUpdate(train: TrainInfo): TrainInfo {
        val num = train.categoryNumber.filter { it.isDigit() }
        if (num.isBlank()) return train

        try {
            // 1. Suche Zug für ID (Logik aus TreniRT: verwende infomobilita Endpoint)
            val searchUrl = "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/cercaNumeroTrenoTrenoAutocomplete/$num"
            val searchRes = Jsoup.connect(searchUrl)
                .ignoreContentType(true)
                .timeout(10000)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
                .execute()
                .body()
                .trim()
            
            if (searchRes.isNotEmpty()) {
                // TreniRT parsing logic: nimm die erste valide Zeile
                val line = searchRes.lines().firstOrNull { it.contains("|") } ?: return train
                val parts = line.split("|")
                if (parts.size >= 2) {
                    val meta = parts[1]
                    val metaParts = meta.split("-")
                    val trainNum = metaParts.getOrNull(0)?.trim() ?: ""
                    val originId = metaParts.getOrNull(1)?.trim() ?: ""
                    val referenceDay = metaParts.getOrNull(2)?.trim() ?: "" // Midnight-Timestamp
                    
                    if (trainNum.isNotEmpty() && originId.isNotEmpty()) {
                        // 2. Andamento abfragen (mit referenceDay zur Disambiguierung)
                        val andamentoUrl = if (referenceDay.isNotEmpty()) {
                            "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/andamentoTreno/$originId/$trainNum/$referenceDay"
                        } else {
                            "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/andamentoTreno/$originId/$trainNum"
                        }
                        
                        val andamentoRes = Jsoup.connect(andamentoUrl)
                            .ignoreContentType(true)
                            .timeout(10000)
                            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
                            .execute()
                            .body()
                        
                        val andamentoJson = JSONObject(andamentoRes)
                        val ritardo = andamentoJson.optInt("ritardo", -999)
                        val provvedimento = andamentoJson.optInt("provvedimento", 0)
                        val isSopresso = (provvedimento != 0) || andamentoJson.optBoolean("provvedimento", false)

                        if (isSopresso) {
                            return train.copy(vtStatus = "entfällt", vtDelay = "")
                        } else if (ritardo != -999) {
                            val vtDisplay = if (ritardo >= 0) "+$ritardo" else ritardo.toString()
                            val vtStatus = if (ritardo > 0) "Verspätung" else "pünktlich"
                            return train.copy(vtDelay = vtDisplay, vtStatus = vtStatus)
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return train
    }

    private fun extractDate(node: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            node.optString(key).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val dateTime = node.optJSONObject("dateTime")
        return dateTime?.optString("date")?.takeIf { it.isNotEmpty() }
    }

    private fun extractTime(node: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            val element = node.opt(key)
            parseTimeFromElement(element, key.contains("RT", ignoreCase = true) || key.contains("Estimated", ignoreCase = true))?.let { return it }
        }
        val dateTime = node.optJSONObject("dateTime")
        if (dateTime != null) {
            val isRT = keys.any { it.contains("RT", ignoreCase = true) || it.contains("Estimated", ignoreCase = true) }
            val t = if (isRT) {
                dateTime.optString("rtTime").takeIf { it.isNotEmpty() }
                    ?: dateTime.optString("time").takeIf { it.isNotEmpty() }
            } else {
                dateTime.optString("time").takeIf { it.isNotEmpty() }
            }
            t?.let { timeStr ->
                Regex("""\b(\d{2}:\d{2})\b""").find(timeStr)?.value?.let { return it }
            }
        }
        return null
    }

    private fun parseTimeFromElement(element: Any?, isRT: Boolean): String? {
        if (element is JSONObject) {
            val h = element.optString("hour").takeIf { it.isNotEmpty() }?.padStart(2, '0')
            val m = element.optString("minute").takeIf { it.isNotEmpty() }?.padStart(2, '0')
            if ((h != null) && (m != null)) return "$h:$m"

            val t = if (isRT) {
                element.optString("rtTime").takeIf { it.isNotEmpty() }
                    ?: element.optString("time").takeIf { it.isNotEmpty() }
            } else {
                element.optString("time").takeIf { it.isNotEmpty() }
            }
            t?.let { timeStr ->
                Regex("""\b(\d{2}:\d{2})\b""").find(timeStr)?.value?.let { return it }
            }
        }
        (element as? String)?.let { str ->
            Regex("""\b(\d{2}:\d{2})\b""").find(str)?.value?.let { return it }
        }
        return null
    }

    private fun calculateActualDepartureDateTime(
        planDate: String,
        planTime: String,
        realTime: String?,
    ): LocalDateTime {
        val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val date = try {
            java.time.LocalDate.parse(planDate, dateFormatter)
        } catch (_: Exception) {
            java.time.LocalDate.now()
        }

        val plannedTime = parseLocalTime(planTime) ?: LocalTime.MIDNIGHT
        val actualTime = parseLocalTime(realTime ?: planTime) ?: plannedTime

        var actualDate = date

        val plannedMinutes = (plannedTime.hour * 60) + plannedTime.minute
        val actualMinutes = (actualTime.hour * 60) + actualTime.minute

        /*
         * Mitternachtswechsel erkennen.
         * Wenn die Abfahrt eigentlich schon gestern war (oder heute sehr spät),
         * aber die Verspätung sie über Mitternacht schiebt.
         */
        if ((actualMinutes < plannedMinutes) && ((plannedMinutes - actualMinutes) > 720)) {
            actualDate = actualDate.plusDays(1)
        }

        return LocalDateTime.of(actualDate, actualTime)
    }

    private fun parseLocalTime(timeStr: String): LocalTime? {
        return try {
            LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveEfaId(stationName: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val searchTerm = if (stationName.contains("Bahnhof", ignoreCase = true)) stationName else "$stationName Bahnhof"
                val encodedName = java.net.URLEncoder.encode(searchTerm, "UTF-8")
                val url = "https://efa.sta.bz.it/web/XML_STOPFINDER_REQUEST?language=de&outputFormat=JSON&type_sf=stop&name_sf=$encodedName"
                val responseStr = Jsoup.connect(url).ignoreContentType(true).execute().body()
                val root = JSONObject(responseStr)
                val sf = root.optJSONObject("stopFinder") ?: return@withContext "66000468"
                val points = sf.optJSONArray("point")
                if ((points != null) && (points.length() > 0)) {
                    points.getJSONObject(0).optString("stateless", "66000468")
                } else {
                    "66000468"
                }
            } catch (_: Exception) {
                "66000468"
            }
        }
    }
}
