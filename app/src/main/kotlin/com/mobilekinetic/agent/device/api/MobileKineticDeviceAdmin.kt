package com.mobilekinetic.agent.device.api

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.util.Log
import android.widget.Toast

class MobileKineticDeviceAdmin : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "MobileKineticDeviceAdmin"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, MobileKineticDeviceAdmin::class.java)
        }

        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(getComponentName(context))
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled")
        Toast.makeText(context, "mK:a Device Admin enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device admin disabled")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling mK:a device admin will remove lock screen capabilities."
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val attempts = dpm.currentFailedPasswordAttempts
        Log.w(TAG, "Password failed. Total failed attempts: $attempts")
    }
}
