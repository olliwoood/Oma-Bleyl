import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Gemini API-Key aus local.properties lesen (wird NICHT ins Git eingecheckt)
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localProps.load(localPropsFile.inputStream())
}
val geminiApiKey: String = localProps.getProperty("GEMINI_API_KEY", "")

android {
    namespace = "com.example.voicelauncher"
    compileSdk = 34

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release-keystore.jks")
            storePassword = "omableyl2024"
            keyAlias = "oma-bleyl"
            keyPassword = "omableyl2024"
        }
    }

    defaultConfig {
        applicationId = "com.example.voicelauncher"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "7.0"

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-text-google-fonts:1.6.2")

    // Room Database
    val room_version = "2.6.1"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    ksp("androidx.room:room-compiler:$room_version")

    // Networking (WebSockets for Gemini Live API)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JSON Parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // Location (Wetter Koordinaten)
    implementation("com.google.android.gms:play-services-location:21.1.0")
}
