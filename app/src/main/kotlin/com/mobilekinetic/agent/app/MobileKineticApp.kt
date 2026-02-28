package com.mobilekinetic.agent.app

import android.app.Application
import com.mobilekinetic.agent.data.preferences.SettingsRepository
import com.mobilekinetic.agent.data.tts.TtsManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class MobileKineticApp : Application() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        TtsManager.init(this)

        // Phase 2C: Start config sync — writes ~/user_config.json whenever preferences change
        val homeDir = File(filesDir, "home")
        settingsRepository.startConfigSync(homeDir, applicationScope)
    }
}
