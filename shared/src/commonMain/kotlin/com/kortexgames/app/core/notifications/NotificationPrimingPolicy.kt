package com.kortexgames.app.core.notifications

/**
 * # Cuándo ofrecer los recordatorios
 *
 * Decide si toca mostrar la **antesala de permiso** (el paso propio de la app que
 * explica qué gana el usuario antes de que aparezca el diálogo del sistema).
 *
 * ## Por qué existe esta antesala
 *
 * El permiso de notificaciones es de una sola oportunidad real: iOS solo muestra su
 * diálogo la primera vez y Android deja de mostrarlo tras dos negativas. Lanzarlo a
 * pelo significa que un "no" —el reflejo por defecto de casi todo el mundo ante un
 * diálogo que no ha pedido— cierra la puerta para siempre. Con una antesala propia,
 * un "ahora no" no consume nada: el diálogo del sistema solo se muestra a quien ya
 * ha dicho que sí, así que la conversión del diálogo irrepetible es altísima y se
 * puede volver a ofrecer más adelante.
 *
 * ## Por qué después de la primera partida y no en el onboarding
 *
 * Al terminar el onboarding el usuario todavía no ha visto nada: no sabe qué es la
 * racha, ni la misión diaria, ni tiene récords que defender, así que la oferta habla
 * de cosas que para él aún no existen. Tras su primera partida sí: acaba de ver su
 * resultado y su progreso, y la oferta es concreta.
 *
 * Es una función pura para poder fijar estas reglas en tests sin simulador, igual
 * que [NotificationPlanner].
 */
class NotificationPrimingPolicy {

    /**
     * ¿Mostrar ahora la antesala?
     *
     * @param gamesPlayed partidas registradas en el historial local.
     * @param timesDeclined veces que el usuario ya respondió "ahora no".
     * @param permissionAlreadyAsked true si el diálogo del sistema ya se mostró
     *   alguna vez (aceptado o denegado): a partir de ahí insistir no aporta nada,
     *   porque el sistema ya no lo volverá a mostrar.
     */
    fun shouldPrime(
        gamesPlayed: Int,
        timesDeclined: Int,
        permissionAlreadyAsked: Boolean,
    ): Boolean {
        if (permissionAlreadyAsked) return false
        if (timesDeclined >= MAX_ATTEMPTS) return false
        // Los umbrales crecen con cada negativa: quien ya dijo que no merece que la
        // segunda oferta llegue cuando de verdad tenga algo que perder (varias
        // partidas jugadas), no dos pantallas después.
        val threshold = if (timesDeclined == 0) FIRST_OFFER_GAMES else SECOND_OFFER_GAMES
        return gamesPlayed >= threshold
    }

    companion object {
        /**
         * Como mucho dos ofertas. Una tercera ya no es un recordatorio, es insistencia:
         * quien dijo "ahora no" dos veces está diciendo que no, y le queda el
         * interruptor de Ajustes cuando cambie de idea.
         */
        const val MAX_ATTEMPTS = 2

        /** Primera oferta: en cuanto ha terminado una partida y ha visto su resultado. */
        const val FIRST_OFFER_GAMES = 1

        /**
         * Segunda oferta: con cinco partidas ya hay racha y récords reales de por
         * medio, así que la propuesta apela a algo que el usuario puede perder.
         */
        const val SECOND_OFFER_GAMES = 5
    }
}
