package fumi.day.literallauncher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private enum class MediaGalleryMode {
    BROWSE,
    VIEWER,
    SELECT,
    MEDIA_SHOW,
}

private enum class MediaGalleryType {
    IMAGE,
    VIDEO,
}

private enum class MediaGallerySource(val label: String) {
    ALL("All"),
    LOCAL("Local"),
    USB("USB"),
}

private data class MediaGalleryItem(
    val uri: Uri,
    val type: MediaGalleryType,
    val source: MediaGallerySource,
    val displayName: String,
    val mimeType: String,
    val dateAddedSeconds: Long,
) {
    val key: String = uri.toString()
}

@Composable
internal fun MediaGalleryScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    var mediaItems by remember { mutableStateOf<List<MediaGalleryItem>>(emptyList()) }
    var sourceFilter by remember { mutableStateOf(MediaGallerySource.ALL) }
    var mode by remember { mutableStateOf(MediaGalleryMode.BROWSE) }
    var selectedKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerItems by remember { mutableStateOf<List<MediaGalleryItem>>(emptyList()) }
    var viewerIndex by remember { mutableIntStateOf(0) }
    var mediaShowItems by remember { mutableStateOf<List<MediaGalleryItem>>(emptyList()) }
    var imageIntervalSeconds by remember { mutableIntStateOf(5) }

    var permissionGranted by remember {
        mutableStateOf(hasMediaGalleryPermission(context))
    }
    var refreshKey by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val filteredItems = remember(mediaItems, sourceFilter) {
        when (sourceFilter) {
            MediaGallerySource.ALL -> mediaItems
            MediaGallerySource.LOCAL -> mediaItems.filter {
                it.source == MediaGallerySource.LOCAL
            }
            MediaGallerySource.USB -> mediaItems.filter {
                it.source == MediaGallerySource.USB
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionGranted = hasMediaGalleryPermission(context)
        if (permissionGranted) {
            errorMessage = null
            refreshKey++
        } else {
            errorMessage =
                "Photo and video access is required to browse media on this device."
        }
    }

    DisposableEffect(context) {
        val storageReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                refreshKey++
            }
        }
        val storageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED)
            addDataScheme("file")
        }

        ContextCompat.registerReceiver(
            context,
            storageReceiver,
            storageFilter,
            ContextCompat.RECEIVER_EXPORTED,
        )

        onDispose {
            runCatching { context.unregisterReceiver(storageReceiver) }
        }
    }

    LaunchedEffect(permissionGranted, refreshKey) {
        if (!permissionGranted) {
            mediaItems = emptyList()
            isLoading = false
            return@LaunchedEffect
        }

        isLoading = true
        errorMessage = null

        val result = withContext(Dispatchers.IO) {
            runCatching { queryMediaGalleryItems(context) }
        }

        result.fold(
            onSuccess = { refreshedItems ->
                mediaItems = refreshedItems
                val availableByKey = refreshedItems.associateBy { it.key }

                selectedKeys = selectedKeys.filter { it in availableByKey }
                viewerItems = viewerItems.mapNotNull { availableByKey[it.key] }
                mediaShowItems = mediaShowItems.mapNotNull { availableByKey[it.key] }

                if (viewerItems.isEmpty() && mode == MediaGalleryMode.VIEWER) {
                    mode = MediaGalleryMode.BROWSE
                } else if (viewerItems.isNotEmpty()) {
                    viewerIndex = viewerIndex.coerceIn(0, viewerItems.lastIndex)
                }

                if (mediaShowItems.isEmpty() && mode == MediaGalleryMode.MEDIA_SHOW) {
                    mode = MediaGalleryMode.SELECT
                }

                if (refreshedItems.isEmpty()) {
                    errorMessage =
                        "MediaStore did not find any indexed images or videos."
                }
            },
            onFailure = { error ->
                mediaItems = emptyList()
                errorMessage = error.message?.takeIf { it.isNotBlank() }
                    ?: "Unable to read media from MediaStore."
                if (mode == MediaGalleryMode.VIEWER ||
                    mode == MediaGalleryMode.MEDIA_SHOW
                ) {
                    mode = MediaGalleryMode.BROWSE
                }
            },
        )

        isLoading = false
    }

    when {
        !permissionGranted -> MediaGalleryPermissionScreen(
            message = errorMessage,
            onBack = onBack,
            onRequestPermission = {
                permissionLauncher.launch(mediaGalleryPermissions())
            },
        )

        isLoading && mediaItems.isEmpty() -> MediaGalleryLoadingScreen(onBack = onBack)

        mode == MediaGalleryMode.VIEWER && viewerItems.isNotEmpty() -> {
            MediaGalleryViewerScreen(
                mediaItems = viewerItems,
                currentIndex = viewerIndex,
                onIndexChanged = { viewerIndex = it },
                onBack = { mode = MediaGalleryMode.BROWSE },
            )
        }

        mode == MediaGalleryMode.MEDIA_SHOW && mediaShowItems.isNotEmpty() -> {
            SelectedMediaShowScreen(
                mediaItems = mediaShowItems,
                imageIntervalSeconds = imageIntervalSeconds,
                onExit = { mode = MediaGalleryMode.SELECT },
            )
        }

        else -> MediaGalleryBrowserScreen(
            mediaItems = mediaItems,
            filteredItems = filteredItems,
            sourceFilter = sourceFilter,
            selectionMode = mode == MediaGalleryMode.SELECT,
            selectedKeys = selectedKeys,
            imageIntervalSeconds = imageIntervalSeconds,
            errorMessage = errorMessage,
            onBack = {
                if (mode == MediaGalleryMode.SELECT) {
                    selectedKeys = emptyList()
                    mode = MediaGalleryMode.BROWSE
                } else {
                    onBack()
                }
            },
            onFilterChanged = { sourceFilter = it },
            onRefresh = { refreshKey++ },
            onOpenItem = { index ->
                viewerItems = filteredItems
                viewerIndex = index
                mode = MediaGalleryMode.VIEWER
            },
            onEnterSelectionMode = {
                selectedKeys = emptyList()
                mode = MediaGalleryMode.SELECT
            },
            onToggleSelection = { media ->
                selectedKeys = if (media.key in selectedKeys) {
                    selectedKeys - media.key
                } else {
                    selectedKeys + media.key
                }
            },
            onSelectVisible = {
                val newKeys = filteredItems
                    .map { it.key }
                    .filterNot { it in selectedKeys }
                selectedKeys = selectedKeys + newKeys
            },
            onClearSelection = { selectedKeys = emptyList() },
            onIntervalChanged = { imageIntervalSeconds = it },
            onStartMediaShow = {
                val itemsByKey = mediaItems.associateBy { it.key }
                mediaShowItems = selectedKeys.mapNotNull { itemsByKey[it] }
                if (mediaShowItems.isNotEmpty()) {
                    mode = MediaGalleryMode.MEDIA_SHOW
                }
            },
        )
    }
}

@Composable
private fun MediaGalleryBrowserScreen(
    mediaItems: List<MediaGalleryItem>,
    filteredItems: List<MediaGalleryItem>,
    sourceFilter: MediaGallerySource,
    selectionMode: Boolean,
    selectedKeys: List<String>,
    imageIntervalSeconds: Int,
    errorMessage: String?,
    onBack: () -> Unit,
    onFilterChanged: (MediaGallerySource) -> Unit,
    onRefresh: () -> Unit,
    onOpenItem: (Int) -> Unit,
    onEnterSelectionMode: () -> Unit,
    onToggleSelection: (MediaGalleryItem) -> Unit,
    onSelectVisible: () -> Unit,
    onClearSelection: () -> Unit,
    onIntervalChanged: (Int) -> Unit,
    onStartMediaShow: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        val compactMode = maxWidth < 950.dp || maxHeight < 540.dp
        val outerPadding = if (compactMode) 14.dp else 24.dp
        val controlHeight = if (compactMode) 44.dp else 52.dp
        val headerFontSize = if (compactMode) 25.sp else 32.sp
        val gridGap = if (compactMode) 10.dp else 16.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(outerPadding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compactMode) 8.dp else 12.dp),
            ) {
                GalleryControlButton(
                    text = if (selectionMode) "Cancel" else "Back",
                    height = controlHeight,
                    primary = true,
                    onClick = onBack,
                )

                Text(
                    text = if (selectionMode) "Select Media" else "Gallery",
                    color = Color.White,
                    fontSize = headerFontSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )

                Spacer(modifier = Modifier.width(if (compactMode) 2.dp else 8.dp))

                MediaGallerySource.entries.forEach { source ->
                    val count = when (source) {
                        MediaGallerySource.ALL -> mediaItems.size
                        MediaGallerySource.LOCAL -> mediaItems.count {
                            it.source == MediaGallerySource.LOCAL
                        }
                        MediaGallerySource.USB -> mediaItems.count {
                            it.source == MediaGallerySource.USB
                        }
                    }
                    GalleryFilterButton(
                        source = source,
                        count = count,
                        selected = sourceFilter == source,
                        height = controlHeight,
                        onClick = { onFilterChanged(source) },
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                GalleryControlButton(
                    text = "Refresh",
                    height = controlHeight,
                    onClick = onRefresh,
                )

                if (!selectionMode) {
                    GalleryControlButton(
                        text = "Create Media Show",
                        height = controlHeight,
                        primary = true,
                        enabled = mediaItems.isNotEmpty(),
                        onClick = onEnterSelectionMode,
                    )
                }
            }

            if (selectionMode) {
                Spacer(modifier = Modifier.height(if (compactMode) 10.dp else 14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (compactMode) 8.dp else 12.dp),
                ) {
                    Text(
                        text = "Selected ${selectedKeys.size}",
                        color = Color.White,
                        fontSize = if (compactMode) 17.sp else 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )

                    GalleryControlButton(
                        text = "Select Visible",
                        height = controlHeight,
                        enabled = filteredItems.isNotEmpty(),
                        onClick = onSelectVisible,
                    )
                    GalleryControlButton(
                        text = "Clear",
                        height = controlHeight,
                        enabled = selectedKeys.isNotEmpty(),
                        onClick = onClearSelection,
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = "Image interval",
                        color = Color(0xFFBDBDBD),
                        fontSize = if (compactMode) 13.sp else 15.sp,
                    )
                    listOf(3, 5, 10).forEach { seconds ->
                        GalleryControlButton(
                            text = "${seconds}s",
                            height = controlHeight,
                            primary = imageIntervalSeconds == seconds,
                            onClick = { onIntervalChanged(seconds) },
                        )
                    }

                    GalleryControlButton(
                        text = "Start",
                        height = controlHeight,
                        primary = true,
                        enabled = selectedKeys.isNotEmpty(),
                        onClick = onStartMediaShow,
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (compactMode) 12.dp else 18.dp))

            if (filteredItems.isEmpty()) {
                MediaGalleryEmptyState(
                    sourceFilter = sourceFilter,
                    message = errorMessage,
                    onRefresh = onRefresh,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (compactMode) 160.dp else 190.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(gridGap),
                    verticalArrangement = Arrangement.spacedBy(gridGap),
                ) {
                    itemsIndexed(
                        items = filteredItems,
                        key = { _, media -> media.key },
                    ) { index, media ->
                        val selectedOrder = selectedKeys.indexOf(media.key) + 1
                        MediaGalleryThumbnailCard(
                            media = media,
                            selectionMode = selectionMode,
                            selectedOrder = selectedOrder,
                            compactMode = compactMode,
                            onClick = {
                                if (selectionMode) {
                                    onToggleSelection(media)
                                } else {
                                    onOpenItem(index)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaGalleryThumbnailCard(
    media: MediaGalleryItem,
    selectionMode: Boolean,
    selectedOrder: Int,
    compactMode: Boolean,
    onClick: () -> Unit,
) {
    val isSelected = selectedOrder > 0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.38f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
        border = BorderStroke(
            width = if (isSelected) 3.dp else 1.dp,
            color = if (isSelected) Color.White else Color(0xFF363636),
        ),
        shape = RoundedCornerShape(if (compactMode) 10.dp else 14.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            MediaGalleryThumbnail(
                media = media,
                modifier = Modifier.fillMaxSize(),
            )

            Text(
                text = media.source.label,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = Color.White,
                fontSize = if (compactMode) 11.sp else 12.sp,
            )

            if (media.type == MediaGalleryType.VIDEO) {
                Text(
                    text = "▶  Video",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(
                            color = Color.Black.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    color = Color.White,
                    fontSize = if (compactMode) 12.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(if (compactMode) 28.dp else 32.dp)
                        .background(
                            color = if (isSelected) Color.White else Color.Black.copy(alpha = 0.72f),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isSelected) selectedOrder.toString() else "",
                        color = if (isSelected) Color.Black else Color.White,
                        fontSize = if (compactMode) 13.sp else 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Text(
                text = media.displayName,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.76f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                color = Color.White,
                fontSize = if (compactMode) 11.sp else 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MediaGalleryThumbnail(
    media: MediaGalleryItem,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(media.key) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(media.key) { mutableStateOf(false) }

    LaunchedEffect(media.key) {
        bitmap = null
        failed = false
        bitmap = withContext(Dispatchers.IO) {
            loadMediaGalleryThumbnail(
                context = context,
                media = media,
                maxWidth = 420,
                maxHeight = 260,
            )
        }
        failed = bitmap == null
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val loadedBitmap = bitmap
        when {
            loadedBitmap != null -> Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = media.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            failed -> Text(
                text = if (media.type == MediaGalleryType.VIDEO) "VIDEO" else "IMAGE",
                color = Color(0xFF777777),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )

            else -> CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun MediaGalleryViewerScreen(
    mediaItems: List<MediaGalleryItem>,
    currentIndex: Int,
    onIndexChanged: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val currentMedia = mediaItems[currentIndex]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GalleryControlButton(
                text = "Back to Gallery",
                height = 52.dp,
                primary = true,
                onClick = onBack,
            )
            GalleryControlButton(
                text = "Previous",
                height = 52.dp,
                onClick = {
                    onIndexChanged(
                        if (currentIndex == 0) mediaItems.lastIndex else currentIndex - 1,
                    )
                },
            )
            GalleryControlButton(
                text = "Next",
                height = 52.dp,
                onClick = { onIndexChanged((currentIndex + 1) % mediaItems.size) },
            )

            Spacer(modifier = Modifier.weight(1f))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = currentMedia.displayName,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${currentIndex + 1} / ${mediaItems.size}  ·  " +
                        "${currentMedia.type.label()}  ·  ${currentMedia.source.label}  ·  " +
                        currentMedia.mimeType.ifBlank { "Unknown format" },
                    color = Color(0xFF9E9E9E),
                    fontSize = 14.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            border = BorderStroke(1.dp, Color(0xFF363636)),
            shape = RoundedCornerShape(14.dp),
        ) {
            MediaGalleryItemContent(
                media = currentMedia,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                autoPlayVideo = true,
                showVideoControls = true,
            )
        }
    }
}

@Composable
private fun SelectedMediaShowScreen(
    mediaItems: List<MediaGalleryItem>,
    imageIntervalSeconds: Int,
    onExit: () -> Unit,
) {
    var currentIndex by remember(mediaItems) { mutableIntStateOf(0) }
    var failedVideoKeys by remember(mediaItems) { mutableStateOf<Set<String>>(emptySet()) }
    val currentMedia = mediaItems[currentIndex.coerceIn(0, mediaItems.lastIndex)]

    fun showNext(excludedKeys: Set<String> = failedVideoKeys) {
        var candidateIndex = currentIndex
        repeat(mediaItems.size) {
            candidateIndex = (candidateIndex + 1) % mediaItems.size
            if (mediaItems[candidateIndex].key !in excludedKeys) {
                currentIndex = candidateIndex
                return
            }
        }
    }

    LaunchedEffect(currentMedia.key, imageIntervalSeconds) {
        if (currentMedia.type == MediaGalleryType.IMAGE) {
            delay(imageIntervalSeconds * 1_000L)
            showNext()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        MediaGalleryItemContent(
            media = currentMedia,
            modifier = Modifier.fillMaxSize(),
            autoPlayVideo = true,
            showVideoControls = false,
            onVideoEnded = { showNext() },
            onVideoError = {
                val updatedFailedKeys = failedVideoKeys + currentMedia.key
                failedVideoKeys = updatedFailedKeys
                showNext(excludedKeys = updatedFailedKeys)
            },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onExit),
        )

        Text(
            text = "${currentIndex + 1} / ${mediaItems.size}  ·  " +
                "${currentMedia.type.label()}  ·  Touch anywhere to return",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.60f),
                    shape = RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
            color = Color(0xFF9E9E9E),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun MediaGalleryItemContent(
    media: MediaGalleryItem,
    modifier: Modifier = Modifier,
    autoPlayVideo: Boolean,
    showVideoControls: Boolean,
    onVideoEnded: () -> Unit = {},
    onVideoError: () -> Unit = {},
) {
    when (media.type) {
        MediaGalleryType.IMAGE -> MediaGalleryImage(
            uri = media.uri,
            displayName = media.displayName,
            modifier = modifier,
        )
        MediaGalleryType.VIDEO -> MediaGalleryVideo(
            uri = media.uri,
            modifier = modifier,
            autoPlay = autoPlayVideo,
            showControls = showVideoControls,
            onPlaybackEnded = onVideoEnded,
            onPlaybackError = onVideoError,
        )
    }
}

@Composable
private fun MediaGalleryImage(
    uri: Uri,
    displayName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        bitmap = null
        failed = false
        bitmap = withContext(Dispatchers.IO) {
            loadScaledMediaGalleryBitmap(
                context = context,
                uri = uri,
                maxWidth = 1920,
                maxHeight = 1080,
            )
        }
        failed = bitmap == null
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val loadedBitmap = bitmap
        when {
            loadedBitmap != null -> Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            failed -> Text(
                text = "Unable to load this image.",
                color = Color(0xFFEF9A9A),
                fontSize = 18.sp,
            )
            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
@OptIn(UnstableApi::class)
private fun MediaGalleryVideo(
    uri: Uri,
    modifier: Modifier = Modifier,
    autoPlay: Boolean,
    showControls: Boolean,
    onPlaybackEnded: () -> Unit,
    onPlaybackError: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)
    var playbackError by remember(uri) { mutableStateOf<String?>(null) }

    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = autoPlay
            prepare()
        }
    }

    LaunchedEffect(player, autoPlay) {
        player.playWhenReady = autoPlay
        if (autoPlay) player.play() else player.pause()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    currentOnPlaybackEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackError = error.errorCodeName
                currentOnPlaybackError()
            }
        }
        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    this.player = player
                    useController = showControls
                    setBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                }
            },
            update = { playerView ->
                playerView.player = player
                playerView.useController = showControls
            },
            modifier = Modifier.fillMaxSize(),
        )

        playbackError?.let { errorCode ->
            Text(
                text = "Unable to play this video. ($errorCode)",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                color = Color(0xFFEF9A9A),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MediaGalleryPermissionScreen(
    message: String?,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Photo and video access required",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Gallery uses Android MediaStore to browse local and USB media.",
            color = Color(0xFF9E9E9E),
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        message?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = it,
                color = Color(0xFFEF9A9A),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GalleryControlButton(
                text = "Back",
                height = 54.dp,
                onClick = onBack,
            )
            GalleryControlButton(
                text = "Allow Media Access",
                height = 54.dp,
                primary = true,
                onClick = onRequestPermission,
            )
        }
    }
}

@Composable
private fun MediaGalleryLoadingScreen(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        GalleryControlButton(
            text = "Back",
            height = 52.dp,
            primary = true,
            onClick = onBack,
        )

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(color = Color.White)
            Text(
                text = "Scanning local and USB media...",
                color = Color(0xFFBDBDBD),
                fontSize = 18.sp,
            )
        }
    }
}

@Composable
private fun MediaGalleryEmptyState(
    sourceFilter: MediaGallerySource,
    message: String?,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
        border = BorderStroke(1.dp, Color(0xFF363636)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = when (sourceFilter) {
                    MediaGallerySource.ALL -> "No indexed media found"
                    MediaGallerySource.LOCAL -> "No local media found"
                    MediaGallerySource.USB -> "No USB media found"
                },
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = message ?: when (sourceFilter) {
                    MediaGallerySource.USB ->
                        "Connect a USB drive and wait for Android to finish scanning it."
                    else ->
                        "Images or videos may not have been indexed by MediaStore yet."
                },
                color = Color(0xFF9E9E9E),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))
            GalleryControlButton(
                text = "Refresh Media",
                height = 52.dp,
                primary = true,
                onClick = onRefresh,
            )
        }
    }
}

@Composable
private fun GalleryFilterButton(
    source: MediaGallerySource,
    count: Int,
    selected: Boolean,
    height: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    GalleryControlButton(
        text = "${source.label} ($count)",
        height = height,
        primary = selected,
        onClick = onClick,
    )
}

@Composable
private fun GalleryControlButton(
    text: String,
    height: androidx.compose.ui.unit.Dp,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(height),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Color.White else Color(0xFF242424),
            contentColor = if (primary) Color.Black else Color.White,
            disabledContainerColor = Color(0xFF1A1A1A),
            disabledContentColor = Color(0xFF666666),
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

private fun MediaGalleryType.label(): String = when (this) {
    MediaGalleryType.IMAGE -> "Image"
    MediaGalleryType.VIDEO -> "Video"
}

private fun mediaGalleryPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun hasMediaGalleryPermission(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED
    }
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) ==
            PackageManager.PERMISSION_GRANTED
    }
    else -> {
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }
}

private fun canReadMediaGalleryType(
    context: Context,
    permission: String,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    val hasFullPermission =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val hasSelectedPermission =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED

    return hasFullPermission || hasSelectedPermission
}

private fun queryMediaGalleryItems(context: Context): List<MediaGalleryItem> {
    val result = mutableListOf<MediaGalleryItem>()

    if (canReadMediaGalleryType(context, Manifest.permission.READ_MEDIA_IMAGES)) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        result += queryMediaGalleryCollection(
            context = context,
            collection = collection,
            type = MediaGalleryType.IMAGE,
        )
    }

    if (canReadMediaGalleryType(context, Manifest.permission.READ_MEDIA_VIDEO)) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        result += queryMediaGalleryCollection(
            context = context,
            collection = collection,
            type = MediaGalleryType.VIDEO,
        )
    }

    return result.sortedByDescending(MediaGalleryItem::dateAddedSeconds)
}

private fun queryMediaGalleryCollection(
    context: Context,
    collection: Uri,
    type: MediaGalleryType,
): List<MediaGalleryItem> {
    val sourceColumnName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.MediaColumns.VOLUME_NAME
    } else {
        @Suppress("DEPRECATION")
        MediaStore.MediaColumns.DATA
    }
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.DATE_ADDED,
        sourceColumnName,
    )
    val result = mutableListOf<MediaGalleryItem>()

    context.contentResolver.query(
        collection,
        projection,
        null,
        null,
        "${MediaStore.MediaColumns.DATE_ADDED} DESC",
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val mimeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
        val dateColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
        val sourceColumn = cursor.getColumnIndex(sourceColumnName)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val mimeType = if (mimeColumn >= 0) cursor.getString(mimeColumn).orEmpty() else ""
            val defaultName = if (type == MediaGalleryType.IMAGE) "Image" else "Video"
            val sourceValue = if (sourceColumn >= 0) cursor.getString(sourceColumn) else null

            result += MediaGalleryItem(
                uri = ContentUris.withAppendedId(collection, id),
                type = type,
                source = classifyMediaGallerySource(sourceValue),
                displayName = if (nameColumn >= 0) {
                    cursor.getString(nameColumn)?.takeIf { it.isNotBlank() } ?: defaultName
                } else {
                    defaultName
                },
                mimeType = mimeType,
                dateAddedSeconds = if (dateColumn >= 0) cursor.getLong(dateColumn) else 0L,
            )
        }
    }

    return result
}

private fun classifyMediaGallerySource(sourceValue: String?): MediaGallerySource {
    if (sourceValue.isNullOrBlank()) return MediaGallerySource.LOCAL

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return if (
            sourceValue.equals(MediaStore.VOLUME_EXTERNAL_PRIMARY, ignoreCase = true) ||
            sourceValue.equals("external_primary", ignoreCase = true)
        ) {
            MediaGallerySource.LOCAL
        } else {
            MediaGallerySource.USB
        }
    }

    val path = sourceValue.lowercase(Locale.US)
    @Suppress("DEPRECATION")
    val primaryPath = Environment.getExternalStorageDirectory().absolutePath
        .lowercase(Locale.US)

    return if (
        path.startsWith(primaryPath) ||
        path.startsWith("/sdcard/") ||
        path.startsWith("/mnt/sdcard/") ||
        path.contains("/emulated/")
    ) {
        MediaGallerySource.LOCAL
    } else {
        MediaGallerySource.USB
    }
}

private fun loadMediaGalleryThumbnail(
    context: Context,
    media: MediaGalleryItem,
    maxWidth: Int,
    maxHeight: Int,
): Bitmap? = when (media.type) {
    MediaGalleryType.IMAGE -> loadScaledMediaGalleryBitmap(
        context = context,
        uri = media.uri,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
    )
    MediaGalleryType.VIDEO -> loadVideoThumbnail(
        context = context,
        uri = media.uri,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
    )
}

private fun loadVideoThumbnail(
    context: Context,
    uri: Uri,
    maxWidth: Int,
    maxHeight: Int,
): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val frame = retriever.getFrameAtTime(
            0L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        ) ?: return null
        scaleMediaGalleryBitmap(frame, maxWidth, maxHeight)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun loadScaledMediaGalleryBitmap(
    context: Context,
    uri: Uri,
    maxWidth: Int,
    maxHeight: Int,
): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > maxWidth * 2 ||
            bounds.outHeight / sampleSize > maxHeight * 2
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: return null

        scaleMediaGalleryBitmap(decoded, maxWidth, maxHeight)
    } catch (_: Exception) {
        null
    }
}

private fun scaleMediaGalleryBitmap(
    bitmap: Bitmap,
    maxWidth: Int,
    maxHeight: Int,
): Bitmap {
    if (bitmap.width <= maxWidth && bitmap.height <= maxHeight) return bitmap

    val widthScale = maxWidth.toFloat() / bitmap.width
    val heightScale = maxHeight.toFloat() / bitmap.height
    val scale = minOf(widthScale, heightScale)
    val scaledWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val scaledHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}
