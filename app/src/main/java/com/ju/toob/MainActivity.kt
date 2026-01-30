package com.ju.toob

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AccelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.ju.toob.ui.theme.JuToobTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private var geckoRuntime: GeckoRuntime? = null
    private var mainSession: GeckoSession? = null
    private var canGoBack by mutableStateOf(false)
    private var isHomePage by mutableStateOf(true)
    private var isHeaderVisible by mutableStateOf(true)
    private var lastScrollY = 0
    private var isInstalling by mutableStateOf(false)
    private var showBlackOverlay by mutableStateOf(false)
    private var isYoutubeLoaded by mutableStateOf(false)
    private var isNetworkConnected by mutableStateOf(true)
    
    private val consoleLogs = mutableStateListOf<String>()
    private var showConsole by mutableStateOf(false)

    private var lastHeartbeatTime = System.currentTimeMillis()
    private var reloadTimeout = 5000L

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        isNetworkConnected = isNetworkAvailable(this)
        
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val hasFinishedInstallation = prefs.getBoolean("extensions_installed_v6", false)
        isInstalling = !hasFinishedInstallation
        if (isInstalling) {
            showBlackOverlay = true
        }

        splashScreen.setKeepOnScreenCondition {
            !isYoutubeLoaded && isNetworkConnected
        }
        
        splashScreen.setOnExitAnimationListener { viewProvider ->
            val fadeOut = ObjectAnimator.ofFloat(viewProvider.view, View.ALPHA, 1f, 0f)
            fadeOut.duration = 400L
            fadeOut.interpolator = AccelerateInterpolator()
            fadeOut.doOnEnd { viewProvider.remove() }
            fadeOut.start()
        }

        enableEdgeToEdge()
        
        val settings = GeckoRuntimeSettings.Builder()
            .consoleOutput(true)
            .arguments(arrayOf(
                "--pref", "media.cache_size=524288",
                "--pref", "media.buffer.low_threshold_ms=5000",
                "--pref", "media.buffer.high_threshold_ms=600000",
                "--pref", "extensions.webextensions.restrictedDomains=\"\"",
                "--pref", "extensions.logging.enabled=false",
                "--pref", "media.suspend-bkgnd-video.enabled=false",
                "--pref", "media.autoplay.default=0",
                "--pref", "media.autoplay.allow-extension-background-pages=true",
                "--pref", "media.geckoview.autoplay.enabled=true"
            ))
            .build()
        
        try {
            geckoRuntime = GeckoRuntime.create(this, settings)
        } catch (e: Exception) {
            Log.e("JuToob", "CRITICAL ERROR: Failed to create GeckoRuntime", e)
        }
        
        val sessionSettings = GeckoSessionSettings.Builder()
            .useTrackingProtection(true)
            .build()
            
        mainSession = GeckoSession(sessionSettings)
        setupMainSessionDelegates(mainSession!!)
        
        mainSession?.apply {
            open(geckoRuntime!!)
            if (isNetworkConnected) {
                loadUri("https://m.youtube.com")
            }
        }

        geckoRuntime?.webExtensionController?.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>
            ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, true, true))
            }
        })
        
        checkExtensions()
        startHeartbeatMonitor()
        requestAudioFocus()
        startPlaybackService()

        setContent {
            JuToobTheme {
                val configuration = LocalConfiguration.current
                val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val view = LocalView.current
                val context = LocalContext.current
                
                var showMenu by remember { mutableStateOf(false) }
                var showAboutDialog by remember { mutableStateOf(false) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = androidx.compose.ui.graphics.Color.Black
                ) { paddingValues ->
                    val currentPadding = if (isLandscape) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(paddingValues)
                    
                    Box(modifier = currentPadding) {
                        YouTubeGeckoPlayer(
                            session = mainSession!!,
                            runtime = geckoRuntime!!,
                            canGoBackState = canGoBack,
                            modifier = Modifier.fillMaxSize(),
                            isInstalling = isInstalling
                        )

                        // ONLY include installation UI components if the app hasn't been successfully set up yet.
                        // On the second run onwards, 'needsInstallationUI' will be false, and these will never be created.
                        val needsInstallationUI = remember { !hasFinishedInstallation }
                        if (needsInstallationUI) {
                            // Persistent Black Overlay during installation
                            AnimatedVisibility(
                                visible = showBlackOverlay,
                                enter = fadeIn(),
                                exit = fadeOut(animationSpec = tween(1000))
                            ) {
                                Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
                            }

                            // Console Popup
                            AnimatedVisibility(
                                visible = showConsole,
                                enter = fadeIn() + scaleIn(),
                                exit = fadeOut() + scaleOut(),
                                modifier = Modifier.align(Alignment.Center)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .shadow(24.dp, RoundedCornerShape(8.dp))
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(androidx.compose.ui.graphics.Color(0xFF0C0C0C))
                                        .border(1.dp, androidx.compose.ui.graphics.Color(0xFF333333), RoundedCornerShape(8.dp))
                                        .fillMaxWidth(0.95f)
                                        .fillMaxHeight(0.7f)
                                        .padding(12.dp)
                                ) {
                                    val listState = rememberLazyListState()
                                    LaunchedEffect(consoleLogs.size) {
                                        if (consoleLogs.isNotEmpty()) {
                                            listState.animateScrollToItem(consoleLogs.size - 1)
                                        }
                                    }
                                    
                                    Column {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 8.dp)
                                        ) {
                                            Column {
                                                Text(
                                                    text = "jutoob@android:~/extensions$",
                                                    color = androidx.compose.ui.graphics.Color(0xFF00FF00).copy(alpha = 0.7f),
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    modifier = Modifier.padding(bottom = 4.dp)
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(androidx.compose.ui.graphics.Color(0xFF333333))
                                                        .padding(top = 1.dp)
                                                )
                                            }
                                        }
                                        
                                        LazyColumn(
                                            state = listState,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            items(consoleLogs) { logLine ->
                                                Text(
                                                    text = if (logLine.startsWith("[")) logLine else "> $logLine",
                                                    color = when {
                                                        logLine.contains("[ERROR]") -> androidx.compose.ui.graphics.Color(0xFFFF5555)
                                                        logLine.contains("[SUCCESS]") -> androidx.compose.ui.graphics.Color(0xFF55FF55)
                                                        else -> androidx.compose.ui.graphics.Color(0xFFBBBBBB)
                                                    },
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 9.sp,
                                                    lineHeight = 12.sp,
                                                    modifier = Modifier.padding(bottom = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = !isLandscape && isHomePage && isHeaderVisible && !showConsole,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Settings",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("About") },
                                        onClick = { 
                                            showMenu = false
                                            showAboutDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Enjoying jutoob?") },
                                        onClick = { 
                                            showMenu = false
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/jutoob"))
                                            context.startActivity(intent)
                                        }
                                    )
                                }
                            }
                        }
                        LaunchedEffect(isLandscape) {
                            val window = (view.context as? ComponentActivity)?.window ?: return@LaunchedEffect
                            val controller = WindowCompat.getInsetsController(window, view)
                            if (isLandscape) {
                                controller.hide(WindowInsetsCompat.Type.systemBars())
                                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                                
                                mainSession?.loadUri("javascript:(function() { " +
                                    "var style = document.getElementById('jutoob-fullscreen-style');" +
                                    "if (!style) {" +
                                    "  style = document.createElement('style');" +
                                    "  style.id = 'jutoob-fullscreen-style';" +
                                    "  document.head.appendChild(style);" +
                                    "}" +
                                    "style.innerHTML = ` " +
                                    "  html, body { overflow: hidden !important; margin: 0 !important; padding: 0 !important; width: 100vw !important; height: 100vh !important; position: fixed !important; left: 0 !important; top: 0 !important; } " +
                                    "  #movie_player, .html5-video-player, #player-container-id, .player-container { " +
                                    "    position: fixed !important; " +
                                    "    top: 0 !important; " +
                                    "    left: 0 !important; " +
                                    "    width: 100vw !important; " +
                                    "    height: 100vh !important; " +
                                    "    z-index: 2147483647 !important; " +
                                    "    background: #000 !important; " +
                                    "  } " +
                                    "  .video-stream, .html5-main-video { " +
                                    "    object-fit: contain !important; " +
                                    "    width: 100vw !important; " +
                                    "    height: 100vh !important; " +
                                    "    left: 0 !important; " +
                                    "    top: 0 !important; " +
                                    "  } " +
                                    "  #header-bar, mobile-topbar-header, ytm-mobile-topbar-header, ytm-mobile-topbar-renderer, .mobile-topbar-header, ytm-search, #masthead-container { " +
                                    "    display: none !important; " +
                                    "  } " +
                                    "`; " +
                                    "window.dispatchEvent(new Event('resize'));" +
                                    "})()")
                            } else {
                                controller.show(WindowInsetsCompat.Type.systemBars())
                                mainSession?.loadUri("javascript:(function() { " +
                                    "var style = document.getElementById('jutoob-fullscreen-style'); " +
                                    "if (style) style.remove(); " +
                                    "window.dispatchEvent(new Event('resize'));" +
                                    "})()")
                            }
                        }
                    }
                }
                
                if (showAboutDialog) {
                    AlertDialog(
                        onDismissRequest = { showAboutDialog = false },
                        title = { Text("About") },
                        text = { Text("jutoob is a minimal GeckoView-based viewer with browser extensions.\n" +
                                "If you find it useful, you may optionally support further development.") },
                        confirmButton = {
                            TextButton(onClick = { showAboutDialog = false }) {
                                Text("OK")
                            }
                        }
                    )
                }

                if (!isNetworkConnected) {
                    AlertDialog(
                        onDismissRequest = { /* Require interaction */ },
                        title = { Text("No Internet Connection") },
                        text = { Text("jutoob requires an active internet connection to work. Please check your connection and try again.") },
                        confirmButton = {
                            TextButton(onClick = {
                                isNetworkConnected = isNetworkAvailable(this@MainActivity)
                                if (isNetworkConnected) {
                                    mainSession?.loadUri("https://m.youtube.com")
                                }
                            }) {
                                Text("Retry")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { finish() }) {
                                Text("Exit")
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // We keep session active for background audio support
        // mainSession?.setActive(false)
    }

    override fun onResume() {
        super.onResume()
        mainSession?.setActive(true)
        // Ensure active state after surface is likely ready
        Handler(Looper.getMainLooper()).postDelayed({
            mainSession?.setActive(true)
        }, 300)
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    private fun requestAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { /* Handle change if needed */ }
                .build()
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun startPlaybackService() {
        val serviceIntent = Intent(this, MediaPlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun startHeartbeatMonitor() {
        lifecycleScope.launch {
            while (true) {
                delay(1000)
                if (isYoutubeLoaded && !isInstalling && isNetworkConnected) {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastHeartbeatTime > reloadTimeout) {
                        Log.e("JuToob", "Heartbeat lost! Re-loading session.")
                        lastHeartbeatTime = currentTime
                        mainSession?.reload()
                    }
                } else {
                    lastHeartbeatTime = System.currentTimeMillis()
                }
            }
        }
    }

    private fun setupMainSessionDelegates(session: GeckoSession) {
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (title?.startsWith("JUTOOB") == true) {
                    lastHeartbeatTime = System.currentTimeMillis()
                    //Log.e("JuToob", "Heartbeat: $title")
                }
            }
        }
        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                if (scrollY > lastScrollY + 10 && scrollY > 100) {
                    isHeaderVisible = false
                } else if (scrollY < lastScrollY - 10 || scrollY < 10) {
                    isHeaderVisible = true
                }
                lastScrollY = scrollY
            }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBackValue: Boolean) { canGoBack = canGoBackValue }
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                val uri = request.uri
                if (uri.startsWith("intent:") || uri.startsWith("vnd.youtube:") || uri.startsWith("youtube:") || uri.startsWith("android-app://com.google.android.youtube")) {
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
            override fun onLocationChange(s: GeckoSession, url: String?, p: List<GeckoSession.PermissionDelegate.ContentPermission>, g: Boolean) {
                 isHomePage = url?.contains("/watch") != true
                 
                 when {
                    url?.contains("accounts.google.com") == true -> {
                        Log.e("JuToob", "User is logging in. Heartbeat expected to stop.")
                        reloadTimeout = 600000L // 10 minutes
                    }
                    url?.contains("m.youtube.com") == true -> {
                        Log.e("JuToob", "User is on YouTube. Heartbeat should resume.")
                        reloadTimeout = 5000L // 5 seconds
                    }
                 }

                 val paddingValue = if (isHomePage) "18px" else "0px"
                 s.loadUri("javascript:(function() { " +
                        "try { Object.defineProperty(navigator, 'userAgent', { get: () => 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36', configurable: false }); } catch(e) {} " +
                        "var bannerStyle = document.getElementById('jutoob-banner-style'); if (!bannerStyle) { bannerStyle = document.createElement('style'); bannerStyle.id = 'jutoob-banner-style'; document.head.appendChild(bannerStyle); } " +
                        "bannerStyle.innerHTML = ` ytm-app-banner, .open-in-app-banner, ytm-mealbar-promo-renderer, ytd-app-promo-renderer, ytd-smart-app-banner-renderer, ytd-banner-promo-renderer, tp-yt-paper-dialog { display: none !important; } .mobile-topbar-header, ytm-mobile-topbar-renderer { padding-right: $paddingValue !important; } `; " +
                        "})()")
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) { if (success && !isYoutubeLoaded && !isInstalling) { Handler(Looper.getMainLooper()).postDelayed({ if (!isYoutubeLoaded) isYoutubeLoaded = true }, 200) } }
            override fun onProgressChange(session: GeckoSession, progress: Int) { if (progress >= 100 && !isYoutubeLoaded && !isInstalling) { Handler(Looper.getMainLooper()).postDelayed({ if (!isYoutubeLoaded) isYoutubeLoaded = true }, 200) } }
        }
    }

    private fun logAndConsole(message: String, showInConsole: Boolean = true, isError: Boolean = false) {
        if (isError) {
            Log.e("JuToob", "Console [ERROR]: $message")
        } else {
            Log.e("JuToob", "Console [INFO]: $message")
        }
        
        if (showInConsole) {
            Handler(Looper.getMainLooper()).post {
                consoleLogs.add(message)
            }
        }
    }

    private fun checkExtensions() {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val isInstalled = prefs.getBoolean("extensions_installed_v6", false)
        Log.e("JuToob", "Prefs flag 'extensions_installed_v6' is $isInstalled")

        if (isInstalled) {
            logAndConsole("Extensions already installed (v6 check passed)")
            Log.e("JuToob", "Requesting extension list from GeckoRuntime...")
            return
        }

        installExtensions()
    }

    private fun installExtensions() {
        Log.e("JuToob", "STARTING FRESH INSTALL")
        
        lifecycleScope.launch {
            delay(300)
            showConsole = true
            isYoutubeLoaded = true
            
            val controller = geckoRuntime?.webExtensionController ?: run {
                Log.e("JuToob", "WebExtensionController IS NULL")
                isInstalling = false
                showConsole = false
                showBlackOverlay = false
                return@launch
            }

            val xpiExtensions = listOf(
                "background_playback.xpi",
                "nonstop_playing.xpi",
                "block_shorts.xpi",
                "ultimate_adblocker.xpi"
            )
            
            val builtInExtensions = listOf(
                "youtube_cleaner_extension/" to "Cleaner",
                "youtube_autolike/" to "Autolike"
            )

            logAndConsole("Installing browser extensions...")

            // 1. Kick off parallel actual installations that log their OWN success/fail
            val xpiJobs = xpiExtensions.map { fileName ->
                async {
                    val extension = suspendCoroutine<WebExtension?> { cont ->
                        controller.install("resource://android/assets/$fileName").accept(
                            { ext -> cont.resume(ext) },
                            { throwable -> 
                                Log.e("JuToob", "Error installing $fileName", throwable)
                                cont.resume(null) 
                            }
                        )
                    }
                    if (extension != null) {
                        controller.enable(extension, WebExtensionController.EnableSource.APP)
                        logAndConsole("[SUCCESS] $fileName ready.")
                    } else {
                        logAndConsole("[ERROR] $fileName failed.", isError = true)
                    }
                    extension != null
                }
            }

            val builtInJobs = builtInExtensions.map { (path, name) ->
                async {
                    val extension = suspendCoroutine<WebExtension?> { cont ->
                        controller.installBuiltIn("resource://android/assets/$path").accept(
                            { ext -> cont.resume(ext) },
                            { cont.resume(null) }
                        )
                    }
                    if (extension != null) {
                        controller.enable(extension, WebExtensionController.EnableSource.APP)
                        delay(Random.nextLong(1000, 2000))
                        logAndConsole("[SUCCESS] $name installed.")
                    } else {
                        logAndConsole("[ERROR] $name failed.", isError = true)
                    }
                    extension != null
                }
            }

            val entertainerJob = launch {
                for ((_, name) in builtInExtensions) {
                    logAndConsole("install $name")
                    delay(Random.nextLong(400, 800))
                }
                for (fileName in xpiExtensions) {
                    logAndConsole("install $fileName")
                    delay(Random.nextLong(1000, 2000))
                }
            }

            // 3. Wait for all background tasks to finish
            (xpiJobs + builtInJobs).awaitAll()
            
            // Ensure entertainer finishes its sequence before closing up
            entertainerJob.join()

            logAndConsole("[SUCCESS] All browser extensions installed.")

            mainSession?.stop()
            mainSession?.loadUri("https://m.youtube.com")
            isInstalling = false
            delay(2000)
            showConsole = false
            delay(2000)
            showBlackOverlay = false
            getPreferences(Context.MODE_PRIVATE).edit().putBoolean("extensions_installed_v6", true).apply()
            Log.e("JuToob", "COMPLETED.")
        }
    }
}

@Composable
fun YouTubeGeckoPlayer(
    session: GeckoSession, 
    runtime: GeckoRuntime, 
    canGoBackState: Boolean,
    modifier: Modifier = Modifier,
    isInstalling: Boolean = false
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(session, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    session.setActive(true)
                    runtime.webExtensionController.setTabActive(session, true)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // We keep session active for background audio support
                    // session.setActive(false)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose { 
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler { if (canGoBackState) session.goBack() }
    
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            GeckoView(ctx).apply {
                setSession(session)
                setBackgroundColor(Color.BLACK)
                
                // Ensure session is active when window focus is regained (unlock/resume)
                viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
                    if (hasFocus && !isInstalling) {
                        session.setActive(true)
                    }
                }
            }
        },
        update = { view ->
            view.setBackgroundColor(Color.BLACK)
            // Nudge session activity if view is visible but Gecko might be stalled
            if (view.isShown && !isInstalling) {
                session.setActive(true)
            }
        }
    )
}