plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.gnslno1.shipnavi"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.gnslno1.shipnavi"
        minSdk = 30          // Wear OS 3 (갤럭시 워치4 이상)
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-test"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.wear.compose:compose-material:1.7.1")
    implementation("androidx.wear.compose:compose-foundation:1.7.1")

    testImplementation("junit:junit:4.13.2")
}
