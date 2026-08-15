package com.example.traincontrolstmobilandroid

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var prefs: SharedPreferences
    private lateinit var trainFetcher: TrainFetcher
    private lateinit var notificationHelper: NotificationHelper

    companion object {
        private var lastExecutionTime: Long = 0
        private const val REQUEST_LOCATION_PERMISSION = 1001
        private const val REQUEST_NOTIFICATION_PERMISSION = 2001

        val CATEGORY_GROUPS = mapOf(
            "Regionalverkehr RFI/SAD" to listOf(
                CategoryFilter("cat_reg", "Regionalzüge(REG)", listOf("REG", "REGIONALE", "SAD"), defaultState = true),
                CategoryFilter("cat_rv", "Regionalexpress (RV)", listOf("RV", "REGIONALE VELOCE"), defaultState = true),
                CategoryFilter("cat_bus", "Bus Schienenersatz", listOf("BUS"), defaultState = true),
            ),
            "Fernverkehr & High-Speed" to listOf(
                CategoryFilter("cat_tn_rj", "Eurocity / Railjet / Trenord (RJ/EC)", listOf("RJ", "RAILJET", "EC", "TRENORD"), defaultState = false),
                CategoryFilter("cat_fv_freccia", "Frecciarossa (Alta Velocità)", listOf("FRECCIAROSSA", "FRECCIARGENTO", "FR"), defaultState = false),
                CategoryFilter("cat_fv_italo", "Italo (Alta Velocità)", listOf("ITALO"), defaultState = false),
                CategoryFilter("cat_fv_ic", "Intercity (IC)", listOf("INTERCITY", "IC"), defaultState = false),
            ),
        )
    }

    private val allStations: List<StationData> by lazy { loadStationsFromAssets() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("TrainControlSTmobilPrefs", MODE_PRIVATE)
        notificationHelper = NotificationHelper(this)
        trainFetcher = TrainFetcher(this)

        if (allStations.isEmpty()) {
            Toast.makeText(this, "stations.json konnte nicht geladen werden.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val currentTime = System.currentTimeMillis()
        if ((currentTime - lastExecutionTime) < 5000) {
            Toast.makeText(this, getString(R.string.wait_moment), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setShowWhenLocked(true)
        val frameLayout = FrameLayout(this)
        val progressBar = ProgressBar(this).apply { isIndeterminate = true }
        frameLayout.addView(progressBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        setContentView(frameLayout)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
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
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = "package:$packageName".toUri() })
                    }
                    finish()
                }
                .setNegativeButton(getString(R.string.battery_opt_later)) { _, _ -> startAppFlow() }
                .setCancelable(false).show()
            return
        }
        startAppFlow()
    }

    private fun startAppFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
                return
            }
        }
        checkLocationPermissionAndFetch()
    }

    private fun checkLocationPermissionAndFetch() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION_PERMISSION)
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) determineStationAndFetch(location)
            else {
                Toast.makeText(this, getString(R.string.no_location), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) checkLocationPermissionAndFetch()
        else if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.isNotEmpty() && (grantResults[0] == PackageManager.PERMISSION_GRANTED)) checkLocationPermissionAndFetch()
            else {
                Toast.makeText(this, getString(R.string.no_location), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun determineStationAndFetch(location: Location) {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, getString(R.string.no_internet), Toast.LENGTH_LONG).show()
            notificationHelper.sendGarminNotification(getString(R.string.no_internet), "Zug-Anzeige")
            finish()
            return
        }
        val homeStation = getSelectedStation("home_station", "Brixen / Bressanone")
        val workStation = getSelectedStation("work_station", "Bozen / Bolzano")
        val regionalStations = allStations.filter { !it.placeId.startsWith("9900") && !it.placeId.endsWith("00") }
        val currentStation = regionalStations.minByOrNull { calculateDistance(location.latitude, location.longitude, it.lat, it.lon) } ?: homeStation

        if ((currentStation.name == homeStation.name) || (currentStation.name == workStation.name)) {
            val targetStation = if (currentStation.name == homeStation.name) workStation else homeStation
            startTrainFetch(currentStation, targetStation)
        } else {
            val km = getSystemService(KeyguardManager::class.java)
            if (km?.isKeyguardLocked == true) finish()
            else showLocationStationDialog(currentStation, homeStation, workStation)
        }
    }

    private fun startTrainFetch(currentStation: StationData, targetStation: StationData) {
        lifecycleScope.launch(Dispatchers.IO) {
            val trains = trainFetcher.fetchAndParseTrains(currentStation, targetStation)
            val relevantTrains = trains.filter { it.stopsAtTarget != false }
            val alarmTrainCount = prefs.getInt("alarm_train_count", 3)
            val delayedTrain = relevantTrains.asSequence().take(alarmTrainCount).firstOrNull { it.hasAnyDelay }
            lastExecutionTime = System.currentTimeMillis()
            val isScreenLocked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

            if (delayedTrain != null) {
                notificationHelper.playSingleBeep()
                if (isScreenLocked) notificationHelper.sendGarminNotification("Zug ${delayedTrain.categoryNumber}\nnach ${delayedTrain.lineTerminal ?: delayedTrain.destination}\n${delayedTrain.time} Uhr\nVerspätung: ${delayedTrain.bestDelayInfo}", "⚠️ Zugverspätung")
            } else if (trains.isEmpty() && isScreenLocked) {
                notificationHelper.sendGarminNotification(getString(R.string.no_departures), "Zug-Anzeige")
            }

            withContext(Dispatchers.Main) {
                if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) finish()
                else showResultPopup(currentStation, targetStation, trains)
            }
        }
    }

    private fun showResultPopup(fromStation: StationData, targetStation: StationData, trains: List<TrainInfo>) {
        val mb = StringBuilder()
        if (trains.isEmpty()) mb.append(getString(R.string.no_departures))
        else {
            val shortTarget = targetStation.name.split("/").first().trim()
            trains.forEachIndexed { i, t ->
                val terminal = (t.lineTerminal ?: t.destination).split("/").first().trim()
                // Entferne lange Namen wie "Regional-Express" oder "Regionalzug" aus der ersten Zeile (Nummerzeile)
                val cleanCat = t.categoryNumber
                    .replace("Regional-Express", "", ignoreCase = true)
                    .replace("Regionalexpress", "", ignoreCase = true)
                    .replace("Regionale Veloce", "", ignoreCase = true)
                    .replace("Regionalzug", "", ignoreCase = true)
                    .replace("Regionale", "", ignoreCase = true)
                    .replace("Zug", "", ignoreCase = true)
                    .trim()
                
                mb.append(getString(R.string.train_item_header, i + 1, cleanCat)).append("<br>")
                mb.append(getString(R.string.train_type_to_label, getTrainTypeLabel(t.categoryNumber))).append("<br>")
                mb.append("<b>$terminal</b><br>")
                mb.append(getString(R.string.departure_time_format, t.time)).append("<br>")
                when (t.stopsAtTarget) {
                    true -> mb.append(getString(R.string.stops_true_format, shortTarget)).append("<br>")
                    false -> mb.append(getString(R.string.stops_false_format, shortTarget)).append("<br>")
                    else -> {}
                }
                if (t.isBus) mb.append(getString(R.string.platform_bus_label)).append("<br>")
                else mb.append(getString(R.string.platform_label_format, t.platform)).append("<br>")
                if (t.hasDelay) mb.append(getString(R.string.delay_label_critical_format, t.delay))
                else mb.append(getString(R.string.delay_label_format, t.delay))
                if ((t.rfiStatus != null) || (t.rfiDelay != null)) {
                    val isRfiCritical = (t.rfiStatus == "Verspätung") || (t.rfiStatus == "entfällt")
                    val rfiColor = if (isRfiCritical) "#D32F2F" else "#999999"
                    val rfiStatusText = t.rfiStatus ?: "pünktlich"
                    val rfiDelayText = t.rfiDelay ?: "+0"
                    val rfiInfo = if (isRfiCritical) "<b>RFI: $rfiStatusText ($rfiDelayText)</b>" else "RFI: $rfiStatusText ($rfiDelayText)"
                    mb.append("<br><small><font color='$rfiColor'>$rfiInfo</font></small>")
                }
                if (i < (trains.size - 1)) mb.append("<br>-----------------------------------<br>")
            }
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 10) }
        val title = TextView(this).apply { text = getString(R.string.departure_station_format, fromStation.name); textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(Color.BLACK) }
        val change = TextView(this).apply { text = getString(R.string.change_station_link); textSize = 14f; setTextColor(Color.BLACK); paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG; setPadding(0, 10, 0, 0) }
        header.addView(title); header.addView(change)

        val sv = ScrollView(this).apply { setPadding(50, 10, 50, 10) }
        val tv = TextView(this).apply { text = android.text.Html.fromHtml(mb.toString(), android.text.Html.FROM_HTML_MODE_LEGACY); textSize = 15f; setTextColor(Color.BLACK) }
        sv.addView(tv)

        val dialog = AlertDialog.Builder(this).setCustomTitle(header).setView(sv)
            .setPositiveButton("OK") { d, _ -> d.dismiss(); finish() }
            .setNeutralButton("⚙️") { d, _ -> d.dismiss(); showSettingsDialog() }
            .setCancelable(false).create()
        change.setOnClickListener { dialog.dismiss(); showCustomSearchDialog(fromStation, targetStation) }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.BLACK)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.let { it.setTextColor(Color.BLACK); it.textSize = 18f }
    }

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 40) }
        val selectable = getSelectableRegionalStations()
        val stationNames = selectable.map { it.name }.toTypedArray()

        val currentHome = prefs.getString("home_station", "Brixen / Bressanone") ?: "Brixen / Bressanone"
        val currentWork = prefs.getString("work_station", "Bozen / Bolzano") ?: "Bozen / Bolzano"

        val spinnerHome = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, stationNames); setSelection(stationNames.indexOf(currentHome).coerceAtLeast(0)) }
        val spinnerWork = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, stationNames); setSelection(stationNames.indexOf(currentWork).coerceAtLeast(0)) }

        container.addView(TextView(this).apply { text = getString(R.string.home_address_label); setTypeface(null, Typeface.BOLD) })
        container.addView(spinnerHome)
        container.addView(TextView(this).apply { text = getString(R.string.work_address_label); setTypeface(null, Typeface.BOLD); setPadding(0, 20, 0, 0) })
        container.addView(spinnerWork)

        // Alarm-Anzahl
        val alarmOptions = arrayOf(getString(R.string.alarm_option_1), getString(R.string.alarm_option_2), getString(R.string.alarm_option_3))
        val spinnerAlarmCount = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, alarmOptions); setSelection(prefs.getInt("alarm_train_count", 3) - 1) }
        container.addView(TextView(this).apply { text = getString(R.string.alarm_label); setTypeface(null, Typeface.BOLD); setPadding(0, 30, 0, 0) })
        container.addView(spinnerAlarmCount)

        // Timer Section
        container.addView(TextView(this).apply { text = getString(R.string.timer_section_title); setTypeface(null, Typeface.BOLD); setPadding(0, 40, 0, 10); textSize = 18f; setTextColor(Color.BLACK) })
        
        val createTimerView = { index: Int, label: String ->
            val enabled = prefs.getBoolean("timer_${index}_enabled", false)
            val h = prefs.getInt("timer_${index}_hour", if (index == 1) 7 else 16)
            val m = prefs.getInt("timer_${index}_minute", 0)
            
            val cb = CheckBox(this).apply { text = label; isChecked = enabled }
            val btnTime = Button(this).apply { text = String.format(java.util.Locale.ROOT, "%02d:%02d", h, m); isEnabled = enabled }
            btnTime.setOnClickListener {
                TimePickerDialog(
                    this,
                    { _, hour, min ->
                        btnTime.text = String.format(java.util.Locale.ROOT, "%02d:%02d", hour, min)
                        prefs.edit { putInt("timer_${index}_hour", hour); putInt("timer_${index}_minute", min) }
                    },
                    prefs.getInt("timer_${index}_hour", h),
                    prefs.getInt("timer_${index}_minute", m),
                    true,
                ).show()
            }
            cb.setOnCheckedChangeListener { _, checked -> btnTime.isEnabled = checked }
            
            val daysLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(20, 10, 20, 10)
            }
            val dayKeys = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
            val dayLabels = listOf(R.string.mon, R.string.tue, R.string.wed, R.string.thu, R.string.fri, R.string.sat, R.string.sun)
            val dayCbs = mutableListOf<CheckBox>()
            val selectedDays = prefs.getStringSet("timer_${index}_days", setOf("mon", "tue", "wed", "thu", "fri")) ?: setOf()
            
            dayKeys.forEachIndexed { i, key ->
                val dayCb = CheckBox(this).apply { text = getString(dayLabels[i]); isChecked = key in selectedDays; setPadding(0, 0, 10, 0); isEnabled = enabled }
                dayCbs.add(dayCb)
                daysLayout.addView(dayCb)
            }
            cb.setOnCheckedChangeListener { _, checked -> 
                btnTime.isEnabled = checked
                dayCbs.forEach { it.isEnabled = checked }
            }

            val hsvDays = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(daysLayout)
            }
            container.addView(cb)
            container.addView(btnTime)
            container.addView(hsvDays, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            Triple(cb, dayCbs, dayKeys)
        }

        val timer1 = createTimerView(1, getString(R.string.timer_1_label))
        val timer2 = createTimerView(2, getString(R.string.timer_2_label))

        // Operators
        container.addView(TextView(this).apply { text = getString(R.string.operators_label); setTypeface(null, Typeface.BOLD); setPadding(0, 30, 0, 10) })
        val checkboxMap = mutableMapOf<String, CheckBox>()
        CATEGORY_GROUPS.forEach { (title, filters) ->
            container.addView(TextView(this).apply { text = getString(R.string.operator_format, title); setTypeface(null, Typeface.BOLD); setPadding(10, 15, 0, 5); setTextColor(Color.DKGRAY) })
            filters.forEach { f ->
                val cb = CheckBox(this).apply { text = f.label; isChecked = prefs.getBoolean(f.prefKey, f.defaultState); setPadding(30, 0, 0, 0) }
                checkboxMap[f.prefKey] = cb; container.addView(cb)
            }
        }

        val sv = ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.settings_dialog_title)).setView(sv)
            .setPositiveButton(getString(R.string.save_action), null)
            .setNegativeButton(getString(R.string.cancel_button)) { _, _ -> finish() }.create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val sHome = spinnerHome.selectedItem.toString(); val sWork = spinnerWork.selectedItem.toString()
            if (sHome == sWork) Toast.makeText(this@MainActivity, getString(R.string.error_identical_addresses), Toast.LENGTH_LONG).show()
            else {
                prefs.edit {
                    putString("home_station", sHome); putString("work_station", sWork); putInt("alarm_train_count", spinnerAlarmCount.selectedItemPosition + 1)
                    putBoolean("timer_1_enabled", timer1.first.isChecked); putStringSet("timer_1_days", timer1.second.asSequence().filter { it.isChecked }.map { cb -> timer1.third[timer1.second.indexOf(cb)] }.toSet())
                    putBoolean("timer_2_enabled", timer2.first.isChecked); putStringSet("timer_2_days", timer2.second.asSequence().filter { it.isChecked }.map { cb -> timer2.third[timer2.second.indexOf(cb)] }.toSet())
                    checkboxMap.forEach { (k, cb) -> putBoolean(k, cb.isChecked) }
                }
                ScheduleHelper.scheduleAlarm(this@MainActivity, 1); ScheduleHelper.scheduleAlarm(this@MainActivity, 2)
                Toast.makeText(this@MainActivity, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show(); dialog.dismiss(); finish()
            }
        }
    }

    private fun getSelectedStation(key: String, default: String): StationData {
        val name = prefs.getString(key, null) ?: default
        return allStations.firstOrNull { it.name == name } ?: allStations.first { it.name == default }
    }

    private fun getSelectableRegionalStations() = allStations.asSequence().filter { (!it.placeId.startsWith("9900")) && (!it.placeId.endsWith("00")) && (it.name !in listOf("Bari Centrale", "Roma Termini", "Firenze S.M.N.", "Verona Porta Nuova", "Milano Centrale", "Venezia Santa Lucia", "Ancona", "Napoli Centrale", "Bologna Centrale", "Rovereto", "Ala")) }.sortedBy { it.name }.toList()

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        return cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun loadStationsFromAssets(): List<StationData> {
        return try {
            assets.open("stations.json").bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                val array = json.getJSONArray("stations")
                List(array.length()) { i ->
                    val s = array.getJSONObject(i)
                    val a = s.getJSONArray("aliases")
                    StationData(
                        s.getString("name"),
                        s.getString("placeId"),
                        s.optString("efaId").takeIf { it.isNotEmpty() },
                        s.getDouble("lat"),
                        s.getDouble("lon"),
                        List(a.length()) { a.getString(it) },
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val res = FloatArray(1); Location.distanceBetween(lat1, lon1, lat2, lon2, res); return res[0]
    }

    private fun getTrainTypeLabel(cat: String): String {
        val upper = cat.uppercase()
        return when {
            upper.contains("BUS") || upper.contains("SEV") || upper.contains("SOSTITUTIVO") -> "Ersatzbus"
            upper.startsWith("RV") || upper.contains("REGIONALE VELOCE") || upper.contains("REGIONAL-EXPRESS") -> "Regionalexpress"
            upper.startsWith("R ") || upper.startsWith("REG") || upper.contains("REGIONALE") -> "Regionalzug"
            upper.startsWith("EC") || upper.startsWith("RJ") || upper.contains("RAILJET") || upper.contains("TRENORD") -> "Fernzug"
            upper.contains("FRECCIA") || upper.contains("ITALO") -> "High-Speed"
            else -> "Zug"
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showLocationStationDialog(detected: StationData, home: StationData, work: StationData) {
        val selectable = getSelectableRegionalStations(); val names = selectable.map { it.name }.toTypedArray()
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 20) }
        val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names); setSelection(selectable.indexOfFirst { it.name == detected.name }.coerceAtLeast(0)) }
        container.addView(TextView(this).apply { text = getString(R.string.departure_station_label); setTypeface(null, Typeface.BOLD); setTextColor(Color.BLACK) })
        container.addView(spinner)
        container.addView(TextView(this).apply { text = getString(R.string.select_target_label); setTypeface(null, Typeface.BOLD); setTextColor(Color.BLACK); setPadding(0, 40, 0, 10) })
        
        var dialog: AlertDialog? = null
        val btn = { label: String, target: StationData ->
            Button(this).apply { text = label; setOnClickListener {
                val from = selectable[spinner.selectedItemPosition]
                if (from.name == target.name) Toast.makeText(context, getString(R.string.error_identical_stations), Toast.LENGTH_LONG).show()
                else { startTrainFetch(from, target); dialog?.dismiss() }
            }}
        }
        container.addView(btn(getString(R.string.to_work_button, work.name.split("/").first().trim()), work))
        container.addView(btn(getString(R.string.to_home_button, home.name.split("/").first().trim()), home))
        container.addView(Button(this).apply { text = getString(R.string.other_target_button); setOnClickListener { dialog?.dismiss(); showCustomSearchDialog(selectable[spinner.selectedItemPosition], home) } })
        container.addView(Button(this).apply { text = getString(R.string.cancel_button); setTextColor(Color.DKGRAY); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { dialog?.dismiss(); finish() } })

        dialog = AlertDialog.Builder(this).setTitle(getString(R.string.location_label, detected.name.split("/").first().trim())).setView(container).setCancelable(false).create()
        dialog.show()
    }

    private fun showCustomSearchDialog(from: StationData, to: StationData) {
        val selectable = getSelectableRegionalStations(); val names = selectable.map { it.name }.toTypedArray()
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 10) }
        val sFrom = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names); setSelection(selectable.indexOfFirst { it.name == from.name }.coerceAtLeast(0)) }
        val sTo = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names); setSelection(selectable.indexOfFirst { it.name == to.name }.coerceAtLeast(0)) }
        container.addView(TextView(this).apply { text = getString(R.string.departure_station_label); setTypeface(null, Typeface.BOLD) }); container.addView(sFrom)
        container.addView(TextView(this).apply { text = getString(R.string.target_station_label); setTypeface(null, Typeface.BOLD); setPadding(0, 30, 0, 10) }); container.addView(sTo)

        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.change_station_title)).setView(container)
            .setPositiveButton(getString(R.string.search_action), null).setNegativeButton(getString(R.string.cancel_button)) { _, _ -> finish() }.setCancelable(false).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val f = selectable[sFrom.selectedItemPosition]
            val t = selectable[sTo.selectedItemPosition]
            if (f.name == t.name) Toast.makeText(this@MainActivity, getString(R.string.error_identical_stations), Toast.LENGTH_LONG).show()
            else {
                startTrainFetch(f, t)
                dialog.dismiss()
            }
        }
    }
}
