package com.audiophile.proeqplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    secondary = Color(0xFF818CF8),
    tertiary = Color(0xFFF472B6),
    background = Color(0xFF0B0F19),
    surface = Color(0xFF151C2C),
    surfaceVariant = Color(0xFF1E293B),
    onPrimary = Color(0xFF0B0F19),
    onBackground = Color(0xFFF1F5F9),
    onSurface = Color(0xFFE2E8F0)
)

@UnstableApi
class MainActivity : ComponentActivity() {

    private var exoPlayer: ExoPlayer? = null
    private val eqEngine = ProEqualizerEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true) // 拔除耳機或小尾巴時自動暫停播放
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    eqEngine.attachToAudioSession(audioSessionId)
                }
            }
        })

        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            eqEngine.attachToAudioSession(player.audioSessionId)
        }

        exoPlayer = player

        setContent {
            MaterialTheme(colorScheme = DarkColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ProEqMusicApp(player = player, eqEngine = eqEngine)
                }
            }
        }
    }

    override fun onDestroy() {
        eqEngine.release()
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }
}

@Composable
fun ProEqMusicApp(
    player: ExoPlayer,
    eqEngine: ProEqualizerEngine
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    var tracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var currentTrackIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = 本地音樂庫, 1 = 10段專業等化器

    // EQ UI Reactive States
    var eqEnabled by remember { mutableStateOf(eqEngine.isEnabled) }
    var preampDb by remember { mutableFloatStateOf(eqEngine.preampDb) }
    var autoPreampEnabled by remember { mutableStateOf(true) }
    var limiterEnabled by remember { mutableStateOf(eqEngine.limiterEnabled) }
    var bassBoostStrength by remember { mutableIntStateOf(eqEngine.bassBoostStrength) }
    var bandGains by remember { mutableStateOf(eqEngine.getBandGains()) }
    var activePresetName by remember { mutableStateOf(ProEqualizerEngine.BUILTIN_PRESETS.first().name) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            scope.launch {
                tracks = LocalMusicScanner.scanDeviceMusic(context)
            }
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            tracks = LocalMusicScanner.scanDeviceMusic(context)
        }
    }

    // 監聽播放狀態與時間軸更新
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTrackIndex = player.currentMediaItemIndex
                durationMs = player.duration.coerceAtLeast(0L)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                durationMs = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPositionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(0L)
            delay(250L)
        }
    }

    fun playTrackAtIndex(index: Int) {
        if (index !in tracks.indices) return
        if (player.mediaItemCount != tracks.size) {
            val mediaItems = tracks.map { MediaItem.fromUri(it.uri) }
            player.setMediaItems(mediaItems, index, 0L)
            player.prepare()
        } else {
            player.seekToDefaultPosition(index)
        }
        player.play()
        currentTrackIndex = index
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "ProEQ Hi-Fi Player",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (eqEnabled) "DSP Active • Preamp ${formatDb(preampDb)}" else "DSP Bypass (Bit-Direct)",
                            fontSize = 11.sp,
                            color = if (eqEnabled) MaterialTheme.colorScheme.primary else Color(0xFF94A3B8)
                        )
                    }
                }

                // 頂部 Tab 切換按鈕
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(3.dp)
                ) {
                    TabPillButton(
                        title = "音樂庫 (${tracks.size})",
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 }
                    )
                    TabPillButton(
                        title = "專業等化器",
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 }
                    )
                }
            }
        },
        bottomBar = {
            val currentTrack = tracks.getOrNull(currentTrackIndex)
            MiniPlayerDock(
                track = currentTrack,
                isPlaying = isPlaying,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                onPlayPause = {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        if (currentTrackIndex == -1 && tracks.isNotEmpty()) {
                            playTrackAtIndex(0)
                        } else {
                            player.play()
                        }
                    }
                },
                onPrevious = {
                    if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
                },
                onNext = {
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                },
                onSeek = { newPos ->
                    player.seekTo(newPos)
                    currentPositionMs = newPos
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selectedTab == 0) {
                MusicLibraryScreen(
                    hasPermission = hasPermission,
                    tracks = tracks,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    currentTrackIndex = currentTrackIndex,
                    isPlaying = isPlaying,
                    onRequestPermission = { permissionLauncher.launch(requiredPermission) },
                    onRescan = {
                        scope.launch {
                            tracks = LocalMusicScanner.scanDeviceMusic(context)
                        }
                    },
                    onSelectTrack = { idx -> playTrackAtIndex(idx) }
                )
            } else {
                ProEqualizerScreen(
                    eqEnabled = eqEnabled,
                    preampDb = preampDb,
                    autoPreampEnabled = autoPreampEnabled,
                    limiterEnabled = limiterEnabled,
                    bassBoostStrength = bassBoostStrength,
                    bandGains = bandGains,
                    activePresetName = activePresetName,
                    onToggleEq = { enabled ->
                        eqEnabled = enabled
                        eqEngine.setMasterEnabled(enabled)
                    },
                    onPreampChange = { newPreamp ->
                        autoPreampEnabled = false
                        preampDb = newPreamp
                        eqEngine.setPreamp(newPreamp)
                    },
                    onToggleAutoPreamp = { enabled ->
                        autoPreampEnabled = enabled
                        if (enabled) {
                            val rec = eqEngine.getRecommendedPreampDb()
                            preampDb = rec
                            eqEngine.setPreamp(rec)
                        }
                    },
                    onBandChange = { index, gainDb ->
                        eqEngine.setBandGain(index, gainDb)
                        bandGains = eqEngine.getBandGains()
                        activePresetName = "Custom (自訂調音)"
                        if (autoPreampEnabled) {
                            val rec = eqEngine.getRecommendedPreampDb()
                            preampDb = rec
                            eqEngine.setPreamp(rec)
                        }
                    },
                    onApplyPreset = { preset ->
                        activePresetName = preset.name
                        eqEngine.setAllBands(preset.preampDb, preset.bandGainsDb)
                        bandGains = eqEngine.getBandGains()
                        preampDb = preset.preampDb
                    },
                    onResetFlat = {
                        val flat = ProEqualizerEngine.BUILTIN_PRESETS.first()
                        activePresetName = flat.name
                        eqEngine.setAllBands(0f, flat.bandGainsDb)
                        bandGains = eqEngine.getBandGains()
                        preampDb = 0f
                    },
                    onToggleLimiter = { enabled ->
                        limiterEnabled = enabled
                        eqEngine.setLimiter(enabled)
                    },
                    onBassBoostChange = { strength ->
                        bassBoostStrength = strength
                        eqEngine.setBassBoost(strength)
                    }
                )
            }
        }
    }
}

@Composable
private fun TabPillButton(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun MusicLibraryScreen(
    hasPermission: Boolean,
    tracks: List<AudioTrack>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    currentTrackIndex: Int,
    isPlaying: Boolean,
    onRequestPermission: () -> Unit,
    onRescan: () -> Unit,
    onSelectTrack: (Int) -> Unit
) {
    if (!hasPermission) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.LibraryMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "需要讀取本地音樂檔案權限",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "授權後即可自動掃描手機內的 FLAC、WAV、AAC、MP3 等無損與高音質音訊檔案。",
                fontSize = 14.sp,
                color = Color(0xFF94A3B8)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onRequestPermission) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("允許讀取本地音樂")
            }
        }
        return
    }

    val filteredIndexed = remember(tracks, searchQuery) {
        tracks.mapIndexedNotNull { index, track ->
            if (searchQuery.isBlank() ||
                track.title.contains(searchQuery, ignoreCase = true) ||
                track.artist.contains(searchQuery, ignoreCase = true) ||
                track.album.contains(searchQuery, ignoreCase = true)
            ) {
                index to track
            } else null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜尋曲名、演出者、專輯或格式...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onRescan,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "重新掃描")
            }
        }

        if (filteredIndexed.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (tracks.isEmpty()) "找不到本地音樂檔案，請將音樂放入手機 Music 資料夾後點擊重新整理" else "沒有符合搜尋條件的曲目",
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(filteredIndexed, key = { _, pair -> pair.second.id }) { _, (origIndex, track) ->
                    val isSelected = origIndex == currentTrackIndex
                    TrackRowItem(
                        track = track,
                        isSelected = isSelected,
                        isPlaying = isSelected && isPlaying,
                        onClick = { onSelectTrack(origIndex) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRowItem(
    track: AudioTrack,
    isSelected: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (isSelected) Color(0xFF1E3A5F) else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Equalizer else Icons.Default.MusicNote,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isSelected) Color(0xFF38BDF8) else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF334155))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = track.formatBadge,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF38BDF8)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${track.artist} • ${track.album}",
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFF94A3B8)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = formatDuration(track.durationMs),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF94A3B8)
        )
    }
}

@Composable
private fun ProEqualizerScreen(
    eqEnabled: Boolean,
    preampDb: Float,
    autoPreampEnabled: Boolean,
    limiterEnabled: Boolean,
    bassBoostStrength: Int,
    bandGains: List<Float>,
    activePresetName: String,
    onToggleEq: (Boolean) -> Unit,
    onPreampChange: (Float) -> Unit,
    onToggleAutoPreamp: (Boolean) -> Unit,
    onBandChange: (Int, Float) -> Unit,
    onApplyPreset: (EqPreset) -> Unit,
    onResetFlat: () -> Unit,
    onToggleLimiter: (Boolean) -> Unit,
    onBassBoostChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 頂部主控開關與防削波保護面板
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "32-bit 浮點 10 頻段等化器引擎",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "目前曲線：$activePresetName",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onResetFlat) {
                            Text("歸零 (0dB)")
                        }
                        Switch(
                            checked = eqEnabled,
                            onCheckedChange = onToggleEq
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 即時頻率響應曲線圖 (Frequency Response Graph)
                FrequencyResponseGraph(
                    preampDb = preampDb,
                    bandGains = bandGains,
                    enabled = eqEnabled
                )
            }
        }

        // 2. 耳機與曲風專業預設集 (Presets)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "耳機特性補償與風格預設集 (Target Curves)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProEqualizerEngine.BUILTIN_PRESETS.forEach { preset ->
                        val isSelected = activePresetName == preset.name
                        FilterChip(
                            selected = isSelected,
                            onClick = { onApplyPreset(preset) },
                            label = { Text(preset.name) }
                        )
                    }
                }
            }
        }

        // 3. Pre-amp 前級增益與防爆音控制 (Headroom Management)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Pre-amp 前級數位增益：${formatDb(preampDb)}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "拉高 EQ 時自動對稱衰減前級，徹底消除破音與數位削波",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto 防爆音", fontSize = 12.sp, color = Color(0xFFCBD5E1))
                        Spacer(modifier = Modifier.width(6.dp))
                        Checkbox(
                            checked = autoPreampEnabled,
                            onCheckedChange = onToggleAutoPreamp
                        )
                    }
                }

                Slider(
                    value = preampDb,
                    onValueChange = onPreampChange,
                    valueRange = -15f..15f,
                    enabled = eqEnabled
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = limiterEnabled,
                            onCheckedChange = onToggleLimiter,
                            enabled = eqEnabled
                        )
                        Text("啟用 -0.5 dBFS 硬體峰值限幅器 (Peak Limiter)", fontSize = 12.sp)
                    }
                }
            }
        }

        // 4. 10 頻段獨立精細推桿 (31Hz ~ 16kHz)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "ISO 10 頻段精細增益調整 (-15.0 dB ~ +15.0 dB)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                ProEqualizerEngine.BAND_LABELS.forEachIndexed { idx, freqLabel ->
                    val gain = bandGains.getOrElse(idx) { 0f }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = freqLabel,
                            modifier = Modifier.width(52.dp),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                        Slider(
                            value = gain,
                            onValueChange = { newGain ->
                                // 以 0.5 dB 為步進精度
                                val stepped = (newGain * 2f).roundToInt() / 2f
                                onBandChange(idx, stepped)
                            },
                            valueRange = -15f..15f,
                            enabled = eqEnabled,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatDb(gain),
                            modifier = Modifier.width(58.dp),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                gain > 0.1f -> Color(0xFF38BDF8)
                                gain < -0.1f -> Color(0xFFF472B6)
                                else -> Color(0xFF94A3B8)
                            }
                        )
                    }
                }
            }
        }

        // 5. 低頻諧波增強 (Sub-Bass Harmonic Exciter)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "低頻泛音強化 (Sub-Bass Punch)：${(bassBoostStrength / 10)}%",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Slider(
                    value = bassBoostStrength.toFloat(),
                    onValueChange = { onBassBoostChange(it.roundToInt()) },
                    valueRange = 0f..1000f,
                    enabled = eqEnabled
                )
            }
        }
    }
}

@Composable
private fun FrequencyResponseGraph(
    preampDb: Float,
    bandGains: List<Float>,
    enabled: Boolean
) {
    val primaryColor = if (enabled) Color(0xFF38BDF8) else Color(0xFF64748B)
    val zeroLineColor = Color(0xFF334155)
    val clippingZoneColor = Color(0x33EF4444)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF090D16))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        val w = size.width
        val h = size.height
        val midY = h / 2f

        // 繪製 0 dB 基準線與 +6dB / -6dB 參考線
        drawLine(
            color = zeroLineColor,
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 2f
        )

        if (bandGains.isEmpty()) return@Canvas

        val points = bandGains.mapIndexed { idx, gain ->
            val x = (idx.toFloat() / (bandGains.size - 1).coerceAtLeast(1)) * w
            val netGain = (gain + preampDb).coerceIn(-18f, 18f)
            val y = midY - (netGain / 18f) * (h / 2f)
            Offset(x, y)
        }

        // 若有超過 0 dBFS 的頻段，標示紅線預警區
        drawLine(
            color = clippingZoneColor,
            start = Offset(0f, midY),
            end = Offset(w, 0f),
            strokeWidth = 1f
        )

        val curvePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 0 until points.size - 1) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val controlX = (p0.x + p1.x) / 2f
                cubicTo(controlX, p0.y, controlX, p1.y, p1.x, p1.y)
            }
        }

        val fillPath = Path().apply {
            addPath(curvePath)
            lineTo(points.last().x, h)
            lineTo(points.first().x, h)
            close()
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(primaryColor.copy(alpha = 0.35f), Color.Transparent)
            )
        )

        drawPath(
            path = curvePath,
            color = primaryColor,
            style = Stroke(width = 4f)
        )

        points.forEach { pt ->
            drawCircle(
                color = primaryColor,
                radius = 5f,
                center = pt
            )
        }
    }
}

@Composable
private fun MiniPlayerDock(
    track: AudioTrack?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            if (track != null && durationMs > 0L) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatDuration(currentPositionMs),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF94A3B8)
                    )
                    Slider(
                        value = currentPositionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                        onValueChange = { onSeek(it.toLong()) },
                        valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                    Text(
                        text = formatDuration(durationMs),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track?.title ?: "尚未選擇曲目",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (track != null) "${track.artist} • ${track.formatBadge}" else "請從音樂庫點選歌曲開始播放",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "上一首")
                    }
                    FilledIconButton(
                        onClick = onPlayPause,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "播放/暫停",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    IconButton(onClick = onNext) {
                        Icon(Icons.Default.SkipNext, contentDescription = "下一首")
                    }
                }
            }
        }
    }
}

private fun formatDb(db: Float): String {
    val sign = if (db > 0f) "+" else ""
    return "$sign${"%.1f".format(db)} dB"
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
