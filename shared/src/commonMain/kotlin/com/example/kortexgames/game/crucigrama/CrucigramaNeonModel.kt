package com.example.kortexgames.game.crucigrama

import kotlin.random.Random

/** Dirección de escritura de una palabra en la rejilla. */
enum class CrucigramaDirection {
    HORIZONTAL,
    VERTICAL,
}

/**
 * Definición estática de una entrada del crucigrama (slot).
 *
 * @property number numeración visible de la pista en el tablero.
 * @property row fila inicial (0-based).
 * @property col columna inicial (0-based).
 */
data class CrucigramaNeonSlotSpec(
    val number: Int,
    val answer: String,
    val clue: String,
    val row: Int,
    val col: Int,
    val direction: CrucigramaDirection,
)

/** Especificación de nivel con una rejilla fija y palabras entrelazadas. */
data class CrucigramaNeonLevelSpec(
    val rows: Int,
    val cols: Int,
    val letters: List<Char>,
    val slots: List<CrucigramaNeonSlotSpec>,
) {
    init {
        require(rows > 0 && cols > 0) { "La rejilla debe tener tamaño positivo." }
        require(letters.isNotEmpty()) { "El teclado inferior necesita al menos una letra." }
        require(slots.isNotEmpty()) { "El nivel debe incluir al menos una pista." }

        val board = mutableMapOf<Pair<Int, Int>, Char>()
        val available = letters.toSet()
        // Pares de celdas contiguas que SÍ pertenecen a una misma palabra (por eje).
        // Sirven para validar después que ninguna otra adyacencia exista.
        val horizontalPairs = mutableSetOf<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
        val verticalPairs = mutableSetOf<Pair<Pair<Int, Int>, Pair<Int, Int>>>()

        slots.forEach { slot ->
            require(slot.answer.isNotBlank()) { "Las respuestas no pueden estar vacías." }
            require(slot.answer == slot.answer.uppercase()) {
                "Las respuestas del crucigrama deben estar en mayúsculas para conservar consistencia visual."
            }
            require(slot.answer.all { it in available }) {
                "La respuesta '${slot.answer}' usa letras que no existen en el teclado del nivel."
            }
            var prev: Pair<Int, Int>? = null
            slot.answer.forEachIndexed { index, letter ->
                val r = if (slot.direction == CrucigramaDirection.VERTICAL) slot.row + index else slot.row
                val c = if (slot.direction == CrucigramaDirection.HORIZONTAL) slot.col + index else slot.col
                require(r in 0 until rows && c in 0 until cols) {
                    "La palabra '${slot.answer}' sale de la rejilla en ($r,$c)."
                }
                val key = r to c
                val existing = board[key]
                require(existing == null || existing == letter) {
                    "Cruce inválido en ($r,$c): '$existing' vs '$letter'."
                }
                board[key] = letter
                // Registrar el par contiguo dentro de la palabra según su dirección.
                prev?.let { p ->
                    if (slot.direction == CrucigramaDirection.HORIZONTAL) horizontalPairs.add(p to key)
                    else verticalPairs.add(p to key)
                }
                prev = key
            }
        }

        // Validación de crucigrama (entrelazado correcto): dos celdas ADYACENTES
        // rellenas deben pertenecer a una MISMA palabra en esa dirección. Si no, al
        // leer la fila/columna se formaría una palabra falsa —p. ej. una 'A' pegada al
        // lateral de "MORA" se lee "AMORA"—. Esto obliga a que las palabras solo se
        // toquen en cruces legítimos (una celda compartida perpendicular), nunca lado
        // a lado ni prolongándose unas a otras.
        board.keys.forEach { (r, c) ->
            val right = r to (c + 1)
            require(right !in board || (r to c to right) in horizontalPairs) {
                "Entrelazado inválido: las celdas ($r,$c) y $right se tocan en horizontal sin ser la misma palabra (formarían una palabra falsa). Sepáralas o crúzalas en una sola celda."
            }
            val down = (r + 1) to c
            require(down !in board || (r to c to down) in verticalPairs) {
                "Entrelazado inválido: las celdas ($r,$c) y $down se tocan en vertical sin ser la misma palabra (formarían una palabra falsa). Sepáralas o crúzalas en una sola celda."
            }
        }
    }
}

/** Estado de una pista durante la partida. */
data class CrucigramaNeonSlotState(
    val number: Int,
    val clue: String,
    val answer: String,
    val row: Int,
    val col: Int,
    val direction: CrucigramaDirection,
    val cellIndices: List<Int>,
    val solved: Boolean = false,
    val solvedAtTick: Long? = null,
)

/** Estado de una celda jugable de la rejilla. */
data class CrucigramaNeonCellState(
    val index: Int,
    val row: Int,
    val col: Int,
    val solution: Char,
    val slotNumbers: Set<Int>,
    val entry: Char? = null,
    val fixed: Boolean = false,
)

/** Puzzle generado listo para pintar y jugar. */
data class CrucigramaNeonPuzzle(
    val rows: Int,
    val cols: Int,
    val letters: List<Char>,
    val cells: List<CrucigramaNeonCellState>,
    val slots: List<CrucigramaNeonSlotState>,
)

/**
 * Generador de crucigramas entrelazados.
 *
 * El set de niveles base es finito y luego cicla para mantener progreso continuo.
 */
object CrucigramaNeonGenerator {
    private val levels: List<CrucigramaNeonLevelSpec> = listOf(
        // Nivel 1 (A,M,O,R,S): las palabras solo se tocan en cruces perpendiculares.
        // AMOR (H, fila 0) — MAR baja por su M, RAMO baja por su R y ROSA cruza a RAMO
        // por su O. Ninguna corre pegada de lado a otra: no hay "AMORA".
        CrucigramaNeonLevelSpec(
            rows = 4,
            cols = 6,
            letters = listOf('A', 'M', 'O', 'R', 'S'),
            slots = listOf(
                CrucigramaNeonSlotSpec(1, "AMOR", "Sentimiento de afecto", 0, 0, CrucigramaDirection.HORIZONTAL),
                CrucigramaNeonSlotSpec(2, "MAR", "Gran masa de agua salada", 0, 1, CrucigramaDirection.VERTICAL),
                CrucigramaNeonSlotSpec(3, "RAMO", "Conjunto de flores", 0, 3, CrucigramaDirection.VERTICAL),
                CrucigramaNeonSlotSpec(4, "ROSA", "Flor con espinas", 3, 2, CrucigramaDirection.HORIZONTAL),
            ),
        ),
        // Nivel 2 (C,A,S,O): escalera CASA → SACO → COSA → ASA, cada palabra cruza a la
        // anterior en una única celda compartida.
        CrucigramaNeonLevelSpec(
            rows = 5,
            cols = 6,
            letters = listOf('C', 'A', 'S', 'O'),
            slots = listOf(
                CrucigramaNeonSlotSpec(1, "CASA", "Lugar donde vives", 0, 0, CrucigramaDirection.HORIZONTAL),
                CrucigramaNeonSlotSpec(2, "SACO", "Bolsa grande", 0, 2, CrucigramaDirection.VERTICAL),
                CrucigramaNeonSlotSpec(3, "COSA", "Objeto o asunto", 2, 2, CrucigramaDirection.HORIZONTAL),
                CrucigramaNeonSlotSpec(4, "ASA", "Parte por donde se agarra una taza", 2, 5, CrucigramaDirection.VERTICAL),
            ),
        ),
        // Nivel 3 (P,A,T): PATA (H) cruza TAPA (V) por la T; APTA (H) cruza TAPA por la P.
        CrucigramaNeonLevelSpec(
            rows = 4,
            cols = 5,
            letters = listOf('P', 'A', 'T'),
            slots = listOf(
                CrucigramaNeonSlotSpec(1, "PATA", "Extremidad de un animal", 0, 0, CrucigramaDirection.HORIZONTAL),
                CrucigramaNeonSlotSpec(2, "TAPA", "Cubre un recipiente", 0, 2, CrucigramaDirection.VERTICAL),
                CrucigramaNeonSlotSpec(3, "APTA", "Adecuada o capaz", 2, 1, CrucigramaDirection.HORIZONTAL),
            ),
        ),
    )

    /** Genera el puzzle del nivel solicitado (1-based, cíclico). */
    fun generate(level: Int, random: Random = Random.Default): CrucigramaNeonPuzzle {
        val spec = levels[indexFor(level)]
        val cellMap = linkedMapOf<Pair<Int, Int>, CrucigramaNeonCellState>()
        val slots = spec.slots.map { slot ->
            val cellIndices = slot.answer.mapIndexed { i, letter ->
                val r = if (slot.direction == CrucigramaDirection.VERTICAL) slot.row + i else slot.row
                val c = if (slot.direction == CrucigramaDirection.HORIZONTAL) slot.col + i else slot.col
                val key = r to c
                val existing = cellMap[key]
                if (existing == null) {
                    val created = CrucigramaNeonCellState(
                        index = cellMap.size,
                        row = r,
                        col = c,
                        solution = letter,
                        slotNumbers = setOf(slot.number),
                    )
                    cellMap[key] = created
                    created.index
                } else {
                    cellMap[key] = existing.copy(slotNumbers = existing.slotNumbers + slot.number)
                    existing.index
                }
            }
            CrucigramaNeonSlotState(
                number = slot.number,
                clue = slot.clue,
                answer = slot.answer,
                row = slot.row,
                col = slot.col,
                direction = slot.direction,
                cellIndices = cellIndices,
            )
        }

        return CrucigramaNeonPuzzle(
            rows = spec.rows,
            cols = spec.cols,
            letters = spec.letters.shuffled(random),
            cells = cellMap.values.toList().sortedBy { it.index },
            slots = slots.sortedBy { it.number },
        )
    }

    private fun indexFor(level: Int): Int {
        val zeroBased = (level - 1) % levels.size
        return if (zeroBased >= 0) zeroBased else zeroBased + levels.size
    }
}

