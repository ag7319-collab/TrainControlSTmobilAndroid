package com.example.traincontrolstmobilandroid

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import java.util.Locale
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

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
)

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

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var prefs: SharedPreferences

    companion object {

        private var lastExecutionTime: Long = 0

        private const val REQUEST_LOCATION_PERMISSION = 1001
        private const val REQUEST_NOTIFICATION_PERMISSION = 2001

        /*
         * Die Meran-Linie:
         *
         * Meran / Merano
         * Mals / Malles
         * Schlanders / Silandro
         * Terlan / Terlano
         * Gargazon / Gargazzone
         *
         * Diese Aliase werden nur zur Erkennung
         * der Meraner Streckenrichtung verwendet.
         */
        val MERAN_LINE_ALIASES = listOf(
            "MALLES",
            "MALS",
            "SILANDRO",
            "SCHLANDERS",
            "MERANO",
            "MERAN",
            "MERANO MAIA",
            "MERAN-UNTERMAIS",
            "LANA",
            "BURGSTALL",
            "LANA-POSTAL",
            "LANA-BURGSTALL",
            "GARGAZZONE",
            "GARGAZON",
            "VILPIAN",
            "NALS",
            "VILPIANO-NALLES",
            "VILPIAN-NALS",
            "TERLAN",
            "TERLANO",
            "TERLANO-ANDRIANO",
            "TERLAN-ANDRIAN",
            "SETTEQUERCE",
            "SIEBENEICH",
            "PONTE D'ADIGE",
            "SIGMUNDSKRON",
            "BOLZANO CASANOVA",
            "BOZEN KAISERAU",
            "BOLZANO SUD",
            "BOZEN SÜD"
        )



        val CATEGORY_GROUPS = mapOf(

            "Regionalverkehr RFI/SAD" to listOf(

                CategoryFilter(
                    "cat_reg",
                    "Regionalzüge(REG)",
                    listOf("REG", "REGIONALE", "SAD"),
                    defaultState = true
                ),

                CategoryFilter(
                    "cat_rv",
                    "Regionalexpress (RV)",
                    listOf("RV", "REGIONALE VELOCE"),
                    defaultState = true
                ),

                CategoryFilter(
                    "cat_bus",
                    "Bus Schienenersatz",
                    listOf("BUS"),
                    defaultState = true
                )
            ),

            "Fernverkehr & High-Speed" to listOf(

                CategoryFilter(
                    "cat_tn_rj",
                    "Eurocity / Railjet / Trenord (RJ/EC)",
                    listOf("RJ", "RAILJET", "EC", "TRENORD"),
                    defaultState = false
                ),

                CategoryFilter(
                    "cat_fv_freccia",
                    "Frecciarossa (Alta Velocità)",
                    listOf("FRECCIAROSSA", "FRECCIARGENTO", "FR"),
                    defaultState = false
                ),

                CategoryFilter(
                    "cat_fv_italo",
                    "Italo (Alta Velocità)",
                    listOf("ITALO"),
                    defaultState = false
                ),

                CategoryFilter(
                    "cat_fv_ic",
                    "Intercity (IC)",
                    listOf("INTERCITY", "IC"),
                    defaultState = false
                )
            )
        )
    }

    private val allStations: List<StationData> by lazy {
        loadStationsFromAssets()
    }

    private fun loadStationsFromAssets(): List<StationData> {

        return try {
            assets.open("stations.json")
                .bufferedReader(Charsets.UTF_8)
                .use { reader ->

                    val stationArray =
                        JSONObject(reader.readText())
                            .getJSONArray("stations")

                    MutableList(stationArray.length()) { index ->

                        val station =
                            stationArray.getJSONObject(index)

                        val aliases =
                            station.getJSONArray("aliases")

                        StationData(
                            name = station.getString("name"),
                            placeId = station.getString("placeId"),
                            efaId = if (station.has("efaId")) station.getString("efaId") else null,
                            lat = station.getDouble("lat"),
                            lon = station.getDouble("lon"),
                            aliases = List(aliases.length()) { aliasIndex ->
                                aliases.getString(aliasIndex)
                            }
                        )
                    }
                }

        } catch (error: Exception) {
            android.util.Log.e(
                "TrainControlSTmobil",
                "stations.json konnte nicht geladen werden",
                error
            )
            emptyList()
        }
    }

    // ====================================================================
    // ACTIVITY
    // ====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(
            "TrainControlSTmobilPrefs",
            MODE_PRIVATE
        )

        if (allStations.isEmpty()) {
            Toast.makeText(
                this,
                "stations.json konnte nicht geladen werden.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        val currentTime = System.currentTimeMillis()

        if ((currentTime - lastExecutionTime) < 5000) {
            Toast.makeText(
                this,
                getString(R.string.wait_moment),
                Toast.LENGTH_SHORT
            ).show()

            finish()
            return
        }

        setShowWhenLocked(true)

        val frameLayout = FrameLayout(this)

        val progressBar = ProgressBar(this).apply {
            isIndeterminate = true
        }

        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }

        frameLayout.addView(
            progressBar,
            layoutParams
        )

        setContentView(frameLayout)

        fusedLocationClient =
            LocationServices
                .getFusedLocationProviderClient(this)

        checkBatteryOptimization()
    }

    @SuppressLint("BatteryLife")
    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.battery_opt_title))
                .setMessage(getString(R.string.battery_opt_message))
                .setPositiveButton(getString(R.string.battery_opt_allow)) { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    } catch (_: Exception) {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = "package:$packageName".toUri()
                        startActivity(intent)
                    }
                    finish()
                }
                .setNegativeButton(getString(R.string.battery_opt_later)) { _, _ ->
                    startAppFlow()
                }
                .setCancelable(false)
                .show()
            return
        }
        startAppFlow()
    }

    // ====================================================================
    // STARTUP / PERMISSIONS
    // ====================================================================

    private fun startAppFlow() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            if (
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.POST_NOTIFICATIONS
                    ),
                    REQUEST_NOTIFICATION_PERMISSION
                )

                return
            }
        }

        checkLocationPermissionAndFetch()
    }

    private fun checkLocationPermissionAndFetch() {

        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                REQUEST_LOCATION_PERMISSION
            )

            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->

                if (location != null) {

                    determineStationAndFetch(location)

                } else {

                    Toast.makeText(
                        this,
                        getString(R.string.no_location),
                        Toast.LENGTH_SHORT
                    ).show()

                    finish()
                }
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        when (requestCode) {

            REQUEST_NOTIFICATION_PERMISSION -> {
                checkLocationPermissionAndFetch()
            }

            REQUEST_LOCATION_PERMISSION -> {

                if (
                    (grantResults.isNotEmpty()) &&
                    (grantResults[0] == PackageManager.PERMISSION_GRANTED)
                ) {

                    checkLocationPermissionAndFetch()

                } else {

                    Toast.makeText(
                        this,
                        getString(R.string.no_location),
                        Toast.LENGTH_LONG
                    ).show()

                    finish()
                }
            }
        }
    }

    // ====================================================================
    // STATION SETTINGS
    // ====================================================================

    private fun getSelectedStation(
        key: String,
        defaultName: String
    ): StationData {

        val savedName =
            prefs.getString(
                key,
                null
            ) ?: defaultName

        return allStations.firstOrNull {
            it.name == savedName
        } ?: allStations.first {
            it.name == defaultName
        }
    }

    private fun getSelectableRegionalStations(): List<StationData> {

        val excludedNames = listOf(
            "Bari Centrale",
            "Roma Termini",
            "Firenze S.M.N.",
            "Verona Porta Nuova",
            "Milano Centrale",
            "Venezia Santa Lucia",
            "Ancona",
            "Napoli Centrale",
            "Bologna Centrale",
            "Rovereto",
            "Ala"
        )

        return allStations
            .asSequence()
            .filter { station ->
                (
                        !station.placeId.startsWith("9900") &&
                                !station.placeId.endsWith("00") &&
                                station.name !in excludedNames
                        )
            }
            .sortedBy {
                it.name
            }
            .toList()
    }

    // ====================================================================
    // AUTOMATISCHE BAHNHOFERMITTLUNG
    // ====================================================================

    private fun determineStationAndFetch(
        location: Location
    ) {

        if (!isNetworkAvailable()) {

            Toast.makeText(
                this,
                getString(R.string.no_internet),
                Toast.LENGTH_LONG
            ).show()

            sendGarminNotification(
                message = getString(R.string.no_internet),
                title = "Zug-Anzeige"
            )

            finish()
            return
        }

        val homeStation =
            getSelectedStation(
                "home_station",
                "Brixen / Bressanone"
            )

        val workStation =
            getSelectedStation(
                "work_station",
                "Bozen / Bolzano"
            )

        val regionalStations =
            allStations.filter {
                !it.placeId.startsWith("9900") &&
                        !it.placeId.endsWith("00")
            }

        val currentStation =
            regionalStations.minByOrNull { station ->

                calculateDistance(
                    location.latitude,
                    location.longitude,
                    station.lat,
                    station.lon
                )
            } ?: homeStation

        val isAtHomeOrWork =
            currentStation.name == homeStation.name ||
                    currentStation.name == workStation.name

        if (isAtHomeOrWork) {

            val targetStation =
                if (
                    currentStation.name ==
                    homeStation.name
                ) {
                    workStation
                } else {
                    homeStation
                }

            startTrainFetch(
                currentStation,
                targetStation
            )

        } else {

            val keyguardManager =
                getSystemService(
                    KEYGUARD_SERVICE
                ) as KeyguardManager

            if (keyguardManager.isKeyguardLocked) {

                finish()

            } else {

                showLocationStationDialog(
                    currentStation,
                    homeStation,
                    workStation
                )
            }
        }
    }

    // ====================================================================
    // POPUP 2: BAHNHOF / RICHTUNG WÄHLEN (Standort-Auswahl)
    // ====================================================================

    private fun showLocationStationDialog(
        detectedStation: StationData,
        homeStation: StationData,
        workStation: StationData
    ) {
        val selectableStations = getSelectableRegionalStations()
        val stationNames = selectableStations.map { it.name }.toTypedArray()

        val containerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val labelDeparture = TextView(this).apply {
            text = getString(R.string.departure_station_label)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }

        val spinnerDeparture = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                stationNames
            )

            val defaultIndex = selectableStations.indexOfFirst {
                it.name == detectedStation.name
            }

            if (defaultIndex >= 0) {
                setSelection(defaultIndex)
            }
        }

        containerLayout.addView(labelDeparture)
        containerLayout.addView(spinnerDeparture)

        val labelTargetHeader = TextView(this).apply {
            text = getString(R.string.select_target_label)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(0, 40, 0, 10)
        }
        containerLayout.addView(labelTargetHeader)

        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 8, 0, 8)
        }

        var dialog: AlertDialog? = null

        val btnWork = android.widget.Button(this).apply {
            text = getString(R.string.to_work_button, workStation.name.split("/").first().trim())
            layoutParams = btnParams
            setOnClickListener {
                val chosenDeparture = selectableStations[spinnerDeparture.selectedItemPosition]
                if (chosenDeparture.name == workStation.name) {
                    Toast.makeText(context, getString(R.string.error_identical_stations), Toast.LENGTH_LONG).show()
                } else {
                    startTrainFetch(chosenDeparture, workStation)
                    dialog?.dismiss()
                }
            }
        }

        val btnHome = android.widget.Button(this).apply {
            text = getString(R.string.to_home_button, homeStation.name.split("/").first().trim())
            layoutParams = btnParams
            setOnClickListener {
                val chosenDeparture = selectableStations[spinnerDeparture.selectedItemPosition]
                if (chosenDeparture.name == homeStation.name) {
                    Toast.makeText(context, getString(R.string.error_identical_stations), Toast.LENGTH_LONG).show()
                } else {
                    startTrainFetch(chosenDeparture, homeStation)
                    dialog?.dismiss()
                }
            }
        }

        val btnCustom = android.widget.Button(this).apply {
            text = getString(R.string.other_target_button)
            layoutParams = btnParams
            setOnClickListener {
                val chosenDeparture = selectableStations[spinnerDeparture.selectedItemPosition]
                dialog?.dismiss()
                showCustomSearchDialog(chosenDeparture, homeStation)
            }
        }

        val btnCancel = android.widget.Button(this).apply {
            text = getString(R.string.cancel_button)
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = btnParams
            setOnClickListener {
                dialog?.dismiss()
                finish()
            }
        }

        containerLayout.addView(btnWork)
        containerLayout.addView(btnHome)
        containerLayout.addView(btnCustom)
        containerLayout.addView(btnCancel)

        dialog = AlertDialog.Builder(this)
            .setTitle("Standort: ${detectedStation.name.split("/").first().trim()}")
            .setView(containerLayout)
            .setCancelable(false)
            .create()

        dialog.show()
    }

    // ====================================================================
    // BAHNHOF WECHSELN: FREIE SUCHE (Manuelle Eingabe)
    // ====================================================================

    private fun showCustomSearchDialog(
        currentDeparture: StationData,
        currentDestination: StationData
    ) {
        val selectableStations = getSelectableRegionalStations()
        val stationNames = selectableStations.map { it.name }.toTypedArray()

        val containerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }

        val labelDeparture = TextView(this).apply {
            text = getString(R.string.departure_station_label)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }

        val spinnerDeparture = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                stationNames
            )
            val defaultIndex = selectableStations.indexOfFirst {
                it.name == currentDeparture.name
            }
            if (defaultIndex >= 0) {
                setSelection(defaultIndex)
            }
        }

        val labelTarget = TextView(this).apply {
            text = getString(R.string.target_station_label)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(0, 30, 0, 10)
        }

        val spinnerTarget = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                stationNames
            )
            val defaultIndex = selectableStations.indexOfFirst {
                it.name == currentDestination.name
            }
            if (defaultIndex >= 0) {
                setSelection(defaultIndex)
            }
        }

        containerLayout.addView(labelDeparture)
        containerLayout.addView(spinnerDeparture)
        containerLayout.addView(labelTarget)
        containerLayout.addView(spinnerTarget)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Bahnhof wechseln")
            .setView(containerLayout)
            .setPositiveButton("Suchen", null)
            .setNegativeButton("Abbrechen") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val chosenDeparture = selectableStations[spinnerDeparture.selectedItemPosition]
            val chosenTarget = selectableStations[spinnerTarget.selectedItemPosition]

            if (chosenDeparture.name == chosenTarget.name) {
                Toast.makeText(
                    this,
                    "Abfahrtsbahnhof und Zielbahnhof sind identisch.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                startTrainFetch(chosenDeparture, chosenTarget)
                dialog.dismiss()
            }
        }

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            ?.setTextColor(Color.DKGRAY)
    }

    // ====================================================================
    // ZUGABFRAGE
    // ====================================================================

    private fun startTrainFetch(
        currentStation: StationData,
        targetStation: StationData
    ) {

        // Meraner Linie erkennen
        val fromIsMeranLine = MERAN_LINE_ALIASES.any { alias ->
            currentStation.aliases.any {
                it.equals(alias, ignoreCase = true)
            }
        }

        val targetIsMeranLine = MERAN_LINE_ALIASES.any { alias ->
            targetStation.aliases.any {
                it.equals(alias, ignoreCase = true)
            }
        }

        // Fahrtrichtung bestimmen wird nicht mehr benötigt (EFA regelt das)
        lifecycleScope.launch(Dispatchers.IO) {
            val trains = fetchAndParseTrains(currentStation, targetStation)

            val relevantTrains =
                trains.filter {
                    it.stopsAtTarget != false
                }

            // NEU: Anzahl der Züge für den Alarm-Check aus den Settings auslesen (Standard: 3)
            val alarmTrainCount = prefs.getInt("alarm_train_count", 3)

            val delayedTrain =
                relevantTrains.asSequence().take(alarmTrainCount).firstOrNull {
                    it.hasDelay
                }

            lastExecutionTime =
                System.currentTimeMillis()

            val isScreenLocked =
                (getSystemService(
                    KEYGUARD_SERVICE
                ) as KeyguardManager)
                    .isKeyguardLocked

            if (delayedTrain != null) {

                playSingleBeep()

                // Bei entsperrtem Bildschirm zeigt das Popup das Ergebnis.
                // Android-/Garmin-Benachrichtigungen sind nur bei Sperre nötig.
                if (isScreenLocked) {
                    sendGarminNotification(

                        message =
                            "Zug ${delayedTrain.categoryNumber}\n" +
                                    "nach ${delayedTrain.destination}\n" +
                                    "${delayedTrain.time} Uhr\n" +
                                    "Verspätung: ${delayedTrain.delay}",

                        title =
                            "⚠️ Zugverspätung"
                    )
                }
            }

            if (trains.isEmpty() && isScreenLocked) {

                sendGarminNotification(
                    message =
                        getString(
                            R.string.no_departures
                        ),
                    title =
                        "Zug-Anzeige"
                )
            }

            withContext(
                Dispatchers.Main
            ) {

                val keyguardManager =
                    getSystemService(
                        KEYGUARD_SERVICE
                    ) as KeyguardManager

                if (
                    keyguardManager.isKeyguardLocked
                ) {

                    finish()

                } else {

                    showResultPopup(
                        currentStation,
                        targetStation,
                        trains
                    )
                }
            }
        }
    }

    // ====================================================================
    // INTERNET
    // ====================================================================

    private fun isNetworkAvailable(): Boolean {

        val connectivityManager =
            getSystemService(
                CONNECTIVITY_SERVICE
            ) as ConnectivityManager

        val network =
            connectivityManager.activeNetwork
                ?: return false

        val activeNetwork =
            connectivityManager
                .getNetworkCapabilities(network)
                ?: return false

        return activeNetwork.hasTransport(
            NetworkCapabilities.TRANSPORT_WIFI
        ) ||
                activeNetwork.hasTransport(
                    NetworkCapabilities.TRANSPORT_CELLULAR
                ) ||
                activeNetwork.hasTransport(
                    NetworkCapabilities.TRANSPORT_ETHERNET
                )
    }

    // ====================================================================
    // RFI ANZEIGETAFEL AUSLESEN
    // ====================================================================

    private suspend fun fetchAndParseTrains(
        fromStation: StationData,
        targetStation: StationData
    ): List<TrainInfo> {
        val rawTrainList = mutableListOf<TrainInfo>()
        val limit = 10

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

                // Abfrage: 60 Min davor (-60m) und Zukunft (jetzt)
                val queryOffsets = listOf(60L, 0L)

                for (offset in queryOffsets) {
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
                        } catch (e: Exception) {
                            null
                        }
                    } ?: continue

                    android.util.Log.d("TrainFetch", "Response: $responseStr")

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
                            val isWalk = tName.contains("Fußweg", true) || legObj.optBoolean("isWalk", false)
                            if (isWalk) continue

                            val upper = tName.uppercase()
                            val isBus = (upper.contains("BUS") || upper.contains("SAD") || upper.contains("SASA") || upper.contains("LINIE")) &&
                                    !upper.contains(" R ") && !upper.startsWith("R ") && !upper.contains("RV") && !upper.contains("RE ") && !upper.contains("EC") && !upper.contains("RJ")

                            val isTrenordOrRJ = upper.contains("RJ") || upper.contains("RAILJET") || upper.contains("EC")
                            val isFreccia = upper.contains("FRECCIA") || upper.contains("FR ")
                            val isItalo = upper.contains("ITALO")
                            val isIC = upper.contains("INTERCITY") || upper.contains("IC ")
                            val isRv = upper.contains("RV") || upper.contains("REGIONALE VELOCE")
                            val isReg = !isBus && !isRv && !isTrenordOrRJ && !isFreccia && !isItalo && !isIC

                            if (isBus || (isTrenordOrRJ && !allowTrenord) || (isFreccia && !allowFreccia) || (isItalo && !allowItalo) || (isIC && !allowIC) || (isRv && !allowRv) || (isReg && !allowReg)) {
                                tripBanned = true
                                break
                            }
                        }
                        if (tripBanned) continue

                        // Direktverbindung prüfen
                        val vehicleLegs = legs.filter { leg ->
                            val legTransp = leg.optJSONObject("transportation") ?: leg.optJSONObject("mode")
                            val legName = legTransp?.optString("name") ?: ""
                            val isWalk = legName.contains("Fußweg", true) || leg.optBoolean("isWalk", false)
                            !isWalk
                        }

                        if (vehicleLegs.size > 1) continue

                        // Trip-Vollständigkeit prüfen
                        val lastLeg = legs.lastOrNull()
                        val tripDestNode = lastLeg?.optJSONObject("destination")
                        val tripDestId = tripDestNode?.optString("id") ?: ""
                        val tripDestName = tripDestNode?.optString("name") ?: ""

                        val targetIdSuffix = if (efaToId.length >= 4) efaToId.takeLast(4) else efaToId
                        val matchesTargetId = targetIdSuffix.isNotEmpty() && tripDestId.contains(targetIdSuffix)

                        val cleanTarget = targetStation.name.split("/").first().trim()
                        val matchesTargetName = tripDestName.contains(cleanTarget, true) ||
                                targetStation.name.contains(tripDestName.split("/").first().trim(), true)

                        if (!matchesTargetId && !matchesTargetName) continue

                        val mainLeg = vehicleLegs.firstOrNull() ?: continue
                        val pointsArray = mainLeg.optJSONArray("point") ?: mainLeg.optJSONArray("points") ?: JSONArray()
                        val points = List(pointsArray.length()) { pointsArray.getJSONObject(it) }
                        
                        val originNode = mainLeg.optJSONObject("origin") ?: points.firstOrNull { it.optString("usage") == "departure" } ?: points.firstOrNull()
                        val transpNode = mainLeg.optJSONObject("transportation") ?: mainLeg.optJSONObject("mode")

                        if (originNode == null || transpNode == null) continue

                        val transpName = transpNode.optString("name").takeIf { it.isNotEmpty() }
                            ?: transpNode.optString("disassembledName").takeIf { it.isNotEmpty() } ?: "Zug"

                        val lineTerminal = transpNode.optJSONObject("destination")?.optString("name")
                            ?: transpNode.optString("direction").takeIf { it.isNotEmpty() }
                            ?: transpNode.optString("destination").takeIf { it.isNotEmpty() }
                            ?: targetStation.name

                        val upperCat = transpName.uppercase()
                        val isBus = (upperCat.contains("BUS") || upperCat.contains("SAD") || upperCat.contains("SASA") || upperCat.contains("LINIE")) &&
                                !upperCat.contains(" R ") && !upperCat.startsWith("R ") && !upperCat.contains("RV") && !upperCat.contains("RE ")

                        val planDate = extractDate(originNode, listOf("itdTime", "dateTime", "departureTimePlanned", "date")) ?: queryStart.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                        val planTime = extractTime(originNode, listOf("itdTime", "dateTime", "departureTimePlanned", "time"))
                        if (planTime == null) continue

                        val realTime = extractTime(originNode, listOf("itdRTTime", "realDateTime", "departureTimeEstimated", "rtTime")) ?: planTime

                        val actualDeparture = calculateActualDepartureDateTime(planDate, planTime, realTime)
                        if (!actualDeparture.isAfter(now.minusMinutes(1))) continue
                        if (actualDeparture.isAfter(now.plusHours(5))) continue

                        if (rawTrainList.any { it.categoryNumber == transpName && it.time == planTime }) continue

                        val trainInfo = TrainInfo(
                            categoryNumber = transpName,
                            destination = lineTerminal,
                            time = planTime,
                            delay = "pünktlich",
                            platform = originNode.optString("platformName", "-"),
                            hasDelay = false,
                            isBus = isBus,
                            stopsAtTarget = true,
                            lineTerminal = lineTerminal
                        )

                        val idx = rawTrainList.size
                        rawTrainList.add(trainInfo)

                        if (realTime != null && realTime != planTime) {
                            val plannedLocalTime = parseLocalTime(planTime)
                            val actualLocalTime = parseLocalTime(realTime)
                            if (plannedLocalTime != null && actualLocalTime != null) {
                                val pTotal = plannedLocalTime.hour * 60 + plannedLocalTime.minute
                                var rTotal = actualLocalTime.hour * 60 + actualLocalTime.minute
                                if (rTotal < pTotal && (pTotal - rTotal) > 720) rTotal += 1440
                                val delayMins = rTotal - pTotal
                                if (delayMins > 0) {
                                    rawTrainList[idx] = rawTrainList[idx].copy(delay = "+$delayMins Min.", hasDelay = true)
                                }
                            }
                        }

                        val isCancelled = originNode.optString("isCancelled") == "1" || originNode.optBoolean("isCancelled", false)
                        if (isCancelled) {
                            rawTrainList[idx] = rawTrainList[idx].copy(delay = "entfällt", hasDelay = true)
                        }

                        if (rawTrainList.size >= limit) break
                    }
                }

                // RFI GEGENCHECK
                try {
                    val rfiUrl = "https://iechub.rfi.it/ArriviPartenze/arrivalsdepartures/Monitor?placeId=${fromStation.placeId}&arrivals=False"
                    val rfiDoc = withContext(Dispatchers.IO) {
                        try {
                            Jsoup.connect(rfiUrl).timeout(8000).userAgent("Mozilla/5.0").get()
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (rfiDoc != null) {
                        val rfiRows = rfiDoc.select("tr")
                        for ((i, train) in rawTrainList.withIndex()) {
                            val efaNum = train.categoryNumber.filter { it.isDigit() }
                            if (efaNum.isBlank()) continue

                            val matchedRow = rfiRows.firstOrNull { it.text().contains(efaNum) }
                            if (matchedRow != null) {
                                val cols = matchedRow.select("td")
                                if (cols.size >= 5) {
                                    val timeRegex = Regex("""\b\d{2}:\d{2}\b""")
                                    val colTexts = cols.map { it.text().trim() }
                                    val timeIdx = colTexts.indexOfFirst { timeRegex.containsMatchIn(it) }

                                    if (timeIdx != -1 && colTexts.size > timeIdx + 1) {
                                        val rawDelay = colTexts[timeIdx + 1]
                                        val isCancelled = rawDelay.contains("SOP", true) || rawDelay.contains("CANC", true) || matchedRow.text().contains("SOPPRESSO", true)

                                        val statusText = when {
                                            isCancelled -> "entfällt"
                                            rawDelay.isBlank() || rawDelay == "0" -> "pünktlich"
                                            else -> "Verspätung"
                                        }

                                        val delayDisplay = when {
                                            isCancelled -> ""
                                            rawDelay.isBlank() || rawDelay == "0" -> "+0"
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
                    // Ignore RFI errors
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (rawTrainList.isNotEmpty()) break
            if (attempt == 1) delay(1.seconds)
        }

        return rawTrainList.sortedBy { it.time }
    }

    // ====================================================================
    // PRÜFUNG: HÄLT DER ZUG AM ZIEL (RFI-MONITOR)
    // ====================================================================

    private fun checkTrainStopsInRfiRow(
        row: Element,
        targetStation: StationData
    ): Boolean? {

        val stopText =
            row.text()
                .substringAfter(
                    "FERMA A:",
                    missingDelimiterValue = ""
                )
                .substringBefore("Informazioni")
                .trim()

        // Kein Info-Popup oder keine Haltestellen: nicht bewertbar.
        if (stopText.isBlank()) {
            return null
        }

        /*
         * Zusätzlich zu den Aliasen berücksichtigen wir die Namen aus
         * "Deutsch / Italienisch", beispielsweise ROMA TERMINI.
         * Der exakte Vergleich vermeidet Fehlalarme wie BOLZANO SUD
         * für das Ziel BOLZANO.
         */
        val targetNames =
            (targetStation.aliases + targetStation.name.split("/"))
                .asSequence()
                .map { normalizeStationName(it) }
                .filter { it.isNotBlank() }
                .toSet()

        val stops =
            stopText
                .split(Regex("""\s*-\s*"""))
                .map { stop ->
                    stop
                        .replace(
                            Regex("""\s*\(\d{1,2}:\d{2}\)"""),
                            ""
                        )
                        .trim()
                }
                .filter { it.isNotBlank() }

        return stops.any { stop ->
            stop
                .split("/")
                .map { normalizeStationName(it) }
                .any { stopName ->
                    stopName in targetNames
                }
        }
    }





// ====================================================================
// STATIONSNAMEN NORMALISIEREN
// ====================================================================

    private fun normalizeStationName(
        value: String
    ): String {

        return value
            .uppercase(Locale.ROOT)
            .replace("Ä", "A")
            .replace("Ö", "O")
            .replace("Ü", "U")
            .replace("À", "A")
            .replace("È", "E")
            .replace("É", "E")
            .replace("Ì", "I")
            .replace("Ò", "O")
            .replace("Ù", "U")
            .replace(
                Regex("""[^A-Z0-9]+"""),
                " "
            )
            .trim()
            .replace(
                Regex("""\s+"""),
                " "
            )
    }

    // ====================================================================
    // ANDROID BENACHRICHTIGUNG
    // ====================================================================

    private fun sendGarminNotification(
        message: String,
        title: String = "Zug-Anzeige"
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                android.util.Log.w("TrainControlSTmobil", "POST_NOTIFICATIONS nicht erlaubt")
                return
            }
        }

        val channelId = "train_delay_instant_v6"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_description)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
            setBypassDnd(true)
        }
        notificationManager.createNotificationChannel(channel)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message.replace("\n", " "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)

        notificationManager.notify(1001, builder.build())
    }


    // ====================================================================
    // SIGNALTON
    // ====================================================================

    private fun playSingleBeep() {
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 350)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    // ====================================================================
    // POPUP 1: ERGEBNISSE
    // ====================================================================

    @SuppressLint("SetTextI18n")
    private fun showResultPopup(
        fromStation: StationData,
        targetStation: StationData,
        trains: List<TrainInfo>
    ) {

        val messageBuilder =
            StringBuilder()

        if (trains.isEmpty()) {

            messageBuilder.append(
                getString(
                    R.string.no_departures
                )
            )

        } else {

            val shortTargetName =
                targetStation
                    .name
                    .split("/")
                    .first()
                    .trim()

            trains.forEachIndexed { index, train ->

                messageBuilder.append(
                    getString(
                        R.string.train_item_header,
                        index + 1,
                        train.categoryNumber
                    )
                )

                messageBuilder.append(
                    "<br>"
                )

                messageBuilder.append(
                    "${train.destination}<br>"
                )

                messageBuilder.append(
                    getString(
                        R.string.departure_time_format,
                        train.time
                    )
                )

                messageBuilder.append(
                    "<br>"
                )

                when (
                    train.stopsAtTarget
                ) {

                    true -> {

                        messageBuilder.append(
                            getString(
                                R.string.stops_true_format,
                                shortTargetName
                            )
                        )

                        messageBuilder.append(
                            "<br>"
                        )
                    }

                    false -> {

                        messageBuilder.append(
                            getString(
                                R.string.stops_false_format,
                                shortTargetName
                            )
                        )

                        messageBuilder.append(
                            "<br>"
                        )
                    }

                    null -> {
                        /*
                         * Bei API-Problemen
                         * nichts anzeigen.
                         */
                    }
                }

                if (train.isBus) {

                    messageBuilder.append(
                        getString(
                            R.string.platform_bus_label
                        )
                    )

                    messageBuilder.append(
                        "<br>"
                    )

                } else {

                    messageBuilder.append(
                        getString(
                            R.string.platform_label_format,
                            train.platform
                        )
                    )

                    messageBuilder.append(
                        "<br>"
                    )
                }

                if (train.hasDelay) {

                    messageBuilder.append(
                        getString(
                            R.string
                                .delay_label_critical_format,
                            train.delay
                        )
                    )

                } else {

                    messageBuilder.append(
                        getString(
                            R.string.delay_label_format,
                            train.delay
                        )
                    )
                }

                // RFI Cross-Check Info
                if (train.rfiStatus != null || train.rfiDelay != null) {
                    messageBuilder.append("<br><small><font color='#999999'>RFI: ${train.rfiStatus ?: "pünktlich"} (${train.rfiDelay ?: "+0"})</font></small>")
                }

                if (
                    index < trains.size - 1
                ) {

                    messageBuilder.append(
                        "<br>-----------------------------------<br>"
                    )
                }
            }
        }

        val headerLayout =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    50,
                    40,
                    50,
                    10
                )
            }

        val titleText =
            TextView(this).apply {

                text =
                    getString(
                        R.string.departure_station_format,
                        fromStation.name
                    )

                textSize = 17f

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setTextColor(
                    Color.BLACK
                )
            }

        val changeStationText =
            TextView(this).apply {

                text = "Bahnhof wechseln..."

                textSize = 14f

                setTextColor(
                    Color.BLACK
                )

                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG

                setPadding(
                    0,
                    10,
                    0,
                    0
                )
            }

        headerLayout.addView(
            titleText
        )

        headerLayout.addView(
            changeStationText
        )

        val scrollView =
            ScrollView(this).apply {
                setPadding(
                    50,
                    10,
                    50,
                    10
                )
            }

        val textView =
            TextView(this).apply {

                text =
                    android.text.Html.fromHtml(
                        messageBuilder.toString(),
                        android.text.Html
                            .FROM_HTML_MODE_LEGACY
                    )

                textSize = 15f

                setTextColor(
                    Color.BLACK
                )
            }

        scrollView.addView(
            textView
        )

        val dialogInstance =
            AlertDialog.Builder(this)

                .setCustomTitle(
                    headerLayout
                )

                .setView(
                    scrollView
                )

                .setPositiveButton(
                    "OK"
                ) { d, _ ->

                    d.dismiss()
                    finish()
                }

                .setNeutralButton(
                    "⚙️"
                ) { d, _ ->

                    d.dismiss()
                    showSettingsDialog()
                }

                .setCancelable(false)
                .create()

        changeStationText.setOnClickListener {
            dialogInstance.dismiss()
            showCustomSearchDialog(fromStation, targetStation)
        }

        dialogInstance.show()

        dialogInstance
            .getButton(
                AlertDialog.BUTTON_POSITIVE
            )
            ?.setTextColor(
                Color.BLACK
            )

        dialogInstance
            .getButton(
                AlertDialog.BUTTON_NEUTRAL
            )
            ?.setTextColor(
                Color.BLACK
            )

        dialogInstance
            .getButton(
                AlertDialog.BUTTON_NEUTRAL
            )
            ?.textSize = 18f
    }


    // ====================================================================
    // EINSTELLUNGEN
    // ====================================================================

    private fun showSettingsDialog() {

        val containerLayout =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    60,
                    40,
                    60,
                    40
                )
            }

        val regionalStations =
            getSelectableRegionalStations()

        val stationNames =
            regionalStations
                .map {
                    it.name
                }
                .toTypedArray()

        val currentHome =
            prefs.getString(
                "home_station",
                "Brixen / Bressanone"
            ) ?: "Brixen / Bressanone"

        val currentWork =
            prefs.getString(
                "work_station",
                "Bozen / Bolzano"
            ) ?: "Bozen / Bolzano"

        val spinnerHome =
            Spinner(this).apply {

                adapter =
                    ArrayAdapter(
                        this@MainActivity,
                        android.R.layout
                            .simple_spinner_dropdown_item,
                        stationNames
                    )

                val index =
                    stationNames.indexOf(
                        currentHome
                    )

                if (index >= 0) {
                    setSelection(index)
                }
            }

        val spinnerWork =
            Spinner(this).apply {

                adapter =
                    ArrayAdapter(
                        this@MainActivity,
                        android.R.layout
                            .simple_spinner_dropdown_item,
                        stationNames
                    )

                val index =
                    stationNames.indexOf(
                        currentWork
                    )

                if (index >= 0) {
                    setSelection(index)
                }
            }

        containerLayout.addView(
            TextView(this).apply {

                text =
                    getString(
                        R.string.home_address_label
                    )

                setTypeface(
                    null,
                    Typeface.BOLD
                )
            }
        )

        containerLayout.addView(
            spinnerHome
        )

        containerLayout.addView(
            TextView(this).apply {

                text =
                    getString(
                        R.string.work_address_label
                    )

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setPadding(
                    0,
                    20,
                    0,
                    0
                )
            }
        )

        containerLayout.addView(
            spinnerWork
        )

        // NEU: Alarm-Anzahl Einstellung
        val alarmOptions = arrayOf("den nächsten Zug", "die nächsten 2 Züge", "die nächsten 3 Züge")
        val currentAlarmCount = prefs.getInt("alarm_train_count", 3)

        val spinnerAlarmCount = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                alarmOptions
            )
            // Array Index beginnt bei 0 (1 Zug = Index 0, 2 Züge = Index 1, ...)
            setSelection(currentAlarmCount - 1)
        }

        containerLayout.addView(
            TextView(this).apply {
                text = getString(R.string.alarm_label)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 30, 0, 0)
            }
        )

        containerLayout.addView(
            spinnerAlarmCount
        )
        // ENDE NEU

        containerLayout.addView(
            TextView(this).apply {

                text =
                    getString(
                        R.string.operators_label
                    )

                setTypeface(
                    null,
                    Typeface.BOLD
                )

                setPadding(
                    0,
                    30,
                    0,
                    10
                )
            }
        )

        val checkboxMap =
            mutableMapOf<String, CheckBox>()

        CATEGORY_GROUPS.forEach { (groupTitle, filters) ->

            val groupHeader =
                TextView(this).apply {

                    text =
                        getString(
                            R.string.operator_format,
                            groupTitle
                        )

                    setTypeface(
                        null,
                        Typeface.BOLD
                    )

                    setPadding(
                        10,
                        15,
                        0,
                        5
                    )

                    setTextColor(
                        Color.DKGRAY
                    )
                }

            containerLayout.addView(
                groupHeader
            )

            filters.forEach {
                    filter ->

                val isChecked =
                    prefs.getBoolean(
                        filter.prefKey,
                        filter.defaultState
                    )

                val cb =
                    CheckBox(this).apply {

                        text =
                            filter.label

                        this.isChecked =
                            isChecked

                        setPadding(
                            30,
                            0,
                            0,
                            0
                        )
                    }

                checkboxMap[
                    filter.prefKey
                ] = cb

                containerLayout.addView(
                    cb
                )
            }
        }

        val scrollView =
            ScrollView(this).apply {
                addView(
                    containerLayout
                )
            }

        val alertDialog =
            AlertDialog.Builder(this)

                .setTitle(
                    getString(
                        R.string.settings_dialog_title
                    )
                )

                .setView(
                    scrollView
                )

                .setPositiveButton(
                    getString(
                        R.string.save_action
                    ),
                    null
                )

                .setNegativeButton(
                    getString(
                        R.string.cancel_button
                    )
                ) { _, _ ->
                    finish()
                }

                .create()

        alertDialog.show()

        alertDialog
            .getButton(
                AlertDialog.BUTTON_POSITIVE
            )
            .setOnClickListener {

                val selectedHome =
                    spinnerHome
                        .selectedItem
                        .toString()

                val selectedWork =
                    spinnerWork
                        .selectedItem
                        .toString()

                if (
                    selectedHome ==
                    selectedWork
                ) {

                    Toast.makeText(
                        this,
                        getString(
                            R.string
                                .error_identical_addresses
                        ),
                        Toast.LENGTH_LONG
                    ).show()

                } else {

                    // NEU: Alarm-Anzahl auslesen und speichern
                    val selectedAlarmCount = spinnerAlarmCount.selectedItemPosition + 1

                    prefs.edit {

                        putString(
                            "home_station",
                            selectedHome
                        )

                        putString(
                            "work_station",
                            selectedWork
                        )

                        putInt(
                            "alarm_train_count",
                            selectedAlarmCount
                        )

                        checkboxMap.forEach { (key, cb) ->

                            putBoolean(
                                key,
                                cb.isChecked
                            )
                        }
                    }

                    Toast.makeText(
                        this,
                        getString(
                            R.string.settings_saved
                        ),
                        Toast.LENGTH_SHORT
                    ).show()

                    alertDialog.dismiss()

                    finish()
                }
            }
    }


    // ====================================================================
    // DISTANZ
    // ====================================================================

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    // --- EFA Helpers ---

    private fun getAsList(node: JSONObject, key: String): List<JSONObject> {
        val array = node.optJSONArray(key) ?: return emptyList()
        return List(array.length()) { array.getJSONObject(it) }
    }

    private fun extractDate(node: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            val date = node.optString(key).takeIf { it.isNotEmpty() }
            if (date != null) return date
        }
        val dateTime = node.optJSONObject("dateTime")
        if (dateTime != null) {
            return dateTime.optString("date").takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun extractTime(node: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            val element = node.opt(key)
            val time = parseTimeFromElement(element, key.contains("RT", true) || key.contains("Estimated", true))
            if (time != null) return time
        }
        val dateTime = node.optJSONObject("dateTime")
        if (dateTime != null) {
            val isRT = keys.any { it.contains("RT", true) || it.contains("Estimated", true) }
            val t = if (isRT) {
                dateTime.optString("rtTime").takeIf { it.isNotEmpty() }
                    ?: dateTime.optString("time").takeIf { it.isNotEmpty() }
            } else {
                dateTime.optString("time").takeIf { it.isNotEmpty() }
            }
            if (t != null) {
                val match = Regex("""\b(\d{2}:\d{2})\b""").find(t)
                if (match != null) return match.value
            }
        }
        return null
    }

    private fun parseTimeFromElement(element: Any?, isRT: Boolean): String? {
        if (element is JSONObject) {
            val h = element.optString("hour").takeIf { it.isNotEmpty() }?.padStart(2, '0')
            val m = element.optString("minute").takeIf { it.isNotEmpty() }?.padStart(2, '0')
            if (h != null && m != null) return "$h:$m"

            val t = if (isRT) {
                element.optString("rtTime").takeIf { it.isNotEmpty() }
                    ?: element.optString("time").takeIf { it.isNotEmpty() }
            } else {
                element.optString("time").takeIf { it.isNotEmpty() }
            }
            if (t != null) {
                val match = Regex("""\b(\d{2}:\d{2})\b""").find(t)
                if (match != null) return match.value
            }
        } else if (element is String) {
            val match = Regex("""\b(\d{2}:\d{2})\b""").find(element)
            if (match != null) return match.value
        }
        return null
    }

    private fun calculateActualDepartureDateTime(planDate: String, planTime: String, realTime: String): LocalDateTime {
        val date = try {
            java.time.LocalDate.parse(planDate, DateTimeFormatter.ofPattern("yyyyMMdd"))
        } catch (e: Exception) {
            java.time.LocalDate.now()
        }
        val pTime = parseLocalTime(planTime) ?: LocalTime.now()
        val rTime = parseLocalTime(realTime) ?: pTime

        var dateTime = LocalDateTime.of(date, rTime)
        // Mitternachts-Korrektur
        if (rTime.isBefore(pTime) && pTime.hour > 20 && rTime.hour < 4) {
            dateTime = dateTime.plusDays(1)
        }
        return dateTime
    }

    private fun parseLocalTime(timeStr: String): LocalTime? {
        return try {
            LocalTime.parse(timeStr, DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
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
                if (points != null && points.length() > 0) {
                    points.getJSONObject(0).optString("stateless", "66000468")
                } else {
                    "66000468"
                }
            } catch (e: Exception) {
                "66000468"
            }
        }
    }
}
