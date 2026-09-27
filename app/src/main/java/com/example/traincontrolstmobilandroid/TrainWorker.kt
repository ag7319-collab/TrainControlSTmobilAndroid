package com.example.traincontrolstmobilandroid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class TrainWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("TrainControlSTmobilPrefs", Context.MODE_PRIVATE)
        val trainFetcher = TrainFetcher(applicationContext)
        val notificationHelper = NotificationHelper(applicationContext)

        val homeStationName = prefs.getString("home_station", "Brixen / Bressanone") ?: "Brixen / Bressanone"
        var workStationName = prefs.getString("work_station", "Bozen / Bolzano") ?: "Bozen / Bolzano"
        val useGpsWork = prefs.getBoolean("use_gps_work", false)

        val allStations = loadStationsFromAssets(applicationContext)

        if (useGpsWork && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)
                val location = Tasks.await(fusedLocationClient.lastLocation, 3, TimeUnit.SECONDS)
                if (location != null) {
                    val currentStation = allStations.asSequence()
                        .filter { !it.placeId.startsWith("9900") }
                        .minByOrNull {
                            val res = FloatArray(1)
                            Location.distanceBetween(location.latitude, location.longitude, it.lat, it.lon, res)
                            res[0]
                        }
                    if (currentStation != null && currentStation.name != homeStationName) {
                        workStationName = currentStation.name
                        prefs.edit { putString("work_station", currentStation.name) }
                    }
                }
            } catch (_: Exception) { }
        }

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
            trainFetcher.fetchAndParseTrains(fromStation, toStation, forceRefresh = true)
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
            delay(1000.milliseconds)
            return@withContext Result.success()
        }

        val relevantTrains = trains.filter { it.stopsAtTarget != false }
        val alarmTrainCount = prefs.getInt("alarm_train_count", 3)

        // Vorherige Benachrichtigungen löschen
        for (i in 0 until 5) {
            notificationHelper.cancelNotification(1001 + i)
        }

        var hasShownNotification = false
        val trainsToNotify = relevantTrains.take(alarmTrainCount).withIndex().filter { it.value.hasNotificationDelay }

        // Benachrichtigungen in umgekehrter Reihenfolge senden, damit der 1. Zug auf dem Smartphone oben erscheint
        trainsToNotify.reversed().forEach { (index, train) ->
            if (!hasShownNotification) {
                notificationHelper.playSingleBeep()
                hasShownNotification = true
            }
            
            val fullDelay = buildString {
                append(train.bestDelayInfo)
                train.extraDelayInfoShort?.let { extra ->
                    append("\n")
                    append(extra)
                }
            }
            notificationHelper.sendGarminNotification(
                message = "Zug ${train.categoryNumber}\nnach ${train.lineTerminal ?: train.destination}\n${train.time} Uhr\n$fullDelay",
                title = if (train.isCancelled) "❌ Zugausfall" else "⚠️ Zugverspätung",
                notificationId = 1001 + index
            )
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
                val json = JSONObject(reader.readText())
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
