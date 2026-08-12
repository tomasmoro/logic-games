/**
 * Módulo de benchmarking instrumentado (`com.android.test`) para el arranque de
 * `androidApp`. No produce APK propio: mide el APK de `androidApp` desde fuera
 * (proceso separado), como haría un usuario real.
 *
 * Dos usos:
 *  1. `StartupBenchmark`: mide el tiempo de arranque en frío (`StartupTimingMetric`)
 *     con y sin Baseline Profile, para comparar y detectar regresiones.
 *  2. `BaselineProfileGenerator`: genera el fichero de perfil (rutas de código
 *     "calientes" del arranque) que ART precompila AOT en la instalación real, en
 *     vez de interpretarlas/JIT-compilarlas en cada arranque en frío.
 *
 * Requiere un dispositivo/emulador conectado (API 28+); no se ejecuta en este
 * entorno de desarrollo sin pantalla — ver README de la sección en CLAUDE.md.
 */
plugins {
    alias(libs.plugins.androidTest)
    // Sin plugin de Kotlin explícito: desde AGP 9 el soporte de Kotlin es nativo del
    // plugin de Android (igual que en androidApp/build.gradle.kts) — declararlo
    // aparte falla con "no longer required since AGP 9.0".
    alias(libs.plugins.baselineProfile)
}

android {
    namespace = "com.kortexgames.app.macrobenchmark"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // StartupTimingMetric y BaselineProfileRule exigen API 28+ (Macrobenchmark
        // usa `dumpsys` con formato que solo existe desde ahí). androidApp sigue
        // soportando minSdk 26: este módulo solo se ejecuta en desarrollo/CI, nunca
        // viaja al usuario final.
        minSdk = 28
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Este módulo mide `androidApp`; no tiene UI ni lógica propia.
    targetProjectPath = ":androidApp"
    // Requisito de Macrobenchmark: el proceso de test se "auto-instrumenta" para
    // poder medir métricas del proceso medido sin interferir con su ejecución.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // Espejo del build type `benchmark` de androidApp (ver androidApp/build.gradle.kts):
        // debe medirse una build optimizada como release, pero instalable sin la
        // firma real de publicación.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

// El plugin `androidx.baselineprofile` conecta este módulo con la tarea
// `:androidApp:generateBaselineProfile` (ejecuta `BaselineProfileGenerator` de
// abajo y vuelca el resultado en androidApp/src/release/generated/baselineProfiles/
// automáticamente). `useConnectedDevices = true`: usa el emulador/dispositivo ya
// conectado en vez de crear un Gradle Managed Device efímero — más simple en local;
// en CI se puede añadir un `managedDevices { ... }` sin tocar los tests.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.testExt.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
