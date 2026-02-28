package com.mobilekinetic.agent.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.mobilekinetic.agent.ui.theme.MobileKineticTheme
import com.mobilekinetic.agent.ui.theme.LcarsBlack
import com.mobilekinetic.agent.ui.theme.LcarsContainerGray
import com.mobilekinetic.agent.ui.theme.LcarsOrange
import com.mobilekinetic.agent.ui.theme.LcarsTextPrimary
import com.mobilekinetic.agent.ui.theme.MyriadPro
import java.io.File

/**
 * QuickApp WebView Runner
 *
 * Opens self-contained HTML files from ~/QuickApps/ in an in-app WebView.
 * No server needed -- loads directly from filesystem via file:// URI.
 * Supports sensor APIs (DeviceMotionEvent), Web Share API (via JS bridge),
 * and external link handling (opens in default browser).
 *
 * Launch via intent:
 *   action: com.mobilekinetic.agent.QUICKAPP
 *   extras: file_path (absolute path), title (optional)
 *   or: filename (relative to ~/QuickApps/)
 */
class QuickAppActivity : ComponentActivity() {

    companion object {
        private const val TAG = "QuickAppActivity"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FILENAME = "filename"
        private const val QUICKAPPS_DIR = "QuickApps"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filePath = resolveFilePath()
        if (filePath == null) {
            Toast.makeText(this, "No QuickApp file specified", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            Toast.makeText(this, "File not found: $filePath", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Security: file must be within app's data directory
        val appDataDir = applicationInfo.dataDir
        if (!file.absolutePath.startsWith(appDataDir)) {
            Toast.makeText(this, "Access denied: file outside app storage", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val title = intent.getStringExtra(EXTRA_TITLE)
            ?: file.nameWithoutExtension.replace("_", " ")
                .replaceFirstChar { it.uppercase() }

        val fileUri = "file://${file.absolutePath}"

        setContent {
            MobileKineticTheme {
                QuickAppScreen(
                    title = title,
                    fileUri = fileUri,
                    onClose = { finish() }
                )
            }
        }
    }

    private fun resolveFilePath(): String? {
        // Priority 1: explicit absolute file_path extra
        intent.getStringExtra(EXTRA_FILE_PATH)?.let { path ->
            // Expand ~ to home directory
            val resolved = if (path.startsWith("~")) {
                path.replaceFirst("~", homeDir().absolutePath)
            } else path
            return resolved
        }

        // Priority 2: intent data URI (file://)
        intent.data?.let { uri ->
            if (uri.scheme == "file") return uri.path
        }

        // Priority 3: filename relative to ~/QuickApps/
        intent.getStringExtra(EXTRA_FILENAME)?.let { name ->
            val resolved = File(File(homeDir(), QUICKAPPS_DIR), name)
            if (resolved.exists()) return resolved.absolutePath
        }

        return null
    }

    private fun homeDir(): File =
        File(filesDir.parentFile ?: filesDir, "home")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun QuickAppScreen(
    title: String,
    fileUri: String,
    onClose: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LcarsBlack)
            .systemBarsPadding()
    ) {
        // LCARS title bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(LcarsContainerGray)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title.uppercase(),
                color = LcarsOrange,
                fontFamily = MyriadPro,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "CLOSE",
                color = LcarsTextPrimary,
                fontFamily = MyriadPro,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable { onClose() }
                    .padding(8.dp)
            )
        }

        // WebView
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    @Suppress("DEPRECATION")
                    settings.allowFileAccess = true

                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                    // JS bridge for Web Share API polyfill
                    addJavascriptInterface(object {
                        @android.webkit.JavascriptInterface
                        fun shareFile(base64Data: String, filename: String, mimeType: String) {
                            try {
                                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                                val tempFile = File(context.cacheDir, filename)
                                tempFile.writeBytes(bytes)

                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    tempFile
                                )

                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = mimeType
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "Share").apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            } catch (e: Exception) {
                                Log.e("QuickAppActivity", "Share failed", e)
                            }
                        }

                        @android.webkit.JavascriptInterface
                        fun shareText(text: String, title: String) {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                                putExtra(Intent.EXTRA_TITLE, title)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, title).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    }, "mK:a")

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            // Local files stay in WebView
                            if (url.startsWith("file://")) return false
                            // External URLs open in default browser
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            return true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Inject Web Share API polyfill
                            view?.evaluateJavascript("""
                                (function() {
                                    if (typeof mK:a !== 'undefined') {
                                        navigator.share = function(data) {
                                            return new Promise(function(resolve, reject) {
                                                try {
                                                    if (data.files && data.files.length > 0) {
                                                        var file = data.files[0];
                                                        var reader = new FileReader();
                                                        reader.onload = function() {
                                                            var base64 = reader.result.split(',')[1];
                                                            mK:a.shareFile(base64, file.name, file.type);
                                                            resolve();
                                                        };
                                                        reader.readAsDataURL(file);
                                                    } else if (data.text || data.url) {
                                                        mK:a.shareText(data.text || data.url, data.title || '');
                                                        resolve();
                                                    } else {
                                                        reject(new Error('Nothing to share'));
                                                    }
                                                } catch(e) { reject(e); }
                                            });
                                        };
                                        navigator.canShare = function(data) {
                                            return !!(data && (data.files || data.text || data.url));
                                        };
                                    }
                                })();
                            """.trimIndent(), null)
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                            Log.d("QuickAppActivity", "JS: ${msg?.message()}")
                            return true
                        }
                    }

                    loadUrl(fileUri)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
        }
    }
}
