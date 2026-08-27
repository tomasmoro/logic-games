package com.kortexgames.app.game.legion

import kotlin.jvm.JvmInline
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * # Neon Legion — modelos de dominio y balance (Fase 1)
 *
 * Modelos puros e inmutables del minijuego **Neon Legion**: runner infinito de carriles en el que
 * el jugador guía un ejército de puntos de luz, atraviesa puertas matemáticas que alteran sus
 * tropas, esquiva láseres de naves y choca contra un ejército enemigo al final de cada ronda.
 *
 * No dependen de Compose ni de ninguna API de plataforma: son la fuente de verdad que el motor
 * (Fases 2-3) hace avanzar tick a tick y que el `Canvas` (Fase 5) traduce a píxeles.
 *
 * ## Decisión 1 — mundo vertical NORMALIZADO (0..1), carriles por índice
 * Igual que Quantum Merge (ver la Decisión 1 de `QuantumMergeModels.kt`), el motor no conoce
 * píxeles: toda posición vertical es una fracción de la pista (`y = 0` borde superior, `y = 1`
 * borde inferior) y la horizontal es un **índice de carril** ([LaneIndex]), nunca una coordenada.
 * Consecuencias buscadas:
 *  - La simulación es idéntica en cualquier pantalla y el tuning de velocidad se hace una vez.
 *  - Un test puede afirmar "la fila cruzó `PLAYER_Y` en el tick N" sin inventar un viewport.
 *  - La expansión de 2 a 3 carriles (ronda 7) no recoloca nada en el dominio: los carriles son
 *    índices y es el render quien reparte el ancho disponible entre ellos.
 *
 * ## Decisión 2 — el eje Y crece hacia ABAJO y el jugador está FIJO en `PLAYER_Y`
 * Convención de pantalla (la misma de `DrawScope`): las puertas/naves nacen en `y < 0` y CAEN
 * hacia el jugador, anclado al 80 % de la altura ([LegionBalance.PLAYER_Y]). Así "avanzar" es
 * sumar `speed * dt` a cada elemento y la colisión es un simple cruce de umbral, sin cámara.
 *
 * ## Decisión 3 — lo derivable DERIVA, no se guarda
 * Velocidad, número de carriles, filas por ronda o si la ronda es de Jefe son funciones puras de
 * la ronda ([LegionBalance]); [LegionState] las expone como propiedades calculadas en vez de
 * campos. Un estado con `round = 8` y `lanes = 2` sería ilegal — al derivarlas, ese bug es
 * imposible por construcción y el tick solo escribe lo que de verdad cambia.
 *
 * ## Decisión 4 — el enemigo es RELATIVO al recorrido, no una constante por ronda
 * Un enemigo con crecimiento fijo (p. ej. +5 %/ronda con techo) elimina el fail state: las
 * puertas `×` hacen crecer al jugador de forma multiplicativa y a partir de ~10 rondas ganaría
 * siempre. En su lugar, el motor calculará el "ejército esperado" de la ronda (recorrerla
 * eligiendo siempre la mejor puerta) y el enemigo será `esperado * factorRonda`, con el factor
 * subiendo de [LegionBalance.ENEMY_FACTOR_MIN] a [LegionBalance.ENEMY_FACTOR_MAX]. El reto es
 * siempre "acércate al recorrido óptimo", a cualquier ronda, y la partida puede perderse.
 */

/**
 * Índice de carril (0-based, de izquierda a derecha). Es una *value class* y no un `Int` pelado
 * para que el compilador impida mezclar carriles con tropas o con índices de opción del examen —
 * en un motor donde casi todo es un entero, ese es el error silencioso más fácil de cometer.
 */
@JvmInline
value class LaneIndex(val index: Int)

/**
 * Constantes y curvas de balance del juego, TODAS en un único sitio para poder ajustar la
 * dificultad sin bucear por el motor. Solo contiene valores y funciones puras de la ronda; la
 * lógica que las consume (generación de pista, temporizadores) vive en el motor (Fases 2-3).
 *
 * Las funciones `*ForRound` reciben la ronda 1-based y son deterministas: el mismo número de
 * ronda produce siempre el mismo balance, lo que las hace triviales de testear y de documentar.
 */
object LegionBalance {

    // ── Pista y geometría (unidades de mundo, ver Decisión 1) ────────────────────────────────

    /** Altura fija del ejército del jugador: el cuarto quinto de la pantalla (spec del juego). */
    const val PLAYER_Y: Float = 0.8f

    /**
     * Altura a la que FRENA el ejército enemigo para el combate: el tercer quinto, justo encima
     * del jugador ([PLAYER_Y], cuarto quinto). Los dos ejércitos quedan así **enfrentados** con
     * un quinto de pantalla de separación, que es el espacio donde se cruzan los disparos y
     * revientan las naves. Si el enemigo siguiera hasta [PLAYER_Y] los dos enjambres se
     * solaparían y el choque se leería como una sola mancha, no como dos bandos.
     */
    const val ENEMY_CLASH_Y: Float = 0.6f

    /** Y de aparición de una fila nueva, por encima del borde visible para que "entre" en escena. */
    const val SPAWN_Y: Float = -0.2f

    /**
     * Separación vertical entre filas de puertas consecutivas. Es el "tiempo para pensar":
     * a la velocidad de la ronda 1 son ~2,3 s entre cálculos.
     *
     * Historia del valor, porque va y viene: partió de 0,45 (demasiado justo, la ronda se
     * resolvía a reflejos), subió a 0,65 para dar aire y ahora baja un 10 % hasta 0,585 —los
     * cálculos llegan un 10 % más seguido— buscando subir la exigencia sin volver al problema
     * original. Es la palanca de dificultad más directa del juego: tocarla cambia el ritmo de
     * TODAS las rondas a la vez.
     */
    const val GATE_ROW_SPACING: Float = 0.585f

    // ── Tropas y velocidad ───────────────────────────────────────────────────────────────────

    /**
     * Tropas de arranque cuando no hay semilla real disponible (valor por defecto de
     * [LegionState] antes de que `LegionEngine.onStart` sortee la semilla de la partida). El
     * juego jugado NO usa este número fijo: cada partida sortea un entero en
     * [INITIAL_TROOPS_MIN]..[INITIAL_TROOPS_MAX] para que la ronda 1 no se sienta siempre igual
     * —antes SIEMPRE eran 40 tropas objetivo y 22 enemigos, partida tras partida—.
     */
    const val INITIAL_TROOPS: Int = 10

    /** Extremo inferior del sorteo de tropas iniciales de la partida (ver [INITIAL_TROOPS]). */
    const val INITIAL_TROOPS_MIN: Int = 5

    /** Extremo superior del sorteo de tropas iniciales de la partida (ver [INITIAL_TROOPS]). */
    const val INITIAL_TROOPS_MAX: Int = 20

    /** Velocidad base de caída de los elementos, en unidades de mundo por segundo (ronda 1). */
    const val BASE_SPEED: Float = 0.252f

    /** Crecimiento de velocidad por ronda (acumulativo). */
    const val SPEED_GROWTH_PER_ROUND: Float = 0.06f

    /**
     * Ronda en la que la velocidad queda FIJADA: a partir de aquí ni la caída ni la cadencia de
     * los cálculos vuelven a subir (techo ≈ 1,26× la base).
     *
     * Se adelantó de la ronda 10 a la 5 porque la velocidad es el eje de dificultad más romo que
     * tiene el juego: acelerar no obliga a pensar mejor, solo recorta el tiempo de leer, y
     * llevado lejos convierte un juego de cálculo en uno de reflejos. Congelándola pronto, lo
     * que sigue creciendo a partir de la ronda 5 es lo que de verdad exige cabeza —más puertas,
     * cuentas más duras, el enemigo más cerca del recorrido óptimo y los láseres—.
     */
    const val SPEED_CAP_ROUND: Int = 5

    /**
     * Velocidad de la ronda dada. Crecimiento **multiplicativo** (+6 % compuesto) y no aditivo:
     * así cada ronda se siente proporcionalmente más rápida que la anterior, y el techo en la
     * ronda [SPEED_CAP_ROUND] evita que el juego se vuelva injugable por pura velocidad — a
     * partir de ahí la dificultad la ponen los láseres y el enemigo, no el reloj.
     */
    fun speedForRound(round: Int): Float =
        BASE_SPEED * (1f + SPEED_GROWTH_PER_ROUND).pow(min(round, SPEED_CAP_ROUND) - 1)

    // ── Carriles ─────────────────────────────────────────────────────────────────────────────

    /** Ronda en la que la pista se expande de 2 a 3 carriles. */
    const val LANE_EXPANSION_ROUND: Int = 7

    /** Carriles disponibles en la ronda dada (2 al inicio, 3 desde [LANE_EXPANSION_ROUND]). */
    fun lanesForRound(round: Int): Int = if (round >= LANE_EXPANSION_ROUND) 3 else 2

    /**
     * Constante de tiempo con la que el ejército persigue al dedo, en segundos: el retardo de
     * "seguimiento" que hace que la legión se sienta como una masa con inercia y no como un
     * cursor pegado al dedo.
     *
     * Se aplica como suavizado exponencial (ver `LegionEngine.stepRace`), no como un retardo
     * literal de 0,2 s: un retardo puro reproduciría el gesto tal cual 0,2 s después —incluidos
     * los tirones del dedo— mientras que el suavizado además redondea la trayectoria, que es lo
     * que da la sensación de arrastrar un enjambre. τ es justamente el tiempo que tarda en
     * cubrir el 63 % de la distancia al dedo, así que el "0,2 s de retraso" se percibe igual.
     */
    const val AIM_FOLLOW_TAU_SEC: Float = 0.2f

    // ── Puertas ──────────────────────────────────────────────────────────────────────────────

    /** Filas de puertas de la ronda 1. */
    const val GATE_ROWS_BASE: Int = 5

    /** Techo de filas por ronda (se alcanza en la ronda 10). */
    const val GATE_ROWS_MAX: Int = 14

    /** Filas de puertas de la ronda dada: 5 en la ronda 1, +1 por ronda, techo en 14. */
    fun gateRowsForRound(round: Int): Int = min(GATE_ROWS_BASE + (round - 1), GATE_ROWS_MAX)

    // ── Escala del ejército (techo de magnitud) ──────────────────────────────────────────────
    //
    // EL PROBLEMA QUE RESUELVE ESTA CURVA: si cada fila ofrece "la mejor puerta" con libertad
    // (un ×2 o un +100 % de las tropas), el recorrido óptimo se DUPLICA por fila. Con 5-14
    // filas por ronda eso es 2^5 … 2^14 por ronda, compuesto entre rondas: en la ronda 5 el
    // ejército pasaba de 30 millones y los rótulos dejaban de ser legibles (y de significar
    // algo: sumar 15 sobre un millón no es una decisión).
    //
    // LA SOLUCIÓN: el generador ya no elige la mejor puerta "libremente", sino que apunta al
    // TAMAÑO OBJETIVO de la ronda ([targetTroopsForRound]) repartido entre sus filas. El
    // ejército queda así acotado al rango 0..[TROOPS_SOFT_CAP] durante toda la partida, y la
    // dificultad la siguen poniendo la velocidad, los láseres y el factor del enemigo.

    /** Techo blando del ejército: ninguna ronda apunta por encima de este tamaño. */
    const val TROOPS_SOFT_CAP: Int = 1_000

    /**
     * Cuántas veces la SEMILLA de tropas de la partida (ver [INITIAL_TROOPS_MIN]) supera al
     * objetivo de la ronda 1. Con la semilla histórica de 10 tropas el objetivo era 40 = 10 × 4;
     * se conserva ese mismo salto para que sortear la semilla cambie la ESCALA de la partida (una
     * que arranca con 20 tropas apunta a 80 al acabar la ronda 1; una que arranca con 5 apunta a
     * 20) sin tocar el RITMO relativo de la curva, que sigue siendo el mismo para cualquier
     * semilla.
     */
    const val TARGET_TROOPS_MULTIPLIER: Float = 4f

    /** Crecimiento del objetivo por ronda (×1,45 acumulativo hasta tocar [TROOPS_SOFT_CAP]). */
    const val TARGET_GROWTH_PER_ROUND: Float = 1.45f

    /**
     * Tamaño de ejército al que apunta el recorrido ÓPTIMO al terminar la ronda dada: el
     * generador de pista reparte este objetivo entre las filas de la ronda (ver
     * `LegionEngine.bestOperationFor`) y el enemigo se dimensiona contra el resultado real.
     *
     * @param seedTroops la semilla de tropas de ESTA partida (ver [INITIAL_TROOPS_MIN]), no un
     *   valor fijo: toda la curva escala desde ahí, así que dos partidas con semillas distintas
     *   viven la misma progresión relativa (×1,45/ronda) pero con objetivos absolutos distintos —
     *   es lo que hace que la ronda 1 no se sienta siempre igual (ver `LegionEngine.seedTroops`).
     *
     * Progresión con la semilla histórica de 10: 40 al acabar la ronda 1, ~176 en la 5, y el
     * techo de 1000 desde la 10-11. Crece rápido al principio —cuando duplicar el ejército se
     * nota— y se aplana justo donde las cifras dejarían de leerse de un vistazo en movimiento.
     *
     * Que el ejército ENTRE a la ronda siguiente con solo el excedente del combate (bastante
     * menor que este objetivo) es lo que mantiene viva la curva: cada ronda vuelve a partir de
     * poco y hay que reconstruir la legión desde ahí.
     */
    fun targetTroopsForRound(round: Int, seedTroops: Int): Int =
        (seedTroops * TARGET_TROOPS_MULTIPLIER * TARGET_GROWTH_PER_ROUND.pow(round - 1))
            .toInt()
            .coerceIn(seedTroops + 1, TROOPS_SOFT_CAP)

    // ── Combate de fin de ronda (ver Decisión 4) ─────────────────────────────────────────────

    /** Cada cuántas rondas el enemigo final es un Jefe (nave gigante). */
    const val BOSS_EVERY: Int = 10

    /** ¿La ronda termina contra un Jefe? (10, 20, 30…). */
    fun isBossRound(round: Int): Boolean = round % BOSS_EVERY == 0

    // ── Duelo contra el Jefe ─────────────────────────────────────────────────────────────────
    //
    // El Jefe NO se resuelve comparando tropas como el ejército normal: es un duelo por tiempo.
    // La legión le dispara sin parar (1 de vida por nave y segundo) mientras él barre con un
    // rayo cada 2 s que fulmina al 30 % de las naves.
    //
    // NOTA DE BALANCE: como la vida del Jefe se calcula sobre las tropas con las que se entra
    // ([BOSS_HP_PER_TROOP] × tropas), el duelo sale SIEMPRE igual —el jugador gana en ~5,2 s
    // conservando en torno al 49 % de su legión— llegue con 50 naves o con 900: la vida y el
    // daño escalan juntos y se cancelan. Es un peaje espectacular, no un examen. Para que la
    // ronda del Jefe sea un filtro de verdad habría que anclar la vida a algo INDEPENDIENTE del
    // jugador (por ejemplo al objetivo de la ronda), y entonces llegar con más naves sí
    // decidiría el resultado.

    /** Vida del Jefe por cada nave con la que el jugador entra al duelo. */
    const val BOSS_HP_PER_TROOP: Int = 4

    /** Segundos entre rayo y rayo del Jefe. */
    const val BOSS_SHOT_INTERVAL_SEC: Float = 2f

    /** Fracción de la legión que fulmina cada rayo del Jefe. */
    const val BOSS_SHOT_KILL_FRACTION: Float = 0.3f

    /** Vida que le quita al Jefe cada nave de la legión por segundo. */
    const val BOSS_DAMAGE_PER_SHIP_SEC: Float = 1f

    /** Cuánto se ve el rayo del Jefe tras disparar (solo feedback; el daño se aplica al salir). */
    const val BOSS_BEAM_DURATION_SEC: Float = 0.45f

    /** Segundos que tarda el Jefe en completar un vaivén lateral de lado a lado. */
    const val BOSS_DRIFT_CYCLE_SEC: Float = 3.2f

    /** Fracción del recorrido óptimo que exige el enemigo en la ronda 1 (asequible). */
    const val ENEMY_FACTOR_MIN: Float = 0.55f

    /** Fracción máxima exigida (rondas altas): cerca del óptimo, pero alcanzable. */
    const val ENEMY_FACTOR_MAX: Float = 0.92f

    /** Ronda en la que el factor toca su máximo y deja de subir. */
    const val ENEMY_FACTOR_CAP_ROUND: Int = 20

    /**
     * Exigencia EXTRA de los Jefes, sumada a [enemyFactor] (con techo en [BOSS_FACTOR_CAP]).
     * El Jefe es "el mismo combate 1v1" por spec, pero con tropas propias: un poco más cerca
     * del recorrido óptimo para que la ronda redonda se sienta como un examen, no como trámite.
     */
    const val BOSS_FACTOR_BONUS: Float = 0.04f

    /** Techo absoluto del factor de un Jefe: exigir el 100 % del óptimo sería imposible. */
    const val BOSS_FACTOR_CAP: Float = 0.97f

    /**
     * Hueco entre la última fila de puertas y el enemigo en las rondas **sin escolta** (1-2):
     * apenas un beat de "ya vienen" (~0,8 s) antes del choque.
     *
     * Es corto a propósito. El hueco largo de las rondas escoltadas solo tiene sentido porque
     * ahí pasa algo —la nave carga y dispara—; sin escolta, ese mismo hueco eran tres segundos
     * mirando una pista vacía, que es tiempo muerto y no tensión.
     */
    const val ENEMY_GAP_BASE: Float = 0.4f

    /**
     * Hueco en las rondas **con escolta**: la separación justa para que el ejército enemigo
     * asome por arriba en el instante en que se cruza la última puerta, y no antes.
     *
     * Sale de la geometría, no del gusto: el enemigo se hace visible en [ENEMY_VISIBLE_Y] y la
     * última fila se cruza en [PLAYER_Y], así que `PLAYER_Y − ENEMY_VISIBLE_Y ≈ 0.95` los hace
     * coincidir. Se redondea a 1.0 para que el enemigo (y su escolta) entren un pelín DESPUÉS de
     * la última puerta, no encima de ella.
     */
    const val ENEMY_GAP_ESCORTED: Float = 1.0f

    /**
     * Y a la que el ejército enemigo empieza a verse (asoma por el borde superior). La comparten
     * el render —que hasta aquí ni lo dibuja— y el motor, que la usa para lanzar la escolta
     * justo cuando el enemigo se hace visible: "aparecen a la vez" solo puede significar lo
     * mismo en los dos sitios si el umbral es uno solo.
     */
    const val ENEMY_VISIBLE_Y: Float = -0.15f

    /** Hueco final de la ronda, según tenga escolta o no (ver ambas constantes). */
    fun enemyGapForRound(round: Int): Float =
        if (hasVerticalLaser(round)) ENEMY_GAP_ESCORTED else ENEMY_GAP_BASE

    /**
     * Exigencia del enemigo en la ronda dada: interpolación lineal de [ENEMY_FACTOR_MIN] a
     * [ENEMY_FACTOR_MAX] entre las rondas 1 y [ENEMY_FACTOR_CAP_ROUND], y constante después.
     * El motor multiplica este factor por el "ejército esperado" de la ronda para dimensionar
     * al enemigo (ver Decisión 4 de la cabecera).
     */
    fun enemyFactor(round: Int): Float {
        val t = (min(round, ENEMY_FACTOR_CAP_ROUND) - 1).toFloat() / (ENEMY_FACTOR_CAP_ROUND - 1)
        return ENEMY_FACTOR_MIN + (ENEMY_FACTOR_MAX - ENEMY_FACTOR_MIN) * t
    }

    // ── Láser vertical ───────────────────────────────────────────────────────────────────────

    /** Ronda a partir de la cual aparecen naves de láser vertical. */
    const val VERTICAL_LASER_MIN_ROUND: Int = 3

    /** Ronda desde la que aparecen DOS naves simultáneas (con 3 carriles siempre queda salida). */
    const val DOUBLE_LASER_MIN_ROUND: Int = 10

    /** Segundos de carga antes del disparo, en la primera ronda con láser. */
    const val LASER_CHARGE_BASE_SEC: Float = 2.0f

    /** Reducción de la carga por ronda. */
    const val LASER_CHARGE_STEP_SEC: Float = 0.1f

    /** Suelo de la carga (se alcanza en la ronda 15): por debajo sería irreaccionable. */
    const val LASER_CHARGE_FLOOR_SEC: Float = 0.8f

    /** Duración del rayo ya disparado (solo visual: el daño se aplica al disparar). */
    const val LASER_FIRE_DURATION_SEC: Float = 0.4f

    /** Fracción de tropas perdidas si el jugador sigue en el carril al disparar. */
    const val LASER_HIT_LOSS_FRACTION: Float = 0.5f

    /**
     * Segundos de carga del láser vertical en la ronda dada: 2,0 s en la ronda
     * [VERTICAL_LASER_MIN_ROUND], −0,1 s por ronda, con suelo en [LASER_CHARGE_FLOOR_SEC].
     */
    fun laserChargeSeconds(round: Int): Float =
        (LASER_CHARGE_BASE_SEC - LASER_CHARGE_STEP_SEC * (round - VERTICAL_LASER_MIN_ROUND))
            .coerceAtLeast(LASER_CHARGE_FLOOR_SEC)

    /**
     * ¿Esta ronda trae escolta con láser vertical? Es **un único evento por ronda**, y siempre
     * en el mismo momento: al cruzar la última puerta, mientras el ejército enemigo entra en
     * escena (ver [ENEMY_GAP]).
     *
     * Antes se sorteaban 1-2 eventos en filas al azar en mitad de la carrera. Anclarlo al final
     * lo convierte en el clímax de la ronda —la escolta que cubre a su ejército— en vez de una
     * interrupción aleatoria que competía con la decisión de las puertas.
     */
    fun hasVerticalLaser(round: Int): Boolean = round >= VERTICAL_LASER_MIN_ROUND

    /**
     * Naves simultáneas de un evento vertical: 1, o 2 desde [DOUBLE_LASER_MIN_ROUND]. El
     * `coerceAtMost(lanes - 1)` es el **invariante de salida garantizada**: pase lo que pase
     * con estas constantes, nunca puede haber tantas naves como carriles — siempre queda al
     * menos uno libre al que huir, porque un láser inesquivable no es dificultad, es una
     * tragaperras.
     */
    fun shipsPerVerticalEvent(round: Int): Int =
        (if (round >= DOUBLE_LASER_MIN_ROUND) 2 else 1).coerceAtMost(lanesForRound(round) - 1)

    /** Barridos horizontales (exámenes) que programa una ronda: uno desde [SWEEP_MIN_ROUND]. */
    fun sweepsForRound(round: Int): Int = if (round >= SWEEP_MIN_ROUND) 1 else 0

    // ── Láser horizontal (examen de opciones múltiples) ──────────────────────────────────────

    /** Ronda a partir de la cual puede aparecer el barrido horizontal. */
    const val SWEEP_MIN_ROUND: Int = 5

    /** Segundos totales para resolver la cuenta del examen. */
    const val QUIZ_TIME_LIMIT_SEC: Float = 10f

    /** Segundos iniciales SIN penalización (después empieza el drenaje gradual). */
    const val QUIZ_GRACE_SEC: Float = 5f

    /** Drenaje máximo acumulable por tiempo (fracción de las tropas al iniciar el examen). */
    const val QUIZ_MAX_DRAIN_FRACTION: Float = 0.5f

    /** Fracción de tropas perdidas de golpe al responder mal. */
    const val QUIZ_WRONG_LOSS_FRACTION: Float = 0.5f

    /**
     * Cuántas opciones de respuesta muestra el examen en la ronda dada. Sube en escalones
     * (3 → 4 → 5) en vez de continuamente para que el jugador perciba el cambio como un nuevo
     * "nivel de examen", no como ruido: rondas 5-8 → 3, rondas 9-13 → 4, desde la 14 → 5.
     */
    fun quizOptionCount(round: Int): Int = when {
        round <= 8 -> 3
        round <= 13 -> 4
        else -> 5
    }

    // ── Puntuación ───────────────────────────────────────────────────────────────────────────
    //
    // El invariante que sostiene el ranking es la MONOTONÍA en la ronda: la peor partida que
    // alcanza la ronda N+1 debe puntuar por encima de la mejor que muere en la N. Se cumple por
    // construcción porque POINTS_PER_ROUND (2000) es mayor que todo lo demás junto:
    // TROOPS_BONUS_CAP + EFFICIENCY_BONUS_MAX + REVIVE_PENALTY = 400 + 500 + 800 = 1700 < 2000.
    // Si se ajusta cualquiera de estas constantes, hay que re-verificar esa desigualdad.

    /** Puntos por ronda alcanzada. Domina el resto de sumandos (ver nota de monotonía). */
    const val POINTS_PER_ROUND: Int = 2_000

    /** Techo del bonus por tropas finales: sin techo, una racha de `×` rompería la monotonía. */
    const val TROOPS_BONUS_CAP: Int = 400

    /** Bonus máximo por eficiencia (elegir la puerta óptima y acertar los exámenes). */
    const val EFFICIENCY_BONUS_MAX: Int = 500

    /**
     * Penalización por usar el revive. Las ayudas SIEMPRE restan en este proyecto: sin
     * penalización, el ranking premiaría a quien más anuncios ve, no a quien mejor juega.
     */
    const val REVIVE_PENALTY: Int = 800

    // ── Feedback visual ──────────────────────────────────────────────────────────────────────

    /** Vida del destello de cruce de puerta / impacto ([GateFlash]), en segundos. */
    const val FLASH_DURATION_SEC: Float = 0.5f
}

/**
 * Operación de una puerta matemática. Sellada (dominio cerrado) en vez de un par
 * `(símbolo, número)`: el compilador obliga a tratar los cuatro casos y hace irrepresentable una
 * operación desconocida.
 *
 * @property isPositive `true` si la operación hace crecer al ejército. Lo usa la UI para el
 *   código de color del sistema de diseño (`+`/`×` en verde/cian, `−`/`÷` en Error/Amber) y el
 *   motor para el feedback (sonido de ganancia vs. pérdida).
 * @property label rótulo matemático de la puerta ("+15", "×2"…). Es notación, no texto de UI:
 *   no se traduce y por eso no va al archivo de strings.
 */
sealed interface GateOperation {
    val isPositive: Boolean
    val label: String

    /**
     * Aplica la operación a [troops] y devuelve el resultado, nunca negativo. La división
     * trunca (entera): un ejército de 7 dividido entre 2 queda en 3 — la pérdida del resto es
     * parte del castigo de la puerta.
     */
    fun apply(troops: Int): Int

    /** Suma [amount] tropas. */
    data class Add(val amount: Int) : GateOperation {
        override val isPositive: Boolean get() = true
        override val label: String get() = "+$amount"
        override fun apply(troops: Int): Int = troops + amount
    }

    /**
     * Resta [amount] tropas, sin bajar de 0. Llegar a 0 **es perder**: el motor ya no aplica
     * ningún suelo al cruzar (ver la cabecera de `LegionEngine`), así que una puerta de castigo
     * mayor que el ejército termina la partida ahí mismo.
     */
    data class Subtract(val amount: Int) : GateOperation {
        override val isPositive: Boolean get() = false
        override val label: String get() = "−$amount"
        override fun apply(troops: Int): Int = (troops - amount).coerceAtLeast(0)
    }

    /** Multiplica las tropas por [factor]. */
    data class Multiply(val factor: Int) : GateOperation {
        override val isPositive: Boolean get() = true
        override val label: String get() = "×$factor"
        override fun apply(troops: Int): Int = troops * factor
    }

    /** Divide las tropas entre [divisor] (división entera, ver [apply] de la interfaz). */
    data class Divide(val divisor: Int) : GateOperation {
        override val isPositive: Boolean get() = false
        override val label: String get() = "÷$divisor"
        override fun apply(troops: Int): Int = troops / divisor
    }
}

/**
 * Una puerta matemática concreta, situada en un carril de su [GateRow].
 *
 * @property lane carril que ocupa dentro de la fila.
 * @property operation operación que aplica al ejército que la cruza.
 */
data class MathGate(
    val lane: LaneIndex,
    val operation: GateOperation,
)

/**
 * Naturaleza de una fila de puertas. TODAS las puertas de una fila son del mismo signo, y esa es
 * la palanca principal de dificultad del juego.
 *
 * El motivo: con filas siempre mixtas (una suma, otra resta) la decisión se resuelve buscando la
 * única puerta que suma —no hay que calcular nada, y menos aún desde que todas se pintan del
 * mismo color—. Al garantizar que a veces **todas** suman o **todas** restan, el jugador no
 * puede fiarse del signo y tiene que comparar los dos resultados de verdad.
 *
 * Es dominio y no un detalle del generador porque describe qué reto ofrece la fila: un test puede
 * afirmar "esta ronda trae al menos una fila POSITIVA" sin reconstruir las operaciones.
 */
enum class GateRowKind {
    /**
     * Todas suman. El caso interesante es `×2` contra `+n`: cuál gana depende del tamaño ACTUAL
     * del ejército (con 15 tropas `+20` bate a `×2`; con 30, no), así que obliga a calcular en
     * vez de reconocer un símbolo.
     */
    POSITIVE,

    /** Todas restan: se elige la menos mala. Aquí el error no se castiga, se elige cuánto duele. */
    NEGATIVE,

    /** Unas suman y otras restan: la fila "fácil", que da respiro entre las difíciles. */
    MIXED,
}

/**
 * Fila de puertas simultáneas (una por carril) que cae hacia el jugador.
 *
 * Cuando el jugador la cruza, el motor la **elimina** de la lista en vez de marcarla con un flag
 * `consumed`: una fila consumida no se dibuja ni colisiona nunca más, así que mantenerla viva
 * solo añadiría un estado intermedio que todo consumidor tendría que recordar filtrar.
 *
 * @property id identificador estable dentro de la partida; permite a Compose animar cada fila
 *   con `key(id)` sin confundir una fila nueva con una reciclada.
 * @property y posición vertical del centro de la fila, en unidades de mundo (crece hacia abajo).
 * @property kind qué clase de decisión plantea (ver [GateRowKind]).
 * @property gates una puerta por carril activo, indexadas por [MathGate.lane].
 */
data class GateRow(
    val id: Long,
    val y: Float,
    val kind: GateRowKind,
    val gates: List<MathGate>,
)

/**
 * Nave enemiga activa sobre la pista. Sellada porque las dos variantes se comportan distinto y
 * el estado las guarda por separado (lista de verticales, barrido único): el tipo común existe
 * para documentar que ambas son "el mismo" evento de nave de cara al spawner del motor.
 */
sealed interface SpaceshipEvent {

    /**
     * Nave que carga un láser sobre UN carril: si al agotarse la carga el jugador sigue ahí,
     * pierde el 50 % de sus tropas ([LegionBalance.LASER_HIT_LOSS_FRACTION]).
     *
     * La carga y el disparo son campos contados en segundos (no timestamps): el motor los
     * decrementa con el `dt` del tick, de modo que pausar la partida congela el láser gratis.
     *
     * @property id identificador estable para animaciones (`key(id)` en Compose).
     * @property lane carril amenazado.
     * @property chargeTotalSec duración total de la carga (fija al nacer; la UI dibuja el
     *   progreso como `1 - chargeRemainingSec / chargeTotalSec`).
     * @property chargeRemainingSec segundos restantes hasta el disparo.
     * @property firingRemainingSec segundos restantes de rayo visible; `0` si aún no disparó.
     *   El daño se aplica UNA vez en la transición carga→disparo, no durante el rayo: el rayo
     *   visible es solo feedback.
     */
    data class VerticalLaser(
        val id: Long,
        val lane: LaneIndex,
        val chargeTotalSec: Float,
        val chargeRemainingSec: Float,
        val firingRemainingSec: Float = 0f,
    ) : SpaceshipEvent {

        /** ¿El rayo está disparado y visible ahora mismo? */
        val firing: Boolean get() = firingRemainingSec > 0f

        /** Progreso de carga 0..1 para la UI (0 = recién aparecida, 1 = disparando). */
        val chargeProgress: Float
            get() = (1f - chargeRemainingSec / chargeTotalSec).coerceIn(0f, 1f)
    }

    /**
     * Nave que barre TODOS los carriles: retira las puertas y abre el examen de opciones
     * múltiples ([quiz]). Solo puede existir una a la vez (campo único en [LegionState], no
     * lista): dos exámenes simultáneos serían irresolubles y el spawner nunca los solapa.
     *
     * @property quiz la cuenta a resolver y sus opciones.
     * @property timeRemainingSec segundos restantes de los [LegionBalance.QUIZ_TIME_LIMIT_SEC].
     * @property troopsAtStart tropas al abrirse el examen. Es la BASE FIJA del drenaje gradual:
     *   drenar "hasta el 50 %" se calcula siempre sobre esta foto y no sobre las tropas vivas,
     *   porque un drenaje proporcional al valor ya drenado decaería exponencialmente y nunca
     *   alcanzaría el 50 % prometido.
     * @property drainedTroops tropas ya drenadas por tiempo (contador acumulado, para que la UI
     *   pueda mostrar la sangría y para que el motor no re-aplique lo ya restado).
     */
    data class HorizontalSweep(
        val quiz: LaserQuiz,
        val timeRemainingSec: Float,
        val troopsAtStart: Int,
        val drainedTroops: Int = 0,
    ) : SpaceshipEvent {

        /** Segundos transcurridos desde que se abrió el examen. */
        val elapsedSec: Float get() = LegionBalance.QUIZ_TIME_LIMIT_SEC - timeRemainingSec

        /** ¿Seguimos en los segundos de gracia (sin drenaje)? */
        val inGrace: Boolean get() = elapsedSec < LegionBalance.QUIZ_GRACE_SEC

        /**
         * 0..1 de la **bajada** de la nave hacia el ejército, que ocupa exactamente los segundos
         * de gracia: cuando llega a 1 la nave está encima de la legión y empieza a disparar. La
         * amenaza es así legible sin leer un número — el jugador ve cuánto le queda de calma por
         * lo cerca que tiene la nave.
         */
        val descentProgress: Float
            get() = (elapsedSec / LegionBalance.QUIZ_GRACE_SEC).coerceIn(0f, 1f)

        /**
         * 0..1 del **ataque**: qué parte del ejército condenado (el
         * [LegionBalance.QUIZ_MAX_DRAIN_FRACTION]) lleva ya destruida la nave. Vale 0 durante
         * toda la gracia y llega a 1 al agotarse el tiempo.
         *
         * Es la fuente única del castigo por tardar: el motor la usa para calcular el drenaje y
         * el render para saber cuántas naves reventar. Si cada uno llevara su propia cuenta, las
         * explosiones y las tropas perdidas se desincronizarían.
         */
        val attackProgress: Float
            get() {
                val window = LegionBalance.QUIZ_TIME_LIMIT_SEC - LegionBalance.QUIZ_GRACE_SEC
                return ((elapsedSec - LegionBalance.QUIZ_GRACE_SEC) / window).coerceIn(0f, 1f)
            }

        /** Naves que la nave llegará a destruir si el jugador agota el tiempo. */
        val doomedTroops: Int
            get() = (troopsAtStart * LegionBalance.QUIZ_MAX_DRAIN_FRACTION).toInt()
    }
}

/**
 * La cuenta del examen del láser horizontal.
 *
 * Las opciones viajan ya barajadas (la correcta se señala por índice): barajar en el motor y no
 * en la UI mantiene el render 100 % tonto y hace testeable que la correcta no cae siempre en la
 * misma posición.
 *
 * @property prompt la expresión a resolver, en notación matemática ("12 × 3 − 5"). Igual que
 *   [GateOperation.label], es notación y no texto de UI: no se traduce.
 * @property options valores de respuesta, uno por botón (3 a 5 según la ronda).
 * @property correctIndex índice de la respuesta correcta dentro de [options].
 */
data class LaserQuiz(
    val prompt: String,
    val options: List<Int>,
    val correctIndex: Int,
)

/**
 * Foto del combate de fin de ronda, para que la UI pinte el choque y su resultado.
 *
 * @property playerTroops tropas del jugador al llegar al choque.
 * @property enemyTroops tamaño del ejército enemigo (dimensionado por el motor, ver Decisión 4).
 *
 * Solo existe en rondas normales: las de Jefe abren un [BossFight] en su lugar, y por eso aquí
 * ya no hay ningún `isBoss` — un [CombatStats] de Jefe es un estado irrepresentable.
 * @property progress avance 0..1 de la resolución del choque, que el motor va subiendo durante
 *   el "beat" de combate. Es DOMINIO y no un reloj propio de la UI porque el mismo valor decide
 *   dos cosas que no pueden desincronizarse: cuándo termina el beat (lo usa el motor para
 *   avanzar de ronda) y cuántas naves han sido ya destruidas en pantalla (lo usa el render para
 *   las explosiones y para encoger los dos enjambres). Con un reloj aparte en la UI, el
 *   resultado podría aparecer antes de que la última nave reventase, o al revés.
 */
data class CombatStats(
    val playerTroops: Int,
    val enemyTroops: Int,
    val progress: Float = 0f,
) {
    /** Regla del choque: se gana solo con MÁS tropas; el empate pierde (spec del juego). */
    val playerWins: Boolean get() = playerTroops > enemyTroops

    /**
     * Naves que se aniquilan mutuamente en el choque: tantas parejas como el menor de los dos
     * ejércitos. El bando mayor sobrevive con la diferencia — que es exactamente el excedente
     * con el que se entra a la ronda siguiente.
     */
    val destroyedPairs: Int get() = min(playerTroops, enemyTroops)

    /** Naves ya destruidas de cada bando en este instante del choque (0..[destroyedPairs]). */
    val destroyedNow: Int get() = (destroyedPairs * progress.coerceIn(0f, 1f)).toInt()
}

/**
 * Duelo contra el Jefe (rondas 10, 20, 30…): sustituye al choque instantáneo de
 * [CombatStats] por un intercambio de disparos que se resuelve con el tiempo.
 *
 *  - La legión dispara **sin parar**: [LegionBalance.BOSS_DAMAGE_PER_SHIP_SEC] de vida por nave
 *    y segundo, así que el daño cae según van muriendo naves.
 *  - El Jefe barre con un rayo cada [LegionBalance.BOSS_SHOT_INTERVAL_SEC] que fulmina el
 *    [LegionBalance.BOSS_SHOT_KILL_FRACTION] de la legión.
 *
 * Gana quien llegue antes a cero. Ver la NOTA DE BALANCE en `LegionBalance` sobre por qué, con
 * la vida atada a las tropas de entrada, el desenlace es hoy el mismo en todas las partidas.
 *
 * @property hp vida restante del Jefe. Es `Float` porque el daño llega por fracciones de frame;
 *   redondear a entero en cada tick perdería casi todo el daño a 120 Hz.
 * @property maxHp vida inicial, para pintar la barra.
 * @property troopsAtStart naves con las que la legión entró al duelo; fija el layout del
 *   enjambre para que las supervivientes no se recoloquen a cada andanada.
 * @property elapsedSec tiempo de duelo, que gobierna el vaivén lateral del Jefe.
 * @property nextShotSec segundos hasta el próximo rayo.
 * @property beamRemainingSec segundos que queda visible el rayo ya disparado.
 * @property volleyAgeSec tiempo desde la última andanada, para desvanecer sus explosiones.
 * @property troopsBeforeVolley naves vivas justo ANTES de la última andanada: con las de ahora
 *   delimita exactamente qué naves reventaron y dónde dibujar cada explosión.
 */
data class BossFight(
    val hp: Float,
    val maxHp: Float,
    val troopsAtStart: Int,
    val elapsedSec: Float = 0f,
    val nextShotSec: Float = LegionBalance.BOSS_SHOT_INTERVAL_SEC,
    val beamRemainingSec: Float = 0f,
    val volleyAgeSec: Float = Float.MAX_VALUE,
    val troopsBeforeVolley: Int = 0,
) {
    /** Vida restante 0..1, para la barra del HUD. */
    val hpFraction: Float get() = if (maxHp <= 0f) 0f else (hp / maxHp).coerceIn(0f, 1f)

    /** ¿Su rayo está disparado y visible ahora mismo? */
    val firing: Boolean get() = beamRemainingSec > 0f

    /**
     * Desplazamiento lateral −1..1 del Jefe. El vaivén no es decoración: mantiene viva la
     * amenaza durante un duelo en el que el jugador ya no controla nada, y es lo que distingue
     * al Jefe de un ejército que solo espera quieto.
     */
    val drift: Float
        get() = sin(elapsedSec / LegionBalance.BOSS_DRIFT_CYCLE_SEC * 2f * PI.toFloat())
}

/**
 * Fase de la ronda en curso. Es un dominio cerrado (no flags booleanos combinables) porque las
 * fases son mutuamente excluyentes: no se puede estar corriendo y en examen a la vez, y un par
 * de booleanos `inQuiz`/`inCombat` permitiría representar justo ese estado ilegal.
 */
enum class LegionPhase {
    /** Carrera normal: puertas y naves caen, el jugador cambia de carril. */
    RACING,

    /** Examen del láser horizontal: la pista se congela y mandan los botones de opción. */
    QUIZ,

    /** Choque contra el ejército enemigo de fin de ronda (resolución + animación). */
    COMBAT,

    /** Duelo contra el Jefe (ver [BossFight]): sustituye al choque en las rondas 10, 20, 30… */
    BOSS,
}

/**
 * Destello de feedback en el mundo (cruce de puerta, impacto de láser). Es ESTADO y no un
 * `Effect` porque dura varios frames y hay que dibujarlo: la regla del proyecto es que lo que
 * tiene duración vive en el estado y lo instantáneo (sonido, háptica) viaja como efecto.
 *
 * @property id identificador estable para animar con `key(id)`.
 * @property lane carril donde ocurrió.
 * @property positive `true` si celebra una ganancia (verde), `false` si señala pérdida (Error).
 * @property ageSec edad del destello; el motor lo elimina al superar
 *   [LegionBalance.FLASH_DURATION_SEC] y la UI lo desvanece en proporción.
 */
data class GateFlash(
    val id: Long,
    val lane: LaneIndex,
    val positive: Boolean,
    val ageSec: Float = 0f,
)

/**
 * Estado de dominio completo de una partida de Neon Legion: lo que el motor escribe en cada tick
 * y lo único que el `Canvas` necesita leer para dibujar la pista.
 *
 * Todo lo derivable de la ronda (velocidad, carriles, jefe) se expone como propiedad calculada
 * (ver Decisión 3 de la cabecera): el tick solo escribe posiciones, tropas y contadores.
 *
 * @property round ronda en curso, 1-based. Es la métrica de récord del juego (ENDLESS,
 *   mayor = mejor).
 * @property troops tropas vivas del jugador ahora mismo.
 * @property roundStartTroops tropas con las que se ENTRÓ a la ronda. Doble uso: es la base del
 *   "recorrido óptimo" con que se dimensiona al enemigo (Decisión 4) y el valor al que restaura
 *   el revive.
 * @property playerX posición horizontal REAL del ejército, en unidades de carril continuas
 *   (`0f` = centro del primer carril, `1f` = centro del segundo…). Es continua y no un índice
 *   porque el ejército persigue al dedo con retardo ([LegionBalance.AIM_FOLLOW_TAU_SEC]) y
 *   durante ese arrastre está literalmente entre dos carriles. Vive en el DOMINIO —no como una
 *   animación de la UI— porque es la misma posición que deciden las colisiones: si la vista
 *   fuera por detrás del dato con el que se cruzan las puertas, el jugador vería su legión en un
 *   carril y chocaría en otro.
 * @property aimX carril al que apunta el dedo (mismas unidades que [playerX]). Es el objetivo
 *   que [playerX] persigue; se queda donde el jugador levantó el dedo.
 * @property phase fase de la ronda ([LegionPhase]).
 * @property gateRows filas de puertas visibles, ordenadas por aparición (la más cercana al
 *   jugador primero). El motor elimina cada fila al cruzarla.
 * @property verticalLasers naves de láser vertical activas (0, 1 o 2 según la ronda; nunca
 *   cubren todos los carriles — invariante del spawner, Fase 3).
 * @property sweep barrido horizontal activo con su examen, o `null` si no hay ninguno.
 * @property combat foto del choque final mientras `phase == COMBAT`; `null` en el resto.
 * @property boss estado del duelo mientras `phase == BOSS` (rondas 10, 20, 30…); `null` en el
 *   resto. Es excluyente con [combat]: una ronda termina en choque o en duelo, nunca en ambos.
 * @property enemyTroops tamaño del enemigo que espera al final de la ronda. Vive en el estado
 *   (no solo en [combat]) porque el HUD lo muestra DURANTE la carrera: saber cuánto hay que
 *   superar es lo que da sentido a elegir bien las puertas.
 * @property enemyY posición vertical del ejército enemigo en la pista (misma convención que
 *   [GateRow.y]: cae hacia el jugador tras la última fila de puertas). `null` solo antes de
 *   arrancar la primera ronda.
 * @property awaitingRevive `true` mientras la partida está congelada ofreciendo el revive por
 *   anuncio (combate perdido con el revive aún disponible). Vive en el DOMINIO y no solo en el
 *   `UiState` porque es el motor quien congela la simulación durante la oferta; el ViewModel se
 *   limita a espejarlo hacia `LegionUiState.awaitingRevive`.
 * @property rowsCleared filas de puertas ya cruzadas en esta ronda (progreso del HUD).
 * @property optimalPicks cuántas veces el jugador cruzó la MEJOR puerta de la fila. Junto a
 *   [totalPicks] alimenta el bonus de eficiencia del puntaje.
 * @property totalPicks filas de puertas cruzadas en toda la partida.
 * @property quizCorrect exámenes del láser horizontal acertados en toda la partida.
 * @property quizTotal exámenes enfrentados en toda la partida.
 * @property reviveUsed `true` si ya se consumió el único revive por anuncio de la partida.
 * @property flashes destellos de feedback vivos (ver [GateFlash]).
 */
data class LegionState(
    val round: Int = 1,
    val troops: Int = LegionBalance.INITIAL_TROOPS,
    val roundStartTroops: Int = LegionBalance.INITIAL_TROOPS,
    val playerX: Float = 0f,
    val aimX: Float = 0f,
    val phase: LegionPhase = LegionPhase.RACING,
    val gateRows: List<GateRow> = emptyList(),
    val verticalLasers: List<SpaceshipEvent.VerticalLaser> = emptyList(),
    val sweep: SpaceshipEvent.HorizontalSweep? = null,
    val combat: CombatStats? = null,
    val boss: BossFight? = null,
    val enemyTroops: Int = 0,
    val enemyY: Float? = null,
    val awaitingRevive: Boolean = false,
    val rowsCleared: Int = 0,
    val optimalPicks: Int = 0,
    val totalPicks: Int = 0,
    val quizCorrect: Int = 0,
    val quizTotal: Int = 0,
    val reviveUsed: Boolean = false,
    val flashes: List<GateFlash> = emptyList(),
) {
    /** Carriles activos en esta ronda (derivado, ver Decisión 3). */
    val lanes: Int get() = LegionBalance.lanesForRound(round)

    /**
     * Carril con el que el ejército interactúa AHORA MISMO: el más cercano a [playerX].
     *
     * Es derivado y no un campo (Decisión 3) para que no pueda contradecir a [playerX]. Todo lo
     * que decide el juego pasa por aquí —qué puerta se cruza, si un láser acierta— así que
     * redondear en un único sitio garantiza que "donde se ve la legión" y "dónde cuenta" sean
     * siempre lo mismo. Con el ejército a medio camino entre dos carriles manda el más próximo,
     * que es la regla que el jugador intuye al arrastrar.
     */
    val playerLane: LaneIndex get() = LaneIndex(playerX.roundToInt().coerceIn(0, lanes - 1))

    /** Velocidad de caída vigente, en unidades de mundo por segundo (derivado). */
    val speed: Float get() = LegionBalance.speedForRound(round)

    /** Filas de puertas totales de esta ronda (para el progreso del HUD). */
    val rowsTotal: Int get() = LegionBalance.gateRowsForRound(round)

    /** ¿El enemigo de esta ronda es un Jefe? (derivado). */
    val isBossRound: Boolean get() = LegionBalance.isBossRound(round)
}
