import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.xpertsavenue.ridevault.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.xpertsavenue.ridevault"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    // Release-Signatur nur lokal (für Play-Store-Upload oder eigene APKs): Datei keystore.properties mit storeFile, storePassword,
    // keyAlias, keyPassword neben settings.gradle.kts anlegen. Sie gehört nicht ins Repository. F-Droid signiert selbst.
    val keystoreFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) } }
    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // Bewusst ohne Verkleinern/Verschleiern: getestet wurde der unveränderte Code, und der Quellcode ist ohnehin offen.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    // Keine Google-signierte Abhängigkeitsliste im APK (F-Droid verlangt das; das Bundle bleibt für Play unverändert nutzbar)
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:device-api"))
    implementation(project(":core:ble"))
    implementation(project(":data"))
    implementation(project(":export"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.osmdroid)
    implementation(libs.androidx.health.connect)
    implementation(libs.material.kolor)
    implementation(project(":driver:bikevee"))
    implementation(project(":driver:igpsport"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
}
