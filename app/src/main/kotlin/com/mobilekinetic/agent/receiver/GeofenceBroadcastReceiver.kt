package com.mobilekinetic.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "GeofenceBroadcastRx"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
            Log.e(TAG, "Null geofencing event")
            return
        }
        if (geofencingEvent.hasError()) {
            Log.e(TAG, "Geofencing error: ${geofencingEvent.errorCode}")
            return
        }

        val transitionType = geofencingEvent.geofenceTransition
        val transitionName = when (transitionType) {
            com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_ENTER -> "enter"
            com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_EXIT -> "exit"
            com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_DWELL -> "dwell"
            else -> "unknown"
        }

        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: emptyList()
        val ids = triggeringGeofences.map { it.requestId }

        Log.i(TAG, "Geofence transition: $transitionName for IDs: $ids")
    }
}
