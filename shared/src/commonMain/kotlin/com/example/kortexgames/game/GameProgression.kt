package com.example.kortexgames.game

/**
 * Cómo progresa un juego, de cara al récord y (a futuro) a la reanudación.
 *
 *  - [LEVELED]: niveles discretos que suben. La métrica es el nivel alcanzado y
 *    tiene sentido "reanudar" (retomar el nivel N). Ej.: Water Sort, Energy Flow.
 *  - [ENDLESS]: una corrida hasta fallar; cada partida empieza de cero. La métrica
 *    es un récord de esa corrida (longitud máx, mejor tiempo, mejor ronda/puntaje).
 *    Ej.: Memoria, Reflejos, Burbujas, Atracción Geométrica.
 */
enum class ProgressionKind { LEVELED, ENDLESS }

/**
 * Dirección de la métrica: define qué marca es "mejor" y, por tanto, cómo se
 * calcula el récord. La app la usa para fusionar marcas (local y entre
 * dispositivos); por eso el backend guarda el valor crudo sin interpretarlo.
 */
enum class MetricDirection {
    /** Mayor = mejor (longitud de secuencia, nivel, puntaje). */
    HIGHER_IS_BETTER,

    /** Menor = mejor (tiempo de reacción en Reflejos). */
    LOWER_IS_BETTER,
}

/**
 * Unidad de la métrica, solo para formatearla en la UI. El valor viaja siempre
 * como entero en su unidad natural (ver [GameProgression.formatValue]).
 */
enum class MetricUnit { LEVEL, COUNT, MILLIS, POINTS }

/**
 * Rasgos de progresión de un juego jugable. Es la referencia única para etiquetar
 * el récord en la tarjeta del catálogo y para decidir la mejor marca al guardar.
 *
 * @property kind LEVELED o ENDLESS (ver [ProgressionKind]).
 * @property direction si mayor o menor es mejor (define el récord).
 * @property recordLabel prefijo del récord en la UI ("Nivel máx", "Mejor").
 * @property unit unidad para formatear el número.
 */
data class GameProgression(
    val kind: ProgressionKind,
    val direction: MetricDirection,
    val recordLabel: String,
    val unit: MetricUnit,
) {
    /**
     * ¿[candidate] supera a [current] según la [direction]? Núcleo del récord:
     * lo usan tanto el guardado local como la fusión al sincronizar.
     */
    fun isBetter(candidate: Int, current: Int): Boolean = when (direction) {
        MetricDirection.HIGHER_IS_BETTER -> candidate > current
        MetricDirection.LOWER_IS_BETTER -> candidate < current
    }

    /** Formatea el valor crudo para mostrarlo (sin el [recordLabel]). */
    fun formatValue(value: Int): String = when (unit) {
        MetricUnit.LEVEL, MetricUnit.COUNT -> value.toString()
        MetricUnit.MILLIS -> "$value ms"
        MetricUnit.POINTS -> "$value pts"
    }

    /** Texto completo del récord para la tarjeta, p. ej. "Nivel máx 7" o "Mejor 320 ms". */
    fun formatRecord(value: Int): String = "$recordLabel ${formatValue(value)}"
}

/**
 * Registro de la progresión de cada juego jugable, indexado por su UUID
 * ([GameIds]). Se mantiene aparte de [GameInfo] (contenido de UI que incluye
 * placeholders sin id) para tener una fuente única y tipada de la semántica de
 * récord, consultable desde el repositorio, los motores y la UI.
 *
 * Nota de clasificación: Burbujas y Atracción Geométrica son mecánicamente
 * "corridas hasta fallar" (supervivencia), por eso son ENDLESS aunque su
 * dificultad suba por rondas; su récord es la mejor ronda / mejor puntaje.
 */
object GameProgressions {
    private val byId: Map<String, GameProgression> = mapOf(
        GameIds.SEQUENCE_MEMORY to GameProgression(
            ProgressionKind.ENDLESS, MetricDirection.HIGHER_IS_BETTER, "Mejor", MetricUnit.COUNT,
        ),
        GameIds.REFLEX_TAP to GameProgression(
            ProgressionKind.ENDLESS, MetricDirection.LOWER_IS_BETTER, "Mejor", MetricUnit.MILLIS,
        ),
        GameIds.WATER_SORT to GameProgression(
            ProgressionKind.LEVELED, MetricDirection.HIGHER_IS_BETTER, "Nivel máx", MetricUnit.LEVEL,
        ),
        GameIds.ENERGY_FLOW to GameProgression(
            ProgressionKind.LEVELED, MetricDirection.HIGHER_IS_BETTER, "Nivel máx", MetricUnit.LEVEL,
        ),
        GameIds.BUBBLE_MATH to GameProgression(
            ProgressionKind.ENDLESS, MetricDirection.HIGHER_IS_BETTER, "Mejor ronda", MetricUnit.COUNT,
        ),
        GameIds.POLARITY_COLLISION to GameProgression(
            ProgressionKind.ENDLESS, MetricDirection.HIGHER_IS_BETTER, "Mejor", MetricUnit.POINTS,
        ),
        GameIds.CRUCIGRAMA_NEON to GameProgression(
            ProgressionKind.LEVELED, MetricDirection.HIGHER_IS_BETTER, "Nivel máx", MetricUnit.LEVEL,
        ),
        GameIds.WORD_CONNECT to GameProgression(
            ProgressionKind.LEVELED, MetricDirection.HIGHER_IS_BETTER, "Nivel máx", MetricUnit.LEVEL,
        ),
        GameIds.NEON_SCREWS to GameProgression(
            ProgressionKind.LEVELED, MetricDirection.HIGHER_IS_BETTER, "Nivel máx", MetricUnit.LEVEL,
        ),
    )

    /** Progresión de un juego, o null si el id es null o no está registrado. */
    fun forId(gameId: String?): GameProgression? = gameId?.let { byId[it] }
}
