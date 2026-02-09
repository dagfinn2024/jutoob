package com.ju.toob

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import com.naman14.androidlame.LameBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

val downloadProgressState = MutableStateFlow(-1f)

private const val TAG = "JutoobDiagnostic"
private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
private const val DOWNLOAD_CHANNEL_ID = "download_channel"
private const val DOWNLOAD_BUFFER_SIZE = 32 * 1024
private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
private const val PARALLEL_THREAD_COUNT = 3
private val nextNotifId = AtomicInteger(100)

class JutoobDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val url = request.url()
        val method = request.httpMethod() ?: "GET"

        val okBuilder = okhttp3.Request.Builder().url(url)

        try {
            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrEmpty()) {
                okBuilder.header("Cookie", cookies)
            }
        } catch (_: Exception) {}

        okBuilder.header("User-Agent", USER_AGENT)

        request.headers().forEach { (key, values) ->
            values.forEach { value ->
                if (!key.equals("User-Agent", ignoreCase = true)) {
                    okBuilder.addHeader(key, value)
                }
            }
        }

        val data = request.dataToSend()
        val body = if (method.uppercase() == "POST") {
            (data ?: ByteArray(0)).toRequestBody(null)
        } else null

        okBuilder.method(method, body)

        val response = client.newCall(okBuilder.build()).execute()

        if (response.code == 429) {
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val responseBody = response.body?.string() ?: ""

        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            responseBody,
            response.request.url.toString()
        )
    }
}

private var isNewPipeInitialized = false

fun initNewPipe(context: Context) {
    if (!isNewPipeInitialized) {
        try {
            NewPipe.init(JutoobDownloader())
            isNewPipeInitialized = true
        } catch (_: Exception) {}
    }
}

private val headClient = OkHttpClient.Builder()
    .followRedirects(true)
    .followSslRedirects(true)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

private val downloadClient = OkHttpClient.Builder()
    .followRedirects(true)
    .followSslRedirects(true)
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .build()

private const val MAX_DOWNLOAD_RETRIES = 5
private const val RETRY_BASE_DELAY_MS = 1000L

/**
 * Get file size using OkHttp.
 * First tries HEAD for Content-Length.
 * If that fails (YouTube DASH streams often omit it), falls back to a
 * Range request (bytes=0-0) and parses Content-Range for the total size.
 */
fun getFileSizeBytes(streamUrl: String): Long {
    return try {
        probeServer(streamUrl).contentLength
    } catch (_: Exception) {
        -1L
    }
}

fun getFileSizeMB(streamUrl: String): String? {
    val bytes = getFileSizeBytes(streamUrl)
    return if (bytes > 0) String.format("%.1f MB", bytes / (1024.0 * 1024.0)) else null
}

fun formatBytes(bytes: Long): String? {
    return if (bytes > 0) String.format("%.1f MB", bytes / (1024.0 * 1024.0)) else null
}

private data class ServerProbe(val contentLength: Long, val supportsRanges: Boolean)

private fun probeServer(streamUrl: String): ServerProbe {
    val cookies = try { CookieManager.getInstance().getCookie(streamUrl) } catch (_: Exception) { null }

    // Attempt 1: HEAD request
    val headReq = okhttp3.Request.Builder()
        .url(streamUrl)
        .head()
        .header("User-Agent", USER_AGENT)
        .apply { if (!cookies.isNullOrEmpty()) header("Cookie", cookies) }
        .build()

    val headResp = headClient.newCall(headReq).execute()
    val acceptRanges = headResp.header("Accept-Ranges")
    var length = headResp.header("Content-Length")?.toLongOrNull() ?: -1L
    headResp.close()

    var supportsRanges = acceptRanges?.equals("bytes", ignoreCase = true) == true

    // Attempt 2: Range fallback for DASH streams
    if (length <= 0) {
        val rangeReq = okhttp3.Request.Builder()
            .url(streamUrl)
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=0-0")
            .apply { if (!cookies.isNullOrEmpty()) header("Cookie", cookies) }
            .build()

        val rangeResp = headClient.newCall(rangeReq).execute()
        val contentRange = rangeResp.header("Content-Range")
        if (rangeResp.code == 206 && contentRange != null) {
            supportsRanges = true
            val total = contentRange.substringAfter("/", "").toLongOrNull()
            if (total != null && total > 0) length = total
        }
        rangeResp.close()
    }

    return ServerProbe(length, supportsRanges && length > 0)
}

/**
 * Clean a video title into a safe, readable filename.
 * Removes illegal chars, collapses whitespace, trims.
 */
fun sanitizeFileName(title: String): String {
    return title
        .replace(Regex("[\\\\/:*?\"<>|]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

data class StreamOption(
    val url: String,
    val resolution: String,
    val format: String,
    val isAudio: Boolean,
    val fileSize: String?,
    val audioUrl: String? = null
)

@Composable
fun DownloadDialog(videoId: String, videoTitle: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var options by remember { mutableStateOf<List<StreamOption>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(videoId) {
        withContext(Dispatchers.IO) {
            try {
                initNewPipe(context)

                val videoUrl = "https://www.youtube.com/watch?v=$videoId"
                val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)

                Log.e(TAG, "StreamInfo retrieved successfully")

                val audioStreams = streamInfo.audioStreams ?: emptyList()
                val videoStreams = streamInfo.videoStreams ?: emptyList()
                val videoOnlyStreams = streamInfo.videoOnlyStreams ?: emptyList()

                // Original-language m4a audio only (no dubs)
                val originalM4a = audioStreams
                    .filter { it.format?.suffix?.lowercase() == "m4a" }
                    .filter {
                        val type = it.audioTrackType
                        type == null || type == AudioTrackType.ORIGINAL
                    }
                    .ifEmpty {
                        // Fallback: if nothing is tagged ORIGINAL, use all m4a
                        audioStreams.filter { it.format?.suffix?.lowercase() == "m4a" }
                    }

                // Best original m4a for muxing with video-only streams
                val bestAudio = originalM4a.maxByOrNull { it.bitrate }

                // ~136kbps original m4a for standalone audio download
                val audio136 = originalM4a
                    .minByOrNull { kotlin.math.abs(it.bitrate - 136000) }

                val result = mutableListOf<StreamOption>()

                // Helper: find best stream for a resolution (muxed preferred, fallback to video-only + audio)
                fun findStream(res: String): StreamOption? {
                    val muxed = videoStreams
                        .filter { it.format?.suffix?.lowercase() == "mp4" && it.resolution?.contains(res) == true }
                        .maxByOrNull { it.bitrate }
                    if (muxed != null) {
                        val size = getFileSizeMB(muxed.content)
                        return StreamOption(muxed.content, "${res}p", "mp4", false, size)
                    }
                    val videoOnly = videoOnlyStreams
                        .filter { it.format?.suffix?.lowercase() == "mp4" && it.resolution?.contains(res) == true }
                        .maxByOrNull { it.bitrate }
                    if (videoOnly != null && bestAudio != null) {
                        val videoBytes = getFileSizeBytes(videoOnly.content)
                        val audioBytes = getFileSizeBytes(bestAudio.content)
                        val combinedSize = if (videoBytes > 0 && audioBytes > 0) formatBytes(videoBytes + audioBytes) else getFileSizeMB(videoOnly.content)
                        return StreamOption(videoOnly.content, "${res}p", "mp4", false, combinedSize, audioUrl = bestAudio.content)
                    }
                    return null
                }

                // 720p + 360p preferred; if no 720p, offer 480p + 360p instead
                val stream720 = findStream("720")
                if (stream720 != null) {
                    result.add(stream720)
                } else {
                    findStream("480")?.let { result.add(it) }
                }
                findStream("360")?.let { result.add(it) }

                audio136?.let {
                    val sizeBytes = getFileSizeBytes(it.content)
                    val size = if (sizeBytes > 0) formatBytes(sizeBytes) else null
                    val isLarge = sizeBytes > 30L * 1024 * 1024
                    val format = if (isLarge) "m4a" else "mp3"
                    val label = if (isLarge) "${it.bitrate / 1000}kbps" else "128kbps"
                    result.add(StreamOption(it.content, label, format, true, size))
                }

                options = result

                if (options.isEmpty()) {
                    errorMessage = "No matching streams found."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during extraction", e)
                errorMessage = "Error: ${e.localizedMessage}"
            } finally {
                isLoading = false
            }
        }
    }

    val thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxWidth(0.9f).wrapContentHeight().clip(RoundedCornerShape(24.dp)).background(Color(0xFF111827)).border(1.dp, Color(0xFF374151), RoundedCornerShape(24.dp))) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Thumbnail
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(thumbnailUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Video thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                )

                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Title
                    Text(
                        text = videoTitle,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    val extractingMessage = remember {
                        listOf(
                            "Finding the good bits\u2026",
                            "Parsing the YouTube spaghetti\u2026",
                            "Liberating the streams\u2026",
                            "Decoding the secret sauce\u2026",
                            "Dodging the algorithm\u2026",
                            "Untangling the digital noodles\u2026",
                            "Untying the data knots\u2026",
                            "Finding the clean feed\u2026",
                            "Peeking behind the curtain\u2026",
                            "Appeasing the streaming gods\u2026"
                        ).random()
                    }

                    if (isLoading) {
                        CircularProgressIndicator(color = Color(0xFFEF4444))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(extractingMessage, color = Color.Gray, fontSize = 14.sp)
                    } else if (errorMessage != null) {
                        Text(text = errorMessage!!, color = Color(0xFFF87171), fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                        Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151))) {
                            Text("Close", color = Color.White)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            items(options) { option ->
                                StreamOptionCard(option) {
                                    performDownload(context, videoTitle, option)
                                    onDismiss()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StreamOptionCard(option: StreamOption, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(12.dp), color = Color(0xFF1F2937)) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                val label = if (option.isAudio) "AUDIO" else "VIDEO"
                Text(text = label, color = if (option.isAudio) Color(0xFF10B981) else Color(0xFF3B82F6), fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text(text = "${option.resolution} • ${option.format.uppercase()}", color = Color.White, fontSize = 16.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (option.fileSize != null) {
                    Text(text = option.fileSize, color = Color(0xFF9CA3AF), fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.Gray)
            }
        }
    }
}

// --- Notification helper ---

private class DownloadNotification(context: Context, title: String) {
    val notifId = nextNotifId.getAndIncrement()
    private val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val builder: NotificationCompat.Builder

    init {
        mgr.createNotificationChannel(
            NotificationChannel(DOWNLOAD_CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        builder = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
    }

    fun progress(percent: Int, text: String) {
        builder.setContentText(text).setProgress(100, percent, false)
        mgr.notify(notifId, builder.build())
    }

    fun indeterminate(text: String) {
        builder.setContentText(text).setProgress(0, 0, true)
        mgr.notify(notifId, builder.build())
    }

    fun complete(text: String) {
        builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentText(text).setProgress(0, 0, false)
            .setOngoing(false).setAutoCancel(true)
        mgr.notify(notifId, builder.build())
    }

    fun error(text: String) {
        builder.setContentText(text).setProgress(0, 0, false)
            .setOngoing(false).setAutoCancel(true)
        mgr.notify(notifId, builder.build())
    }
}

// --- Parallel download engine ---

private suspend fun downloadRange(
    url: String,
    destFile: File,
    startByte: Long,
    endByte: Long,
    cookies: String?,
    bytesDownloaded: AtomicLong
) = withContext(Dispatchers.IO) {
    var written = 0L
    for (attempt in 1..MAX_DOWNLOAD_RETRIES) {
        try {
            val resumeStart = startByte + written
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=$resumeStart-$endByte")
                .apply { if (!cookies.isNullOrEmpty()) header("Cookie", cookies) }
                .build()

            val response = downloadClient.newCall(request).execute()
            if (!response.isSuccessful && response.code != 206) {
                response.close()
                throw IOException("Range download failed: HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty response body")
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)

            RandomAccessFile(destFile, "rw").use { raf ->
                raf.seek(resumeStart)
                body.byteStream().use { input ->
                    var n: Int
                    while (input.read(buffer).also { n = it } != -1) {
                        raf.write(buffer, 0, n)
                        written += n
                        bytesDownloaded.addAndGet(n.toLong())
                    }
                }
            }
            response.close()
            return@withContext // success
        } catch (e: IOException) {
            Log.w(TAG, "Range download attempt $attempt failed: ${e.message}")
            if (attempt == MAX_DOWNLOAD_RETRIES) throw e
            kotlinx.coroutines.delay(RETRY_BASE_DELAY_MS * attempt)
        }
    }
}

private suspend fun singleStreamDownload(
    url: String,
    destFile: File,
    cookies: String?,
    totalSize: Long,
    notification: DownloadNotification,
    onProgress: ((Float) -> Unit)? = null
) = withContext(Dispatchers.IO) {
    var downloaded = 0L
    var lastUpdate = 0L

    for (attempt in 1..MAX_DOWNLOAD_RETRIES) {
        try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .apply {
                    if (!cookies.isNullOrEmpty()) header("Cookie", cookies)
                    if (downloaded > 0) header("Range", "bytes=$downloaded-")
                }
                .build()

            val response = downloadClient.newCall(request).execute()
            if (!response.isSuccessful && response.code != 206) {
                response.close()
                throw IOException("Download failed: HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Empty response body")
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)

            FileOutputStream(destFile, downloaded > 0).use { fos ->
                body.byteStream().use { input ->
                    var n: Int
                    while (input.read(buffer).also { n = it } != -1) {
                        fos.write(buffer, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
                            lastUpdate = now
                            if (totalSize > 0) {
                                val pct = ((downloaded * 100) / totalSize).toInt()
                                notification.progress(pct, String.format("%.1f / %.1f MB (%d%%)",
                                    downloaded / (1024.0 * 1024.0), totalSize / (1024.0 * 1024.0), pct))
                                onProgress?.invoke(pct.toFloat())
                            } else {
                                notification.indeterminate(String.format("%.1f MB downloaded", downloaded / (1024.0 * 1024.0)))
                            }
                        }
                    }
                }
            }
            response.close()
            return@withContext // success
        } catch (e: IOException) {
            Log.w(TAG, "Single stream download attempt $attempt failed (${downloaded} bytes so far): ${e.message}")
            if (attempt == MAX_DOWNLOAD_RETRIES) throw e
            kotlinx.coroutines.delay(RETRY_BASE_DELAY_MS * attempt)
        }
    }
}

private suspend fun parallelDownload(
    url: String,
    destFile: File,
    notification: DownloadNotification,
    onProgress: ((Float) -> Unit)? = null
) = coroutineScope {
    val cookies = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
    val probe = probeServer(url)

    if (!probe.supportsRanges) {
        singleStreamDownload(url, destFile, cookies, probe.contentLength, notification, onProgress)
        return@coroutineScope
    }

    val totalSize = probe.contentLength
    val chunkSize = totalSize / PARALLEL_THREAD_COUNT
    val bytesDownloaded = AtomicLong(0L)

    // Pre-allocate
    RandomAccessFile(destFile, "rw").use { it.setLength(totalSize) }

    // Progress updater coroutine
    val progressJob = launch {
        while (isActive) {
            delay(PROGRESS_UPDATE_INTERVAL_MS)
            val dl = bytesDownloaded.get()
            val pct = ((dl * 100) / totalSize).toInt()
            notification.progress(pct, String.format("%.1f / %.1f MB (%d%%)",
                dl / (1024.0 * 1024.0), totalSize / (1024.0 * 1024.0), pct))
            onProgress?.invoke(pct.toFloat())
        }
    }

    try {
        (0 until PARALLEL_THREAD_COUNT).map { i ->
            val start = i * chunkSize
            val end = if (i == PARALLEL_THREAD_COUNT - 1) totalSize - 1 else (start + chunkSize - 1)
            async(Dispatchers.IO) {
                downloadRange(url, destFile, start, end, cookies, bytesDownloaded)
            }
        }.awaitAll()
    } finally {
        progressJob.cancel()
    }
}

// --- Public download entry points ---

fun performDownload(context: Context, title: String, option: StreamOption) {
    if (option.format == "mp3") {
        performMp3Download(context, title, option)
        return
    }
    if (option.audioUrl != null) {
        performMuxedDownload(context, title, option)
        return
    }
    val cleanTitle = sanitizeFileName(title)
    val fileName = "$cleanTitle.${option.format}"
    val notification = DownloadNotification(context, fileName)
    notification.progress(0, "Starting download...")
    Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()

    downloadProgressState.value = 0f
    CoroutineScope(Dispatchers.IO).launch {
        val downloadsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "jutoob"
        )
        downloadsDir.mkdirs()
        val destFile = File(downloadsDir, fileName)

        try {
            parallelDownload(option.url, destFile, notification) { pct ->
                downloadProgressState.value = pct
            }

            val mimeType = if (option.isAudio) "audio/mp4" else "video/mp4"
            MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf(mimeType), null)

            downloadProgressState.value = 100f
            withContext(Dispatchers.Main) {
                notification.complete("Download complete")
                Toast.makeText(context, "Saved to Downloads/jutoob/", Toast.LENGTH_SHORT).show()
            }
            kotlinx.coroutines.delay(1800)
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            destFile.delete()
            withContext(Dispatchers.Main) {
                notification.error("Download failed")
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            downloadProgressState.value = -1f
        }
    }
}

private fun performMuxedDownload(context: Context, title: String, option: StreamOption) {
    val cleanTitle = sanitizeFileName(title)
    val fileName = "$cleanTitle.mp4"
    val notification = DownloadNotification(context, fileName)
    notification.progress(0, "Starting download...")
    Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()

    downloadProgressState.value = 0f
    CoroutineScope(Dispatchers.IO).launch {
        val downloadsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "jutoob"
        )
        downloadsDir.mkdirs()
        val destFile = File(downloadsDir, fileName)
        val tempVideoFile = File(context.cacheDir, "mux_video_${System.currentTimeMillis()}.mp4")
        val tempAudioFile = File(context.cacheDir, "mux_audio_${System.currentTimeMillis()}.m4a")

        try {
            parallelDownload(option.url, tempVideoFile, notification) { pct ->
                downloadProgressState.value = pct * 0.85f
            }

            notification.indeterminate("Downloading audio...")
            parallelDownload(option.audioUrl!!, tempAudioFile, notification) { pct ->
                downloadProgressState.value = 85f + pct * 0.14f
            }

            downloadProgressState.value = 99f
            notification.indeterminate("Combining audio and video...")
            muxVideoAudio(tempVideoFile, tempAudioFile, destFile)

            MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf("video/mp4"), null)

            downloadProgressState.value = 100f
            withContext(Dispatchers.Main) {
                notification.complete("Download complete")
                Toast.makeText(context, "Saved to Downloads/jutoob/", Toast.LENGTH_SHORT).show()
            }
            kotlinx.coroutines.delay(1800)
        } catch (e: Exception) {
            Log.e(TAG, "Muxed download error", e)
            destFile.delete()
            withContext(Dispatchers.Main) {
                notification.error("Download failed")
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            tempVideoFile.delete()
            tempAudioFile.delete()
            downloadProgressState.value = -1f
        }
    }
}

@android.annotation.SuppressLint("WrongConstant")
private fun writeSamples(extractor: MediaExtractor, muxer: MediaMuxer, trackIndex: Int, buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
    while (true) {
        val sampleSize = extractor.readSampleData(buffer, 0)
        if (sampleSize < 0) break
        bufferInfo.offset = 0
        bufferInfo.size = sampleSize
        bufferInfo.presentationTimeUs = extractor.sampleTime
        bufferInfo.flags = extractor.sampleFlags
        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
        extractor.advance()
    }
}

private suspend fun muxVideoAudio(videoFile: File, audioFile: File, outputFile: File) = withContext(Dispatchers.IO) {
    val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    val videoExtractor = MediaExtractor()
    videoExtractor.setDataSource(videoFile.absolutePath)
    var videoTrackIndex = -1
    for (i in 0 until videoExtractor.trackCount) {
        val format = videoExtractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        if (mime.startsWith("video/")) {
            videoTrackIndex = muxer.addTrack(format)
            videoExtractor.selectTrack(i)
            break
        }
    }

    val audioExtractor = MediaExtractor()
    audioExtractor.setDataSource(audioFile.absolutePath)
    var audioTrackIndex = -1
    for (i in 0 until audioExtractor.trackCount) {
        val format = audioExtractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        if (mime.startsWith("audio/")) {
            audioTrackIndex = muxer.addTrack(format)
            audioExtractor.selectTrack(i)
            break
        }
    }

    if (videoTrackIndex < 0 || audioTrackIndex < 0) {
        videoExtractor.release()
        audioExtractor.release()
        muxer.release()
        throw IOException("Could not find video/audio tracks for muxing")
    }

    muxer.start()

    val buffer = ByteBuffer.allocate(1024 * 1024)
    val bufferInfo = MediaCodec.BufferInfo()

    // Write video samples
    writeSamples(videoExtractor, muxer, videoTrackIndex, buffer, bufferInfo)

    // Write audio samples
    writeSamples(audioExtractor, muxer, audioTrackIndex, buffer, bufferInfo)

    muxer.stop()
    muxer.release()
    videoExtractor.release()
    audioExtractor.release()
}

private fun performMp3Download(context: Context, title: String, option: StreamOption) {
    val cleanTitle = sanitizeFileName(title)
    val notification = DownloadNotification(context, "$cleanTitle.mp3")
    notification.progress(0, "Downloading audio...")
    Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()

    downloadProgressState.value = 0f
    CoroutineScope(Dispatchers.IO).launch {
        val downloadsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "jutoob"
        )
        downloadsDir.mkdirs()
        val m4aFile = File(downloadsDir, "$cleanTitle.m4a")

        try {
            val downloadStart = System.currentTimeMillis()
            parallelDownload(option.url, m4aFile, notification) { pct ->
                downloadProgressState.value = pct * 0.1f
            }
            val downloadDuration = System.currentTimeMillis() - downloadStart
            downloadProgressState.value = 10f

            notification.indeterminate("Converting to MP3...")

            // Simulate progress 10→99% during conversion, paced from the download duration.
            // Files >10MB have slower conversion relative to download, so use slower pacing.
            val fileSize = m4aFile.length()
            val isMediumFile = fileSize > 10L * 1024 * 1024 // 10-30MB range
            val estimatedMs = if (isMediumFile) maxOf(downloadDuration * 5, 8000L) else maxOf(downloadDuration * 2, 3000L)
            val tickMs = 100L
            val baseIncrement = 89f / (estimatedMs / tickMs)
            val progressJob = CoroutineScope(Dispatchers.IO).launch {
                var current = 10f
                while (current < 99f) {
                    delay(tickMs)
                    val factor = if (isMediumFile) when {
                        current < 20f -> 0.20f
                        current < 40f -> 0.16f
                        current < 60f -> 0.12f
                        current < 80f -> 0.08f
                        current < 90f -> 0.04f
                        current < 95f -> 0.02f
                        else -> 0.01f
                    } else when {
                        current < 30f -> 0.20f
                        current < 50f -> 0.16f
                        current < 65f -> 0.12f
                        current < 80f -> 0.08f
                        current < 90f -> 0.04f
                        current < 95f -> 0.02f
                        else -> 0.01f
                    }
                    current = minOf(current + baseIncrement * factor, 99f)
                    downloadProgressState.value = current
                }
            }

            convertM4aToMp3(context, cleanTitle)
            progressJob.cancel()

            downloadProgressState.value = 100f
            withContext(Dispatchers.Main) {
                notification.complete("Download complete")
                Toast.makeText(context, "Saved to Downloads/jutoob/", Toast.LENGTH_SHORT).show()
            }
            delay(1800)
        } catch (e: Exception) {
            Log.e(TAG, "MP3 download/convert error", e)
            m4aFile.delete()
            withContext(Dispatchers.Main) {
                notification.error("Failed: ${e.message}")
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            downloadProgressState.value = -1f
        }
    }
}

private suspend fun convertM4aToMp3(context: Context, cleanTitle: String) =
    withContext(Dispatchers.IO) {
        val downloadsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "jutoob"
        )
        val m4aFile = File(downloadsDir, "$cleanTitle.m4a")
        val mp3File = File(downloadsDir, "$cleanTitle.mp3")
        val tempMp3 = File(context.cacheDir, "temp_audio.mp3")
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(m4aFile.absolutePath)

            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = format
                    break
                }
            }
            if (audioTrackIndex == -1 || trackFormat == null) throw IOException("No audio track found in M4A")

            extractor.selectTrack(audioTrackIndex)
            val sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            val pcmChunks = mutableListOf<ShortArray>()

            while (true) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuf = outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val samples = ShortArray(shortBuf.remaining())
                        shortBuf.get(samples)
                        pcmChunks.add(samples)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            val androidLame = LameBuilder()
                .setInSampleRate(sampleRate)
                .setOutChannels(channels)
                .setOutBitrate(128)
                .setOutSampleRate(44100)
                .setQuality(2)
                .build()

            FileOutputStream(tempMp3).use { fos ->
                val mp3Buf = ByteArray(8192)
                for (chunk in pcmChunks) {
                    val samplesPerChannel = chunk.size / channels
                    if (channels == 1) {
                        val encoded = androidLame.encode(chunk, chunk, samplesPerChannel, mp3Buf)
                        if (encoded > 0) fos.write(mp3Buf, 0, encoded)
                    } else {
                        val encoded = androidLame.encodeBufferInterLeaved(chunk, samplesPerChannel, mp3Buf)
                        if (encoded > 0) fos.write(mp3Buf, 0, encoded)
                    }
                }
                val flushed = androidLame.flush(mp3Buf)
                if (flushed > 0) fos.write(mp3Buf, 0, flushed)
            }

            androidLame.close()

            tempMp3.copyTo(mp3File, overwrite = true)
            tempMp3.delete()
            m4aFile.delete()

            MediaScannerConnection.scanFile(
                context,
                arrayOf(mp3File.absolutePath),
                arrayOf("audio/mpeg"),
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "MP3 conversion error", e)
            tempMp3.delete()
            throw e
        }
    }
