plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.convoyage"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.convoyage"
        minSdk = 28
        targetSdk = 36
        // CI passes -PversionCode=<run number> and, on tags, -PversionName=<tag without v>.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"

        buildConfigField("String", "MAP_STYLE_URL", "\"https://tiles.openfreemap.org/styles/liberty\"")
    }

    // Server base URL, per build type; -PconvoyServerUrl=... overrides both.
    //   debug:   http://127.0.0.1:8090, which the emulator reaches through `adb reverse tcp:8090 tcp:8090`.
    //   release: placeholder below.
    // Example: ./gradlew assembleDebug -PconvoyServerUrl=http://192.168.1.20:8080
    val serverUrlOverride = project.findProperty("convoyServerUrl") as String?

    buildTypes {
        debug {
            buildConfigField("String", "SERVER_URL", "\"${serverUrlOverride ?: "http://127.0.0.1:8090"}\"")
        }
        release {
            // TODO(deploy): replace the placeholder with the real production API origin once it exists.
            buildConfigField("String", "SERVER_URL", "\"${serverUrlOverride ?: "https://api.convoyage.app"}\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
