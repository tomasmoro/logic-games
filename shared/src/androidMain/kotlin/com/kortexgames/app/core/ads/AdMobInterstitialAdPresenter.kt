package com.kortexgames.app.core.ads

import android.content.Context
import com.kortexgames.app.data.remote.auth.CurrentActivityHolder
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Presentador de **intersticiales real** con AdMob. Carga el anuncio bajo demanda y
 * suspende hasta que el usuario lo cierra (o hasta que falla la carga/presentación).
 *
 * Detalles clave:
 *  - **Hilo principal:** todo el ciclo del SDK (load/show/callbacks) debe correr en el
 *    main thread; por eso el cuerpo va en `withContext(Dispatchers.Main)`. El
 *    `AdManager` lo llama desde `Dispatchers.Default`, así que el cambio de hilo aquí
 *    es el que respeta el contrato del SDK.
 *  - **Activity:** para presentarse necesita una Activity en primer plano; la toma de
 *    [CurrentActivityHolder] (la misma que usa el login con Google). Sin Activity se
 *    resuelve como "no mostrado" (el intersticial solo interrumpe, no concede nada).
 *  - **Sin precarga (por ahora):** carga en cada `show()`. Para producción conviene
 *    precargar el siguiente anuncio y así quitar la latencia; ver BACKLOG.
 *
 * @param appContext contexto de aplicación (la **carga** no requiere Activity).
 * @param adUnitId unidad de anuncio intersticial (ver [AdMobConfig]).
 */
internal class AdMobInterstitialAdPresenter(
    private val appContext: Context,
    private val adUnitId: String,
) : InterstitialAdPresenter {

    override suspend fun show(): Unit = withContext(Dispatchers.Main) {
        val activity = CurrentActivityHolder.activity ?: return@withContext
        suspendCancellableCoroutine { cont ->
            InterstitialAd.load(
                appContext,
                adUnitId,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        // No disponible: seguimos sin interrumpir (resume normal).
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onAdLoaded(ad: InterstitialAd) {
                        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                            override fun onAdDismissedFullScreenContent() {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                                if (cont.isActive) cont.resume(Unit)
                            }
                        }
                        ad.show(activity)
                    }
                },
            )
        }
    }
}
