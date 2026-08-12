package com.kortexgames.app.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** `applicationId` de `androidApp` (ver androidApp/build.gradle.kts). */
private const val PACKAGE_NAME = "com.kortexgames.app"

/**
 * Genera el Baseline Profile: registra qué clases/métodos se ejecutan en el camino
 * crítico de arranque (proceso → [com.kortexgames.app.di.AppGraph] → splash → Home
 * pintada) para que ART los compile AOT en la instalación real, en vez de
 * interpretarlos/JIT-compilarlos en el primer arranque de cada usuario — la misma
 * ruta que mide [StartupBenchmark].
 *
 * El recorrido cubre SOLO lo que corre en (casi) todo arranque real —no el flujo de
 * un juego concreto—, porque el perfil es una lista de "qué merece la pena
 * precompilar", no una suite de cobertura de tests.
 *
 * `./gradlew :androidApp:generateBaselineProfile` ejecuta esto y vuelca el
 * resultado en `androidApp/src/release/generated/baselineProfiles/`: hay que
 * commitear ese fichero (lo consume `androidx.profileinstaller` en producción) y
 * regenerarlo cuando cambie de forma relevante el camino de arranque (AppGraph,
 * splash, Home).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generar() = rule.collect(packageName = PACKAGE_NAME) {
        startActivityAndWait()
        // Deja que la splash (~2.4 s, ver SplashScreen.kt) termine y la Home (o la
        // puerta de onboarding, en primera apertura) quede pintada: ese es el tramo
        // que vale la pena precompilar.
        device.waitForIdle()
    }
}
