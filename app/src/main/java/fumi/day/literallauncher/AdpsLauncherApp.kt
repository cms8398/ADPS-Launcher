package fumi.day.literallauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
private const val SCREEN_SAVER_TIMEOUT_MS = 5 * 60 * 1000L

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
    AI_ASSISTANT,
    SCREEN_SAVER,
    GALLERY,
}

private data class FeatureItem(
    val title: String,
    @DrawableRes val iconRes: Int,
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

private sealed interface AiUiState {
    data object Idle : AiUiState
    data object Loading : AiUiState
    data class Success(val answer: String) : AiUiState
    data class Error(val message: String) : AiUiState
}

private enum class TvAppLaunchResult {
    LAUNCHED,
    NOT_FOUND,
    FAILED,
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
        val userInteractionChannel = remember { Channel<Unit>(Channel.CONFLATED) }
        var showCityDialog by remember { mutableStateOf(false) }
        var messageDialog by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(weatherCity, weatherRefreshKey) {
            weatherState = WeatherUiState.Loading
            weatherState = withContext(Dispatchers.IO) {
                WeatherService.fetch(weatherCity)
            }
        }

        LaunchedEffect(currentPage) {
            // 터치할 때마다 Compose state를 변경하지 않습니다.
            // HOME에 있는 동안 입력 이벤트를 Channel로만 받아 타이머를 다시 기다립니다.
            // 따라서 매 터치마다 런처 전체가 재구성되는 현상을 피합니다.
            if (currentPage == LauncherPage.HOME) {
                while (true) {
                    val userInteracted = withTimeoutOrNull(SCREEN_SAVER_TIMEOUT_MS) {
                        userInteractionChannel.receive()
                        true
                    } ?: false

                    if (!userInteracted) {
                        showCityDialog = false
                        messageDialog = null
                        currentPage = LauncherPage.SCREEN_SAVER
                        break
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        userInteractionChannel.trySend(Unit)
                    }
                },
            color = Color.Black,
        ) {
            when (currentPage) {
                LauncherPage.HOME -> HomeScreen(
                    weatherCity = weatherCity,
                    weatherState = weatherState,
                    onWeatherClick = { showCityDialog = true },
                    onWeatherRefresh = { weatherRefreshKey++ },
                    onHdmiClick = {
                        when (launchTvApp(context)) {
                            TvAppLaunchResult.LAUNCHED -> Unit
                            TvAppLaunchResult.NOT_FOUND -> {
                                messageDialog =
                                    "TV 앱을 찾을 수 없습니다. All Apps에서 앱 이름이 정확히 TV로 표시되는지 확인해주세요."
                            }

                            TvAppLaunchResult.FAILED -> {
                                messageDialog =
                                    "TV 앱은 찾았지만 실행할 수 없습니다. 시스템 권한 또는 앱 상태를 확인해주세요."
                            }
                        }
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
                    onAiAssistantClick = { currentPage = LauncherPage.AI_ASSISTANT },
                    onScreenSaverClick = { currentPage = LauncherPage.SCREEN_SAVER },
                    onGalleryClick = { currentPage = LauncherPage.GALLERY },
                    onAllAppsClick = { currentPage = LauncherPage.ALL_APPS },
                )

                LauncherPage.ALL_APPS -> AllAppsScreen(
                    onBack = { currentPage = LauncherPage.HOME },
                    onLaunchError = { messageDialog = it },
                )

                LauncherPage.AI_ASSISTANT -> AiAssistantScreen(
                    onBack = { currentPage = LauncherPage.HOME },
                )

                LauncherPage.SCREEN_SAVER -> ScreenSaverScreen(
                    onExit = { currentPage = LauncherPage.HOME },
                )

                LauncherPage.GALLERY -> MediaGalleryScreen(
                    onBack = { currentPage = LauncherPage.HOME },
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
    onAiAssistantClick: () -> Unit,
    onScreenSaverClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onAllAppsClick: () -> Unit,
) {
    val features = listOf(
        FeatureItem("Weather", R.drawable.ic_weather, onWeatherClick),
        FeatureItem("HDMI", R.drawable.ic_hdmi, onHdmiClick),
        FeatureItem("YouTube", R.drawable.ic_youtube, onYouTubeClick),
        FeatureItem("Browser", R.drawable.ic_browser, onBrowserClick),
        FeatureItem("Maps", R.drawable.ic_maps, onMapsClick),
        FeatureItem("Settings", R.drawable.ic_settings, onSettingsClick),
        FeatureItem("AI Assistant", R.drawable.ic_ai_assistant, onAiAssistantClick),
        FeatureItem("Screen Saver", R.drawable.ic_screen_saver, onScreenSaverClick),
        FeatureItem("Gallery", R.drawable.ic_gallery, onGalleryClick),
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        val fontScale = LocalDensity.current.fontScale
        val veryCompactMode =
            maxWidth < 850.dp || maxHeight < 470.dp || fontScale >= 1.25f
        val compactMode =
            veryCompactMode || maxWidth < 1150.dp || maxHeight < 650.dp || fontScale > 1.05f

        val horizontalPadding = when {
            veryCompactMode -> 12.dp
            compactMode -> 22.dp
            else -> 48.dp
        }
        val verticalPadding = when {
            veryCompactMode -> 10.dp
            compactMode -> 20.dp
            else -> 40.dp
        }
        val sectionGap = when {
            veryCompactMode -> 12.dp
            compactMode -> 20.dp
            else -> 40.dp
        }
        val tileHorizontalGap = when {
            veryCompactMode -> 8.dp
            compactMode -> 14.dp
            else -> 24.dp
        }
        val tileVerticalGap = when {
            veryCompactMode -> 8.dp
            compactMode -> 12.dp
            else -> 18.dp
        }
        val leftPanelWidth = when {
            veryCompactMode -> 220.dp
            compactMode -> 280.dp
            else -> 360.dp
        }
        val clockWeatherGap = when {
            veryCompactMode -> 10.dp
            compactMode -> 18.dp
            else -> 36.dp
        }
        val buttonGap = when {
            veryCompactMode -> 8.dp
            compactMode -> 12.dp
            else -> 16.dp
        }
        val allAppsHeight = when {
            veryCompactMode -> 48.dp
            compactMode -> 56.dp
            else -> 72.dp
        }
        val allAppsFontSize = when {
            veryCompactMode -> 16.sp
            compactMode -> 18.sp
            else -> 22.sp
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = horizontalPadding,
                    vertical = verticalPadding,
                ),
            horizontalArrangement = Arrangement.spacedBy(sectionGap),
        ) {
            Column(
                modifier = Modifier
                    .width(leftPanelWidth)
                    .fillMaxHeight(),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    ClockAndDate(
                        compactMode = compactMode,
                        veryCompactMode = veryCompactMode,
                    )
                    Spacer(modifier = Modifier.height(clockWeatherGap))
                    WeatherSummaryCard(
                        city = weatherCity,
                        weatherState = weatherState,
                        onClick = onWeatherClick,
                        onRefresh = onWeatherRefresh,
                        compactMode = compactMode,
                        veryCompactMode = veryCompactMode,
                    )
                }

                Spacer(modifier = Modifier.height(buttonGap))

                Button(
                    onClick = onAllAppsClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(allAppsHeight),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = "All Apps",
                        fontSize = allAppsFontSize,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(tileVerticalGap),
            ) {
                features.chunked(3).forEach { rowItems ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(tileHorizontalGap),
                    ) {
                        rowItems.forEach { feature ->
                            FeatureTile(
                                item = feature,
                                compactMode = compactMode,
                                veryCompactMode = veryCompactMode,
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
}


@Composable
private fun ScreenSaverScreen(
    onExit: () -> Unit,
) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var positionIndex by remember { mutableIntStateOf(0) }

    val positions = remember {
        listOf(
            Alignment.TopStart,
            Alignment.TopEnd,
            Alignment.Center,
            Alignment.BottomEnd,
            Alignment.BottomStart,
        )
    }

    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault())
    }

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(30_000)
        }
    }

    // OLED 번인 방지를 위해 표시 위치를 주기적으로 이동합니다.
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            positionIndex = (positionIndex + 1) % positions.size
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .clickable(onClick = onExit)
            .padding(48.dp),
        contentAlignment = positions[positionIndex],
    ) {
        val compactMode = maxWidth < 900.dp || maxHeight < 500.dp
        val timeFontSize = if (compactMode) 72.sp else 108.sp
        val dateFontSize = if (compactMode) 20.sp else 28.sp
        val brandFontSize = if (compactMode) 16.sp else 22.sp
        val hintFontSize = if (compactMode) 12.sp else 15.sp

        Column(
            horizontalAlignment = when (positions[positionIndex]) {
                Alignment.TopEnd,
                Alignment.BottomEnd -> Alignment.End

                Alignment.Center -> Alignment.CenterHorizontally
                else -> Alignment.Start
            },
        ) {
            Text(
                text = now.format(timeFormatter),
                color = Color.White,
                fontSize = timeFontSize,
                fontWeight = FontWeight.ExtraLight,
                maxLines = 1,
            )
            Text(
                text = now.format(dateFormatter),
                color = Color(0xFFBDBDBD),
                fontSize = dateFontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "ADPS Transparent Display",
                color = Color(0xFF8A8A8A),
                fontSize = brandFontSize,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Touch anywhere to return",
                color = Color(0xFF5F5F5F),
                fontSize = hintFontSize,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AiAssistantScreen(
    onBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var aiState by remember { mutableStateOf<AiUiState>(AiUiState.Idle) }
    val answerScrollState = rememberScrollState()

    fun submitQuestion() {
        val trimmedQuestion = question.trim()
        if (trimmedQuestion.isEmpty() || aiState is AiUiState.Loading) return

        aiState = AiUiState.Loading
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                GeminiApiClient.generateAnswer(
                    apiKey = BuildConfig.GEMINI_API_KEY,
                    userPrompt = trimmedQuestion,
                )
            }

            aiState = when (result) {
                is GeminiResult.Success -> AiUiState.Success(result.text)
                is GeminiResult.Error -> AiUiState.Error(result.message)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
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
                text = "AI Assistant",
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "Gemini",
                color = Color(0xFF9E9E9E),
                fontSize = 17.sp,
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Row(
            modifier = Modifier
                .fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Card(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
                border = BorderStroke(1.dp, Color(0xFF363636)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                ) {
                    Text(
                        text = "질문",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        label = { Text("Gemini에게 질문하세요") },
                        enabled = aiState !is AiUiState.Loading,
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(
                        onClick = ::submitQuestion,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        enabled = question.isNotBlank() && aiState !is AiUiState.Loading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = if (aiState is AiUiState.Loading) "Sending…" else "Send",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Card(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
                border = BorderStroke(1.dp, Color(0xFF363636)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                ) {
                    Text(
                        text = "답변",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(18.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        when (val state = aiState) {
                            AiUiState.Idle -> Text(
                                text = "왼쪽 입력창에 질문을 입력한 뒤 Send를 눌러주세요.",
                                color = Color(0xFF9E9E9E),
                                fontSize = 19.sp,
                            )

                            AiUiState.Loading -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                )
                                Text(
                                    text = "Gemini가 답변을 생성하고 있습니다…",
                                    color = Color(0xFFD5D5D5),
                                    fontSize = 19.sp,
                                )
                            }

                            is AiUiState.Success -> Text(
                                text = state.answer,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(answerScrollState),
                                color = Color.White,
                                fontSize = 20.sp,
                                lineHeight = 30.sp,
                            )

                            is AiUiState.Error -> Text(
                                text = state.message,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(answerScrollState),
                                color = Color(0xFFEF9A9A),
                                fontSize = 18.sp,
                                lineHeight = 27.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClockAndDate(
    compactMode: Boolean,
    veryCompactMode: Boolean,
) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(30_000)
        }
    }

    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault())
    }
    val timeFontSize = when {
        veryCompactMode -> 42.sp
        compactMode -> 54.sp
        else -> 72.sp
    }
    val dateFontSize = when {
        veryCompactMode -> 13.sp
        compactMode -> 16.sp
        else -> 21.sp
    }

    Text(
        text = now.format(timeFormatter),
        color = Color.White,
        fontSize = timeFontSize,
        fontWeight = FontWeight.Light,
        maxLines = 1,
    )
    Text(
        text = now.format(dateFormatter),
        color = Color(0xFFBDBDBD),
        fontSize = dateFontSize,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun WeatherSummaryCard(
    city: String,
    weatherState: WeatherUiState,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
    compactMode: Boolean,
    veryCompactMode: Boolean,
) {
    val contentPadding = when {
        veryCompactMode -> 12.dp
        compactMode -> 18.dp
        else -> 24.dp
    }
    val labelFontSize = when {
        veryCompactMode -> 12.sp
        compactMode -> 14.sp
        else -> 16.sp
    }
    val cityFontSize = when {
        veryCompactMode -> 18.sp
        compactMode -> 22.sp
        else -> 26.sp
    }
    val temperatureFontSize = when {
        veryCompactMode -> 28.sp
        compactMode -> 36.sp
        else -> 44.sp
    }
    val conditionFontSize = when {
        veryCompactMode -> 13.sp
        compactMode -> 15.sp
        else -> 18.sp
    }
    val detailFontSize = when {
        veryCompactMode -> 11.sp
        compactMode -> 13.sp
        else -> 15.sp
    }
    val hintFontSize = when {
        veryCompactMode -> 10.sp
        compactMode -> 12.sp
        else -> 13.sp
    }
    val titleGap = when {
        veryCompactMode -> 4.dp
        compactMode -> 6.dp
        else -> 10.dp
    }
    val bottomGap = when {
        veryCompactMode -> 6.dp
        compactMode -> 8.dp
        else -> 12.dp
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
        border = BorderStroke(1.dp, Color(0xFF363636)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(contentPadding)) {
            Text(
                text = "Weather",
                color = Color(0xFFBDBDBD),
                fontSize = labelFontSize,
            )
            Spacer(modifier = Modifier.height(titleGap))

            when (weatherState) {
                WeatherUiState.Loading -> {
                    Text(
                        text = city,
                        fontSize = cityFontSize,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Loading...",
                        color = Color(0xFFBDBDBD),
                        fontSize = conditionFontSize,
                    )
                }

                is WeatherUiState.Success -> {
                    val data = weatherState.data
                    Text(
                        text = data.placeName,
                        fontSize = cityFontSize,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = String.format(Locale.getDefault(), "%.0f°C", data.temperatureC),
                        fontSize = temperatureFontSize,
                        fontWeight = FontWeight.Light,
                        maxLines = 1,
                    )
                    Text(
                        text = data.condition,
                        color = Color(0xFFD5D5D5),
                        fontSize = conditionFontSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = String.format(
                            Locale.getDefault(),
                            "High %.0f°  /  Low %.0f°",
                            data.highC,
                            data.lowC,
                        ),
                        color = Color(0xFF9E9E9E),
                        fontSize = detailFontSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                is WeatherUiState.Error -> {
                    Text(
                        text = city,
                        fontSize = cityFontSize,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = weatherState.message,
                        color = Color(0xFFEF9A9A),
                        fontSize = detailFontSize,
                        maxLines = if (veryCompactMode) 2 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onRefresh) {
                        Text(
                            text = "Retry",
                            fontSize = conditionFontSize,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(bottomGap))
            Text(
                text = "Tap to change city",
                color = Color(0xFF7D7D7D),
                fontSize = hintFontSize,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FeatureTile(
    item: FeatureItem,
    compactMode: Boolean,
    veryCompactMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val contentPadding = when {
        veryCompactMode -> 10.dp
        compactMode -> 14.dp
        else -> 20.dp
    }
    val iconSize = when {
        veryCompactMode -> 58.dp
        compactMode -> 76.dp
        else -> 100.dp
    }
    val titleFontSize = when {
        veryCompactMode -> 15.sp
        compactMode -> 18.sp
        else -> 22.sp
    }
    val iconTitleGap = when {
        veryCompactMode -> 6.dp
        compactMode -> 10.dp
        else -> 14.dp
    }

    Card(
        modifier = modifier.clickable(onClick = item.onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
        border = BorderStroke(1.dp, Color(0xFF363636)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(id = item.iconRes),
                contentDescription = item.title,
                modifier = Modifier
                    .width(iconSize)
                    .height(iconSize),
                contentScale = ContentScale.Fit,
            )

            Spacer(modifier = Modifier.height(iconTitleGap))

            Text(
                text = item.title,
                color = Color.White,
                fontSize = titleFontSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
            .safeDrawingPadding()
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

private fun launchTvApp(context: Context): TvAppLaunchResult {
    val tvApps = loadLaunchableApps(context)
        .filter { app -> app.label.trim().equals("TV", ignoreCase = true) }

    if (tvApps.isEmpty()) return TvAppLaunchResult.NOT_FOUND

    return if (tvApps.any { app -> launchAppEntry(context, app) }) {
        TvAppLaunchResult.LAUNCHED
    } else {
        TvAppLaunchResult.FAILED
    }
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
