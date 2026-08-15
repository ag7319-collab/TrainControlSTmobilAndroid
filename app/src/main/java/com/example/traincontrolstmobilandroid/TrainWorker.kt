package com.example.traincontrolstmobilandroid

import android.content.Context
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

        // In order to get StationData, we need the full list.
        // We'll use a simplified version: load stations from assets here too or pass IDs.
        // For simplicity, we'll re-load from assets.
        val allStations = loadStationsFromAssets(applicationContext)
        
        val homeStation = allStations.firstOrNull { it.name == homeStationName } ?: return@withContext Result.failure()
        val workStation = allStations.firstOrNull { it.name == workStationName } ?: return@withContext Result.failure()

        // Logic: Timer 1 (Morning) -> Home to Work, Timer 2 (Afternoon) -> Work to Home
        // We get the timer index from input data
        val timerIndex = inputData.getInt("timer_index", 1)
        val fromStation = if (timerIndex == 1) homeStation else workStation
        val toStation = if (timerIndex == 1) workStation else homeStation

        val trains = trainFetcher.fetchAndParseTrains(fromStation, toStation)
        val relevantTrains = trains.filter { it.stopsAtTarget != false }
        val alarmTrainCount = prefs.getInt("alarm_train_count", 3)

        val delayedTrain = relevantTrains.asSequence().take(alarmTrainCount).firstOrNull {
            it.hasAnyDelay
        }

        if (delayedTrain != null) {
            notificationHelper.playSingleBeep()
            notificationHelper.sendGarminNotification(
                message = "Zug ${delayedTrain.categoryNumber}\nnach ${delayedTrain.lineTerminal ?: delayedTrain.destination}\n${delayedTrain.time} Uhr\nVerspätung: ${delayedTrain.bestDelayInfo}",
                title = "⚠️ Zugverspätung",
            )
            // Safety delay: Give the audio hardware enough time to play the tone 
            // before the WorkManager process is potentially killed.
            kotlinx.coroutines.delay(1000.milliseconds)
        } else if (trains.isEmpty()) {
            notificationHelper.sendGarminNotification(
                message = applicationContext.getString(R.string.no_departures),
                title = "Zug-Anzeige",
            )
            // Wait a bit to ensure the notification is fully posted
            kotlinx.coroutines.delay(1000.milliseconds)
        }

        Result.success()
    }

    private fun loadStationsFromAssets(context: Context): List<StationData> {
        return try {
            context.assets.open("stations.json").bufferedReader().use { reader ->
                val json = org.json.JSONObject(reader.readText())
                val array = json.getJSONArray("stations")
                List(array.length()) { i ->
                    val s = array.getJSONObject(i)
                    val aliases = s.getJSONArray("aliases")
                    StationData(
                        name = s.getString("name"),
                        placeId = s.getString("placeId"),
                        efaId = s.optString("efaId").takeIf { it.isNotEmpty() },
                        lat = s.getDouble("lat"),
                        lon = s.getDouble("lon"),
                        aliases = List(aliases.length()) { aliases.getString(it) },
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
