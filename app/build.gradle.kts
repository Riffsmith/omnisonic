plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineProfile)
}

android {
    namespace = "app.omnisonic"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.omnisonic"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/AL2.0"
            excludes += "/META-INF/LGPL2.1"
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.useJUnitPlatform() }
        }
    }
}

dependencies {
    baselineProfile(project(":baseline-profile"))
    implementation(project(":core-data"))
    implementation(project(":core-network"))
    implementation(project(":playback"))
    implementation(project(":playback-api"))
    implementation(project(":provider-api"))
    implementation(project(":provider-navidrome"))
    implementation(project(":provider-local"))
    implementation(project(":style"))
    implementation(project(":feature-library"))
    implementation(project(":feature-search"))
    implementation(project(":feature-downloads"))
    implementation(project(":feature-profiles"))
    implementation(project(":feature-settings"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.media3.session)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.navigation)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.moshi)
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    // core-data keeps room as `implementation`; the wiring test opens the DB.
    testImplementation(libs.room.runtime)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
