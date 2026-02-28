package com.mobilekinetic.agent.device.api

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * mK:a Accessibility Service - enables full UI automation across all apps.
 *
 * Capabilities:
 * - Click by coordinates or selector (text/id/class)
 * - Gesture simulation (swipe, scroll, pinch)
 * - Read all visible screen text
 * - Global actions (home, back, recents, notifications, power dialog)
 * - Find UI elements by text, resource ID, or class name
 *
 * The service stores itself as a companion object singleton so DeviceApiServer
 * can access it directly without binding.
 */
class MobileKineticAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "mK:aA11y"

        @Volatile
        var instance: MobileKineticAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to react to events — this service is for on-demand automation only.
        // Keeping this minimal to avoid performance overhead.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }

    // ==================== CLICK OPERATIONS ====================

    /**
     * Click at absolute screen coordinates using gesture dispatch.
     * Returns true if the gesture was dispatched successfully.
     */
    fun clickAtCoordinates(x: Float, y: Float, callback: (Boolean) -> Unit) {
        try {
            val clickPath = Path().apply {
                moveTo(x, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 100))
                .build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Click completed at ($x, $y)")
                    callback(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Click cancelled at ($x, $y)")
                    callback(false)
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Click failed at ($x, $y)", e)
            callback(false)
        }
    }

    /**
     * Click on a UI element found by selector criteria.
     * Selector can match by text, content description, resource ID, or class name.
     */
    fun clickBySelector(
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        className: String? = null
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeBySelector(root, text, contentDescription, resourceId, className)
        if (node != null) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!result) {
                // Try clicking the parent if node itself isn't clickable
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        val parentResult = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        parent.recycle()
                        node.recycle()
                        root.recycle()
                        return parentResult
                    }
                    val grandParent = parent.parent
                    parent.recycle()
                    parent = grandParent
                }
            }
            node.recycle()
            root.recycle()
            return result
        }
        root.recycle()
        return false
    }

    // ==================== GESTURE OPERATIONS ====================

    /**
     * Perform a swipe gesture from (startX, startY) to (endX, endY) over the given duration.
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long, callback: (Boolean) -> Unit) {
        try {
            val swipePath = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(swipePath, 0, durationMs))
                .build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback(false)
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Swipe failed", e)
            callback(false)
        }
    }

    /**
     * Scroll in a direction. Uses swipe gestures to simulate scrolling.
     * direction: "up", "down", "left", "right"
     */
    fun scroll(direction: String, durationMs: Long = 300, callback: (Boolean) -> Unit) {
        // Use center of screen with a reasonable scroll distance
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val centerY = displayMetrics.heightPixels / 2f
        val scrollDistance = displayMetrics.heightPixels / 3f

        val (startX, startY, endX, endY) = when (direction.lowercase()) {
            "up" -> listOf(centerX, centerY + scrollDistance / 2, centerX, centerY - scrollDistance / 2)
            "down" -> listOf(centerX, centerY - scrollDistance / 2, centerX, centerY + scrollDistance / 2)
            "left" -> listOf(centerX + scrollDistance / 2, centerY, centerX - scrollDistance / 2, centerY)
            "right" -> listOf(centerX - scrollDistance / 2, centerY, centerX + scrollDistance / 2, centerY)
            else -> {
                callback(false)
                return
            }
        }

        swipe(startX, startY, endX, endY, durationMs, callback)
    }

    /**
     * Pinch gesture (zoom in or zoom out).
     * Requires API 26+ (GestureDescription with multiple strokes for multi-touch).
     */
    fun pinch(centerX: Float, centerY: Float, zoomIn: Boolean, span: Float = 200f, durationMs: Long = 400, callback: (Boolean) -> Unit) {
        try {
            val halfSpan = span / 2f
            val finger1Path = Path()
            val finger2Path = Path()

            if (zoomIn) {
                // Pinch out: fingers move apart
                finger1Path.moveTo(centerX - halfSpan / 3, centerY)
                finger1Path.lineTo(centerX - halfSpan, centerY)
                finger2Path.moveTo(centerX + halfSpan / 3, centerY)
                finger2Path.lineTo(centerX + halfSpan, centerY)
            } else {
                // Pinch in: fingers move together
                finger1Path.moveTo(centerX - halfSpan, centerY)
                finger1Path.lineTo(centerX - halfSpan / 3, centerY)
                finger2Path.moveTo(centerX + halfSpan, centerY)
                finger2Path.lineTo(centerX + halfSpan / 3, centerY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(finger1Path, 0, durationMs))
                .addStroke(GestureDescription.StrokeDescription(finger2Path, 0, durationMs))
                .build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback(false)
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Pinch gesture failed", e)
            callback(false)
        }
    }

    // ==================== SCREEN TEXT ====================

    /**
     * Traverse the entire accessibility tree and extract all visible text.
     * Returns a JSONArray of objects with text, bounds, class, and resource ID.
     */
    fun getScreenText(): JSONArray {
        val result = JSONArray()
        val root = rootInActiveWindow ?: return result
        collectText(root, result)
        root.recycle()
        return result
    }

    private fun collectText(node: AccessibilityNodeInfo, result: JSONArray) {
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()

        if (!text.isNullOrBlank() || !contentDesc.isNullOrBlank()) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val entry = JSONObject().apply {
                if (!text.isNullOrBlank()) put("text", text)
                if (!contentDesc.isNullOrBlank()) put("content_description", contentDesc)
                put("class", node.className?.toString() ?: "unknown")
                put("resource_id", node.viewIdResourceName ?: "")
                put("bounds", JSONObject().apply {
                    put("left", bounds.left)
                    put("top", bounds.top)
                    put("right", bounds.right)
                    put("bottom", bounds.bottom)
                })
                put("clickable", node.isClickable)
                put("enabled", node.isEnabled)
                put("focusable", node.isFocusable)
            }
            result.put(entry)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, result)
            child.recycle()
        }
    }

    // ==================== SCREENSHOT CAPTURE ====================

    /**
     * Capture a screenshot using AccessibilityService.takeScreenshot() (API 30+).
     * Returns base64-encoded JPEG string, or null on failure.
     * Blocks the calling thread up to 5 seconds waiting for the async callback.
     *
     * @param quality JPEG quality 1-100 (default 60)
     * @param maxWidth Max width in pixels, image is scaled down if larger (default 1080)
     */
    fun captureScreenshot(quality: Int = 60, maxWidth: Int = 1080): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Screenshot capture requires API 30+")
            return null
        }

        val latch = CountDownLatch(1)
        var resultBase64: String? = null

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val colorSpace = screenshot.colorSpace
                    val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                    hardwareBuffer.close()

                    if (hwBitmap != null) {
                        // Copy hardware bitmap to software bitmap for compression
                        val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        hwBitmap.recycle()

                        if (softBitmap != null) {
                            // Scale down if needed to avoid memory issues
                            val finalBitmap = if (softBitmap.width > maxWidth) {
                                val ratio = maxWidth.toFloat() / softBitmap.width
                                val newHeight = (softBitmap.height * ratio).toInt()
                                val scaled = Bitmap.createScaledBitmap(softBitmap, maxWidth, newHeight, true)
                                softBitmap.recycle()
                                scaled
                            } else {
                                softBitmap
                            }

                            val stream = ByteArrayOutputStream()
                            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                            finalBitmap.recycle()

                            resultBase64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                            stream.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing screenshot bitmap", e)
                } finally {
                    latch.countDown()
                }
            }

            override fun onFailure(errorCode: Int) {
                Log.e(TAG, "Screenshot failed with error code: $errorCode")
                latch.countDown()
            }
        })

        latch.await(5, TimeUnit.SECONDS)
        return resultBase64
    }

    // ==================== GLOBAL ACTIONS ====================

    /**
     * Perform a global action by name.
     * Supported: home, back, recents, notifications, quick_settings, power_dialog,
     *            lock_screen, take_screenshot, split_screen
     */
    fun performGlobalActionByName(actionName: String): Boolean {
        val actionId = when (actionName.lowercase()) {
            "home" -> GLOBAL_ACTION_HOME
            "back" -> GLOBAL_ACTION_BACK
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    GLOBAL_ACTION_LOCK_SCREEN
                } else {
                    Log.w(TAG, "Lock screen requires API 28+")
                    return false
                }
            }
            "take_screenshot" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    GLOBAL_ACTION_TAKE_SCREENSHOT
                } else {
                    Log.w(TAG, "Take screenshot requires API 28+")
                    return false
                }
            }
            else -> {
                Log.w(TAG, "Unknown global action: $actionName")
                return false
            }
        }
        return performGlobalAction(actionId)
    }

    // ==================== FIND ELEMENTS ====================

    /**
     * Find UI elements matching criteria. Returns a JSONArray of matching elements.
     */
    fun findElements(
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        className: String? = null,
        limit: Int = 20
    ): JSONArray {
        val result = JSONArray()
        val root = rootInActiveWindow ?: return result
        collectMatchingNodes(root, text, contentDescription, resourceId, className, result, limit)
        root.recycle()
        return result
    }

    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo,
        text: String?,
        contentDescription: String?,
        resourceId: String?,
        className: String?,
        result: JSONArray,
        limit: Int
    ) {
        if (result.length() >= limit) return

        val matches = matchesSelector(node, text, contentDescription, resourceId, className)
        if (matches) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val entry = JSONObject().apply {
                put("text", node.text?.toString() ?: "")
                put("content_description", node.contentDescription?.toString() ?: "")
                put("class", node.className?.toString() ?: "unknown")
                put("resource_id", node.viewIdResourceName ?: "")
                put("package", node.packageName?.toString() ?: "")
                put("bounds", JSONObject().apply {
                    put("left", bounds.left)
                    put("top", bounds.top)
                    put("right", bounds.right)
                    put("bottom", bounds.bottom)
                })
                put("clickable", node.isClickable)
                put("enabled", node.isEnabled)
                put("focusable", node.isFocusable)
                put("checkable", node.isCheckable)
                put("checked", node.isChecked)
                put("scrollable", node.isScrollable)
                put("editable", node.isEditable)
            }
            result.put(entry)
        }

        for (i in 0 until node.childCount) {
            if (result.length() >= limit) return
            val child = node.getChild(i) ?: continue
            collectMatchingNodes(child, text, contentDescription, resourceId, className, result, limit)
            child.recycle()
        }
    }

    // ==================== SET TEXT ====================

    /**
     * Set text on an editable field found by selector.
     */
    fun setText(
        value: String,
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        className: String? = null
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeBySelector(root, text, contentDescription, resourceId, className)
        if (node != null && node.isEditable) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            node.recycle()
            root.recycle()
            return result
        }
        node?.recycle()
        root.recycle()
        return false
    }

    // ==================== PRIVATE HELPERS ====================

    private fun findNodeBySelector(
        root: AccessibilityNodeInfo,
        text: String?,
        contentDescription: String?,
        resourceId: String?,
        className: String?
    ): AccessibilityNodeInfo? {
        // Use built-in find methods for efficiency when possible
        if (!resourceId.isNullOrBlank()) {
            val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
            if (nodes.isNotEmpty()) return nodes[0]
        }
        if (!text.isNullOrBlank()) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) return nodes[0]
        }
        // Fall back to manual tree traversal for content description and class name
        return findNodeRecursive(root, text, contentDescription, resourceId, className)
    }

    private fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        text: String?,
        contentDescription: String?,
        resourceId: String?,
        className: String?
    ): AccessibilityNodeInfo? {
        if (matchesSelector(node, text, contentDescription, resourceId, className)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeRecursive(child, text, contentDescription, resourceId, className)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun matchesSelector(
        node: AccessibilityNodeInfo,
        text: String?,
        contentDescription: String?,
        resourceId: String?,
        className: String?
    ): Boolean {
        var anySpecified = false
        var allMatch = true

        if (!text.isNullOrBlank()) {
            anySpecified = true
            val nodeText = node.text?.toString() ?: ""
            if (!nodeText.contains(text, ignoreCase = true)) allMatch = false
        }
        if (!contentDescription.isNullOrBlank()) {
            anySpecified = true
            val nodeDesc = node.contentDescription?.toString() ?: ""
            if (!nodeDesc.contains(contentDescription, ignoreCase = true)) allMatch = false
        }
        if (!resourceId.isNullOrBlank()) {
            anySpecified = true
            val nodeResId = node.viewIdResourceName ?: ""
            if (!nodeResId.contains(resourceId, ignoreCase = true)) allMatch = false
        }
        if (!className.isNullOrBlank()) {
            anySpecified = true
            val nodeClass = node.className?.toString() ?: ""
            if (!nodeClass.contains(className, ignoreCase = true)) allMatch = false
        }

        return anySpecified && allMatch
    }
}
