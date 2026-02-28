plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mobilekinetic.agent"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.mobilekinetic.agent"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        noCompress += "model"
        noCompress += "tflite"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.material3)
    implementation(libs.material3.adaptive)
    implementation(libs.material3.adaptive.layout)
    implementation(libs.material3.adaptive.navigation)
    implementation(libs.material3.adaptive.navigation.suite)
    implementation(libs.material.icons.extended)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.nanohttpd)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.ktor.client.cio)
    implementation(libs.sqlcipher)
    implementation(libs.biometric)
    implementation(libs.window)
    implementation(libs.window.core)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.play.services.location)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.lifecycle.process)
    implementation(libs.work.runtime.ktx)
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.litert)
    implementation(libs.onnxruntime)
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.hierynomus:smbj:0.13.0")

    // Internal modules
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))
    implementation(project(":shared"))

    debugImplementation(libs.compose.ui.tooling)
}
