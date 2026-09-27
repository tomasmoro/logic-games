package com.kortexgames.app.domain.model

import kotlinx.datetime.Instant

/**
 * # Torneos (eventos) — modelo de dominio
 *
 * Un **evento** es un torneo acotado en el tiempo sobre UN juego del catálogo
 * ("Torneo de Sudoku del sábado"). Espeja la tabla `public.events` (migración
 * 0051) sin arrastrar nada de Supabase ni de Compose: es dominio puro.
 *
 * La diferencia con el ranking mundial (`GameRanking`) es el universo de
 * comparación: allí compiten TODAS las mejores marcas históricas del juego; aquí
 * solo las conseguidas dentro de la ventana del torneo, y con sus reglas (reto
 * fijo, dificultad fija, tope de intentos). Son dos tablas distintas a propósito
 * — entrar hoy a un torneo tiene sentido aunque el top mundial sea inalcanzable.
 */

/**
 * Cómo se agregan los intentos de un jugador. Espeja el ENUM `event_scoring_mode`.
 * Hoy el backend solo produce [BEST_RUN]; los otros dos existen ya porque el tipo
 * SQL los contempla y el cliente debe poder decodificar un evento futuro sin
 * romperse (ver [fromRaw]).
 */
enum class EventScoringMode {
    /** Cuenta el mejor intento. Único modo en uso. */
    BEST_RUN,

    /** Suma de los N mejores intentos (premia constancia). */
    SUM_TOP_N,

    /** Suma de todos los intentos (premia volumen). */
    TOTAL;

    companion object {
        /**
         * Decodifica el valor tal cual llega del backend. Un valor desconocido
         * —un modo nuevo servido a una app vieja— cae a [BEST_RUN] en vez de
         * lanzar: preferimos enseñar el torneo con la regla más común a dejar la
         * Home en blanco por un enum que no conocemos todavía.
         */
        fun fromRaw(raw: String): EventScoringMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: BEST_RUN
    }
}

/** En qué momento de su vida está un torneo respecto a un instante dado. */
enum class EventPhase {
    /** Aún no ha empezado: se anuncia con cuenta atrás. */
    UPCOMING,

    /** Abierto: admite intentos. */
    LIVE,

    /** Cerrado: la tabla ya no cambia y el puesto es definitivo. */
    FINISHED,
}

/**
 * Un torneo del calendario.
 *
 * @property id UUID de `public.events`; es lo que viaja a las RPC.
 * @property gameId UUID del juego, que coincide con el id del catálogo local
 *   (`GameIds`) — la app usa el mismo UUID en ambos lados, sin traducción.
 * @property difficultyLevel dificultad FIJA del torneo, o null si el jugador la
 *   elige. En los juegos que separan ranking por dificultad (`GameRankingScopes`)
 *   dejarla libre tendría el mismo defecto que allí se corrigió: ganaría quien
 *   juega en fácil.
 * @property rankByTime true ⇒ gana el tiempo más bajo; false ⇒ la puntuación más
 *   alta. Mismo criterio que `GameRanking.rankedByTime`.
 * @property attemptsLimit intentos permitidos por jugador, o null si son
 *   ilimitados. Con reto fijo conviene topar: sin tope, la tabla la gana quien más
 *   veces repita el mismo tablero ya memorizado.
 * @property adAttemptsLimit cuántos intentos EXTRA puede desbloquear el jugador
 *   viendo un anuncio. 0 = ninguno. El tope existe para que el torneo no lo gane
 *   quien más anuncios ve, que sería premiar paciencia en vez de cabeza.
 * @property puzzleId puzzle concreto que juegan todos (hoy solo Neon Sudoku
 *   Matrix), extraído de `events.payload`. null ⇒ el reto no se fija por puzzle.
 * @property seed semilla del generador para los juegos procedurales, extraída del
 *   mismo `payload`. null ⇒ cada jugador recibe su propio reto.
 * @property rewardTopN cuántos puestos reciben insignia (0 = solo el puesto).
 * @property rewardBadgeKey clave del icono de la insignia, o null.
 */
data class GameEvent(
    val id: String,
    val gameId: String,
    val title: String,
    val subtitle: String?,
    val startsAt: Instant,
    val endsAt: Instant,
    val difficultyLevel: Int?,
    val rankByTime: Boolean,
    val scoringMode: EventScoringMode,
    val attemptsLimit: Int?,
    val adAttemptsLimit: Int,
    val puzzleId: String?,
    val seed: Long?,
    val rewardTopN: Int,
    val rewardBadgeKey: String?,
) {
    /**
     * Fase del torneo en [now]. La comparación se hace siempre contra un instante
     * que se pasa desde fuera (nunca contra un reloj leído aquí dentro) para que la
     * UI pueda recomponerse con el tick de su cuenta atrás y para poder probar las
     * tres fases sin trucar el reloj del sistema.
     *
     * El final es **exclusivo** (`now < endsAt`), igual que la ventana que valida el
     * servidor en `submit_event_result`: así el cliente nunca deja pulsar "jugar" un
     * segundo después de que el backend haya empezado a rechazar intentos.
     */
    fun phaseAt(now: Instant): EventPhase = when {
        now < startsAt -> EventPhase.UPCOMING
        now < endsAt -> EventPhase.LIVE
        else -> EventPhase.FINISHED
    }

    /** Atajo de [phaseAt] para la pregunta más frecuente: ¿admite intentos ahora? */
    fun isLiveAt(now: Instant): Boolean = phaseAt(now) == EventPhase.LIVE
}

/**
 * La fila del propio jugador en la tabla del torneo.
 *
 * @property rank puesto (1 = primero).
 * @property bestScore puntos de su mejor partida.
 * @property bestTimeMs tiempo de esa misma partida; null si no se cronometró.
 *   **Es la misma partida que [bestScore]**, no el mejor tiempo suelto de otro
 *   intento: el backend actualiza ambas columnas a la vez (ver 0053).
 * @property attempts intentos consumidos, para contrastarlos con
 *   [GameEvent.attemptsLimit].
 */
data class EventStanding(
    val rank: Long,
    val bestScore: Int,
    val bestTimeMs: Int?,
    val attempts: Int,
)

/**
 * La tabla del torneo tal y como se pinta: los primeros puestos más la fila del
 * jugador aparte, porque casi nadie está en el top y sin su propia fila abriría la
 * pantalla sin encontrarse.
 *
 * @property top primeros puestos, ya ordenados. Reutiliza [LeaderboardEntry], cuyo
 *   `score` es "el valor por el que se ordena": puntos, o milisegundos en los
 *   torneos con [GameEvent.rankByTime].
 * @property me fila del jugador, o null si aún no tiene MARCA (no ha competido, solo
 *   ha abandonado, o es invitado). Puede estar repetida dentro de [top] si está
 *   entre los primeros: la UI evita pintarla dos veces, no el modelo.
 * @property myExtraAttempts intentos extra que el jugador ya ha desbloqueado con
 *   anuncios. El cupo total es `event.attemptsLimit + myExtraAttempts`.
 * @property myAttempts intentos gastados por el jugador, tenga marca o no. Es un
 *   campo aparte de [me] precisamente porque abandonar gasta intento SIN dejar
 *   marca (migración 0056): quien solo ha abandonado tiene `me = null` y aquí un
 *   número mayor que cero.
 */
data class EventLeaderboard(
    val totalPlayers: Long,
    val top: List<LeaderboardEntry>,
    val me: EventStanding?,
    val myAttempts: Int = 0,
    val myExtraAttempts: Int = 0,
)

/**
 * Lo que devuelve el backend al aceptar un intento de torneo.
 *
 * @property rank puesto tras el intento; null solo en el caso límite de un torneo
 *   por tiempo donde la partida no se cronometró (queda fuera de la tabla).
 * @property improved true si este intento MEJORÓ la marca guardada. La UI lo usa
 *   para celebrar; un intento peor no es un fracaso, pero tampoco una subida.
 * @property attemptsLeft intentos que quedan, o null si son ilimitados.
 */
data class EventSubmission(
    val rank: Long?,
    val totalPlayers: Long,
    val improved: Boolean,
    val attemptsUsed: Int,
    val attemptsLimit: Int?,
) {
    val attemptsLeft: Int? get() = attemptsLimit?.let { (it - attemptsUsed).coerceAtLeast(0) }
}

/**
 * Rechazos del backend al enviar un intento, como tipos y no como texto.
 *
 * El servidor los emite con SQLSTATE propios (`KXE01`…, ver la migración 0053)
 * precisamente para que el cliente pueda DISTINGUIRLOS y decir algo útil: "el
 * torneo ya cerró" y "te quedaste sin intentos" piden mensajes —y salidas—
 * distintas. Son `Exception` para poder viajar dentro de un `Result`.
 */
sealed class EventSubmitException(message: String) : Exception(message) {

    /** El torneo no está abierto ahora mismo (según el reloj del SERVIDOR). */
    data object Closed : EventSubmitException("El torneo no está abierto")

    /** Se agotó el tope de intentos del torneo. */
    data object NoAttemptsLeft : EventSubmitException("Sin intentos disponibles")

    /** La dificultad jugada no es la que fija el torneo. Indica un bug del cliente. */
    data object WrongDifficulty : EventSubmitException("Dificultad no permitida")

    /** No hay sesión: un invitado no puede puntuar (sí ver el torneo). */
    data object NotAuthenticated : EventSubmitException("Necesitas una cuenta")

    /** Red caída o error no clasificado. El intento NO quedó registrado. */
    data class Unknown(val cause0: Throwable?) : EventSubmitException("No se pudo enviar el resultado")
}

/**
 * Una insignia ganada: el puesto premiado de un torneo ya cerrado.
 *
 * Es un modelo aparte de [com.kortexgames.app.domain.model.Achievement] a
 * propósito. Un logro es **progreso hacia un umbral** sobre las estadísticas del
 * propio jugador, definido en un catálogo fijo y escrito por el cliente; una
 * insignia es **un puesto en una fecha concreta**, uno por torneo, derivado en el
 * servidor de las marcas de todos los participantes. Compartir tabla obligaría a
 * publicar una release por cada torneo nuevo y pondría en manos del cliente la
 * escritura de sus propios trofeos (ver la migración 0059).
 *
 * @property position puesto final (1 = campeón). Siempre dentro del premio: el
 *   backend no devuelve los torneos donde no se ganó nada.
 * @property badgeKey clave de arte de la insignia elegida por el evento, o null
 *   para la genérica. Todavía no hay catálogo de arte; viaja porque es el dato que
 *   lo decidirá.
 * @property endedAt cierre del torneo. Ordena la vitrina: lo más reciente primero.
 */
data class EventAward(
    val eventId: String,
    val eventTitle: String,
    val gameId: String,
    val position: Long,
    val totalPlayers: Long,
    val badgeKey: String?,
    val endedAt: Instant,
)
