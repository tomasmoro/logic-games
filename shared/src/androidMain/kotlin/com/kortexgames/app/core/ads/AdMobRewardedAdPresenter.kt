package com.kortexgames.app.core.ads

import android.content.Context
import com.kortexgames.app.data.remote.auth.CurrentActivityHolder
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Presentador de **anuncios recompensados real** con AdMob (revivir con una vida,
 * revelar una pista, etc.). Carga bajo demanda y suspende hasta conocer el desenlace.
 *
 * Mapea el resultado del SDK a [RewardResult] (el contrato que decide si se concede la
 * recompensa):
 *  - vio el anuncio completo (se disparó `onUserEarnedReward`) → [RewardResult.EARNED];
 *  - lo cerró antes de terminar → [RewardResult.DISMISSED] (no se concede);
 *  - no cargó / no había Activity / falló al mostrar → [RewardResult.UNAVAILABLE].
 *
 * Mismos detalles que [AdMobInterstitialAdPresenter]: todo el ciclo del SDK corre en el
 * hilo principal (`withContext(Dispatchers.Main)`) y la presentación usa la Activity de
 * [CurrentActivityHolder].
 *
 * @param appContext contexto de aplicación (la carga no requiere Activity).
 * @param adUnitId unidad de anuncio recompensado (ver [AdMobConfig]).
 */
internal class AdMobRewardedAdPresenter(
    private val appContext: Context,
    private val adUnitId: String,
) : RewardedAdPresenter {

    override suspend fun show(): RewardResult = withContext(Dispatchers.Main) {
        val activity = CurrentActivityHolder.activity ?: return@withContext RewardResult.UNAVAILABLE
        suspendCancellableCoroutine { cont ->
            RewardedAd.load(
                appContext,
                adUnitId,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        if (cont.isActive) cont.resume(RewardResult.UNAVAILABLE)
                    }

                    override fun onAdLoaded(ad: RewardedAd) {
                        // La recompensa solo se concede si el usuario ganó el premio ANTES
                        // de cerrar; el desenlace se resuelve al descartarse el anuncio.
                        var earned = false
                        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                            override fun onAdDismissedFullScreenContent() {
                                if (cont.isActive) {
                                    cont.resume(if (earned) RewardResult.EARNED else RewardResult.DISMISSED)
                                }
                            }

                            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                                if (cont.isActive) cont.resume(RewardResult.UNAVAILABLE)
                            }
                        }
                        ad.show(activity) { earned = true }
                    }
                },
            )
        }
    }
}
