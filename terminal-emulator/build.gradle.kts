plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mobilekinetic.agent.terminal.emulator"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
}

