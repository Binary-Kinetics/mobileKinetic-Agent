package com.mobilekinetic.agent.device.api

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.Data

class DeviceApiWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "DeviceApiWorker"
    }

    override fun doWork(): Result {
        val taskName = inputData.getString("task_name") ?: "unknown"
        Log.d(TAG, "Executing work task: $taskName")

        return try {
            // Generic worker - task_name can be used to dispatch to specific logic
            when (taskName) {
                // Add specific task implementations here as needed
                else -> {
                    Log.d(TAG, "Completed generic task: $taskName")
                }
            }
            Result.success(
                Data.Builder()
                    .putString("task_name", taskName)
                    .putLong("completed_at", System.currentTimeMillis())
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Work task failed: $taskName", e)
            Result.failure(
                Data.Builder()
                    .putString("task_name", taskName)
                    .putString("error", e.message ?: "Unknown error")
                    .build()
            )
        }
    }
}
