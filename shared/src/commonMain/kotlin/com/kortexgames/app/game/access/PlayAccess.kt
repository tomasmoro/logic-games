package com.kortexgames.app.game.access

/**
 * Partidas gratis por día y por juego premium antes de pedir un anuncio.
 *
 * Es por juego y no un cupo global: quien agota Hexa Orbit todavía tiene sus 5
 * partidas de Quantum Merge. Un cupo compartido castigaría justo la variedad que
 * queremos fomentar entre los juegos premium.
 */
const val FREE_DAILY_PLAYS: Int = 5

/**
 * Qué necesita el jugador para empezar **una partida más** de un juego. Lo calcula
 * [resolvePlayAccess]; la UI solo lo pinta (contador, rótulo del CTA) y
 * [PlayQuotaManager.requestPlay] es quien lo hace cumplir.
 */
sealed interface PlayAccess {

    /**
     * Sin límite: el juego no es premium, el jugador es premium, o la partida no
     * pertenece al cupo (torneo, bienvenida de primera apertura). La UI no muestra
     * ningún contador.
     */
    data object Unlimited : PlayAccess

    /**
     * Quedan partidas gratis hoy; empezar una consume una.
     *
     * @property remaining partidas gratis que quedan hoy (siempre ≥ 1).
     * @property limit cupo diario total, para pintar "3 de 5".
     */
    data class Free(val remaining: Int, val limit: Int) : PlayAccess

    /**
     * Cupo agotado: la siguiente partida cuesta un anuncio recompensado (una
     * partida por anuncio, sin tope diario). Es un trato opcional: el jugador
     * siempre puede no aceptarlo y salir.
     */
    data object NeedsAd : PlayAccess
}

/**
 * Política de acceso pura (sin reloj ni almacenamiento, para poder testearla):
 * decide qué [PlayAccess] corresponde a un juego según el plan del jugador y las
 * partidas gratis que ya gastó hoy.
 *
 * El orden de las comprobaciones importa: "no hay cupo que aplicar" (juego libre,
 * premium, partida exenta) gana siempre a lo que diga el contador, para que un
 * contador heredado de antes de hacerse premium nunca le pida un anuncio.
 *
 * @param isPremiumGame el juego está marcado premium en el catálogo.
 * @param isPremiumUser el jugador tiene el plan premium vigente.
 * @param exempt la partida no cuenta para el cupo: modo torneo (tiene sus propios
 *        intentos por anuncio, migración 0057) o bienvenida de primera apertura
 *        (todavía no se puede pedir un anuncio: falta el consentimiento UMP/ATT).
 * @param usedToday partidas gratis ya empezadas hoy en este juego.
 * @param limit cupo diario ([FREE_DAILY_PLAYS] por defecto).
 */
fun resolvePlayAccess(
    isPremiumGame: Boolean,
    isPremiumUser: Boolean,
    exempt: Boolean,
    usedToday: Int,
    limit: Int = FREE_DAILY_PLAYS,
): PlayAccess {
    if (!isPremiumGame || isPremiumUser || exempt) return PlayAccess.Unlimited
    val remaining = limit - usedToday
    return if (remaining > 0) PlayAccess.Free(remaining, limit) else PlayAccess.NeedsAd
}
