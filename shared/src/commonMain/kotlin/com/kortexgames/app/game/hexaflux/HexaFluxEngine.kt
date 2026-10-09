package com.kortexgames.app.game.hexaflux

import kotlin.random.Random

/**
 * # HexaFluxEngine — reglas del tablero
 *
 * Reductor **puro** de Neon Hexa Flux: recibe un tablero inmutable y devuelve otro.
 * No guarda estado ni conoce el objetivo del nivel, el cronómetro o la puntuación
 * final; eso es cosa de [HexaFluxViewModel]. La separación existe para poder fijar
 * las reglas con tests sin montar corrutinas ni repositorios.
 *
 * Un turno son dos pasos, y el ViewModel decide si el segundo llega a ocurrir:
 *
 *  1. [place] — coloca la pieza (con paso por portales) y resuelve todas las
 *     fusiones en cadena que provoque.
 *  2. [endTurn] — el tablero "responde": avanzan las bombas y, en supervivencia,
 *     aparece una ficha corrupta. Si la jugada ya cumplió el objetivo este paso se
 *     omite: castigar al jugador después de ganar no tendría sentido.
 *
 * ## Fusión y combo
 * Tras colocar, cada ficha nueva es una *semilla*. Si la semilla pertenece a un
 * grupo conexo (vecindad de 6) de [HexaFluxConfig.MERGE_GROUP] o más fichas de su
 * mismo tier, el grupo entero se retira y en la celda de la semilla queda una ficha
 * del tier siguiente, que vuelve a ser semilla. Como aquí no hay gravedad, **solo la
 * celda fusionada puede haber creado un grupo nuevo**: no hace falta re-escanear el
 * tablero, y la cadena termina sola porque cada fusión retira al menos dos fichas.
 *
 * Cada fusión de la cadena multiplica sus puntos por su posición en ella (×1, ×2,
 * ×3…), que es lo que hace rentable preparar una reacción en vez de fusionar a la
 * primera oportunidad.
 */
object HexaFluxEngine {

    /** Puntos por cada golpe a un gimmick (capa de hielo, bomba desactivada, corrupta). */
    const val GIMMICK_POINTS = 15

    /**
     * Resultado de [place].
     *
     * @property board tablero tras colocar y fusionar.
     * @property scoreGained puntos obtenidos por la jugada.
     * @property combo fusiones encadenadas; `0` si la pieza no fusionó nada.
     * @property gimmicksBroken gimmicks retirados del todo (una capa de hielo que
     *   deja otra debajo no cuenta: la celda sigue bloqueada).
     * @property teleported `true` si alguna ficha cruzó un portal.
     */
    data class Placement(
        val board: Map<HexCoord, HexCell>,
        val scoreGained: Int,
        val combo: Int,
        val gimmicksBroken: Int,
        val teleported: Boolean,
    )

    /**
     * Resultado de [endTurn].
     *
     * @property board tablero tras la respuesta del nivel.
     * @property bombsDetonated bombas que llegaron a cero en este turno.
     */
    data class TurnEnd(val board: Map<HexCoord, HexCell>, val bombsDetonated: Int)

    /** `true` si todas las fichas de [piece], anclada en [anchor], caen en celdas libres. */
    fun canPlace(board: Map<HexCoord, HexCell>, piece: HexPiece, anchor: HexCoord): Boolean =
        piece.cellsAt(anchor).all { board[it]?.isFree == true }

    /**
     * `true` si alguna de [pieces] cabe en algún sitio **en alguna rotación**. Rotar
     * es gratis, así que una pieza que solo entra girada sigue siendo una jugada: sin
     * probar las seis orientaciones se declararía la derrota con el tablero aún vivo.
     */
    fun hasAnyMove(board: Map<HexCoord, HexCell>, pieces: List<HexPiece>): Boolean =
        pieces.any { piece ->
            generateSequence(piece) { it.rotatedClockwise() }
                .take(HexCoord.DIRECTIONS.size)
                .any { rotated -> board.keys.any { canPlace(board, rotated, it) } }
        }

    /**
     * Coloca [piece] con su ancla en [anchor] y resuelve las fusiones.
     *
     * @return el resultado, o `null` si la colocación es ilegal (el tablero no cambia
     *   y el turno no se consume).
     */
    fun place(board: Map<HexCoord, HexCell>, piece: HexPiece, anchor: HexCoord): Placement? {
        if (!canPlace(board, piece, anchor)) return null
        // LinkedHashMap y no HashMap: el orden de iteración del tablero decide qué
        // celda elige `endTurn` al sortear, y debe ser el mismo en todas las plataformas.
        val cells = board.mapValuesTo(LinkedHashMap()) { (_, cell) ->
            if (cell.anim == CellAnim.NONE) cell else cell.copy(anim = CellAnim.NONE)
        }

        val targets = piece.cellsAt(anchor)
        val seeds = ArrayDeque<HexCoord>()
        var teleported = false
        for (tile in piece.tiles) {
            val at = anchor + tile.offset
            val portal = cells.getValue(at).gimmick as? Gimmick.Portal
            // La salida debe estar libre y no ser destino de otra ficha de esta misma
            // pieza: si no, la que llega después se encontraría la celda ocupada.
            val exit = portal?.let { cells[it.exit] }?.takeIf { it.tier == null && it.coord !in targets }
            if (exit != null) {
                // En CORE no hay tier superior: la ficha cruza sin cambiar.
                cells[exit.coord] = exit.copy(tier = tile.tier.next ?: tile.tier, anim = CellAnim.TELEPORTED)
                seeds += exit.coord
                teleported = true
            } else {
                cells[at] = cells.getValue(at).copy(tier = tile.tier, anim = CellAnim.PLACED)
                seeds += at
            }
        }

        var combo = 0
        var gained = 0
        var broken = 0
        while (seeds.isNotEmpty()) {
            val seed = seeds.removeFirst()
            // Una semilla anterior pudo llevarse esta ficha en su fusión.
            val tier = cells[seed]?.tier ?: continue
            val group = groupOf(cells, seed, tier)
            if (group.size < HexaFluxConfig.MERGE_GROUP) continue

            combo++
            gained += tier.points * group.size * combo
            for (at in group) cells[at] = cells.getValue(at).copy(tier = null, anim = CellAnim.NONE)
            // Un grupo de CORE no tiene a qué ascender: se desintegra y libera sus celdas.
            tier.next?.let { upgraded ->
                cells[seed] = cells.getValue(seed).copy(tier = upgraded, anim = CellAnim.MERGED)
                seeds += seed
            }

            // La onda de la fusión golpea a los gimmicks que tocan CUALQUIER celda del
            // grupo, no solo la semilla: así un grupo grande limpia más, que es el
            // incentivo para construirlo junto a los obstáculos.
            for (at in group.flatMapTo(LinkedHashSet()) { it.neighbors() }) {
                val cell = cells[at] ?: continue
                val gimmick = cell.gimmick?.takeIf { it.breaksOnAdjacentMerge } ?: continue
                val remaining = (gimmick as? Gimmick.Ice)?.takeIf { it.layers > 1 }?.let { it.copy(layers = it.layers - 1) }
                cells[at] = cell.copy(gimmick = remaining, anim = CellAnim.SHATTERED)
                if (remaining == null) broken++
                gained += GIMMICK_POINTS * combo
            }
        }
        return Placement(cells, gained, combo, broken, teleported)
    }

    /**
     * Respuesta del tablero al final del turno.
     *
     * Las bombas detonan **convirtiéndose en metal junto con sus vecinas vacías**, en
     * vez de terminar la partida: perder de golpe por un contador que quizá no se
     * podía alcanzar sería frustrante, mientras que perder terreno es un castigo que
     * el jugador puede ver venir, medir y a veces asumir a propósito.
     *
     * @param spawnCorrupt si toca que aparezca una ficha corrupta (lo decide el
     *   ViewModel según [WinCondition.Survive.spawnEvery]).
     * @param random fuente del sorteo de la celda; la conserva el ViewModel para que
     *   la partida sea reproducible desde la semilla del nivel.
     */
    fun endTurn(board: Map<HexCoord, HexCell>, spawnCorrupt: Boolean, random: Random): TurnEnd {
        val cells = LinkedHashMap(board)
        var detonated = 0
        for (cell in board.values) {
            val bomb = cell.gimmick as? Gimmick.TurnBomb ?: continue
            if (bomb.turnsLeft > 1) {
                cells[cell.coord] = cell.copy(gimmick = bomb.copy(turnsLeft = bomb.turnsLeft - 1))
                continue
            }
            detonated++
            cells[cell.coord] = cell.copy(gimmick = Gimmick.Stone, anim = CellAnim.SPAWNED)
            for (near in cell.coord.neighbors()) {
                val neighbor = cells[near] ?: continue
                // Solo se funden las celdas vacías: las fichas, portales y demás
                // gimmicks vecinos sobreviven a la detonación.
                if (neighbor.tier == null && neighbor.gimmick == null) {
                    cells[near] = neighbor.copy(gimmick = Gimmick.Stone, anim = CellAnim.SPAWNED)
                }
            }
        }
        if (spawnCorrupt) {
            cells.values.filter { it.tier == null && it.gimmick == null }.randomOrNull(random)?.let {
                cells[it.coord] = it.copy(gimmick = Gimmick.Corrupt, anim = CellAnim.SPAWNED)
            }
        }
        return TurnEnd(cells, detonated)
    }

    /** Grupo conexo de fichas de [tier] que contiene a [start] (búsqueda en anchura, 6 vecinos). */
    private fun groupOf(cells: Map<HexCoord, HexCell>, start: HexCoord, tier: FluxTier): Set<HexCoord> {
        val group = linkedSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            for (next in queue.removeFirst().neighbors()) {
                if (cells[next]?.tier == tier && group.add(next)) queue += next
            }
        }
        return group
    }
}
