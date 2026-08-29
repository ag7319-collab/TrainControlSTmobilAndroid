package com.example.traincontrolstmobilandroid

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class TrainWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("TrainControlSTmobilPrefs", Context.MODE_PRIVATE)
        val trainFetcher = TrainFetcher(applicationContext)
        val notificationHelper = NotificationHelper(applicationContext)

        val homeStationName = prefs.getString("home_station", "Brixen / Bressanone") ?: "Brixen / Bressanone"
        val workStationName = prefs.getString("work_station", "Bozen / Bolzano") ?: "Bozen / Bolzano"

        val allStations = loadStationsFromAssets(applicationContext)
        
        val homeStation = allStations.firstOrNull { it.name == homeStationName } ?: return@withContext Result.failure()
        val workStation = allStations.firstOrNull { it.name == workStationName } ?: return@withContext Result.failure()

        val timerIndex = inputData.getInt("timer_index", 1)
        val fromStation = if ((timerIndex == 1) || (timerIndex == 3)) homeStation else workStation
        val toStation = if ((timerIndex == 1) || (timerIndex == 3)) workStation else homeStation

        // Internet-Check vor der Abfrage
        if (!isNetworkAvailable()) {
            notificationHelper.sendGarminNotification(
                message = applicationContext.getString(R.string.no_internet),
                title = "Zug-Anzeige",
                isSilent = true,
            )
            return@withContext Result.retry()
        }

        val trains = try {
            trainFetcher.fetchAndParseTrains(fromStation, toStation)
        } catch (_: Exception) {
            emptyList()
        }

        if (trains.isEmpty()) {
            // Falls während der Abfrage das Internet weggegangen ist
            if (!isNetworkAvailable()) {
                notificationHelper.sendGarminNotification(
                    message = applicationContext.getString(R.string.no_internet), 
                    title = "Zug-Anzeige",
                    isSilent = true
                )
                return@withContext Result.retry()
            }
            notificationHelper.sendGarminNotification(
                message = applicationContext.getString(R.string.no_departures),
                title = "Zug-Anzeige",
                isSilent = true,
            )
            kotlinx.coroutines.delay(1000.milliseconds)
            return@withContext Result.success()
        }

        val relevantTrains = trains.filter { it.stopsAtTarget != false }
        val alarmTrainCount = prefs.getInt("alarm_train_count", 3)

        val delayedTrain = relevantTrains.asSequence().take(alarmTrainCount).firstOrNull {
            it.hasAnyDelay
        }

        if (delayedTrain != null) {
            notificationHelper.playSingleBeep()
            val fullDelay = buildString {
                append(delayedTrain.bestDelayInfo)
                delayedTrain.extraDelayInfoShort?.let { extra ->
                    append("\n")
                    append(extra)
                }
            }
            notificationHelper.sendGarminNotification(
                message = "Zug ${delayedTrain.categoryNumber}\nnach ${delayedTrain.lineTerminal ?: delayedTrain.destination}\n${delayedTrain.time} Uhr\n$fullDelay",
                title = "⚠️ Zugverspätung",
            )
            kotlinx.coroutines.delay(1000.milliseconds)
        }

        Result.success()
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        return cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || 
               cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || 
               cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun loadStationsFromAssets(context: Context): List<StationData> {
        return try {
            context.assets.open("stations.json").bufferedReader().use { reader ->
                val json = org.json.JSONObject(reader.readText())
                val array = json.getJSONArray("stations")
                List(array.length()) { i ->
                    val s = array.getJSONObject(i)
                    val aliasesArr = s.getJSONArray("aliases")
                    StationData(
                        name = s.getString("name"),
                        placeId = s.getString("placeId"),
                        efaId = s.optString("efaId").takeIf { it.isNotEmpty() },
                        lat = s.getDouble("lat"),
                        lon = s.getDouble("lon"),
                        aliases = List(aliasesArr.length()) { aliasesArr.getString(it) },
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
