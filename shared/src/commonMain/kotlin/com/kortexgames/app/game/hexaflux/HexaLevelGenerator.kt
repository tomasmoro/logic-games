package com.kortexgames.app.game.hexaflux

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * # Generador de niveles de Neon Hexa Flux
 *
 * Convierte un número de nivel en un [HexaLevelConfig] completo. Es **determinista**:
 * el mismo nivel produce siempre el mismo tablero (la semilla es el propio nivel),
 * porque reiniciar debe devolver el mismo reto y porque comparar marcas entre
 * jugadores solo es justo si todos jugaron el mismo nivel N.
 *
 * ## Las cuatro dimensiones
 * Cada una avanza con un periodo distinto, y por eso los niveles no se sienten
 * repetidos aunque la mecánica base no cambie:
 *
 * | Dimensión | Periodo | Fuente |
 * |-----------|---------|--------|
 * | Silueta del tablero | 10 niveles | [BoardMask.forLevel] |
 * | Tamaño del tablero | mitades de 5 dentro de cada máscara | [cellsFor] |
 * | Ritmo de dificultad | 5 niveles | [WavePhase.forLevel] |
 * | Objetivo de victoria | 3 niveles | [winConditionFor] |
 * | Gimmicks | desbloqueo único por nivel | [GimmickKind.unlockLevel] |
 *
 * ## Ecuación de dificultad en olas
 * Una rampa lineal cansa (cada nivel es un poco peor que el anterior, sin alivio) y
 * no deja sitio para enseñar mecánicas nuevas. Se usa un **diente de sierra
 * ascendente**:
 *
 * ```
 * D(n) = min( D_max , (1 + k · ⌊(n − 1) / 5⌋) · w((n − 1) mod 5) )
 * ```
 *
 *  - `1 + k·⌊(n−1)/5⌋` es la **rampa lenta**: sube un escalón [RAMP_PER_CYCLE] por
 *    cada ola completada, no por cada nivel.
 *  - `w(·)` es la **forma de la ola** ([WavePhase.intensity]): 0.6 → 0.85 → 1.0 →
 *    1.35 → 0.5. Dentro de una ola la presión crece hasta el jefe (N4) y se desploma
 *    en la catarsis (N5), que queda por debajo incluso de la introducción.
 *  - `D_max` ([MAX_DIFFICULTY]) evita que los niveles muy altos sean irresolubles: a
 *    partir de ahí la variedad la aportan máscara, objetivo y semilla, no más castigo.
 *
 * `D` no es la dificultad percibida directamente sino el dial del que cuelga todo lo
 * demás: densidad de gimmicks, capas de hielo, mecha de las bombas, colores en la
 * bolsa de piezas, puntos objetivo y cadencia de fichas corruptas. Cambiar el
 * equilibrio del juego es tocar las constantes de este archivo, no la lógica.
 *
 * Dos reglas adicionales dan su carácter a los extremos de la ola:
 *  - **Introducción (N1)**: solo aparece la familia de gimmick *más reciente*
 *    desbloqueada, aislada del resto, para que el jugador la entienda sin ruido.
 *  - **Catarsis (N5)**: la bolsa se reduce a dos tiers, así casi cualquier jugada
 *    fusiona y los combos encadenan solos.
 *
 * ## De coordenadas axiales a pantalla
 * El tablero se direcciona en axial `(q, r)` con hexágonos *pointy-top* (ver
 * [HexCoord]). El centro de cada celda, para un hexágono de radio `R` (centro a
 * vértice), es:
 *
 * ```
 * x = R · √3 · (q + r/2)
 * y = R · 3/2 · r
 * ```
 *
 * De dónde sale: en pointy-top el ancho de un hexágono es `√3·R`, que es el paso
 * horizontal entre vecinos de la misma fila (por eso `√3·q`); cada fila baja `3/2·R`
 * —no `2R`— porque las filas se encajan entre sí, y al encajarse se desplazan medio
 * hexágono a la derecha (por eso el `r/2`). [HexCoord.unitX]/[HexCoord.unitY]
 * implementan la fórmula con `R = 1`.
 *
 * Para dibujar, la pantalla calcula la caja que envuelve todos los centros en esas
 * unidades, elige el mayor `R` que la hace caber en el lienzo y la centra. Así una
 * máscara ancha (la mariposa mide 11 hexágonos) y una compacta se ajustan solas, y
 * el generador no necesita saber nada de píxeles ni de densidad.
 */
object HexaLevelGenerator {

    /** Escalón que sube la dificultad base por cada ola de 5 niveles completada. */
    private const val RAMP_PER_CYCLE = 0.15f

    /** Techo de `D`; ver "Ecuación de dificultad" en el KDoc del objeto. */
    private const val MAX_DIFFICULTY = 4f

    /** Fracción de celdas que ocupan los gimmicks bloqueantes con `D = 1`. */
    private const val GIMMICK_DENSITY = 0.07f

    /**
     * Tope de celdas bloqueadas por gimmicks sorteados. Por encima de un cuarto del
     * tablero ya no queda sitio para maniobrar piezas de tres fichas y el nivel deja
     * de ser difícil para ser injusto.
     */
    private const val MAX_BLOCKED_RATIO = 0.25f

    /** Puntos que rinde de media una jugada competente; calibra [WinCondition.TargetScore]. */
    private const val POINTS_PER_MOVE = 12

    /**
     * Sal de la semilla. Evita que el nivel N comparta secuencia pseudoaleatoria con
     * cualquier otro `Random(N)` del proyecto.
     */
    private const val SEED_SALT = 0x4E48_4658

    /**
     * Siluetas de pieza por número de fichas, todas con ancla en el origen. Las de
     * tres cubren los tres casos topológicamente distintos (recta, codo abierto de
     * 120° y triángulo cerrado); el resto de orientaciones sale de rotarlas.
     */
    private val SHAPES: Map<Int, List<List<HexCoord>>> = mapOf(
        1 to listOf(listOf(HexCoord.ORIGIN)),
        2 to listOf(listOf(HexCoord.ORIGIN, HexCoord(1, 0))),
        3 to listOf(
            listOf(HexCoord(-1, 0), HexCoord.ORIGIN, HexCoord(1, 0)),
            listOf(HexCoord(-1, 1), HexCoord.ORIGIN, HexCoord(1, 0)),
            listOf(HexCoord.ORIGIN, HexCoord(1, 0), HexCoord(0, 1)),
        ),
    )

    /**
     * Valor `D` de la ecuación de dificultad para [level] (1-based). Se expone porque
     * la puntuación final lo usa para ponderar el nivel alcanzado.
     */
    fun difficulty(level: Int): Float {
        val ramp = 1f + RAMP_PER_CYCLE * ((level - 1) / WavePhase.CYCLE)
        return (ramp * WavePhase.forLevel(level).intensity).coerceAtMost(MAX_DIFFICULTY)
    }

    /**
     * Genera la receta completa del [level].
     *
     * El orden interno importa: primero los portales (necesitan celdas concretas, en
     * islas distintas), después los gimmicks bloqueantes y por último las fichas del
     * objetivo; así lo estructural nunca se queda sin sitio por culpa de lo sorteado.
     *
     * @throws IllegalArgumentException si [level] es menor que 1.
     */
    fun generate(level: Int): HexaLevelConfig {
        require(level >= 1) { "El nivel es 1-based: $level" }
        val random = Random(SEED_SALT + level)
        val mask = BoardMask.forLevel(level)
        val phase = WavePhase.forLevel(level)
        val d = difficulty(level)
        val coords = cellsFor(mask, enlarged = (level - 1) % BoardMask.LEVELS_PER_MASK >= WavePhase.CYCLE)

        val unlocked = GimmickKind.entries.filter { level >= it.unlockLevel }
        // En la introducción la mecánica nueva aparece sola (ver KDoc del objeto).
        var active = if (phase == WavePhase.INTRO) listOfNotNull(unlocked.lastOrNull()) else unlocked
        // Las islas no tienen otra forma de comunicarse: ahí los portales son
        // estructura del tablero, no un modificador opcional.
        if (mask == BoardMask.ISLANDS && GimmickKind.PORTAL !in active) active = active + GimmickKind.PORTAL

        val board = coords.associateWith { HexCell(it) }.toMutableMap()
        val pool = (coords - protectedCells(mask)).shuffled(random).toMutableList()

        if (GimmickKind.PORTAL in active) placePortals(mask, coords, board, pool, random)
        placeBlockers(active - GimmickKind.PORTAL, coords.size, d, board, pool)

        val win = winConditionFor(level, d, coords.size)
        if (win == WinCondition.ClearBoard) {
            // Las corruptas son el objetivo aunque aún no haya hielo desbloqueado: sin
            // ellas un nivel de limpieza temprano nacería ya resuelto.
            val corrupt = (3 + 2 * d).roundToInt().coerceAtMost(coords.size / 4)
            repeat(corrupt) {
                val at = pool.removeLastOrNull() ?: return@repeat
                board[at] = HexCell(at, gimmick = Gimmick.Corrupt)
            }
        }

        val clearTargets = board.values.count { it.isClearTarget }
        return HexaLevelConfig(
            level = level,
            mask = mask,
            phase = phase,
            difficulty = d,
            winCondition = win,
            moveLimit = when (win) {
                is WinCondition.TargetScore -> scoreMoves(coords.size)
                // Tres jugadas por celda objetivo: una fusión suele necesitar dos
                // piezas de preparación; el fijo cubre el arranque en tablero vacío.
                WinCondition.ClearBoard -> clearTargets * 3 + 8
                is WinCondition.Survive -> win.turns
            },
            gimmicks = active.toSet(),
            initialBoard = board.toMap(),
            pieceBag = PieceBag(
                tiers = FluxTier.entries.take(
                    if (phase == WavePhase.CATHARSIS) 2 else (2 + d.toInt()).coerceIn(2, 4),
                ),
                maxTiles = if (d < 1f) 2 else 3,
            ),
        )
    }

    /**
     * Celdas habilitadas de una máscara.
     *
     * Todas las siluetas se construyen componiendo hexágonos regulares ([hexagon]),
     * así heredan su simetría y basta razonar con distancias entre centros:
     *  - **Dona**: hexágono menos su núcleo.
     *  - **Mariposa**: dos lóbulos de radio 2 con centros a 6 pasos. Sus bordes quedan
     *    a 2 pasos (no se tocan) y el origen, adyacente a ambos, es el único puente.
     *  - **Islas**: hexágonos de radio 1 cuyos centros distan al menos 4, que es la
     *    separación mínima para que no compartan ninguna arista.
     *
     * @param enlarged variante grande, usada en la segunda mitad de cada máscara. La
     *   mariposa no tiene: ensancharla diluiría el cuello de botella que la define.
     */
    fun cellsFor(mask: BoardMask, enlarged: Boolean): Set<HexCoord> = when (mask) {
        BoardMask.CONCENTRIC -> hexagon(HexCoord.ORIGIN, if (enlarged) 3 else 2)
        BoardMask.DONUT ->
            if (enlarged) hexagon(HexCoord.ORIGIN, 3) - hexagon(HexCoord.ORIGIN, 1)
            else hexagon(HexCoord.ORIGIN, 2) - HexCoord.ORIGIN
        BoardMask.BUTTERFLY ->
            hexagon(HexCoord(-3, 0), 2) + hexagon(HexCoord(3, 0), 2) + HexCoord.ORIGIN
        BoardMask.ISLANDS -> {
            val k = if (enlarged) 4 else 3
            val outer = listOf(HexCoord(k, 0), HexCoord(-k, k), HexCoord(0, -k))
            // La isla central solo cabe con k = 4: con k = 3 quedaría pegada a las otras.
            val centers = if (enlarged) outer + HexCoord.ORIGIN else outer
            centers.flatMap { hexagon(it, 1) }.toSet()
        }
    }

    /**
     * Sortea la siguiente pieza del nivel.
     *
     * No forma parte de [generate] porque la bandeja se repone durante toda la
     * partida; el motor conserva su propio [random] para que la secuencia sea
     * reproducible desde la semilla del nivel.
     *
     * @param id identificador que tendrá la pieza ([HexPiece.id]).
     */
    fun nextPiece(bag: PieceBag, random: Random, id: Long): HexPiece {
        val size = random.nextInt(1, bag.maxTiles + 1)
        val shape = SHAPES.getValue(size).random(random)
        var piece = HexPiece(id, shape.map { PieceTile(it, bag.tiers.random(random)) })
        // Orientación inicial al azar: sin esto el jugador vería siempre las mismas
        // tres siluetas "de fábrica" y rotar sería un trámite previsible.
        repeat(random.nextInt(HexCoord.DIRECTIONS.size)) { piece = piece.rotatedClockwise() }
        return piece
    }

    /** Hexágono regular de [radius] anillos alrededor de [center] (radio 2 → 19 celdas). */
    private fun hexagon(center: HexCoord, radius: Int): Set<HexCoord> = buildSet {
        for (dq in -radius..radius) {
            // La tercera coordenada cúbica también debe quedar dentro del radio; eso
            // es lo que recorta las esquinas del rombo axial y deja un hexágono.
            for (dr in maxOf(-radius, -dq - radius)..minOf(radius, -dq + radius)) {
                add(center + HexCoord(dq, dr))
            }
        }
    }

    /**
     * Celdas que nunca reciben un gimmick. En la mariposa, tapar el cuello partiría
     * el tablero en dos sin que el jugador pudiera evitarlo.
     */
    private fun protectedCells(mask: BoardMask): Set<HexCoord> =
        if (mask == BoardMask.BUTTERFLY) setOf(HexCoord.ORIGIN) else emptySet()

    /** Objetivo del nivel; rota con periodo 3 (ver [WinCondition]). */
    private fun winConditionFor(level: Int, d: Float, cellCount: Int): WinCondition =
        when ((level - 1) % 3) {
            0 -> {
                val raw = scoreMoves(cellCount) * POINTS_PER_MOVE * (0.6f + 0.2f * d)
                // Redondeo a 50: una meta "850" se lee de un vistazo; "837" no.
                WinCondition.TargetScore(((raw / 50f).roundToInt() * 50).coerceAtLeast(100))
            }
            1 -> WinCondition.ClearBoard
            else -> WinCondition.Survive(
                turns = (12 + 4 * d).roundToInt(),
                spawnEvery = (4 - d.roundToInt()).coerceIn(1, 3),
            )
        }

    /** Jugadas de un nivel de puntuación: crecen con el tablero, que da más juego. */
    private fun scoreMoves(cellCount: Int): Int = 14 + cellCount / 2

    /**
     * Coloca las parejas de portales.
     *
     * En [BoardMask.ISLANDS] se encadenan las islas (0↔1, 1↔2…) con un portal en cada
     * extremo, lo que garantiza que todas queden comunicadas. En el resto de máscaras
     * hay una sola pareja, con sus extremos lo más alejados que permita el sorteo: un
     * portal a dos pasos de su salida no cambia ninguna decisión.
     */
    private fun placePortals(
        mask: BoardMask,
        coords: Set<HexCoord>,
        board: MutableMap<HexCoord, HexCell>,
        pool: MutableList<HexCoord>,
        random: Random,
    ) {
        val pairs: List<Pair<HexCoord, HexCoord>> = if (mask == BoardMask.ISLANDS) {
            components(coords).zipWithNext().mapNotNull { (a, b) ->
                val from = pool.filter { it in a }.randomOrNull(random)
                val to = pool.filter { it in b }.randomOrNull(random)
                if (from != null && to != null) {
                    pool.remove(from)
                    pool.remove(to)
                    from to to
                } else {
                    null
                }
            }
        } else {
            val from = pool.removeLastOrNull() ?: return
            val to = pool.maxByOrNull { it.distanceTo(from) } ?: return
            pool.remove(to)
            listOf(from to to)
        }
        pairs.forEachIndexed { id, (a, b) ->
            board[a] = HexCell(a, gimmick = Gimmick.Portal(id, exit = b))
            board[b] = HexCell(b, gimmick = Gimmick.Portal(id, exit = a))
        }
    }

    /**
     * Reparte el presupuesto de gimmicks bloqueantes entre las familias activas, en
     * rotación para que ninguna monopolice el tablero.
     */
    private fun placeBlockers(
        kinds: List<GimmickKind>,
        cellCount: Int,
        d: Float,
        board: MutableMap<HexCoord, HexCell>,
        pool: MutableList<HexCoord>,
    ) {
        if (kinds.isEmpty()) return
        val budget = (cellCount * GIMMICK_DENSITY * d).roundToInt()
            .coerceIn(1, (cellCount * MAX_BLOCKED_RATIO).toInt())
        // Las bombas se racionan aparte: cada una abre un frente con cuenta atrás y
        // varias a la vez dejan de ser una decisión táctica para ser una lotería.
        val bombCap = 1 + (d / 2f).toInt()
        var bombs = 0
        repeat(budget) { i ->
            var kind = kinds[i % kinds.size]
            if (kind == GimmickKind.TURN_BOMB && bombs >= bombCap) {
                kind = kinds.first().takeIf { it != GimmickKind.TURN_BOMB } ?: return@repeat
            }
            val at = pool.removeLastOrNull() ?: return@repeat
            board[at] = HexCell(
                coord = at,
                gimmick = when (kind) {
                    GimmickKind.ICE -> Gimmick.Ice(layers = if (d >= 2.5f) 2 else 1)
                    GimmickKind.STONE -> Gimmick.Stone
                    GimmickKind.TURN_BOMB -> {
                        bombs++
                        Gimmick.TurnBomb(turnsLeft = (9 - (d * 1.5f).roundToInt()).coerceAtLeast(4))
                    }
                    // Los portales se colocan en placePortals; aquí nunca llegan.
                    GimmickKind.PORTAL -> return@repeat
                },
            )
        }
    }

    /**
     * Componentes conexas de un conjunto de celdas (búsqueda en anchura). Se usa para
     * descubrir las islas sin tener que repetir aquí los centros de [cellsFor]: si la
     * geometría de la máscara cambia, los portales la siguen solos.
     */
    private fun components(cells: Set<HexCoord>): List<Set<HexCoord>> {
        val pending = cells.toMutableSet()
        val result = mutableListOf<Set<HexCoord>>()
        while (pending.isNotEmpty()) {
            val start = pending.first()
            pending.remove(start)
            val component = mutableSetOf(start)
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                for (next in queue.removeFirst().neighbors()) {
                    if (pending.remove(next)) {
                        component += next
                        queue += next
                    }
                }
            }
            result.add(component)
        }
        return result
    }
}
