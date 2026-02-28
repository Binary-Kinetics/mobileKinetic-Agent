package com.mobilekinetic.agent.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.mobilekinetic.agent.app.MobileKineticService
import com.mobilekinetic.agent.terminal.emulator.TerminalColors
import com.mobilekinetic.agent.terminal.emulator.TerminalSession
import com.mobilekinetic.agent.terminal.emulator.TextStyle
import com.mobilekinetic.agent.terminal.view.TerminalView
import com.mobilekinetic.agent.terminal.view.TerminalViewClient

private const val TAG = "TerminalScreen"

/**
 * Full-screen Compose terminal that wraps the native TerminalView.
 *
 * Binds to MobileKineticService, gets or creates a session, and attaches
 * the session to the TerminalView. Implements TerminalViewClient for
 * key/touch event handling.
 *
 * Phase 1: Single session, no tabs, no toolbar.
 */
@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    var service by remember { mutableStateOf<MobileKineticService?>(null) }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    val focusRequester = remember { FocusRequester() }

    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = (binder as MobileKineticService.LocalBinder).service
                service = svc
                Log.i(TAG, "Service connected")

                // Attach session to view if view is ready
                terminalView?.let { view ->
                    attachSessionToView(svc, view, context)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                Log.w(TAG, "Service disconnected")
            }
        }
    }

    DisposableEffect(Unit) {
        val intent = Intent(context, MobileKineticService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        Log.i(TAG, "Binding to service")

        onDispose {
            context.unbindService(connection)
            Log.i(TAG, "Unbound from service")
        }
    }

    // Request focus after composition to ensure keyboard works
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AndroidView(
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                setTextSize(42)
                setTerminalViewClient(MobileKineticViewClient(this))

                // Set dark background color on the view itself
                setBackgroundColor(Color.BLACK)

                // Configure terminal color scheme: white text on black background
                val colorScheme = TerminalColors.COLOR_SCHEME
                colorScheme.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND] = 0xFFFFFFFF.toInt()
                colorScheme.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND] = 0xFF000000.toInt()
                colorScheme.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR] = 0xFFFFFFFF.toInt()

                // Enable focus for keyboard input
                isFocusable = true
                isFocusableInTouchMode = true

                terminalView = this

                // If service is already connected, attach session now
                service?.let { svc ->
                    attachSessionToView(svc, this, ctx)
                }
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .imePadding()
            .focusRequester(focusRequester)
    )
}

private fun attachSessionToView(service: MobileKineticService, view: TerminalView, context: Context) {
    val session = service.getOrCreateSession()
    view.attachSession(session)

    // Register UI update callback
    service.onSessionUpdate = {
        view.post { view.onScreenUpdated() }
    }

    Log.i(TAG, "Session attached to view. PID: " + session.pid)

    // Request focus and show keyboard after layout is complete
    view.post {
        view.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
}

/**
 * Minimal TerminalViewClient implementation.
 * Handles key events and touch for Phase 1.
 */
private class MobileKineticViewClient(
    private val terminalView: TerminalView
) : TerminalViewClient {

    override fun onScale(scale: Float): Float {
        // No scaling in Phase 1
        return 1.0f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        // Show soft keyboard on tap
        val imm = terminalView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {
        // No-op for Phase 1
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        // Let TerminalView handle most keys
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        return false
    }

    override fun onLongPress(event: MotionEvent): Boolean {
        return false
    }

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        return false
    }

    override fun onEmulatorSet() {
        // Terminal emulator has been initialized
    }

    override fun logError(tag: String?, message: String?) {
        Log.e(tag ?: TAG, message ?: "")
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag ?: TAG, message ?: "")
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag ?: TAG, message ?: "")
    }

    override fun logDebug(tag: String?, message: String?) {
        Log.d(tag ?: TAG, message ?: "")
    }

    override fun logVerbose(tag: String?, message: String?) {
        Log.v(tag ?: TAG, message ?: "")
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message ?: "", e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag ?: TAG, "Stack trace", e)
    }
}
