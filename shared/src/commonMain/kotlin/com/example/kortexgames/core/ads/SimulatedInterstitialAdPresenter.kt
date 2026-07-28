package com.example.kortexgames.core.ads

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Presentador de intersticiales **de desarrollo**: NO muestra ningún anuncio real;
 * solo simula la interrupción (una breve espera) y vuelve. Es el gemelo de
 * [SimulatedRewardedAdPresenter] para el intersticial.
 *
 * Vive en `commonMain` para poder probar end-to-end el modelo de tiempo del
 * [AdManager] (contar desde la entrada a la app → marcar pendiente → mostrar en el
 * breakpoint) sin depender todavía del SDK de AdMob por plataforma.
 *
 * > **Importante**: al integrar AdMob, registra el presentador real en su lugar
 * > (ver [AdManager.setInterstitialAdPresenter]); este objeto NO debe usarse en
 * > producción, porque no genera ningún ingreso (no hay anuncio de verdad).
 *
 * @param playbackDelay cuánto "dura" el intersticial simulado antes de volver.
 */
class SimulatedInterstitialAdPresenter(
    private val playbackDelay: Duration = 1500.milliseconds,
) : InterstitialAdPresenter {

    /** Simula mostrar el intersticial: espera y vuelve. */
    override suspend fun show() {
        delay(playbackDelay)
    }
}
