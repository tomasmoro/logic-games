package com.kortexgames.app.core.review

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * # Cuándo invitar a valorar la app
 *
 * Decide si toca mostrar el diálogo que propone dejar una opinión en la tienda.
 *
 * ## Por qué no se pregunta cuanto antes
 *
 * Una valoración es un juicio sobre el producto, y quien lleva dos partidas todavía
 * no lo ha visto: no conoce el catálogo, no tiene racha ni marcas que superar, y lo
 * que puntuaría es una primera impresión. Peor aún, la nota de la ficha es
 * acumulativa y prácticamente irreversible en la práctica —nadie vuelve a corregir
 * una estrella—, así que preguntar pronto no solo convierte mal: fija por escrito la
 * opinión de alguien que aún no tenía una. Por eso el primer aviso espera a
 * [FIRST_OFFER_GAMES] partidas.
 *
 * ## Por qué como mucho dos veces, y espaciadas
 *
 * Es la misma disciplina de la antesala de notificaciones
 * ([com.kortexgames.app.core.notifications.NotificationPrimingPolicy]): un "ahora no"
 * es una respuesta, no una invitación a repetir la pregunta en la pantalla
 * siguiente. Se permite una segunda oferta —el jugador que ha vuelto muchas veces
 * más sí puede haber cambiado de idea— con dos condiciones: mucho más juego a sus
 * espaldas ([SECOND_OFFER_GAMES]) y al menos [COOLDOWN] de distancia. Una tercera no
 * existe.
 *
 * Es una función pura para poder fijar las reglas en tests sin simulador.
 */
class ReviewPromptPolicy {

    /**
     * ¿Invitar ahora a valorar?
     *
     * @param storeAvailable hay ficha de tienda a la que enviar al jugador (ver
     *   [storeReviewLink]). En iOS es false mientras la app no esté en la App Store,
     *   y entonces esta política no muestra nada.
     * @param gamesPlayed partidas registradas en el historial local.
     * @param timesDismissed veces que el usuario ya respondió "ahora no".
     * @param hasRated el usuario ya aceptó ir a la tienda alguna vez: se da por
     *   valorada y no se le vuelve a pedir jamás (ver [ReviewPromptState.hasRated]).
     * @param lastPromptAt cuándo respondió a la invitación por última vez, o null si
     *   nunca se le ha preguntado.
     * @param now instante actual (inyectable en tests).
     */
    fun shouldPrompt(
        storeAvailable: Boolean,
        gamesPlayed: Int,
        timesDismissed: Int,
        hasRated: Boolean,
        lastPromptAt: Instant?,
        now: Instant,
    ): Boolean {
        if (!storeAvailable) return false
        if (hasRated) return false
        if (timesDismissed >= MAX_ATTEMPTS) return false
        // El respiro se mide desde la RESPUESTA y no desde que el diálogo apareció en
        // pantalla, porque esta decisión se evalúa de forma reactiva: anotar "ya se
        // mostró" al pintarlo volvería falsa la condición al instante y el diálogo se
        // desvanecería delante del jugador. Marcar solo al responder tiene además una
        // consecuencia deliberada: si cierra la app con la invitación abierta, sin
        // contestar, la volverá a encontrar — no se le gasta una de las dos balas por
        // algo que no llegó a decidir.
        if (lastPromptAt != null && now - lastPromptAt < COOLDOWN) return false
        val threshold = if (timesDismissed == 0) FIRST_OFFER_GAMES else SECOND_OFFER_GAMES
        return gamesPlayed >= threshold
    }

    companion object {
        /** Como mucho dos ofertas: dos "ahora no" son un no. */
        const val MAX_ATTEMPTS = 2

        /**
         * Primera oferta: con diez partidas el jugador ya ha visto varios juegos, ha
         * completado alguna misión diaria y tiene marcas propias — hay una opinión
         * formada que valga la pena pedir.
         */
        const val FIRST_OFFER_GAMES = 10

        /**
         * Segunda (y última) oferta: reservada a quien ha seguido volviendo mucho
         * después de decir "ahora no".
         */
        const val SECOND_OFFER_GAMES = 40

        /** Respiro mínimo entre dos invitaciones. */
        val COOLDOWN: Duration = 30.days
    }
}
