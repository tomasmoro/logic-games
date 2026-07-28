import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.shared)

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.example.kortexgames"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.example.kortexgames"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        // AdMob App ID → meta-data del manifest (placeholder `admobAppId`). Por defecto
        // el App ID de PRUEBA de Google (público, seguro en dev); el real se inyecta
        // desde secrets.properties (ADMOB_APP_ID, gitignored) al publicar, sin commitearlo.
        val secretsFile = rootProject.file("secrets.properties")
        val admobAppId = if (secretsFile.exists()) {
            Properties()
                .apply { secretsFile.inputStream().use { load(it) } }
                .getProperty("ADMOB_APP_ID", "ca-app-pub-3940256099942544~3347511713")
        } else {
            "ca-app-pub-3940256099942544~3347511713"
        }
        manifestPlaceholders["admobAppId"] = admobAppId
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}