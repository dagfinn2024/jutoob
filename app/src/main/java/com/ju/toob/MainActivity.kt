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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.window.DialogProperties
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
import kotlinx.coroutines.flow.collect
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
    private val chromeUserAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    private var geckoRuntime: GeckoRuntime? = null
    private var mainSession: GeckoSession? = null
    private var geckoView: GeckoView? = null
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
    private var reloadTimeout = 30000L
    private var isFirstLoad = true
    private var skipSponsorsEnabled by mutableStateOf(true)
    private var blockShortsEnabled by mutableStateOf(true)
    private var blockCommunityPostsEnabled by mutableStateOf(true)

    private var showDownloadDialog by mutableStateOf(false)
    private var currentVideoId by mutableStateOf<String?>(null)
    private var currentVideoTitle by mutableStateOf<String>("jutoob_video")
    private var isLiveStream by mutableStateOf(false)
    private var isReloading = false
    private var currentPageUrl: String? = null

    private var showConsentDialog by mutableStateOf(false)
    private var showWhimsicalExitDialog by mutableStateOf(false)

    private val sponsorBlockScript = """
        (function() {
          if (window._jutoob_sb_injected) return;
          window._jutoob_sb_injected = true;
          const SPONSOR_API = "https://sponsor.ajay.app/api/skipSegments";
          const CATEGORIES = ["sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "music_offtopic", "poi_highlight"];
          let segments = [];
          let lastSkipTime = -1;
          let video = null;
          function getVideoId() { return new URL(location.href).searchParams.get("v"); }
          async function loadSegments(videoId) {
            const params = new URLSearchParams({ videoID: videoId, actionType: "skip", service: "YouTube" });
            CATEGORIES.forEach(c => params.append("category", c));
            const res = await fetch(SPONSOR_API + "?" + params.toString());
            if (!res.ok) return [];
            const data = await res.json();
            return data.map(e => ({ start: e.segment[0], end: e.segment[1] })).sort((a, b) => a.start - b.start);
          }
          function shouldSkip(time) {
            for (const s of segments) {
              if (time >= s.start && time < s.end && Math.abs(time - lastSkipTime) > 1) return s;
            }
            return null;
          }
          function attachWatcher() {
            if (!video) return;
            video.addEventListener("timeupdate", () => {
              if (!window._jutoob_sb_enabled) return;
              const t = video.currentTime;
              const seg = shouldSkip(t);
              if (seg) {
                lastSkipTime = seg.end;
                video.currentTime = seg.end;
              }
            });
          }
          function findVideo() {
            video = document.querySelector("video");
            if (video) attachWatcher();
          }
          async function init() {
            const videoId = getVideoId();
            if (!videoId) return;
            segments = await loadSegments(videoId);
            findVideo();
            let lastUrl = location.href;
            new MutationObserver(async () => {
              if (location.href !== lastUrl) {
                lastUrl = location.href;
                segments = [];
                lastSkipTime = -1;
                const newId = getVideoId();
                if (newId) { segments = await loadSegments(newId); }
                findVideo();
              }
            }).observe(document, { subtree: true, childList: true });
          }
          init();
        })();
    """.trimIndent().replace("\n", " ")

    private val fixSearchFiltersScript = """
        (function() {
          if (window._jutoob_filter_fix) return;
          window._jutoob_filter_fix = true;
          document.addEventListener('click', function(e) {
            var path = e.composedPath();
            for (var i = 0; i < path.length; i++) {
              var el = path[i];
              if (!el.tagName) continue;
              var tag = el.tagName.toLowerCase();
              if (tag === 'ytm-chip-cloud-chip-renderer' || tag === 'yt-chip-cloud-chip-renderer' || tag === 'ytd-search-filter-renderer') {
                var link = el.querySelector('a[href]');
                if (!link) {
                  var shadow = el.shadowRoot;
                  if (shadow) link = shadow.querySelector('a[href]');
                }
                if (link && link.href) {
                  e.preventDefault();
                  e.stopPropagation();
                  window.location.href = link.href;
                  return;
                }
                var btn = el.querySelector('button');
                if (!btn) {
                  var shadow2 = el.shadowRoot;
                  if (shadow2) btn = shadow2.querySelector('button');
                }
                if (btn) {
                  var rect = btn.getBoundingClientRect();
                  var cx = rect.left + rect.width / 2;
                  var cy = rect.top + rect.height / 2;
                  try {
                    var ts = new TouchEvent('touchstart', {bubbles: true, cancelable: true, touches: [new Touch({identifier: 1, target: btn, clientX: cx, clientY: cy})]});
                    var te = new TouchEvent('touchend', {bubbles: true, cancelable: true, changedTouches: [new Touch({identifier: 1, target: btn, clientX: cx, clientY: cy})]});
                    btn.dispatchEvent(ts);
                    setTimeout(function() { btn.dispatchEvent(te); }, 50);
                  } catch(ex) {
                    btn.click();
                  }
                }
                return;
              }
            }
          }, true);
        })();
    """.trimIndent().replace("\n", " ")

    private val blockShortsScript = """
        (function() {
          if (window._jutoob_bs_injected) return;
          window._jutoob_bs_injected = true;
          function removeSideBarIcon() {
            const sideBarIconMini = document.querySelector("ytd-mini-guide-entry-renderer[aria-label=Shorts]");
            if (sideBarIconMini) sideBarIconMini.remove();
            const sideBarIconNormal = document.querySelector("ytd-guide-entry-renderer a[title=Shorts]");
            if (sideBarIconNormal) sideBarIconNormal.remove();
            const pivotShorts = document.querySelector('ytm-pivot-bar-item-renderer[content-type="shorts"]');
            if (pivotShorts) pivotShorts.remove();
          }
          function removeShortsCaroussel() {
            const videosCaroussel = document.querySelectorAll('ytd-rich-section-renderer ytd-rich-shelf-renderer, ytm-shorts-lockup-view-model, ytm-reel-shelf-renderer');
            videosCaroussel.forEach(singleCaroussel => {
              if (singleCaroussel.tagName.toLowerCase() === 'ytm-shorts-lockup-view-model' || singleCaroussel.tagName.toLowerCase() === 'ytm-reel-shelf-renderer') {
                singleCaroussel.remove();
                return;
              }
              const titleEl = singleCaroussel.querySelector('#dismissible > #rich-shelf-header-container > #rich-shelf-header > h2 #title-container #title');
              if (titleEl && titleEl.textContent.trim() === 'Shorts') singleCaroussel.remove();
            });
          }
          function removeShortsCarousselFromSearch() {
            const videosCaroussel = document.querySelectorAll('grid-shelf-view-model');
            videosCaroussel.forEach(singleCaroussel => {
              const titleEl = singleCaroussel.querySelector('yt-section-header-view-model yt-shelf-header-layout h2 > span');
              if (titleEl && titleEl.textContent.trim() === 'Shorts') singleCaroussel.remove();
            });
          }
          function removeShortsBetweenVideos() {
            const thumbnails = document.querySelectorAll('ytd-thumbnail a, ytm-thumbnail-overlay-endpoint a');
            thumbnails.forEach(singleThumbnail => {
              if (singleThumbnail.href.includes('/shorts/')) {
                const parent = singleThumbnail.closest('ytd-video-renderer, ytm-video-with-context-renderer, ytm-compact-video-renderer');
                if (parent) parent.remove();
              }
            });
          }
          function removeShortsFromVideoArea() {
            const shortsInVideoSideBar = document.querySelector("ytd-reel-shelf-renderer, ytm-reel-shelf-renderer");
            if (shortsInVideoSideBar) shortsInVideoSideBar.remove();
          }
          function blockShorts() {
            if (!window._jutoob_block_shorts) return;
            removeSideBarIcon();
            removeShortsCaroussel();
            removeShortsCarousselFromSearch();
            removeShortsFromVideoArea();
            removeShortsBetweenVideos();
          }
          setInterval(blockShorts, 1000);
        })();
    """.trimIndent().replace("\n", " ")

    private val blockCommunityPostsScript = """
        (function() {
          if (window._jutoob_bcp_injected) return;
          window._jutoob_bcp_injected = true;
          var style = document.createElement('style');
          style.id = 'jutoob-bcp-style';
          style.textContent = '.ypc-hide { display: none !important; }';
          document.head.appendChild(style);
          function hideNonVideoPosts(root) {
            if (!window._jutoob_block_community_posts) return;
            var items = (root || document).querySelectorAll('ytm-rich-section-renderer');
            items.forEach(function(item) {
              var hasVideo = item.querySelector('ytm-thumbnail,a[href*="watch"],a[href*="/shorts/"]');
              if (!hasVideo) item.classList.add('ypc-hide');
            });
          }
          function unhideAll() {
            document.querySelectorAll('.ypc-hide').forEach(function(el) { el.classList.remove('ypc-hide'); });
          }
          setTimeout(function() { hideNonVideoPosts(); }, 3000);
          var observer = new MutationObserver(function(mutations) {
            if (!window._jutoob_block_community_posts) return;
            mutations.forEach(function(mutation) {
              mutation.addedNodes.forEach(function(node) {
                if (node.nodeType === 1) hideNonVideoPosts(node);
              });
            });
          });
          observer.observe(document.body, { childList: true, subtree: true });
          setInterval(function() {
            if (window._jutoob_block_community_posts) hideNonVideoPosts();
            else unhideAll();
          }, 3000);
        })();
    """.trimIndent().replace("\n", " ")

    private val returnDislikeScript = """
        (function() {
          if (window._jutoob_ryd_injected) return;
          window._jutoob_ryd_injected = true;
          function formatCount(c) {
            if (c >= 1000000) return (c / 1000000).toFixed(1) + 'M';
            if (c >= 1000) return (c / 1000).toFixed(1) + 'K';
            return c.toString();
          }
          var _ryd_cache = {};
          function findDislikeBtn() {
            var allBtns = document.querySelectorAll('button');
            for (var i = 0; i < allBtns.length; i++) {
              var lbl = (allBtns[i].getAttribute('aria-label') || '').toLowerCase();
              if (lbl.indexOf('dislike') !== -1) return allBtns[i];
            }
            for (var i = 0; i < allBtns.length; i++) {
              if (allBtns[i].textContent.trim() === 'Dislike') return allBtns[i];
            }
            var sels = ['#segmented-dislike-button button', 'dislike-button-view-model button', '.segmented-dislike-button button'];
            for (var j = 0; j < sels.length; j++) { try { var e = document.querySelector(sels[j]); if (e) return e; } catch(x){} }
            return null;
          }
          async function getDislikeCount(videoId) {
            if (_ryd_cache[videoId]) return _ryd_cache[videoId];
            var res = await fetch('https://returnyoutubedislikeapi.com/votes?videoId=' + videoId);
            if (!res.ok) return null;
            var data = await res.json();
            _ryd_cache[videoId] = data.dislikes;
            return data.dislikes;
          }
          async function applyDislikes() {
            var videoId = null;
            try {
              videoId = new URL(location.href).searchParams.get('v');
              if (!videoId) { var m = location.pathname.match(/\/shorts\/([^/?]+)/); if (m) videoId = m[1]; }
            } catch(e) {}
            if (!videoId) return false;
            var btn = findDislikeBtn();
            if (!btn) return false;
            var count = await getDislikeCount(videoId);
            if (count === null) return false;
            var formatted = formatCount(count);
            var existing = document.getElementById('ryd-dislikes-val');
            if (existing) { existing.textContent = formatted; return true; }
            /* Try replacing a "Dislike" text span inside the button */
            var spans = btn.querySelectorAll('span');
            for (var i = 0; i < spans.length; i++) {
              var t = spans[i].textContent.trim();
              if (t === 'Dislike' || t === 'dislike') { spans[i].textContent = formatted; spans[i].id = 'ryd-dislikes-val'; return true; }
            }
            /* No text span found — create one and append */
            var el = document.createElement('span');
            el.id = 'ryd-dislikes-val';
            el.style.cssText = 'margin-left:6px; font-size:inherit; display:inline-flex; align-items:center; pointer-events:none;';
            el.textContent = formatted;
            btn.style.overflow = 'visible';
            btn.appendChild(el);
            return true;
          }
          function tryApply(n) {
            applyDislikes().then(function(ok) {
              if (!ok && n > 0) setTimeout(function() { tryApply(n - 1); }, 2000);
            }).catch(function() {
              if (n > 0) setTimeout(function() { tryApply(n - 1); }, 2000);
            });
          }
          tryApply(10);
          var lastUrl = location.href;
          new MutationObserver(function() {
            if (location.href !== lastUrl) {
              lastUrl = location.href;
              var old = document.getElementById('ryd-dislikes-val'); if (old) old.remove();
              setTimeout(function() { tryApply(10); }, 1500);
            }
          }).observe(document, { subtree: true, childList: true });
        })();
    """.trimIndent().replace("\n", " ")

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        isNetworkConnected = isNetworkAvailable(this)

        val prefs = getPreferences(Context.MODE_PRIVATE)
        val hasFinishedInstallation = prefs.getBoolean("extensions_installed_v6", false)
        val hasConsented = prefs.getBoolean("user_consented", false)

        skipSponsorsEnabled = prefs.getBoolean("skip_sponsors_enabled", true)
        blockShortsEnabled = prefs.getBoolean("block_shorts_enabled", true)
        blockCommunityPostsEnabled = prefs.getBoolean("block_community_posts_enabled", true)

        showConsentDialog = !hasConsented

        // Only start installation process if we have consent
        if (hasConsented) {
            isInstalling = !hasFinishedInstallation
            if (isInstalling) {
                showBlackOverlay = true
            }
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
                "--pref", "media.cache_size=512000",
                "--pref", "media.memory_cache_max_size=128000",
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
            .useTrackingProtection(false)
            .build()

        mainSession = GeckoSession(sessionSettings)
        setupMainSessionDelegates(mainSession!!)

        mainSession?.apply {
            open(geckoRuntime!!)
            if (isNetworkConnected) {
                if (hasFinishedInstallation || !hasConsented) {
                    loadUri("https://m.youtube.com/?jutoob_startup=1")
                }
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

        if (hasConsented) {
            checkExtensions()
        }

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

                    var pullProgress by remember { mutableStateOf(0f) }

                    Box(modifier = currentPadding) {
                        YouTubeGeckoPlayer(
                            session = mainSession!!,
                            runtime = geckoRuntime!!,
                            canGoBackState = canGoBack,
                            modifier = Modifier.fillMaxSize(),
                            isInstalling = isInstalling,
                            isAtScrollTop = lastScrollY <= 5,
                            isPullToRefreshEnabled = !isHomePage && !isLandscape,
                            onViewReady = { geckoView = it },
                            onPullProgress = { pullProgress = it },
                            onPullRelease = { progress ->
                                if (progress >= 1f) {
                                    mainSession?.loadUri("javascript:(function(){ " +
                                        "var v = document.querySelector('video'); " +
                                        "var t = (v && v.currentTime > 0) ? v.currentTime : 0; " +
                                        "var s = (v && !v.paused) ? 'R' : 'P'; " +
                                        "var oldT = document.title; " +
                                        "document.title = 'JUTOOB_SAVE_' + t + '_' + s; " +
                                        "setTimeout(function(){ document.title = oldT; }, 100); " +
                                        "})()")
                                }
                                pullProgress = 0f
                            }
                        )

                        if (pullProgress > 0f) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .fillMaxWidth(pullProgress)
                                    .height(3.dp)
                                    .background(
                                        if (pullProgress >= 1f)
                                            androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                        else
                                            androidx.compose.ui.graphics.Color(0xFFF44336)
                                    )
                            )
                        }

                        // ONLY include installation UI components if the app hasn't been successfully set up yet.
                        if (!hasFinishedInstallation) {
                            AnimatedVisibility(
                                visible = showBlackOverlay,
                                enter = fadeIn(),
                                exit = fadeOut(animationSpec = tween(2000))
                            ) {
                                Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
                            }

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
                                                    text = "jutoob@android:~/",
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

                        // Top Bar Actions (Settings on Right)
                        if (!isLandscape && isHeaderVisible && !showConsole) {
                            // Settings Button on the Right
                            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                                if (isHomePage) {
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
                                                text = { Text(if (skipSponsorsEnabled) "Skip Sponsors ✓" else "Skip Sponsors") },
                                                onClick = {
                                                    skipSponsorsEnabled = !skipSponsorsEnabled
                                                    prefs.edit().putBoolean("skip_sponsors_enabled", skipSponsorsEnabled).apply()
                                                    mainSession?.loadUri("javascript:window._jutoob_sb_enabled = $skipSponsorsEnabled;")
                                                    showMenu = false
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(if (blockShortsEnabled) "Block Shorts ✓" else "Block Shorts") },
                                                onClick = {
                                                    blockShortsEnabled = !blockShortsEnabled
                                                    prefs.edit().putBoolean("block_shorts_enabled", blockShortsEnabled).apply()
                                                    mainSession?.loadUri("javascript:window._jutoob_block_shorts = $blockShortsEnabled;")
                                                    showMenu = false
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(if (blockCommunityPostsEnabled) "Block Community Posts ✓" else "Block Community Posts") },
                                                onClick = {
                                                    blockCommunityPostsEnabled = !blockCommunityPostsEnabled
                                                    prefs.edit().putBoolean("block_community_posts_enabled", blockCommunityPostsEnabled).apply()
                                                    mainSession?.loadUri("javascript:window._jutoob_block_community_posts = $blockCommunityPostsEnabled;")
                                                    showMenu = false
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Enjoying jutoob?") },
                                                onClick = {
                                                    showMenu = false
                                                    try {
                                                        val customTabsIntent = CustomTabsIntent.Builder().build()
                                                        customTabsIntent.launchUrl(context, Uri.parse("https://buymeacoffee.com/jutoob"))
                                                    } catch (e: Exception) {
                                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/jutoob"))
                                                        context.startActivity(intent)
                                                    }
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("About") },
                                                onClick = {
                                                    showMenu = false
                                                    showAboutDialog = true
                                                }
                                            )
                                        }
                                    }
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
                                delay(100)
                                mainSession?.loadUri("javascript:(function() { " +
                                    "var style = document.getElementById('jutoob-fullscreen-style'); " +
                                    "if (style) style.remove(); " +
                                    "window.scrollTo(0, 0);" +
                                    "window.dispatchEvent(new Event('resize'));" +
                                    "setTimeout(function() { window.dispatchEvent(new Event('resize')); }, 250);" +
                                    "})()")
                            }
                        }
                    }
                }

                if (showConsentDialog) {
                    AlertDialog(
                        onDismissRequest = { /* Require interaction */ },
                        title = { Text("Ads… Who Needs ’Em?") },
                        text = { Text("By continuing, you agree to install a third-party ad-blocker that may prevent ads from appearing in YouTube videos. You acknowledge that jutoob is not responsible for any issues arising from this, and you indemnify us from any claims. Proceed only if you consent!") },
                        confirmButton = {
                            TextButton(onClick = {
                                prefs.edit().putBoolean("user_consented", true).apply()
                                showConsentDialog = false
                                checkExtensions()
                            }) {
                                Text("✅ Yes, block the ads!")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showConsentDialog = false
                                showWhimsicalExitDialog = true
                            }) {
                                Text("❌ Nope")
                            }
                        },
                        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
                    )
                }

                if (showWhimsicalExitDialog) {
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text("No worries!") },
                        text = { Text("YouTube ads will keep you company.") },
                        confirmButton = {
                            TextButton(onClick = { finish() }) {
                                Text("Exit")
                            }
                        },
                        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
                    )
                }

                if (showDownloadDialog && currentVideoId != null) {
                    DownloadDialog(
                        videoId = currentVideoId!!,
                        videoTitle = currentVideoTitle,
                        onDismiss = { showDownloadDialog = false }
                    )
                }

                LaunchedEffect(Unit) {
                    downloadProgressState.collect { progress ->
                        mainSession?.loadUri("javascript:if(window._jutoob_setProgress)window._jutoob_setProgress($progress)")
                    }
                }

                if (showAboutDialog) {
                    val versionName = remember {
                        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { "?" }
                    }
                    AlertDialog(
                        onDismissRequest = { showAboutDialog = false },
                        title = { Text("jutoob v$versionName") },
                        text = {
                            Column {
                                Text("jutoob is a minimal, Android YouTube player built on GeckoView, for distraction-free viewing")
                                Text(
                                    text = "jutoob.com",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp).clickable {
                                        try {
                                            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse("https://jutoob.com"))
                                        } catch (_: Exception) {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://jutoob.com")))
                                        }
                                    }
                                )
                            }
                        },
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
    }

    override fun onResume() {
        super.onResume()
        lastHeartbeatTime = System.currentTimeMillis()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        mainSession?.setActive(true)
        geckoRuntime?.webExtensionController?.setTabActive(mainSession!!, true)
        lastHeartbeatTime = System.currentTimeMillis()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            stopPlaybackAndShutdown()
        }
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
                .setOnAudioFocusChangeListener { }
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
                    if (isReloading) continue
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastHeartbeatTime > reloadTimeout) {
                        Log.e("JuToob", "Heartbeat lost! Re-loading session.")
                        isReloading = true
                        mainSession?.reload()
                        Handler(Looper.getMainLooper()).postDelayed({
                            isReloading = false
                            lastHeartbeatTime = System.currentTimeMillis()
                        }, 30000)
                    }
                } else if (!isReloading) {
                    lastHeartbeatTime = System.currentTimeMillis()
                }
            }
        }
    }

    private fun setupMainSessionDelegates(session: GeckoSession) {
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (title == "JUTOOB_DO_DL_V3") {
                    showDownloadDialog = true
                    return
                }

                if (title == "JUTOOB_SHARE") {
                    val videoId = currentVideoId ?: return
                    val shareUrl = "https://youtu.be/$videoId"
                    val intent = Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareUrl)
                    }
                    startActivity(Intent.createChooser(intent, null))
                    return
                }

                if (title?.startsWith("JUTOOB_SAVE_") == true) {
                    val parts = title.removePrefix("JUTOOB_SAVE_").split("_")
                    val time = if (parts.size == 2) parts[0].toDoubleOrNull() ?: 0.0 else 0.0
                    val wasPaused = parts.size == 2 && parts[1] == "P"
                    val pageUrl = currentPageUrl
                    if (pageUrl != null && time > 0) {
                        val uri = Uri.parse(pageUrl)
                        val newUrl = uri.buildUpon()
                            .clearQuery()
                            .also { builder ->
                                uri.queryParameterNames.forEach { param ->
                                    if (param != "t") builder.appendQueryParameter(param, uri.getQueryParameter(param))
                                }
                                builder.appendQueryParameter("t", "${time.toInt()}s")
                            }
                            .build().toString()
                        mainSession?.loadUri(newUrl)
                    } else {
                        mainSession?.reload()
                    }
                    // Simulate a real tap on the video area to create a trusted user gesture,
                    // then use that activation window to play/seek the video
                    val handler = Handler(Looper.getMainLooper())
                    handler.postDelayed({
                        val view = geckoView ?: return@postDelayed
                        val x = view.width / 2f
                        val y = view.height / 4f
                        val downTime = android.os.SystemClock.uptimeMillis()
                        val down = android.view.MotionEvent.obtain(downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                        val up = android.view.MotionEvent.obtain(downTime, downTime + 50, android.view.MotionEvent.ACTION_UP, x, y, 0)
                        view.dispatchTouchEvent(down)
                        view.dispatchTouchEvent(up)
                        down.recycle()
                        up.recycle()
                        // After the trusted tap, ensure correct state
                        handler.postDelayed({
                            val seekJs = if (time > 0) "if(Math.abs(v.currentTime-$time)>2) v.currentTime=$time;" else ""
                            val action = if (wasPaused) "if(!v.paused) v.pause();" else "if(v.paused) v.play().catch(function(){});"
                            mainSession?.loadUri("javascript:(function(){ var v = document.querySelector('video'); if(!v) return; $seekJs $action })()")
                        }, 500)
                    }, 5000)
                    return
                }

                if (title == "JUTOOB_ERR_LONG") {
                    Handler(Looper.getMainLooper()).post { 
                        Toast.makeText(this@MainActivity, "Videos longer than a day cannot be downloaded", Toast.LENGTH_LONG).show()
                    }
                    return
                }

                if (title == "JUTOOB_LIVE_TRUE") {
                    isLiveStream = true
                    lastHeartbeatTime = System.currentTimeMillis()
                    return
                }

                if (title == "JUTOOB_LIVE_FALSE") {
                    isLiveStream = false
                    lastHeartbeatTime = System.currentTimeMillis()
                    return
                }
                
                if (title?.startsWith("JUTOOB") == true) {
                    lastHeartbeatTime = System.currentTimeMillis()
                    isReloading = false
                    if (isFirstLoad) {
                        isFirstLoad = false
                        reloadTimeout = 30000L
                        Handler(Looper.getMainLooper()).postDelayed({
                            reloadTimeout = 9000L
                        }, 9000)
                    }
                } else if (title != null && title != "YouTube") {
                    currentVideoTitle = title.replace(" - YouTube", "").trim()
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
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                // Load popup URLs in the current session instead of blocking them
                mainSession?.loadUri(uri)
                return GeckoResult.fromValue(null)
            }
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                val uri = request.uri
                if (uri.startsWith("intent:") || uri.startsWith("vnd.youtube:") || uri.startsWith("youtube:") || uri.startsWith("android-app://com.google.android.youtube")) {
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
            override fun onLocationChange(s: GeckoSession, url: String?, p: List<GeckoSession.PermissionDelegate.ContentPermission>, g: Boolean) {
                 currentPageUrl = url
                 isHomePage = url?.contains("/watch") != true && url?.contains("/shorts/") != true && url?.contains("/live/") != true
                 isLiveStream = url?.contains("/live/") == true
                 
                 currentVideoId = if (!isHomePage) {
                     val uri = Uri.parse(url)
                     uri.getQueryParameter("v") ?: url?.substringAfter("/shorts/")?.substringAfter("/live/")?.substringBefore("?")?.takeIf { it.length == 11 }
                 } else {
                     null
                 }

                 when {
                    url?.contains("accounts.google.com") == true -> {
                        reloadTimeout = 600000L
                    }
                    url?.contains("m.youtube.com") == true -> {
                        reloadTimeout = if (isFirstLoad) 30000L else 9000L
                    }
                 }

                 val paddingValue = if (isHomePage) "18px" else "0px"
                 s.loadUri("javascript:(function() { " +
                        "window._jutoob_sb_enabled = $skipSponsorsEnabled; " +
                        "window._jutoob_block_shorts = $blockShortsEnabled; " +
                        "window._jutoob_block_community_posts = $blockCommunityPostsEnabled; " +
                        "try { Object.defineProperty(navigator, 'userAgent', { get: () => '$chromeUserAgent', configurable: true }); } catch(e) {} " +
                        "var bannerStyle = document.getElementById('jutoob-banner-style'); if (!bannerStyle) { bannerStyle = document.createElement('style'); bannerStyle.id = 'jutoob-banner-style'; document.head.appendChild(bannerStyle); } " +
                        "bannerStyle.innerHTML = ` ytm-app-banner, .open-in-app-banner, ytm-mealbar-promo-renderer, ytd-app-promo-renderer, ytd-smart-app-banner-renderer, ytd-banner-promo-renderer { display: none !important; } .mobile-topbar-header, ytm-mobile-topbar-renderer { padding-right: $paddingValue !important; } `; " +
                        "function checkLive() { var isLive = !!(document.querySelector('.ytp-live') || document.querySelector('.badge-style-type-live-now') || !!document.querySelector('[itemprop=\"isLiveBroadcast\"]')); var oldT = document.title; document.title = isLive ? 'JUTOOB_LIVE_TRUE' : 'JUTOOB_LIVE_FALSE'; setTimeout(function(){ document.title = oldT; }, 100); } " +
                        "if (window._jutoob_live_interval) clearInterval(window._jutoob_live_interval); window._jutoob_live_interval = setInterval(checkLive, 5000); checkLive(); " +
                        "})()")

                 s.loadUri("javascript:$fixSearchFiltersScript")
                 s.loadUri("javascript:$blockShortsScript")
                 s.loadUri("javascript:$blockCommunityPostsScript")

                 if (url?.contains("/watch") == true || url?.contains("/shorts/") == true || url?.contains("/live/") == true) {
                     s.loadUri("javascript:$sponsorBlockScript")
                     s.loadUri("javascript:$returnDislikeScript")
                     
                     s.loadUri("javascript:(function() { " +
                        "function injectDownloadBtn() { " +
                        "  if (!location.href.includes('/watch') && !location.href.includes('/shorts/') && !location.href.includes('/live/')) { " +
                        "    var existing = document.getElementById('jutoob-download-btn'); " +
                        "    if (existing) existing.remove(); " +
                        "    return; " +
                        "  } " +
                        "  var topBar = document.querySelector('ytm-mobile-topbar-renderer .header-bar') || document.querySelector('.mobile-topbar-header') || document.querySelector('mobile-topbar-header'); " +
                        "  if (!topBar || document.getElementById('jutoob-download-btn')) return; " +
                        "  var btn = document.createElement('div'); " +
                        "  btn.id = 'jutoob-download-btn'; " +
                        "  btn.style.cssText = 'margin-left:8px; margin-right:8px; display:inline-flex; align-items:center; cursor:pointer; position:relative; z-index:99; width:40px; height:40px; justify-content:center; pointer-events:auto !important;'; " +
                        "  btn.innerHTML = '<svg viewBox=\"0 0 24 24\" style=\"width:24px; height:24px; fill:white; pointer-events:none;\"><path d=\"M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z\"/></svg>'; " +
                        "  " +
                        "  btn.onclick = function(e) { " +
                        "    e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); " +
                        "    var v = document.querySelector('video'); " +
                        "    if (v && v.duration > 86399) { " +
                        "      var oldT = document.title; " +
                        "      document.title = 'JUTOOB_ERR_LONG'; " +
                        "      setTimeout(function() { document.title = oldT; }, 100); " +
                        "      return false; " +
                        "    } " +
                        "    var oldTitle = document.title; " +
                        "    document.title = 'JUTOOB_DO_DL_V3'; " +
                        "    setTimeout(function() { document.title = oldTitle; }, 100); " +
                        "    return false; " +
                        "  }; " +
                        "  " +
                        "  var logo = topBar.querySelector('.header-logo') || topBar.querySelector('a[href=\"/\"]') || topBar.querySelector('.header-logo-container') || topBar.firstElementChild; " +
                        "  if (logo) logo.insertAdjacentElement('afterend', btn); " +
                        "} " +
                        "injectDownloadBtn(); " +
                        "if (window._jutoob_observer) window._jutoob_observer.disconnect(); " +
                        "window._jutoob_observer = new MutationObserver(injectDownloadBtn); " +
                        "window._jutoob_observer.observe(document.body, { childList: true, subtree: true }); " +
                        "if (window._jutoob_dl_interval) clearInterval(window._jutoob_dl_interval); " +
                        "window._jutoob_dl_interval = setInterval(injectDownloadBtn, 2000); " +
                        "})()")

                     s.loadUri("javascript:(function() { " +
                        "function hijackShareBtn() { " +
                        "  var btns = document.querySelectorAll('button[aria-label*=\"Share\" i], button[aria-label*=\"share\" i]'); " +
                        "  btns.forEach(function(btn) { " +
                        "    if (btn._jutoob_share_hijacked) return; " +
                        "    btn._jutoob_share_hijacked = true; " +
                        "    btn.addEventListener('click', function(e) { " +
                        "      e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation(); " +
                        "      var oldTitle = document.title; " +
                        "      document.title = 'JUTOOB_SHARE'; " +
                        "      setTimeout(function() { document.title = oldTitle; }, 100); " +
                        "    }, true); " +
                        "  }); " +
                        "} " +
                        "hijackShareBtn(); " +
                        "if (window._jutoob_share_observer) window._jutoob_share_observer.disconnect(); " +
                        "window._jutoob_share_observer = new MutationObserver(hijackShareBtn); " +
                        "window._jutoob_share_observer.observe(document.body, { childList: true, subtree: true }); " +
                        "if (window._jutoob_share_interval) clearInterval(window._jutoob_share_interval); " +
                        "window._jutoob_share_interval = setInterval(hijackShareBtn, 2000); " +
                        "})()")

                     s.loadUri("javascript:(function() { " +
                        "if (!document.getElementById('jutoob-dl-anim-style')) { " +
                        "  var s = document.createElement('style'); s.id = 'jutoob-dl-anim-style'; " +
                        "  s.textContent = '" +
                        "@keyframes jutoob-dl-bounce{0%,100%{transform:translateY(-2px)}50%{transform:translateY(2px)}} " +
                        "#jutoob-dl-anim{animation:jutoob-dl-bounce 0.8s ease-in-out infinite} " +
                        "@keyframes jutoob-dl-blink{0%,100%{opacity:1}50%{opacity:0}}'; " +
                        "  document.head.appendChild(s); " +
                        "} " +
                        "window._jutoob_setProgress = function(p) { " +
                        "  var el = document.getElementById('jutoob-dl-progress'); " +
                        "  var pct = document.getElementById('jutoob-dl-pct'); " +
                        "  var svg = document.getElementById('jutoob-dl-anim'); " +
                        "  if (!el) return; " +
                        "  if (p < 0) { el.style.display = 'none'; el.style.animation = ''; " +
                        "    if (svg) svg.style.fill = '#FFD600'; el.style.color = '#FFD600'; return; } " +
                        "  el.style.display = 'inline-flex'; " +
                        "  if (p >= 100) { " +
                        "    el.style.color = '#00E676'; if (svg) svg.style.fill = '#00E676'; " +
                        "    if (pct) pct.textContent = '100%'; " +
                        "    el.style.animation = 'jutoob-dl-blink 0.4s ease-in-out 3'; " +
                        "  } else { " +
                        "    el.style.color = '#FFD600'; if (svg) svg.style.fill = '#FFD600'; " +
                        "    el.style.animation = ''; " +
                        "    if (pct) pct.textContent = p < 10 ? p.toFixed(1) + '%' : Math.floor(p) + '%'; " +
                        "  } " +
                        "}; " +
                        "function injectProgress() { " +
                        "  var dlBtn = document.getElementById('jutoob-download-btn'); " +
                        "  if (!dlBtn || document.getElementById('jutoob-dl-progress')) return; " +
                        "  var prog = document.createElement('div'); " +
                        "  prog.id = 'jutoob-dl-progress'; " +
                        "  prog.style.cssText = 'display:none;align-items:center;margin-right:8px;color:#FFD600;font-size:11px;white-space:nowrap;font-family:sans-serif;'; " +
                        "  prog.innerHTML = '<svg id=\"jutoob-dl-anim\" viewBox=\"0 0 24 24\" style=\"width:16px;height:16px;fill:#FFD600;margin-right:2px;\"><path d=\"M19 9h-4V3H9v6H5l7 7 7-7z\"/><rect x=\"5\" y=\"18\" width=\"14\" height=\"2\"/></svg><span id=\"jutoob-dl-pct\"></span>'; " +
                        "  dlBtn.insertAdjacentElement('afterend', prog); " +
                        "} " +
                        "injectProgress(); " +
                        "if (window._jutoob_progress_observer) window._jutoob_progress_observer.disconnect(); " +
                        "window._jutoob_progress_observer = new MutationObserver(injectProgress); " +
                        "window._jutoob_progress_observer.observe(document.body, {childList:true, subtree:true}); " +
                        "if (window._jutoob_progress_interval) clearInterval(window._jutoob_progress_interval); " +
                        "window._jutoob_progress_interval = setInterval(injectProgress, 2000); " +
                        "})()")
                 }
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) { if (success && !isYoutubeLoaded && !isInstalling) { Handler(Looper.getMainLooper()).postDelayed({ if (!isYoutubeLoaded) isYoutubeLoaded = true }, 200) } }
            override fun onProgressChange(session: GeckoSession, progress: Int) { if (progress >= 100 && !isYoutubeLoaded && !isInstalling) { Handler(Looper.getMainLooper()).postDelayed({ if (!isYoutubeLoaded) isYoutubeLoaded = true }, 200) } }
        }

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onChoicePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ChoicePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val choices = prompt.choices ?: run {
                    result.complete(prompt.dismiss())
                    return result
                }
                Handler(Looper.getMainLooper()).post {
                    val labels = choices.map { it.label ?: "" }.toTypedArray()
                    val selectedIndex = choices.indexOfFirst { it.selected }
                    android.app.AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog)
                        .setTitle(prompt.title ?: prompt.message)
                        .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                            result.complete(prompt.confirm(choices[which].id))
                            dialog.dismiss()
                        }
                        .setNegativeButton("Cancel") { _, _ ->
                            result.complete(prompt.dismiss())
                        }
                        .setOnCancelListener {
                            result.complete(prompt.dismiss())
                        }
                        .show()
                }
                return result
            }

            override fun onAlertPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.AlertPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                Handler(Looper.getMainLooper()).post {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(prompt.title)
                        .setMessage(prompt.message)
                        .setPositiveButton("OK") { _, _ -> result.complete(prompt.dismiss()) }
                        .setOnCancelListener { result.complete(prompt.dismiss()) }
                        .show()
                }
                return result
            }

            override fun onButtonPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ButtonPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                Handler(Looper.getMainLooper()).post {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(prompt.title)
                        .setMessage(prompt.message)
                        .setPositiveButton("OK") { _, _ -> result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
                        .setNegativeButton("Cancel") { _, _ -> result.complete(prompt.confirm(GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
                        .setOnCancelListener { result.complete(prompt.dismiss()) }
                        .show()
                }
                return result
            }

            override fun onPopupPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.PopupPrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                mainSession?.loadUri(prompt.targetUri ?: "")
                result.complete(prompt.dismiss())
                return result
            }
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
        if (isInstalled) {
            logAndConsole("Extensions already installed...")
            return
        }
        
        // Trigger installation UI
        isInstalling = true
        showBlackOverlay = true
        installExtensions()
    }

    private fun installExtensions() {
        lifecycleScope.launch {
            delay(300)
            showConsole = true
            isYoutubeLoaded = true
            
            val controller = geckoRuntime?.webExtensionController ?: run {
                isInstalling = false
                showConsole = false
                showBlackOverlay = false
                return@launch
            }

            val xpiExtensions = listOf("background_playback.xpi", "nonstop_playing.xpi", "ultimate_adblocker.xpi")
            val builtInExtensions = listOf("youtube_cleaner_extension/" to "Interface Cleaner", "youtube_autolike/" to "Like Subscribed", "members_only_hider/" to "Members Only Hider")

            logAndConsole("Installing browser extensions...")

            val xpiJobs = xpiExtensions.map { fileName ->
                async {
                    val extension = suspendCoroutine<WebExtension?> { cont ->
                        controller.install("resource://android/assets/$fileName").accept({ ext -> cont.resume(ext) }, { cont.resume(null) })
                    }
                    if (extension != null) {
                        controller.enable(extension, WebExtensionController.EnableSource.APP)
                        logAndConsole("[SUCCESS] $fileName installed.")
                    }
                    extension != null
                }
            }

            val builtInJobs = builtInExtensions.map { (path, name) ->
                async {
                    val extension = suspendCoroutine<WebExtension?> { cont ->
                        controller.installBuiltIn("resource://android/assets/$path").accept({ ext -> cont.resume(ext) }, { cont.resume(null) })
                    }
                    if (extension != null) {
                        controller.enable(extension, WebExtensionController.EnableSource.APP)
                        delay(Random.nextLong(250, 750))
                        logAndConsole("[SUCCESS] $name installed.")
                    }
                    extension != null
                }
            }

            (xpiJobs + builtInJobs).awaitAll()
            delay(1500)
            logAndConsole("[SUCCESS] All browser extensions installed.")

            // Slight delay to allow GeckoView to finalize extension registration globally
            delay(1500)
            logAndConsole("Finalizing setup and refreshing session...")

            mainSession?.stop()
            delay(1500)
            // Re-activate the session after stop, then load with all extensions active
            mainSession?.setActive(true)
            geckoRuntime?.webExtensionController?.setTabActive(mainSession!!, true)
            mainSession?.loadUri("https://m.youtube.com/results?search_query=trending&jutoob_startup=1")

            isInstalling = false
            delay(2000)
            showConsole = false
            delay(2000)
            showBlackOverlay = false
            getPreferences(Context.MODE_PRIVATE).edit().putBoolean("extensions_installed_v6", true).apply()
            
            logAndConsole("Setup complete!")
        }
    }
    
    private fun stopPlaybackAndShutdown() {
        mainSession?.close()
        mainSession = null
        stopService(Intent(this, MediaPlaybackService::class.java))
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

@Composable
fun YouTubeGeckoPlayer(
    session: GeckoSession,
    runtime: GeckoRuntime,
    canGoBackState: Boolean,
    modifier: Modifier = Modifier,
    isInstalling: Boolean = false,
    isAtScrollTop: Boolean = true,
    isPullToRefreshEnabled: Boolean = false,
    onViewReady: (GeckoView) -> Unit = {},
    onPullProgress: (Float) -> Unit = {},
    onPullRelease: (Float) -> Unit = {}
) {
    val atTopState = remember { mutableStateOf(true) }
    val pullEnabledState = remember { mutableStateOf(false) }
    val progressCallback = remember { mutableStateOf<(Float) -> Unit>({}) }
    val releaseCallback = remember { mutableStateOf<(Float) -> Unit>({}) }
    val installingState = remember { mutableStateOf(false) }

    atTopState.value = isAtScrollTop
    pullEnabledState.value = isPullToRefreshEnabled
    progressCallback.value = onPullProgress
    releaseCallback.value = onPullRelease
    installingState.value = isInstalling

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(session, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                session.setActive(true)
                runtime.webExtensionController.setTabActive(session, true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler { if (canGoBackState) session.goBack() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val threshold = 150f * ctx.resources.displayMetrics.density
            var pullStartY = 0f
            var isPulling = false

            GeckoView(ctx).apply {
                setSession(session)
                setBackgroundColor(Color.BLACK)

                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            if (pullEnabledState.value && atTopState.value) {
                                pullStartY = event.y
                                isPulling = true
                            }
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            if (isPulling) {
                                if (!atTopState.value) {
                                    progressCallback.value(0f)
                                    isPulling = false
                                } else {
                                    val dy = event.y - pullStartY
                                    if (dy > 0) {
                                        progressCallback.value((dy / threshold).coerceIn(0f, 1f))
                                    } else {
                                        progressCallback.value(0f)
                                    }
                                }
                            }
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            if (isPulling) {
                                val dy = event.y - pullStartY
                                val progress = if (atTopState.value && dy > 0) (dy / threshold).coerceIn(0f, 1f) else 0f
                                releaseCallback.value(progress)
                                isPulling = false
                            }
                        }
                    }
                    false
                }

                viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
                    if (hasFocus && !installingState.value) session.setActive(true)
                }
            }.also { onViewReady(it) }
        },
        update = { view ->
            view.setBackgroundColor(Color.BLACK)
            if (view.isShown && !installingState.value) session.setActive(true)
        }
    )
}
