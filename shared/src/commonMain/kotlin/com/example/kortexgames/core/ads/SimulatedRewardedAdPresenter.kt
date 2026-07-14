package com.example.kortexgames.core.ads

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Presentador de anuncios recompensados **de desarrollo**: NO muestra ningún anuncio
 * real; simplemente simula la reproducción (una breve espera) y concede la recompensa.
 *
 * Es un **placeholder** hasta integrar el SDK de AdMob en cada plataforma
 * (`androidMain` / `iosMain`) con su `RewardedAd` real. Vive en `commonMain` para que
 * el flujo completo de "revivir viendo un anuncio" ([RewardResult.EARNED] → +1 vida)
 * sea probable en desarrollo sin depender todavía del SDK.
 *
 * > **Importante**: al integrar AdMob, registra el presentador real en su lugar
 * > (ver [AdManager.setRewardedAdPresenter]); este objeto NO debe usarse en producción,
 * > porque regala la recompensa sin mostrar publicidad (ingreso perdido).
 *
 * @param playbackDelay cuánto "dura" el anuncio simulado antes de conceder la recompensa.
 */
class SimulatedRewardedAdPresenter(
    private val playbackDelay: Duration = 1500.milliseconds,
) : RewardedAdPresenter {

    /** Simula ver el anuncio completo: espera y concede siempre la recompensa. */
    override suspend fun show(): RewardResult {
        delay(playbackDelay)
        return RewardResult.EARNED
    }
}
