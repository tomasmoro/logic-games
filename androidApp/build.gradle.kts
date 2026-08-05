import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/**
 * App ID de PRUEBA de AdMob (lo publica Google, es público: no es un secreto). Lo usa
 * `debug` siempre, y `release` solo como red de seguridad si aún no hay uno real.
 */
val TEST_ADMOB_APP_ID = "ca-app-pub-3940256099942544~3347511713"

/**
 * App ID real de AdMob, leído de `secrets.properties` (gitignored) para no commitearlo.
 * `null` mientras no esté configurado.
 */
val realAdmobAppId: String? = rootProject.file("secrets.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }
    ?.getProperty("ADMOB_APP_ID")
    ?.takeIf { it.isNotBlank() }

// Aviso —no error— al ensamblar un release sin App ID real: fallar el build bloquearía
// probar un release legítimamente (y hoy aún no existen los IDs), pero publicar con los
// de prueba significa no monetizar, así que tiene que verse. Se decide mirando las
// tareas pedidas porque es lo único consultable en configuración sin romper el
// configuration cache; por eso es una heurística y no una comprobación exacta.
val assemblesRelease = gradle.startParameter.taskNames.any { taskName ->
    val name = taskName.substringAfterLast(':')
    name.contains("release", ignoreCase = true) || name in setOf("build", "assemble", "bundle")
}
if (assemblesRelease && realAdmobAppId == null) {
    logger.warn(
        "AVISO AdMob: se está ensamblando RELEASE sin ADMOB_APP_ID en secrets.properties. " +
            "Se usará el App ID de PRUEBA y la app NO monetizará. Añade ADMOB_APP_ID, " +
            "ADMOB_INTERSTITIAL_UNIT_ID y ADMOB_REWARDED_UNIT_ID antes de publicar.",
    )
}

/**
 * Datos del keystore de firma de `release`, leídos de `keystore.properties` (gitignored).
 * `null` mientras no exista: en ese caso `release` se firma con la clave de **debug**
 * para que se pueda instalar y probar en un dispositivo, que es justo lo que AGP no hace
 * por defecto (de ahí el error "cannot be signed ... specify a signing configuration").
 *
 * Un APK firmado con la clave de debug NO sirve para publicar —Play rechaza la subida—,
 * así que el fallback no puede colarse a producción por descuido.
 *
 * Formato esperado de `keystore.properties` (en la raíz del repo):
 * ```
 * storeFile=/ruta/absoluta/kortexgames-upload.jks
 * storePassword=...
 * keyAlias=upload
 * keyPassword=...
 * ```
 */
val keystoreProps: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

/**
 * Motivo por el que NO se puede firmar con la clave real, o `null` si la config está
 * completa. Se valida aquí —y no se deja fallar a AGP— porque un `keystore.properties`
 * a medias produce errores crípticos en mitad del empaquetado ("keystore password was
 * incorrect", rutas nulas) en vez de decir qué falta.
 */
val signingProblem: String? = run {
    val props = keystoreProps ?: return@run "no existe keystore.properties"
    val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .filter { props.getProperty(it).isNullOrBlank() }
    val storeFile = props.getProperty("storeFile").orEmpty()
    when {
        missing.isNotEmpty() -> "keystore.properties no tiene valor para $missing"
        // `file()` resuelve relativo a androidApp/, no a la raíz: un .jks en la raíz del
        // repo se referencia como `../nombre.jks`. Es el error de ruta más habitual.
        !file(storeFile).exists() -> "el storeFile de keystore.properties no existe: $storeFile"
        else -> null
    }
}

if (assemblesRelease && signingProblem != null) {
    logger.warn(
        "AVISO firma: $signingProblem. El release se firmará con la clave de DEBUG: sirve " +
            "para instalar y probar en un dispositivo, pero Play rechazará esta build.",
    )
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
    namespace = "com.kortexgames.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.kortexgames.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 4
        versionName = "1.0.3"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        // Solo se registra si la config está completa y válida; si no, `release` cae a la
        // clave de debug (ver `signingProblem`).
        keystoreProps?.takeIf { signingProblem == null }?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        // El App ID de AdMob se inyecta como meta-data del manifest (placeholder
        // `admobAppId`); sin él `MobileAds.initialize` crashea. Se decide por tipo de
        // build, en pareja con las ad units que resuelve `AdMobConfig`: así `debug`
        // nunca toca la app real de AdMob y `release` nunca sale con datos de prueba,
        // sin depender de que nadie se acuerde de cambiar nada antes de publicar.
        getByName("debug") {
            manifestPlaceholders["admobAppId"] = TEST_ADMOB_APP_ID
        }
        getByName("release") {
            isMinifyEnabled = false
            manifestPlaceholders["admobAppId"] = realAdmobAppId ?: TEST_ADMOB_APP_ID
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            // El AAB trae .so nativos de dependencias (Compose, DataStore), no de código
            // propio. SYMBOL_TABLE hace que AGP genere el zip de símbolos y lo empaquete
            // DENTRO del propio bundle en `bundleRelease` (nada que subir a mano en Play
            // Console); solo agrega metadata de depuración, no cambia el binario que corre
            // en el dispositivo. Silencia el aviso de Play "código nativo sin símbolos".
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}