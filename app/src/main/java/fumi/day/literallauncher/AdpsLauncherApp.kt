package fumi.day.literallauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val PREFS_NAME = "adps_launcher"
private const val PREF_WEATHER_CITY = "weather_city"
private const val DEFAULT_CITY = "Seoul"

private val LauncherColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF121212),
    onSurface = Color.White,
    outline = Color(0xFF3D3D3D),
)

private enum class LauncherPage {
    HOME,
    ALL_APPS,
}

private data class FeatureItem(
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
)

private data class AppEntry(
    val label: String,
    val packageName: String,
    val activityName: String,
)

private data class WeatherData(
    val placeName: String,
    val temperatureC: Double,
    val highC: Double,
    val lowC: Double,
    val condition: String,
)

private sealed interface WeatherUiState {
    data object Loading : WeatherUiState
    data class Success(val data: WeatherData) : WeatherUiState
    data class Error(val message: String) : WeatherUiState
}

@Composable
fun AdpsLauncherApp() {
    MaterialTheme(colorScheme = LauncherColors) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val preferences = remember {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        var currentPage by remember { mutableStateOf(LauncherPage.HOME) }
        var weatherCity by remember {
            mutableStateOf(preferences.getString(PREF_WEATHER_CITY, DEFAULT_CITY) ?: DEFAULT_CITY)
        }
        var weatherState by remember { mutableStateOf<WeatherUiState>(WeatherUiState.Loading) }
        var weatherRefreshKey by remember { mutableIntStateOf(0) }
        var showCityDialog by remember { mutableStateOf(false) }
        var messageDialog by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(weatherCity, weatherRefreshKey) {
            weatherState = WeatherUiState.Loading
            weatherState = withContext(Dispatchers.IO) {
                WeatherService.fetch(weatherCity)
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            when (currentPage) {
                LauncherPage.HOME -> HomeScreen(
                    weatherCity = weatherCity,
                    weatherState = weatherState,
                    onWeatherClick = { showCityDialog = true },
                    onWeatherRefresh = { weatherRefreshKey++ },
                    onHdmiClick = {
                        messageDialog = "HDMI 버튼은 준비되었습니다. 실제 입력 전환은 보드 제조사 API를 연결한 뒤 동작합니다."
                    },
                    onYouTubeClick = {
                        val opened = launchFirstInstalledPackage(
                            context = context,
                            packageNames = listOf(
                                "com.google.android.youtube",
                                "com.google.android.youtube.tv",
                            ),
                            fallbackUri = Uri.parse("https://www.youtube.com"),
                        )
                        if (!opened) messageDialog = "YouTube 또는 브라우저를 실행할 수 없습니다."
                    },
                    onBrowserClick = {
                        if (!openUri(context, Uri.parse("https://www.google.com"))) {
                            messageDialog = "실행 가능한 브라우저가 없습니다."
                        }
                    },
                    onMapsClick = {
                        val mapOpened = openUri(context, Uri.parse("geo:0,0"))
                        if (!mapOpened && !openUri(context, Uri.parse("https://map.naver.com/"))) {
                            messageDialog = "실행 가능한 지도 앱이나 브라우저가 없습니다."
                        }
                    },
                    onSettingsClick = {
                        if (!startIntent(context, Intent(Settings.ACTION_SETTINGS))) {
                            messageDialog = "시스템 설정을 열 수 없습니다."
                        }
                    },
                    onAllAppsClick = { currentPage = LauncherPage.ALL_APPS },
                )

                LauncherPage.ALL_APPS -> AllAppsScreen(
                    onBack = { currentPage = LauncherPage.HOME },
                    onLaunchError = { messageDialog = it },
                )
            }
        }

        if (showCityDialog) {
            CityInputDialog(
                initialCity = weatherCity,
                onDismiss = { showCityDialog = false },
                onConfirm = { newCity ->
                    val trimmedCity = newCity.trim()
                    if (trimmedCity.isNotEmpty()) {
                        weatherCity = trimmedCity
                        preferences.edit().putString(PREF_WEATHER_CITY, trimmedCity).apply()
                        showCityDialog = false
                    }
                },
            )
        }

        messageDialog?.let { message ->
            AlertDialog(
                onDismissRequest = { messageDialog = null },
                confirmButton = {
                    TextButton(onClick = { messageDialog = null }) {
                        Text("OK")
                    }
                },
                title = { Text("Information") },
                text = { Text(message) },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    weatherCity: String,
    weatherState: WeatherUiState,
    onWeatherClick: () -> Unit,
    onWeatherRefresh: () -> Unit,
    onHdmiClick: () -> Unit,
    onYouTubeClick: () -> Unit,
    onBrowserClick: () -> Unit,
    onMapsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAllAppsClick: () -> Unit,
) {
    val features = listOf(
        FeatureItem("Weather", "Select a city", onWeatherClick),
        FeatureItem("HDMI", "Input switching", onHdmiClick),
        FeatureItem("YouTube", "Open video app", onYouTubeClick),
        FeatureItem("Browser", "Open the web", onBrowserClick),
        FeatureItem("Maps", "Open map app", onMapsClick),
        FeatureItem("Settings", "System settings", onSettingsClick),
    )

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                ClockAndDate()
                Spacer(modifier = Modifier.height(36.dp))
                WeatherSummaryCard(
                    city = weatherCity,
                    weatherState = weatherState,
                    onClick = onWeatherClick,
                    onRefresh = onWeatherRefresh,
                )
            }

            Button(
                onClick = onAllAppsClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = "All Apps",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            features.chunked(3).forEach { rowItems ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    rowItems.forEach { feature ->
                        FeatureTile(
                            item = feature,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClockAndDate() {
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000)
        }
    }

    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault())
    }

    Text(
        text = now.format(timeFormatter),
        color = Color.White,
        fontSize = 72.sp,
        fontWeight = FontWeight.Light,
        maxLines = 1,
    )
    Text(
        text = now.format(dateFormatter),
        color = Color(0xFFBDBDBD),
        fontSize = 21.sp,
        maxLines = 2,
    )
}

@Composable
private fun WeatherSummaryCard(
    city: String,
    weatherState: WeatherUiState,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
        border = BorderStroke(1.dp, Color(0xFF363636)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "Weather",
                color = Color(0xFFBDBDBD),
                fontSize = 16.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))

            when (weatherState) {
                WeatherUiState.Loading -> {
                    Text(
                        text = city,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Loading…",
                        color = Color(0xFFBDBDBD),
                        fontSize = 18.sp,
                    )
                }

                is WeatherUiState.Success -> {
                    val data = weatherState.data
                    Text(
                        text = data.placeName,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = String.format(Locale.getDefault(), "%.0f°C", data.temperatureC),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text(
                        text = data.condition,
                        color = Color(0xFFD5D5D5),
                        fontSize = 18.sp,
                    )
                    Text(
                        text = String.format(
                            Locale.getDefault(),
                            "High %.0f°  /  Low %.0f°",
                            data.highC,
                            data.lowC,
                        ),
                        color = Color(0xFF9E9E9E),
                        fontSize = 15.sp,
                    )
                }

                is WeatherUiState.Error -> {
                    Text(
                        text = city,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = weatherState.message,
                        color = Color(0xFFEF9A9A),
                        fontSize = 15.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(onClick = onRefresh) {
                        Text("Retry")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Tap to change city",
                color = Color(0xFF7D7D7D),
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun FeatureTile(
    item: FeatureItem,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = item.onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
        border = BorderStroke(1.dp, Color(0xFF363636)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 31.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = item.subtitle,
                    color = Color(0xFF9E9E9E),
                    fontSize = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun CityInputDialog(
    initialCity: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var cityInput by remember(initialCity) { mutableStateOf(initialCity) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weather city") },
        text = {
            Column {
                Text("Enter a city name. Examples: Seoul, Busan, Madrid")
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = cityInput,
                    onValueChange = { cityInput = it },
                    singleLine = true,
                    label = { Text("City") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(cityInput) },
                enabled = cityInput.isNotBlank(),
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun AllAppsScreen(
    onBack: () -> Unit,
    onLaunchError: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(36.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
            ) {
                Text("Back")
            }
            Spacer(modifier = Modifier.width(24.dp))
            Text(
                text = "All Apps",
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (isLoading) "Loading…" else "${apps.size} apps",
                color = Color(0xFF9E9E9E),
                fontSize = 17.sp,
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(apps, key = { "${it.packageName}/${it.activityName}" }) { app ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp)
                        .clickable {
                            if (!launchAppEntry(context, app)) {
                                onLaunchError("${app.label} 앱을 실행할 수 없습니다.")
                            }
                        },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
                    border = BorderStroke(1.dp, Color(0xFF303030)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = app.label,
                            textAlign = TextAlign.Center,
                            fontSize = 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun loadLaunchableApps(context: Context): List<AppEntry> {
    val packageManager = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    return packageManager.queryIntentActivities(launcherIntent, 0)
        .asSequence()
        .filter { it.activityInfo.packageName != context.packageName }
        .map { resolveInfo ->
            AppEntry(
                label = resolveInfo.loadLabel(packageManager).toString(),
                packageName = resolveInfo.activityInfo.packageName,
                activityName = resolveInfo.activityInfo.name,
            )
        }
        .distinctBy { it.packageName to it.activityName }
        .sortedBy { it.label.lowercase(Locale.getDefault()) }
        .toList()
}

private fun launchAppEntry(context: Context, app: AppEntry): Boolean {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        component = ComponentName(app.packageName, app.activityName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return startIntent(context, intent)
}

private fun launchFirstInstalledPackage(
    context: Context,
    packageNames: List<String>,
    fallbackUri: Uri,
): Boolean {
    val packageManager = context.packageManager
    for (packageName in packageNames) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (startIntent(context, launchIntent)) return true
        }
    }
    return openUri(context, fallbackUri)
}

private fun openUri(context: Context, uri: Uri): Boolean {
    return startIntent(
        context,
        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun startIntent(context: Context, intent: Intent): Boolean {
    return try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}

private object WeatherService {
    fun fetch(city: String): WeatherUiState {
        return try {
            val encodedCity = URLEncoder.encode(city, Charsets.UTF_8.name())
            val geocodingUrl =
                "https://geocoding-api.open-meteo.com/v1/search" +
                    "?name=$encodedCity&count=1&language=en&format=json"

            val geocodingJson = getJson(geocodingUrl)
            val results = geocodingJson.optJSONArray("results")
                ?: return WeatherUiState.Error("City not found. Tap and enter another city.")

            if (results.length() == 0) {
                return WeatherUiState.Error("City not found. Tap and enter another city.")
            }

            val location = results.getJSONObject(0)
            val latitude = location.getDouble("latitude")
            val longitude = location.getDouble("longitude")
            val cityName = location.optString("name", city)
            val admin = location.optString("admin1")
            val country = location.optString("country")
            val placeName = listOf(cityName, admin, country)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")

            val forecastUrl =
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$latitude&longitude=$longitude" +
                    "&current=temperature_2m,weather_code" +
                    "&daily=temperature_2m_max,temperature_2m_min" +
                    "&timezone=auto&forecast_days=1"

            val forecastJson = getJson(forecastUrl)
            val current = forecastJson.getJSONObject("current")
            val daily = forecastJson.getJSONObject("daily")

            val weatherCode = current.optInt("weather_code", -1)
            val data = WeatherData(
                placeName = placeName,
                temperatureC = current.getDouble("temperature_2m"),
                highC = daily.getJSONArray("temperature_2m_max").getDouble(0),
                lowC = daily.getJSONArray("temperature_2m_min").getDouble(0),
                condition = weatherCodeDescription(weatherCode),
            )
            WeatherUiState.Success(data)
        } catch (error: Exception) {
            WeatherUiState.Error(
                error.message?.takeIf { it.isNotBlank() }
                    ?: "Unable to load weather. Check the network connection.",
            )
        }
    }

    private fun getJson(url: String): JSONObject {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ADPS-Literal-Launcher/1.0")

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("Weather service error: HTTP $responseCode")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun weatherCodeDescription(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Current weather"
    }
}
