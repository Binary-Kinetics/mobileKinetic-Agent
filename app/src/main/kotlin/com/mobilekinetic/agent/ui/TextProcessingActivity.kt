package com.mobilekinetic.agent.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilekinetic.agent.ui.theme.MobileKineticTheme
import com.mobilekinetic.agent.ui.theme.LcarsBlack
import com.mobilekinetic.agent.ui.theme.LcarsContainerGray
import com.mobilekinetic.agent.ui.theme.LcarsOrange
import com.mobilekinetic.agent.ui.theme.LcarsTextBody
import com.mobilekinetic.agent.ui.theme.LcarsTextPrimary
import com.mobilekinetic.agent.ui.theme.LcarsTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Transparent overlay activity that handles android.intent.action.PROCESS_TEXT.
 *
 * When the user highlights text anywhere on the device and selects "mK:a"
 * from the text selection menu, this activity launches with a Material bottom sheet
 * offering several processing actions.
 *
 * Actions:
 *   - Add to Calendar: POSTs to DeviceApiServer /calendar/create
 *   - Add to Task: POSTs to DeviceApiServer /tasks/create
 *   - Examine Text: Opens mK:a chat with analysis prompt
 *   - Ask mK:a: Opens mK:a chat with the text as a question
 *   - Summarize: Opens mK:a chat with summarization prompt
 */
class TextProcessingActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TextProcessingActivity"
        private const val DEVICE_API_BASE = "http://localhost:5563"
        const val EXTRA_PREFILLED_MESSAGE = "com.mobilekinetic.agent.EXTRA_PREFILLED_MESSAGE"
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedText = intent
            ?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
            ?.trim()

        if (selectedText.isNullOrEmpty()) {
            Toast.makeText(this, "No text selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            MobileKineticTheme {
                TextProcessingSheet(
                    selectedText = selectedText,
                    onAction = { action -> handleAction(action, selectedText) },
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun handleAction(action: TextAction, text: String) {
        when (action) {
            TextAction.ADD_TO_CALENDAR -> postToCalendar(text)
            TextAction.ADD_TO_TASK -> postToTask(text)
            TextAction.EXAMINE -> launchChatWithPrompt("Examine and analyze the following text in detail:\n\n$text")
            TextAction.ASK -> launchChatWithPrompt(text)
            TextAction.SUMMARIZE -> launchChatWithPrompt("Summarize the following text concisely:\n\n$text")
        }
    }

    /**
     * POST to DeviceApiServer /calendar/create with the selected text.
     * Sends the text as the event title with a 1-hour duration starting now.
     */
    private fun postToCalendar(text: String) {
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                // Get the first calendar ID available
                val calendarId = fetchFirstCalendarId()
                if (calendarId == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@TextProcessingActivity,
                            "No calendar found. Open mK:a to configure.",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                    return@launch
                }

                val now = System.currentTimeMillis()
                val oneHourLater = now + 3600000L

                val json = JSONObject().apply {
                    put("calendar_id", calendarId)
                    put("title", text.take(200))
                    put("description", if (text.length > 200) text else "")
                    put("start_time", now)
                    put("end_time", oneHourLater)
                    put("all_day", false)
                }

                val body = json.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$DEVICE_API_BASE/calendar/create")
                    .post(body)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()
                val success = responseBody?.let {
                    JSONObject(it).optBoolean("success", false)
                } ?: false

                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(
                            this@TextProcessingActivity,
                            "Added to calendar",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@TextProcessingActivity,
                            "Failed to add to calendar",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@TextProcessingActivity,
                        "Calendar error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        }
    }

    /**
     * Fetch the first available calendar ID from the DeviceApiServer.
     */
    private fun fetchFirstCalendarId(): Long? {
        return try {
            val request = Request.Builder()
                .url("$DEVICE_API_BASE/calendar/list")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val calendars = json.optJSONArray("calendars") ?: return null

            if (calendars.length() > 0) {
                calendars.getJSONObject(0).optLong("id", -1).takeIf { it > 0 }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * POST to DeviceApiServer /tasks/create with the selected text as the task summary.
     */
    private fun postToTask(text: String) {
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("summary", text.take(500))
                    put("description", if (text.length > 500) text else "")
                    put("status", "NEEDS-ACTION")
                }

                val body = json.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$DEVICE_API_BASE/tasks/create")
                    .post(body)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()
                val success = responseBody?.let {
                    JSONObject(it).optBoolean("success", false)
                } ?: false

                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(
                            this@TextProcessingActivity,
                            "Added to tasks",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@TextProcessingActivity,
                            "Failed to add task",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@TextProcessingActivity,
                        "Task error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        }
    }

    /**
     * Launch mK:a's main chat with a pre-filled message for the orchestrator.
     * The message is passed via an Intent extra that MainActivity/ChatViewModel can pick up.
     */
    private fun launchChatWithPrompt(prompt: String) {
        val intent = Intent(this, com.mobilekinetic.agent.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PREFILLED_MESSAGE, prompt)
        }
        startActivity(intent)
        Toast.makeText(this, "Sending to mK:a", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        httpClient.dispatcher.executorService.shutdown()
    }
}

/**
 * Enumeration of available text processing actions.
 */
enum class TextAction(val label: String) {
    ADD_TO_CALENDAR("Add to Calendar"),
    ADD_TO_TASK("Add to Task"),
    EXAMINE("Examine Text"),
    ASK("Ask mK:a"),
    SUMMARIZE("Summarize")
}

/**
 * Compose bottom sheet UI for text processing action selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextProcessingSheet(
    selectedText: String,
    onAction: (TextAction) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var showSheet by remember { mutableStateOf(true) }

    // Transparent background that fills the screen
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) {
                showSheet = false
                onDismiss()
            }
    ) {
        if (showSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    showSheet = false
                    onDismiss()
                },
                sheetState = sheetState,
                containerColor = LcarsBlack,
                contentColor = LcarsTextPrimary,
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(LcarsTextSecondary)
                    )
                }
            ) {
                BottomSheetContent(
                    selectedText = selectedText,
                    onAction = { action ->
                        scope.launch {
                            sheetState.hide()
                            showSheet = false
                            onAction(action)
                        }
                    }
                )
            }
        }
    }
}

/**
 * Content of the bottom sheet: title, text preview, divider, and action rows.
 */
@Composable
private fun BottomSheetContent(
    selectedText: String,
    onAction: (TextAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
    ) {
        // Title
        Text(
            text = "Process Text",
            color = LcarsOrange,
            fontSize = 18.sp,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )

        // Selected text preview
        Text(
            text = selectedText,
            color = LcarsTextBody,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        HorizontalDivider(
            color = LcarsContainerGray,
            thickness = 1.dp
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Action rows
        TextAction.entries.forEach { action ->
            ActionRow(
                label = action.label,
                onClick = { onAction(action) }
            )
        }
    }
}

/**
 * A single clickable action row in the bottom sheet.
 */
@Composable
private fun ActionRow(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Text(
            text = label,
            color = LcarsTextPrimary,
            fontSize = 16.sp,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
