import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

// FASE 6: inyección de los Client IDs de Google (login con Google) SIN commitearlos.
// Se leen de `secrets.properties` (gitignored) en tiempo de configuración y se
// generan como un objeto Kotlin en `commonMain`. Se genera a `commonMain` —y no vía
// BuildConfig— porque BuildConfig es exclusivo de Android y no llegaría a `iosMain`.
// Si el archivo falta o un valor está vacío, se generan cadenas vacías y el login
// con Google falla de forma controlada (la UI ofrece email).
val secretsProps = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Capturamos solo Strings (no el objeto Properties) para no romper el configuration cache.
val googleWebClientId: String = secretsProps.getProperty("GOOGLE_WEB_CLIENT_ID", "")
val googleIosClientId: String = secretsProps.getProperty("GOOGLE_IOS_CLIENT_ID", "")

val generateSecrets by tasks.registering {
    // Copias locales: el `doLast` captura estos vals (no las propiedades a nivel de
    // script), condición para que el configuration cache pueda serializar la tarea.
    val outDir = layout.buildDirectory.dir("generated/secrets/kotlin")
    val webId = googleWebClientId
    val iosId = googleIosClientId
    // Declarar los valores como inputs → Gradle regenera solo cuando cambian.
    inputs.property("googleWebClientId", webId)
    inputs.property("googleIosClientId", iosId)
    outputs.dir(outDir)
    doLast {
        val pkgDir = outDir.get().asFile.resolve("com/example/kortexgames/data/remote")
        pkgDir.mkdirs()
        pkgDir.resolve("Secrets.kt").writeText(
            """
            package com.example.kortexgames.data.remote

            /**
             * GENERADO por la tarea Gradle `generateSecrets` desde `secrets.properties`.
             * NO editar a mano ni commitear. El "porqué" de cada valor está en el KDoc
             * público de [SupabaseConfig], que reexporta estas constantes.
             */
            internal object Secrets {
                const val GOOGLE_WEB_CLIENT_ID: String = "$webId"
                const val GOOGLE_IOS_CLIENT_ID: String = "$iosId"
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    androidLibrary {
       namespace = "com.example.kortexgames.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            // FASE 3: driver SQLite Android + motor Ktor para Supabase
            implementation(libs.sqldelight.android.driver)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
            // FASE 6: login con Google nativo (Credential Manager + Google Identity)
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.playServicesAuth)
            implementation(libs.google.googleid)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            // --- FASE 5: navegación multiplataforma (Scaffold + BottomNav) ---
            implementation(libs.navigation.compose)
            // --- FASE 5: iconos vectoriales Material (sin emojis como iconos) ---
            implementation(libs.compose.materialIconsExtended)

            // --- FASE 3 ---
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.androidx.datastore.preferences.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.auth)
            implementation(libs.supabase.functions)
        }
        iosMain.dependencies {
            // FASE 3: driver SQLite nativo + motor Ktor Darwin
            implementation(libs.sqldelight.native.driver)
            implementation(libs.ktor.client.darwin)
            // FASE 6: cliente Ktor para canjear el código OAuth de Google por un
            // ID token (login con Google en iOS vía ASWebAuthenticationSession).
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        // FASE 6: sumar el objeto `Secrets` generado (Client IDs de Google) a las
        // fuentes de commonMain. Pasar el TaskProvider hace que la compilación Kotlin
        // dependa de `generateSecrets` automáticamente (Android e iOS).
        commonMain.configure { kotlin.srcDir(generateSecrets) }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

// FASE 3: genera la base de datos local a partir de shared/.../sqldelight/**.sq
sqldelight {
    databases {
        create("LogicGamesDb") {
            packageName.set("com.example.kortexgames.data.local.db")
        }
    }
}