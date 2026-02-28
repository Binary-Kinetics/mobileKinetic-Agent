package com.mobilekinetic.agent.ui.components

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * Three.js Audio Visualizer Component
 *
 * Displays a sphere grid with particles that bounce inside.
 * When particles hit the sphere wall, the corresponding grid cell flashes.
 * Particle speed increases with audio level during TTS playback.
 *
 * Uses WebView to render the Three.js visualization for exact parity
 * with the AudioKinetics web implementation.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AudioVisualizer(
    isPlaying: Boolean,
    audioLevel: Float = 0f,
    isClaudeRunning: Boolean = true,
    isStreaming: Boolean = false,
    subAgentStatus: String = "none",
    mcpAlive: Boolean = true,
    subAgentCrashed: Boolean = false,
    modifier: Modifier = Modifier
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Update audio level in WebView
    LaunchedEffect(audioLevel) {
        webView?.evaluateJavascript("window.setAudioLevel($audioLevel);", null)
    }

    // Update playing state in WebView
    LaunchedEffect(isPlaying) {
        webView?.evaluateJavascript("window.setPlaying($isPlaying);", null)
    }

    // Update Claude state in WebView (processing/stopped/idle + sub-agent variants)
    LaunchedEffect(isClaudeRunning, isStreaming, subAgentStatus) {
        val state = when {
            !isClaudeRunning -> "stopped"
            isStreaming && subAgentStatus == "stale" -> "processing_subagent_stale"
            isStreaming && subAgentStatus == "active" -> "processing_subagent"
            isStreaming -> "processing"
            else -> "idle"
        }
        webView?.evaluateJavascript("window.setClaudeState && window.setClaudeState('$state');", null)
    }

    // Update sub-agent crash state in WebView
    LaunchedEffect(subAgentCrashed) {
        webView?.evaluateJavascript("window.setSubAgentCrashed && window.setSubAgentCrashed($subAgentCrashed);", null)
    }

    // Update MCP health in WebView
    LaunchedEffect(mcpAlive) {
        webView?.evaluateJavascript("window.setMcpState && window.setMcpState($mcpAlive);", null)
    }

    // LAYER_ORDER: AndroidView hosts the WebView for Three.js visualization
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                // LAYER_ORDER: WebView fills entire parent for full-screen visualization
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                // Enable JavaScript for Three.js
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                // LAYER_ORDER: Hardware acceleration required for transparent WebGL overlay
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                // LAYER_ORDER: Transparent background so chat content shows through
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                // Prevent white flash on load
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Ensure playing state is synced after load
                        evaluateJavascript("window.setPlaying($isPlaying);", null)
                        val state = when {
                            !isClaudeRunning -> "stopped"
                            isStreaming && subAgentStatus == "stale" -> "processing_subagent_stale"
                            isStreaming && subAgentStatus == "active" -> "processing_subagent"
                            isStreaming -> "processing"
                            else -> "idle"
                        }
                        evaluateJavascript("window.setClaudeState('$state');", null)
                        evaluateJavascript("window.setMcpState($mcpAlive);", null)
                    }
                }

                // Load the visualization
                loadUrl("file:///android_asset/visualizer/visualizer.html")

                webView = this
            }
        },
        modifier = modifier.fillMaxSize(), // LAYER_ORDER: AndroidView modifier fills parent
        update = { view ->
            // Update reference if needed
            webView = view
        }
    )

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
        }
    }
}

/**
 * Audio Visualizer with simulated audio levels for testing
 */
@Composable
fun AudioVisualizerDemo(
    modifier: Modifier = Modifier
) {
    var isPlaying by remember { mutableStateOf(true) }
    var audioLevel by remember { mutableStateOf(0f) }

    // Simulate audio level changes
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            // Simulate varying audio levels
            audioLevel = (0.2f + (Math.random() * 0.6f).toFloat())
            delay(100)
        }
        audioLevel = 0f
    }

    AudioVisualizer(
        isPlaying = isPlaying,
        audioLevel = audioLevel,
        modifier = modifier
    )
}
