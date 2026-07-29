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
// Ad units REALES de AdMob. Vacías mientras no existan: `AdMobConfig` cae entonces a
// las de prueba, así que un clon del repo sin `secrets.properties` compila y funciona.
val admobInterstitialUnitId: String = secretsProps.getProperty("ADMOB_INTERSTITIAL_UNIT_ID", "")
val admobRewardedUnitId: String = secretsProps.getProperty("ADMOB_REWARDED_UNIT_ID", "")

val generateSecrets by tasks.registering {
    // Copias locales: el `doLast` captura estos vals (no las propiedades a nivel de
    // script), condición para que el configuration cache pueda serializar la tarea.
    val outDir = layout.buildDirectory.dir("generated/secrets/kotlin")
    val webId = googleWebClientId
    val iosId = googleIosClientId
    val interstitialId = admobInterstitialUnitId
    val rewardedId = admobRewardedUnitId
    // Declarar los valores como inputs → Gradle regenera solo cuando cambian.
    inputs.property("googleWebClientId", webId)
    inputs.property("googleIosClientId", iosId)
    inputs.property("admobInterstitialUnitId", interstitialId)
    inputs.property("admobRewardedUnitId", rewardedId)
    outputs.dir(outDir)
    doLast {
        val pkgDir = outDir.get().asFile.resolve("com/kortexgames/app/data/remote")
        pkgDir.mkdirs()
        pkgDir.resolve("Secrets.kt").writeText(
            """
            package com.kortexgames.app.data.remote

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

        // Los ad units van en su propio objeto y en el paquete de `core.ads` (no en
        // [Secrets], que es de auth/remoto) para que `AdMobConfig` los lea sin import
        // y no se mezclen dos dominios distintos en un mismo "cajón de secretos".
        val adsPkgDir = outDir.get().asFile.resolve("com/kortexgames/app/core/ads")
        adsPkgDir.mkdirs()
        adsPkgDir.resolve("AdMobSecrets.kt").writeText(
            """
            package com.kortexgames.app.core.ads

            /**
             * GENERADO por la tarea Gradle `generateSecrets` desde `secrets.properties`.
             * NO editar a mano ni commitear.
             *
             * Cadena vacía = "no configurado": [AdMobConfig] lo interpreta como que aún
             * no hay unidad real y usa la de prueba. El porqué de esa política está en
             * el KDoc de [AdMobConfig].
             */
            internal object AdMobSecrets {
                const val INTERSTITIAL_UNIT_ID: String = "$interstitialId"
                const val REWARDED_UNIT_ID: String = "$rewardedId"
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
       namespace = "com.kortexgames.app.shared"
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
            // Back del sistema (PlatformBackHandler.android.kt): el BackHandler común
            // de Compose Multiplatform no publica variante Android (ver su KDoc).
            implementation(libs.androidx.activity.compose)
            // FASE 3: driver SQLite Android + motor Ktor para Supabase
            implementation(libs.sqldelight.android.driver)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
            // FASE 6: login con Google nativo (Credential Manager + Google Identity)
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.playServicesAuth)
            implementation(libs.google.googleid)
            // Anuncios: SDK de AdMob (presentadores reales en core/ads *.android.kt).
            // Su AAR aporta al merge del manifest lo que el SDK necesita; el App ID
            // va como meta-data en el manifest de androidApp.
            implementation(libs.play.services.ads)
            // Consentimiento GDPR/EEA (UMP): formulario previo al primer anuncio.
            implementation(libs.user.messaging.platform)
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
            packageName.set("com.kortexgames.app.data.local.db")
        }
    }
}