plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mobilekinetic.agent.terminal.view"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":terminal-emulator"))
    implementation("androidx.annotation:annotation:1.9.1")
}
