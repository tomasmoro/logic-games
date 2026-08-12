package com.kortexgames.app.core.ads

import com.kortexgames.app.core.audio.PlatformContext

/**
 * Arranca el flujo de **consentimiento de anuncios** de la plataforma (GDPR/UMP y,
 * en iOS, además el ATT de Apple) y, al resolverse, inicializa el SDK.
 *
 * ## Por qué es una llamada explícita y no parte del arranque
 * Antes esto ocurría nada más abrir la app (en `MainActivity.onCreate` y en
 * `iOSApp.swift`). Con la **bienvenida jugable** (ver
 * [com.kortexgames.app.game.FirstRunGames]) eso ya no vale por dos motivos, uno legal
 * y otro de producto:
 *
 *  - Durante la bienvenida no se pide ni un solo anuncio, así que resolver el
 *    consentimiento antes no aporta nada; y presentar el formulario en los primeros
 *    segundos de vida de la app —antes incluso de que el jugador sepa qué es— es la
 *    forma más rápida de perderlo.
 *  - El consentimiento **debe** estar resuelto antes de la primera petición de
 *    anuncio (política de AdMob y ePrivacy/RGPD en el EEE/RU). Al moverlo aquí, la
 *    app pasa de "se pide siempre al arrancar" a "se pide justo antes de que haga
 *    falta": cuando la primera apertura termina ([OnboardingGate.isFirstRunOver]).
 *
 * Es idempotente en ambas plataformas: llamarla de más no reinicializa el SDK.
 *
 * @param context contexto de plataforma (en Android envuelve el `Context` de la app;
 *        el formulario, que necesita una `Activity`, la busca el propio `actual`).
 */
expect suspend fun beginAdConsentFlow(context: PlatformContext)
