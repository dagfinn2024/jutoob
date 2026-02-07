package com.ju.toob

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun generateDownloadToken(): String {
    val secret = "overflowy2mate"
    val header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}"
    val now = System.currentTimeMillis() / 1000
    val payload = "{\"authorized\":true,\"timestamp\":${System.currentTimeMillis()},\"iat\":$now,\"exp\":${now + 180}}"
    
    val base64Header = Base64.encodeToString(header.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    val base64Payload = Base64.encodeToString(payload.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    
    val data = "$base64Header.$base64Payload"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    val signature = mac.doFinal(data.toByteArray())
    val base64Signature = Base64.encodeToString(signature, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    
    return "$data.$base64Signature"
}

@Composable
fun DownloadDialog(videoId: String, videoTitle: String, token: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.80f)
                .fillMaxHeight(0.65f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF374151))
                .border(1.dp, Color(0xFF333333), RoundedCornerShape(16.dp))
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(1.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Text("X", color = MaterialTheme.colorScheme.primary)
                    }
                }
                
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.setSupportZoom(false)
                            settings.builtInZoomControls = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    view?.loadUrl("javascript:(function() { " +
                                        "document.body.style.margin='0'; " +
                                        "document.body.style.padding='0'; " +
                                        "var container = document.querySelector('.container') || document.body; " +
                                        "container.style.display = 'block'; " +
                                        "container.style.paddingTop = '0'; " +
                                        "var si = setInterval(function() { window.scrollTo(0, 0); document.documentElement.scrollTop = 0; }, 100); " +
                                        "setTimeout(function() { clearInterval(si); }, 2000); " +
                                        "})()")
                                }
                            }
                            
                            setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                                val ext = if (mimetype.contains("audio")) "mp3" else "mp4"
                                val safeTitle = videoTitle.replace("[^a-zA-Z0-9.-]".toRegex(), " ").replace("\\s+".toRegex(), " ").trim().trim('.').ifEmpty { "untitled" }
                                val fileName = "${safeTitle}.$ext"

                                val request = DownloadManager.Request(Uri.parse(url))
                                    .setTitle(fileName)
                                    .setDescription("jutoob download")
                                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "jutoob/$fileName")
                                    .addRequestHeader("User-Agent", userAgent)

                                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                try {
                                    dm.enqueue(request)
                                    Toast.makeText(context, "Downloading: $fileName", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                                
                                onDismiss()
                            }
                            
                            val youtubeUrl = "https://youtube.com/watch?v=$videoId"
                            val iframeUrl = "https://meowing.ssstik.art/download?url=${Uri.encode(youtubeUrl)}&token=$token"
                            loadUrl(iframeUrl)
                        }
                    }
                )
            }
        }
    }
}
