package com.example.traincontrolstmobilandroid

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {

    companion object {
        val Landtagsrot = Color(0xFFCE1126)
        
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        
        val prefs = getSharedPreferences("TrainControlSTmobilPrefs", MODE_PRIVATE)
        val trainFetcher = TrainFetcher(this)

        setContent {
            val isDark = isSystemInDarkTheme()
            val customColorScheme = if (isDark) {
                darkColorScheme(
                    primary = Landtagsrot,
                    secondary = Landtagsrot,
                    tertiary = Color.Gray,
                    onPrimary = Color.White,
                )
            } else {
                lightColorScheme(
                    primary = Landtagsrot,
                    secondary = Landtagsrot,
                    tertiary = Color.Gray,
                    onPrimary = Color.White,
                )
            }

            MaterialTheme(
                colorScheme = customColorScheme,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TrainApp(
                        onFinish = { finish() },
                        trainFetcher = trainFetcher,
                        prefs = prefs,
                    )
                }
            }
        }
    }
}

@Composable
fun BatteryOptimizationDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Energieeinstellungen") },
        text = {
            Text("Damit die App auch im Hintergrund zuverlässig über Verspätungen informieren kann, muss die Akku-Optimierung für diese App deaktiviert werden (Einstellung 'Nicht eingeschränkt').")
        },
        confirmButton = {
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    context.startActivity(intent)
                    onDismiss()
                }
            ) {
                Text("Einstellungen öffnen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Später")
            }
        }
    )
}

sealed class UIState {
    object Loading : UIState()
    data class Results(val from: StationData, val to: StationData, val trains: List<TrainInfo>) : UIState()
    data class LocationSelection(val detected: StationData, val home: StationData, val work: StationData) : UIState()
    data class CustomSearch(val from: StationData, val to: StationData) : UIState()
}

class TrainViewModel(
    private val trainFetcher: TrainFetcher,
    private val prefs: SharedPreferences,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UIState>(UIState.Loading)
    val uiState: StateFlow<UIState> = _uiState.asStateFlow()

    private val _showSettings = MutableStateFlow(value = false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    private val _selectedTrain = MutableStateFlow<TrainInfo?>(null)
    val selectedTrain: StateFlow<TrainInfo?> = _selectedTrain.asStateFlow()

    private val _allStations = MutableStateFlow<List<StationData>>(emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _allStations.value = loadStationsFromAssets()
        }
    }

    fun startAppFlow() {
        viewModelScope.launch {
            var count = 0
            while ((_allStations.value.isEmpty()) && (count < 50)) {
                kotlinx.coroutines.delay(100.milliseconds)
                count++
            }

            val home = getSelectedStation("home_station", "Brixen / Bressanone")
            val work = getSelectedStation("work_station", "Bozen / Bolzano")

            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                fetchTrains(home, work)
                return@launch
            }

            try {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)
                
                // Add a timeout for location fetch
                var locationReceived = false
                viewModelScope.launch {
                    kotlinx.coroutines.delay(5.seconds)
                    if (!locationReceived) {
                        Log.d("TrainControl", "Location timeout, fallback")
                        fetchTrains(home, work)
                    }
                }

                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    locationReceived = true
                    if (location != null) {
                        determineStation(location, home, work)
                    } else {
                        fetchTrains(home, work)
                    }
                }.addOnFailureListener {
                    locationReceived = true
                    fetchTrains(home, work)
                }
            } catch (_: Exception) {
                fetchTrains(home, work)
            }
        }
    }

    private fun determineStation(location: Location, home: StationData, work: StationData) {
        val stations = _allStations.value
        if (stations.isEmpty()) {
            fetchTrains(home, work)
            return
        }

        val currentStation = stations.asSequence().filter { !it.placeId.startsWith("9900") }.minByOrNull { 
            val res = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, it.lat, it.lon, res)
            res[0]
        } ?: home

        if ((currentStation.name == home.name) || (currentStation.name == work.name)) {
            val targetStation = if (currentStation.name == home.name) work else home
            fetchTrains(currentStation, targetStation)
        } else {
            _uiState.value = UIState.LocationSelection(currentStation, home, work)
        }
    }

    fun fetchTrains(from: StationData, to: StationData) {
        _uiState.value = UIState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val trains = trainFetcher.fetchAndParseTrains(from, to)
                _uiState.value = UIState.Results(from, to, trains)
            } catch (_: Exception) {
                _uiState.value = UIState.Results(from, to, emptyList())
            }
        }
    }

    fun getSelectedStation(key: String, default: String): StationData {
        val stations = _allStations.value
        val name = prefs.getString(key, null) ?: default
        return stations.firstOrNull { it.name == name } ?: stations.firstOrNull { it.name == default } 
            ?: StationData(default, "000", null, 46.4983, 11.3548, emptyList())
    }

    fun getSelectableRegionalStations() = _allStations.value.asSequence()
        .filter { (!it.placeId.startsWith("9900")) && (it.name !in listOf("Bari Centrale", "Roma Termini", "Firenze S.M.N.", "Verona Porta Nuova", "Milano Centrale", "Venezia Santa Lucia", "Ancona", "Napoli Centrale", "Bologna Centrale", "Rovereto", "Ala")) }
        .sortedBy { it.name }.toList()

    fun openSettings() { _showSettings.value = true }
    fun closeSettings() { _showSettings.value = false }
    fun selectTrain(train: TrainInfo?) { _selectedTrain.value = train }
    fun showCustomSearch(from: StationData, to: StationData) { _uiState.value = UIState.CustomSearch(from, to) }

    private fun loadStationsFromAssets(): List<StationData> {
        return try {
            appContext.assets.open("stations.json").bufferedReader().use { reader ->
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
}

@Composable
fun TrainApp(
    onFinish: () -> Unit,
    trainFetcher: TrainFetcher,
    prefs: SharedPreferences
) {
    val context = LocalContext.current
    val viewModel: TrainViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return TrainViewModel(trainFetcher, prefs, context.applicationContext) as T
            }
        }
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showSettings by viewModel.showSettings.collectAsStateWithLifecycle()
    val selectedTrain by viewModel.selectedTrain.collectAsStateWithLifecycle()

    var showBatteryDialog by remember { mutableStateOf(value = false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.startAppFlow()
    }

    LaunchedEffect(Unit) {
        // Check battery optimization
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            showBatteryDialog = true
        }

        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        if (permissions.all { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            viewModel.startAppFlow()
        } else {
            launcher.launch(permissions.toTypedArray())
        }
    }

    if (showBatteryDialog) {
        BatteryOptimizationDialog { showBatteryDialog = false }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (val state = uiState) {
                is UIState.Loading -> LoadingScreen()
                is UIState.Results -> ResultsScreen(
                    state.from, state.to, state.trains,
                    onOpenSettings = { viewModel.openSettings() },
                    onFinish = onFinish,
                    onChangeStation = { viewModel.showCustomSearch(state.from, state.to) },
                ) {
                    viewModel.selectTrain(it)
                }
                is UIState.LocationSelection -> LocationStationDialog(
                    state.detected, state.home, state.work,
                    onTargetSelected = { from, to -> viewModel.fetchTrains(from, to) },
                    onOtherTarget = { from, to -> viewModel.showCustomSearch(from, to) },
                    onCancel = onFinish,
                    viewModel = viewModel,
                )
                is UIState.CustomSearch -> CustomSearchDialog(
                    state.from, state.to,
                    onSearch = { from, to -> viewModel.fetchTrains(from, to) },
                    onCancel = onFinish,
                    viewModel = viewModel,
                )
            }

            if (showSettings) {
                SettingsDialog(
                    onDismiss = { viewModel.closeSettings() },
                    viewModel = viewModel,
                    prefs = prefs,
                    onFinish = onFinish
                )
            }

            if (selectedTrain != null) {
                TripDetailBottomSheet(train = selectedTrain!!) {
                    viewModel.selectTrain(null)
                }
            }
        }
    }
}

@Composable
fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(140.dp),
            tint = MainActivity.Landtagsrot
        )
        Spacer(modifier = Modifier.height(20.dp))
        CircularProgressIndicator(color = MainActivity.Landtagsrot)
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Lade Zugdaten...",
            fontStyle = FontStyle.Italic,
            color = Color.Gray,
            fontSize = 14.sp,
        )
    }
}

@Composable
fun ResultsScreen(
    from: StationData,
    to: StationData,
    trains: List<TrainInfo>,
    onOpenSettings: () -> Unit,
    onFinish: () -> Unit,
    onChangeStation: () -> Unit,
    onTrainClick: (TrainInfo) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Abfahrt: ${from.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Bahnhof ändern",
                    color = Color.DarkGray,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { onChangeStation() },
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (trains.isEmpty()) {
            Text("Keine Abfahrten gefunden")
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(trains) { train ->
                    TrainItem(train, to) { onTrainClick(train) }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
        Button(
            onClick = onFinish,
            modifier = Modifier.align(Alignment.End).padding(top = 8.dp)
        ) { Text("OK") }
    }
}

@Composable
fun TrainItem(train: TrainInfo, target: StationData, onClick: () -> Unit) {
    val terminal = (train.lineTerminal ?: train.destination).split("/").first().trim()
    val cleanCat = train.categoryNumber
        .replace("Regional-Express", "", ignoreCase = true)
        .replace("Regionalexpress", "", ignoreCase = true)
        .replace("Regionale Veloce", "", ignoreCase = true)
        .replace("Regionalzug", "", ignoreCase = true)
        .replace("Regionale", "", ignoreCase = true)
        .replace("Zug", "", ignoreCase = true)
        .trim()
    
    val shortTarget = target.name.split("/").first().trim()

    Column(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(4.dp)) {
        Text(text = "1. n. $cleanCat, ${getTrainTypeLabel(train.categoryNumber)}", style = MaterialTheme.typography.bodySmall)
        
        FlowRow(
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = terminal,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (!terminal.equals(shortTarget, ignoreCase = true)) {
                    Text(
                        text = " | ",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Gray,
                    )
                }
            }
            
            if (!terminal.equals(shortTarget, ignoreCase = true)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "hält in $shortTarget",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
        }
        
        Row {
            Text(
                text = "${train.time} Uhr ",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (train.isBus) "Bus" else "Gleis ${train.platform}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.width(8.dp))
            val delayColor = if (train.hasAnyDelay) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                text = train.bestDelayInfo,
                color = delayColor,
                fontWeight = if (train.hasAnyDelay) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        train.extraDelayInfoLong?.let { extra ->
            Text(
                text = extra,
                color = if (train.hasAnyDelay) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (train.stopsAtTarget == false) {
            Text(text = "Hält NICHT in $shortTarget", color = Color.Red, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailBottomSheet(train: TrainInfo, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
            Text(text = "${train.categoryNumber} nach ${train.lineTerminal ?: train.destination}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn {
                items(train.stops) { stop ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stop.name, fontWeight = FontWeight.Medium)
                            Text(text = "Geplant: ${stop.scheduledTime}", style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            val color = if ((stop.isCancelled) || (stop.delay != "pünktlich")) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
                            Text(text = stop.actualTime, color = color, fontWeight = FontWeight.Bold)
                            Text(text = stop.delay, color = color, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit, viewModel: TrainViewModel, prefs: SharedPreferences, onFinish: () -> Unit) {
    val context = LocalContext.current
    val stations = viewModel.getSelectableRegionalStations()
    val stationNames = stations.map { it.name }
    
    var homeStation by remember { mutableStateOf(prefs.getString("home_station", "Brixen / Bressanone") ?: "Brixen / Bressanone") }
    var workStation by remember { mutableStateOf(prefs.getString("work_station", "Bozen / Bolzano") ?: "Bozen / Bolzano") }
    var alarmCount by remember { mutableIntStateOf(prefs.getInt("alarm_train_count", 3)) }
    
    val timer1Enabled = remember { mutableStateOf(prefs.getBoolean("timer_1_enabled", false)) }
    val timer1Hour = remember { mutableIntStateOf(prefs.getInt("timer_1_hour", 7)) }
    val timer1Minute = remember { mutableIntStateOf(prefs.getInt("timer_1_minute", 0)) }
    val timer1Days = remember { mutableStateOf(prefs.getStringSet("timer_1_days", setOf("mon", "tue", "wed", "thu", "fri")) ?: setOf()) }
    
    val timer3Enabled = remember { mutableStateOf(prefs.getBoolean("timer_3_enabled", false)) }
    val timer3Hour = remember { mutableIntStateOf(prefs.getInt("timer_3_hour", 8)) }
    val timer3Minute = remember { mutableIntStateOf(prefs.getInt("timer_3_minute", 0)) }
    val timer3Days = remember { mutableStateOf(prefs.getStringSet("timer_3_days", setOf()) ?: setOf()) }

    val timer2Enabled = remember { mutableStateOf(prefs.getBoolean("timer_2_enabled", false)) }
    val timer2Hour = remember { mutableIntStateOf(prefs.getInt("timer_2_hour", 16)) }
    val timer2Minute = remember { mutableIntStateOf(prefs.getInt("timer_2_minute", 0)) }
    val timer2Days = remember { mutableStateOf(prefs.getStringSet("timer_2_days", setOf("mon", "tue", "wed", "thu", "fri")) ?: setOf()) }

    val timer4Enabled = remember { mutableStateOf(prefs.getBoolean("timer_4_enabled", false)) }
    val timer4Hour = remember { mutableIntStateOf(prefs.getInt("timer_4_hour", 17)) }
    val timer4Minute = remember { mutableIntStateOf(prefs.getInt("timer_4_minute", 0)) }
    val timer4Days = remember { mutableStateOf(prefs.getStringSet("timer_4_days", setOf()) ?: setOf()) }
    
    val categoryStates = remember {
        mutableStateMapOf<String, Boolean>().apply {
            MainActivity.CATEGORY_GROUPS.forEach { (_, filters) ->
                filters.forEach { put(it.prefKey, prefs.getBoolean(it.prefKey, it.defaultState)) }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f), shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text("Einstellungen", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Heimatbahnhof", fontWeight = FontWeight.Bold)
                StationSpinner(stationNames, homeStation) { homeStation = it }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Arbeitsbahnhof", fontWeight = FontWeight.Bold)
                StationSpinner(stationNames, workStation) { workStation = it }
                Spacer(modifier = Modifier.height(16.dp))
                Text("Verspätungen anzeigen", fontWeight = FontWeight.Bold)
                AlarmSpinner(alarmCount) { alarmCount = it }
                Spacer(modifier = Modifier.height(24.dp))
                Text("Update-Zeiten", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TimerSection("Morgens", timer1Enabled, timer1Hour, timer1Minute, timer1Days)
                TimerSection("", timer3Enabled, timer3Hour, timer3Minute, timer3Days, showMasterCheckbox = false)
                Spacer(modifier = Modifier.height(8.dp))
                TimerSection("Nachmittags", timer2Enabled, timer2Hour, timer2Minute, timer2Days)
                TimerSection("", timer4Enabled, timer4Hour, timer4Minute, timer4Days, showMasterCheckbox = false)
                Spacer(modifier = Modifier.height(24.dp))
                MainActivity.CATEGORY_GROUPS.forEach { (group, filters) ->
                    Text(group, style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                    filters.forEach { filter ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = categoryStates[filter.prefKey] ?: filter.defaultState, onCheckedChange = { categoryStates[filter.prefKey] = it })
                            Text(filter.label)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                    Button(
                        onClick = {
                            if (homeStation == workStation) {
                                Toast.makeText(context, "Stationen identisch!", Toast.LENGTH_SHORT).show()
                            } else {
                                prefs.edit {
                                    putString("home_station", homeStation)
                                    putString("work_station", workStation)
                                    putInt("alarm_train_count", alarmCount)
                                    putBoolean("timer_1_enabled", timer1Enabled.value)
                                    putInt("timer_1_hour", timer1Hour.intValue)
                                    putInt("timer_1_minute", timer1Minute.intValue)
                                    putStringSet("timer_1_days", timer1Days.value)
                                    putBoolean("timer_3_enabled", timer3Enabled.value)
                                    putInt("timer_3_hour", timer3Hour.intValue)
                                    putInt("timer_3_minute", timer3Minute.intValue)
                                    putStringSet("timer_3_days", timer3Days.value)
                                    putBoolean("timer_2_enabled", timer2Enabled.value)
                                    putInt("timer_2_hour", timer2Hour.intValue)
                                    putInt("timer_2_minute", timer2Minute.intValue)
                                    putStringSet("timer_2_days", timer2Days.value)
                                    putBoolean("timer_4_enabled", timer4Enabled.value)
                                    putInt("timer_4_hour", timer4Hour.intValue)
                                    putInt("timer_4_minute", timer4Minute.intValue)
                                    putStringSet("timer_4_days", timer4Days.value)
                                    categoryStates.forEach { (k, v) -> putBoolean(k, v) }
                                }
                                ScheduleHelper.scheduleAlarm(context, 1)
                                ScheduleHelper.scheduleAlarm(context, 3)
                                ScheduleHelper.scheduleAlarm(context, 2)
                                ScheduleHelper.scheduleAlarm(context, 4)
                                onDismiss()
                                onFinish()
                            }
                        }
                    ) {
                        Text("Speichern")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerSection(
    label: String,
    enabled: MutableState<Boolean>,
    hour: MutableIntState,
    minute: MutableIntState,
    days: MutableState<Set<String>>,
    showMasterCheckbox: Boolean = true
) {
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        val timePickerState = rememberTimePickerState(
            initialHour = hour.intValue,
            initialMinute = minute.intValue,
            is24Hour = true
        )
        Material3TimePickerDialog(
            onDismiss = { showDialog = false },
            onConfirm = {
                hour.intValue = timePickerState.hour
                minute.intValue = timePickerState.minute
                showDialog = false
            }
        ) {
            TimePicker(state = timePickerState)
        }
    }

    val isActuallyEnabled = if (showMasterCheckbox) enabled.value else true

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        if (showMasterCheckbox) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = enabled.value, onCheckedChange = { enabled.value = it })
                Text(label)
            }
        }
        
        Button(
            onClick = { showDialog = true },
            enabled = isActuallyEnabled,
            modifier = Modifier.padding(start = 32.dp)
        ) {
            Text(String.format(Locale.ROOT, "%02d:%02d", hour.intValue, minute.intValue))
        }
        Row(modifier = Modifier.padding(start = 32.dp, top = 4.dp).horizontalScroll(rememberScrollState())) {
            val keys = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
            val labels = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
            keys.forEachIndexed { i, key ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                    Checkbox(
                        checked = key in days.value,
                        onCheckedChange = { checked ->
                            if (checked) days.value += key else days.value -= key
                            if (!showMasterCheckbox) {
                                enabled.value = days.value.isNotEmpty()
                            }
                        },
                        enabled = isActuallyEnabled
                    )
                    Text(labels[i], style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun Material3TimePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("OK")
            }
        },
        text = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                content()
            }
        }
    )
}

@Composable
fun StationSpinner(options: List<String>, selected: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(value = false) }
    Box {
        Text(text = selected, modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(8.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp))
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onSelected(option); expanded = false }) }
        }
    }
}

@Composable
fun AlarmSpinner(selected: Int, onSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(value = false) }
    val options = listOf(
        1 to "des nächsten Zuges",
        2 to "der nächsten 2 Züge",
        3 to "der nächsten 3 Züge"
    )
    val selectedLabel = options.find { it.first == selected }?.second ?: "$selected Züge"
    
    Box {
        Text(text = selectedLabel, modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(8.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp))
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) -> 
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelected(value); expanded = false }) 
            }
        }
    }
}

@Composable
fun LocationStationDialog(detected: StationData, home: StationData, work: StationData, onTargetSelected: (StationData, StationData) -> Unit, onOtherTarget: (StationData, StationData) -> Unit, onCancel: () -> Unit, viewModel: TrainViewModel) {
    val stations = viewModel.getSelectableRegionalStations()
    val names = stations.map { it.name }
    var from by remember { mutableStateOf(detected) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Standort: ${detected.name.split("/").first().trim()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Text("Abfahrt", fontWeight = FontWeight.Bold)
                    StationSpinner(names, from.name) { n -> from = stations.first { it.name == n } }
                }
                
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onTargetSelected(from, work) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Nach ${work.name.split("/").first().trim()}")
                    }
                    
                    Button(
                        onClick = { onTargetSelected(from, home) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Nach ${home.name.split("/").first().trim()}")
                    }
                    
                    OutlinedButton(
                        onClick = { onOtherTarget(from, home) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Anderes Ziel...")
                    }
                }
            }
        },
    confirmButton = {
        TextButton(onClick = onCancel) {
            Text("Abbrechen")
        }
    },
)
}

@Composable
fun CustomSearchDialog(from: StationData, to: StationData, onSearch: (StationData, StationData) -> Unit, onCancel: () -> Unit, viewModel: TrainViewModel) {
    val stations = viewModel.getSelectableRegionalStations()
    val names = stations.map { it.name }
    var sFrom by remember { mutableStateOf(from) }
    var sTo by remember { mutableStateOf(to) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Route wählen") },
        text = {
            Column {
            Text("Abfahrt", fontWeight = FontWeight.Bold)
            StationSpinner(names, sFrom.name) { n -> sFrom = stations.first { it.name == n } }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Ziel", fontWeight = FontWeight.Bold)
            StationSpinner(names, sTo.name) { n -> sTo = stations.first { it.name == n } }
        }
    },
    confirmButton = {
        Button(onClick = { onSearch(sFrom, sTo) }) {
            Text("Suchen")
        }
    },
    dismissButton = {
        TextButton(onClick = onCancel) {
            Text("Abbrechen")
        }
    }
)
}

fun getTrainTypeLabel(cat: String): String {
    val u = cat.uppercase()
    return when {
        u.contains("BUS") || u.contains("SEV") || u.contains("SOSTITUTIVO") -> "Ersatzbus"
        u.startsWith("RV") || u.contains("REGIONALE VELOCE") || u.contains("REGIONAL-EXPRESS") -> "Regionalexpress"
        u.startsWith("R ") || u.startsWith("REG") || u.contains("REGIONALE") -> "Regionalzug"
        u.startsWith("EC") || u.startsWith("RJ") || u.contains("RAILJET") || u.contains("TRENORD") -> "Fernzug"
        u.contains("FRECCIA") || u.contains("ITALO") -> "High-Speed"
        else -> "Zug"
    }
}
