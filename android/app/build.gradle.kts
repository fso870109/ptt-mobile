plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.ptt.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.ptt.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.4.0"
    }

    val ksPath = System.getenv("GEELO_KEYSTORE_FILE")
    val ksPass = System.getenv("GEELO_KEYSTORE_PASSWORD")
    val keyAliasEnv = System.getenv("GEELO_KEY_ALIAS")
    val keyPass = System.getenv("GEELO_KEY_PASSWORD")
    val canSign = !ksPath.isNullOrBlank() &&
        !ksPass.isNullOrBlank() &&
        !keyAliasEnv.isNullOrBlank() &&
        !keyPass.isNullOrBlank() &&
        file(ksPath).isFile

    if (canSign) {
        signingConfigs {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = keyAliasEnv
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (canSign) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media:media:1.7.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
