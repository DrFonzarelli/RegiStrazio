plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.registrazio"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.registrazio"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // Da AGP 8 `BuildConfig` non si genera più da solo. Serve per
        // `BuildConfig.DEBUG`, con cui il misuratore di scatti resta spento
        // nelle build di release.
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Compose (versioni gestite dal BOM)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.foundation:foundation")

    // Activity & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Stessa versione di lifecycle-runtime: moduli lifecycle disallineati
    // si trascinano dietro copie diverse di lifecycle-viewmodel.
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    // Esplicita anche se arriva da exoplayer: il DataSource che decifra MEGA
    // estende BaseDataSource, e dipenderne per via transitiva è fragile.
    implementation("androidx.media3:media3-datasource:1.2.0")
    implementation("androidx.media3:media3-session:1.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    // `Task.await()`: le API Firebase restituiscono Task, non funzioni
    // sospendibili. Senza questo modulo ogni chiamata andrebbe incartata a mano
    // in una `suspendCancellableCoroutine`, una volta per chiamata. Stessa
    // versione delle coroutines, o si tirano dietro due copie del runtime.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.1")

    // Firebase — dalla BOM 34 gli artefatti "-ktx" non esistono più:
    // le estensioni Kotlin sono dentro ai moduli principali.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)

    // Room DB
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // HTTP client per MEGA API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Identita cifrata persistente
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
