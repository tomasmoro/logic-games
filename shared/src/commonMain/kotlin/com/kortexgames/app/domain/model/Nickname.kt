package com.kortexgames.app.domain.model

import kotlin.time.Instant

/**
 * Motivo por el que un nickname no se puede usar.
 *
 * Es un `enum` y no un `String` porque la UI tiene que decidir qué mensaje enseñar y
 * si el campo debe quedar en rojo o solo con un aviso; con texto libre del backend
 * eso acabaría en comparaciones de cadenas repartidas por la pantalla. Los valores
 * espejan el campo `reason` que devuelven las RPC `check_nickname_available` y
 * `claim_nickname` (migración 0044).
 */
enum class NicknameRejection {
    /** Vacío o solo espacios. */
    EMPTY,

    /** Longitud fuera de 3..16, o caracteres no permitidos. */
    INVALID,

    /** Coincide con la lista de patrones prohibidos (ofensivos o reservados). */
    BLOCKED,

    /** Ya lo tiene otro jugador — incluidas las variantes con homoglifos o leet. */
    TAKEN,

    /** Cambió de nickname hace menos de 30 días. Ver [NicknameOutcome.Rejected.retryAfter]. */
    COOLDOWN,

    /**
     * El backend respondió algo que esta versión de la app no conoce, o falló la red.
     * Existe para que añadir un motivo nuevo en el servidor no rompa clientes viejos:
     * degradan a un mensaje genérico en vez de lanzar excepción.
     */
    UNKNOWN,
}

/**
 * Veredicto sobre un nickname, tanto al comprobarlo como al reclamarlo.
 *
 * Se modela como `sealed` en vez de con un booleano + motivo nullable para que sea
 * imposible construir el estado absurdo "aceptado pero con motivo de rechazo".
 */
sealed interface NicknameOutcome {

    /** Disponible (al comprobar) o ya asignado (al reclamar). */
    data class Ok(val nickname: String) : NicknameOutcome

    /**
     * @property retryAfter solo con [NicknameRejection.COOLDOWN]: instante a partir
     *   del cual se puede volver a cambiar. Permite decir "podrás cambiarlo el 12 de
     *   septiembre" en vez de un genérico "demasiado pronto".
     */
    data class Rejected(
        val reason: NicknameRejection,
        val retryAfter: Instant? = null,
    ) : NicknameOutcome
}
