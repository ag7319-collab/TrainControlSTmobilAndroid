package com.example.traincontrolauto

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
import org.json.JSONObject
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
)

data class StationData(
    val name: String,
    val placeId: String,
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
            "LANA-POSTAL",
            "LANA-BURGSTALL",
            "GARGAZZONE",
            "GARGAZON",
            "VILPIANO-NALLES",
            "VILPIAN-NALS",
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
                "TrainControl",
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
            "TrainControlPrefs",
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

        // Fahrtrichtung bestimmen.
        // Bei einem Linienwechsel zwischen Eisacktal- und Meraner Linie
        // ist Bozen das entscheidende Zwischenziel.
        val goingNorth =
            if (
                fromIsMeranLine != targetIsMeranLine &&
                currentStation.placeId != "685"
            ) {
                46.4983 > currentStation.lat
            } else {
                targetStation.lat > currentStation.lat
            }

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            val trains =
                fetchAndParseTrains(
                    currentStation,
                    targetStation,
                    goingNorth
                )

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
        targetStation: StationData,
        goingNorth: Boolean
    ): List<TrainInfo> {

        val limit = 3

        val url =
            "https://iechub.rfi.it/ArriviPartenze/arrivalsdepartures/Monitor" +
                    "?placeId=${fromStation.placeId}" +
                    "&arrivals=False"

        val rawTrainList =
            mutableListOf<TrainInfo>()

        val allowReg =
            prefs.getBoolean(
                "cat_reg",
                true
            )

        val allowRv =
            prefs.getBoolean(
                "cat_rv",
                true
            )

        val allowBus =
            prefs.getBoolean(
                "cat_bus",
                true
            )

        val allowTrenord =
            prefs.getBoolean(
                "cat_tn_rj",
                false
            )

        val allowFreccia =
            prefs.getBoolean(
                "cat_fv_freccia",
                false
            )

        val allowItalo =
            prefs.getBoolean(
                "cat_fv_italo",
                false
            )

        val allowIC =
            prefs.getBoolean(
                "cat_fv_ic",
                false
            )

        val targetIsMeranLine =
            MERAN_LINE_ALIASES.any { alias ->

                targetStation.aliases.any {
                    it.equals(
                        alias,
                        ignoreCase = true
                    )
                }
            }

        val targetIsBozen =
            targetStation.placeId == "685" ||
                    targetStation.aliases.any {
                        it.equals(
                            "BOZEN",
                            ignoreCase = true
                        ) ||
                                it.equals(
                                    "BOLZANO",
                                    ignoreCase = true
                                )
                    }

        val targetIsSouthOfBozen =
            targetStation.lat < 46.45

        for (attempt in 1..2) {

            rawTrainList.clear()

            try {

                val doc =
                    Jsoup.connect(url)
                        .timeout(12000)
                        .userAgent(
                            "Mozilla/5.0 " +
                                    "(Windows NT 10.0; Win64; x64) " +
                                    "AppleWebKit/537.36 " +
                                    "(KHTML, like Gecko) " +
                                    "Chrome/120.0.0.0 Safari/537.36"
                        )
                        .get()

                val rows =
                    doc.select("tr")

                val timeRegex =
                    Regex(
                        """\b\d{2}:\d{2}\b"""
                    )

                for (row in rows) {

                    val cols =
                        row.select("td")

                    if (cols.isEmpty()) {
                        continue
                    }

                    val colTexts =
                        cols.map {
                            it.text().trim()
                        }

                    val fullRowTextUpper =
                        colTexts
                            .joinToString(" ")
                            .uppercase()

                    val fullRowHtmlLower =
                        row.outerHtml()
                            .lowercase()

                    val timeCellIndex =
                        colTexts.indexOfFirst {
                            timeRegex.containsMatchIn(it)
                        }

                    if (timeCellIndex == -1) {
                        continue
                    }

                    val categoryPartUpper =
                        colTexts
                            .subList(
                                0,
                                timeCellIndex
                            )
                            .joinToString(" ")
                            .uppercase()

                    val trainIsMeranLine =
                        MERAN_LINE_ALIASES.any {
                                alias ->
                            fullRowTextUpper
                                .contains(alias)
                        }

                    if (
                        targetIsMeranLine &&
                        !trainIsMeranLine
                    ) {
                        continue
                    }

                    if (
                        targetIsSouthOfBozen &&
                        !targetIsBozen &&
                        trainIsMeranLine
                    ) {
                        continue
                    }

                    val isTrenordOrRJ =
                        fullRowHtmlLower
                            .contains("trenord") ||
                                fullRowHtmlLower
                                    .contains("/tn.") ||
                                fullRowTextUpper
                                    .contains("TRENORD") ||
                                categoryPartUpper
                                    .contains("RJ") ||
                                categoryPartUpper
                                    .contains("RAILJET") ||
                                categoryPartUpper
                                    .contains("EC")

                    val isFreccia =
                        fullRowHtmlLower
                            .contains("freccia") ||
                                categoryPartUpper
                                    .contains("FRECCIA") ||
                                Regex(
                                    """\bFR\b"""
                                ).containsMatchIn(
                                    categoryPartUpper
                                )

                    val isItalo =
                        fullRowHtmlLower
                            .contains("italo") ||
                                categoryPartUpper
                                    .contains("ITALO")

                    val isIC =
                        fullRowHtmlLower
                            .contains("intercity") ||
                                categoryPartUpper
                                    .contains("INTERCITY") ||
                                Regex(
                                    """\bIC\b"""
                                ).containsMatchIn(
                                    categoryPartUpper
                                )

                    val isBus =
                        categoryPartUpper.contains("BUS") ||
                                fullRowTextUpper.contains("BUS")

                    val isRv =
                        categoryPartUpper.contains("RV") ||
                                categoryPartUpper.contains("REGIONALE VELOCE") ||
                                Regex("""\bRV\b""").containsMatchIn(categoryPartUpper)

                    val isReg =
                        !isBus && !isRv && (
                                categoryPartUpper.contains("REG") ||
                                        categoryPartUpper.contains("REGIONALE") ||
                                        categoryPartUpper.contains("SAD") ||
                                        (!isTrenordOrRJ && !isFreccia && !isItalo && !isIC)
                                )

                    if (isTrenordOrRJ && !allowTrenord) {
                        continue
                    }

                    if (isFreccia && !allowFreccia) {
                        continue
                    }

                    if (isItalo && !allowItalo) {
                        continue
                    }

                    if (isIC && !allowIC) {
                        continue
                    }

                    if (isBus && !allowBus) {
                        continue
                    }

                    if (isRv && !allowRv) {
                        continue
                    }

                    if (isReg && !allowReg) {
                        continue
                    }

                    val time =
                        colTexts[
                            timeCellIndex
                        ]

                    val stationCandidates =
                        allStations
                            .asSequence()
                            .flatMap { station ->

                                station.aliases.map {
                                        alias ->
                                    station to alias
                                }
                            }
                            .sortedByDescending {
                                it.second.length
                            }
                            .toList()

                    var matchingStation:
                            StationData? = null

                    for (
                    candidate
                    in stationCandidates
                    ) {

                        val station =
                            candidate.first

                        val alias =
                            candidate.second

                        if (
                            fullRowTextUpper
                                .contains(
                                    alias.uppercase()
                                )
                        ) {

                            matchingStation =
                                station

                            break
                        }
                    }

                    if (
                        matchingStation != null
                    ) {

                        if (goingNorth) {

                            if (
                                matchingStation.lat <
                                fromStation.lat
                            ) {
                                continue
                            }

                            if (
                                trainIsMeranLine &&
                                !targetIsMeranLine
                            ) {
                                continue
                            }

                        } else {

                            // Meraner Züge dürfen bei Fahrten
                            // zur Meraner Linie bzw. nach Bozen
                            // trotz ihres nördlich gelegenen Endbahnhofs
                            // weiter geprüft werden. Die eigentliche
                            // Zielprüfung erfolgt anschließend über
                            // die FERMA-A-Haltestellen der RFI-Zeile.
                            val allowMeranLineConnection =
                                trainIsMeranLine &&
                                        (
                                                targetIsMeranLine ||
                                                        targetIsBozen
                                                )

                            if (
                                matchingStation.lat >
                                fromStation.lat &&
                                !allowMeranLineConnection
                            ) {
                                continue
                            }
                        }
                    }

                    var rawDest =
                        if (timeCellIndex >= 1) {
                            colTexts[
                                timeCellIndex - 1
                            ]
                        } else {
                            matchingStation?.name
                                ?: "Zug"
                        }

                    var finalCatNum =
                        if (timeCellIndex >= 2) {

                            colTexts
                                .subList(
                                    0,
                                    timeCellIndex - 1
                                )
                                .joinToString(" ")
                                .trim()

                        } else {
                            ""
                        }

                    if (
                        (
                                finalCatNum.isBlank() ||
                                        finalCatNum.equals(
                                            rawDest,
                                            ignoreCase = true
                                        )
                                ) &&
                        matchingStation != null
                    ) {

                        val upperRaw =
                            rawDest.uppercase()

                        val stationName =
                            matchingStation.name
                                .uppercase()

                        val destIndex =
                            upperRaw.indexOf(
                                stationName
                            )

                        if (destIndex != -1) {

                            finalCatNum =
                                rawDest
                                    .substring(
                                        0,
                                        destIndex
                                    )
                                    .trim()

                            rawDest =
                                rawDest
                                    .substring(
                                        destIndex
                                    )
                                    .trim()
                        }
                    }

                    val cleanDest =
                        rawDest
                            .split(
                                Regex(
                                    "(?i)\\b" +
                                            "(Fermate|nach|FERMA|Treno)" +
                                            "\\b"
                                ),
                                2
                            )[0]
                            .replace(
                                Regex(
                                    """^\d+\s*"""
                                ),
                                ""
                            )
                            .trim()

                    if (
                        finalCatNum.isBlank()
                    ) {
                        finalCatNum =
                            "Zug"
                    }

                    val rawDelay =
                        if (
                            colTexts.size >
                            timeCellIndex + 1
                        ) {
                            colTexts[
                                timeCellIndex + 1
                            ]
                        } else {
                            ""
                        }

                    val rawPlatform =
                        if (
                            colTexts.size >
                            timeCellIndex + 2
                        ) {
                            colTexts[
                                timeCellIndex + 2
                            ]
                        } else {
                            "-"
                        }

                    val isCancelled =
                        rawDelay.contains(
                            "SOP",
                            ignoreCase = true
                        )

                    val hasDelay =
                        isCancelled ||
                                (
                                        rawDelay.isNotBlank() &&
                                                rawDelay != "0"
                                        )

                    val formattedDelay =
                        when {

                            isCancelled ->
                                getString(
                                    R.string
                                        .train_status_cancelled
                                )

                            rawDelay.isBlank() ||
                                    rawDelay == "0" ->
                                getString(
                                    R.string
                                        .train_status_ontime
                                )

                            rawDelay.all {
                                it.isDigit()
                            } ->
                                getString(
                                    R.string
                                        .train_status_delayed_format,
                                    rawDelay
                                )

                            else ->
                                rawDelay
                        }

                    /*
                     * Die Haltestellen aus dem Info-Popup stehen bereits
                     * im HTML genau dieser Zugzeile.
                     */
                    val stopsAtTarget =
                        checkTrainStopsInRfiRow(
                            row,
                            targetStation
                        )

                    rawTrainList.add(
                        TrainInfo(
                            categoryNumber =
                                finalCatNum.ifBlank {
                                    getString(
                                        R.string
                                            .default_train_category
                                    )
                                },

                            destination =
                                cleanDest,

                            time =
                                time,

                            delay =
                                formattedDelay,

                            platform =
                                rawPlatform.ifBlank {
                                    "-"
                                },

                            hasDelay =
                                hasDelay,

                            isBus =
                                isBus,

                            stopsAtTarget =
                                stopsAtTarget
                        )
                    )

                    if (
                        rawTrainList.size >=
                        limit
                    ) {
                        break
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (
                rawTrainList.isNotEmpty()
            ) {
                break
            }

            if (attempt == 1) {
                delay(1.seconds)
            }
        }

        return rawTrainList
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
                android.util.Log.w("TrainControl", "POST_NOTIFICATIONS nicht erlaubt")
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

    private fun calculateDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Float {

        val results =
            FloatArray(1)

        Location.distanceBetween(
            lat1,
            lon1,
            lat2,
            lon2,
            results
        )

        return results[0]
    }
}
