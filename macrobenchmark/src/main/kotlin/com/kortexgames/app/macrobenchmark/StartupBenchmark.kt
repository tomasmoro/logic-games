package com.kortexgames.app.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** `applicationId` de `androidApp` (ver androidApp/build.gradle.kts). */
private const val PACKAGE_NAME = "com.kortexgames.app"

/**
 * Mide el arranque en frío de `androidApp` en tres compilaciones, para cuantificar
 * el efecto real del Baseline Profile en vez de asumirlo:
 *  - [startupSinPerfil]: sin AOT — el peor caso, un desinstalar/instalar limpio.
 *  - [startupConBaselineProfile]: lo que instala un usuario real tras actualizar
 *    (ART ya compiló AOT las rutas del perfil en `generateBaselineProfile`).
 *  - [startupCompilacionCompleta]: mejor caso teórico (todo AOT) — solo de
 *    referencia, Play NO instala así (sirve para ver cuánto margen queda).
 *
 * Corre con `./gradlew :macrobenchmark:connectedBenchmarkAndroidTest` sobre un
 * dispositivo/emulador API 28+ conectado. `iterations = 10`: por debajo de eso el
 * ruido (caché de página fría, scheduler) domina sobre la señal real.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupSinPerfil() = startup(CompilationMode.None())

    @Test
    fun startupConBaselineProfile() =
        startup(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    @Test
    fun startupCompilacionCompleta() = startup(CompilationMode.Full())

    private fun startup(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
        compilationMode = compilationMode,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
