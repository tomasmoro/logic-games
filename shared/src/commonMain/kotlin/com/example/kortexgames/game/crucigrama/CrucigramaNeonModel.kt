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

/**
 * Especificación de nivel con una rejilla fija y palabras entrelazadas.
 *
 * @property extraWords palabras **bonus** que también se forman con el teclado del
 *   nivel pero NO se colocan en la rejilla. El jugador puede descubrirlas escribiendo
 *   y se muestran en el panel lateral de "extras". No deben ser prefijo de una palabra
 *   de la rejilla (si no, se consumirían antes de poder completar esa palabra).
 */
data class CrucigramaNeonLevelSpec(
    val rows: Int,
    val cols: Int,
    val letters: List<Char>,
    val slots: List<CrucigramaNeonSlotSpec>,
    val extraWords: List<String> = emptyList(),
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
        // lateral de "MORA" se lee "AMORA"—.
        board.keys.forEach { (r, c) ->
            val right = r to (c + 1)
            require(right !in board || (r to c to right) in horizontalPairs) {
                "Entrelazado inválido: las celdas ($r,$c) y $right se tocan en horizontal sin ser la misma palabra."
            }
            val down = (r + 1) to c
            require(down !in board || (r to c to down) in verticalPairs) {
                "Entrelazado inválido: las celdas ($r,$c) y $down se tocan en vertical sin ser la misma palabra."
            }
        }

        // Validación de las palabras extra (bonus).
        val gridAnswers = slots.map { it.answer }.toSet()
        val gridMaxLen = slots.maxOf { it.answer.length }
        extraWords.forEach { extra ->
            require(extra.isNotBlank()) { "Las palabras extra no pueden estar vacías." }
            require(extra == extra.uppercase()) { "La palabra extra '$extra' debe ir en mayúsculas." }
            require(extra.all { it in available }) {
                "La palabra extra '$extra' usa letras que no existen en el teclado del nivel."
            }
            require(extra !in gridAnswers) { "La palabra extra '$extra' ya está en la rejilla." }
            require(gridAnswers.none { it != extra && it.startsWith(extra) }) {
                "La palabra extra '$extra' es prefijo de una palabra de la rejilla."
            }
            require(extra.length <= gridMaxLen) {
                "La palabra extra '$extra' es más larga que la palabra mayor de la rejilla y no podría escribirse."
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
    val extraWords: List<String> = emptyList(),
)

/**
 * Generador de crucigramas entrelazados.
 *
 * Cada nivel se define solo como una **lista de palabras** (con su pista) más las
 * palabras extra (bonus). Un **entrelazador por backtracking** ([placeWords]) calcula
 * automáticamente posiciones válidas —las palabras solo se tocan en cruces legítimos,
 * sin palabras falsas— lo que permite subir a 4–7 palabras de longitudes variadas sin
 * colocarlas a mano. Dificultad creciente: 4 palabras y +1 cada 4 niveles hasta 7.
 *
 * El set de niveles base es finito y luego cicla para mantener progreso continuo.
 */
object CrucigramaNeonGenerator {

    /** Palabra ya colocada en la rejilla durante el entrelazado. */
    private data class Placed(val word: String, val row: Int, val col: Int, val dir: CrucigramaDirection) {
        fun cell(i: Int): Pair<Int, Int> {
            val r = if (dir == CrucigramaDirection.VERTICAL) row + i else row
            val c = if (dir == CrucigramaDirection.HORIZONTAL) col + i else col
            return r to c
        }
    }

    /** Definición de un nivel: palabras (palabra→pista) a entrelazar + extras (bonus). */
    private class LevelDef(
        val words: List<Pair<String, String>>,
        val extras: List<String>,
    )

    private val DIRECTIONS = listOf(CrucigramaDirection.HORIZONTAL, CrucigramaDirection.VERTICAL)

    /**
     * Comprueba una colocación tentativa: sin conflictos de letra en los cruces y —lo
     * esencial— que **cada tramo máximo** (horizontal o vertical) de longitud ≥2 sea
     * EXACTAMENTE una de las palabras colocadas. Eso descarta fusiones ("AMORA") y
     * palabras escondidas dentro de otra, garantizando un crucigrama legible.
     */
    private fun layoutValid(placed: List<Placed>): Boolean {
        val grid = HashMap<Pair<Int, Int>, Char>()
        for (p in placed) {
            for (i in p.word.indices) {
                val cell = p.cell(i)
                val ex = grid[cell]
                if (ex != null && ex != p.word[i]) return false
                grid[cell] = p.word[i]
            }
        }
        if (grid.isEmpty()) return true
        val minR = grid.keys.minOf { it.first }
        val maxR = grid.keys.maxOf { it.first }
        val minC = grid.keys.minOf { it.second }
        val maxC = grid.keys.maxOf { it.second }
        val runs = mutableListOf<String>()
        for (r in minR..maxR) {
            var c = minC
            while (c <= maxC) {
                if (grid[r to c] == null) { c++; continue }
                val sb = StringBuilder()
                while (c <= maxC && grid[r to c] != null) { sb.append(grid[r to c]); c++ }
                if (sb.length >= 2) runs.add(sb.toString())
            }
        }
        for (c in minC..maxC) {
            var r = minR
            while (r <= maxR) {
                if (grid[r to c] == null) { r++; continue }
                val sb = StringBuilder()
                while (r <= maxR && grid[r to c] != null) { sb.append(grid[r to c]); r++ }
                if (sb.length >= 2) runs.add(sb.toString())
            }
        }
        return runs.sorted() == placed.map { it.word }.sorted()
    }

    /** Coloca la primera palabra en horizontal y entrelaza el resto por backtracking. */
    private fun placeWords(words: List<String>): List<Placed> {
        val start = Placed(words[0], 0, 0, CrucigramaDirection.HORIZONTAL)
        return backtrack(listOf(start), words, 1)
            ?: error("No se pudo entrelazar el nivel con las palabras: $words")
    }

    private fun backtrack(placed: List<Placed>, words: List<String>, idx: Int): List<Placed>? {
        if (idx >= words.size) return placed
        val word = words[idx]
        // Cada celda ya colocada es un posible ancla de cruce (si la letra coincide).
        val anchors = placed.flatMap { p -> p.word.indices.map { p.cell(it) to p.word[it] } }
        for ((cell, ch) in anchors) {
            for (k in word.indices) {
                if (word[k] != ch) continue
                for (dir in DIRECTIONS) {
                    val row = if (dir == CrucigramaDirection.VERTICAL) cell.first - k else cell.first
                    val col = if (dir == CrucigramaDirection.HORIZONTAL) cell.second - k else cell.second
                    val next = placed + Placed(word, row, col, dir)
                    if (layoutValid(next)) {
                        backtrack(next, words, idx + 1)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    /** Entrelaza las palabras del nivel y construye su [CrucigramaNeonLevelSpec]. */
    private fun buildSpec(def: LevelDef): CrucigramaNeonLevelSpec {
        val order = def.words.map { it.first }
        val placed = placeWords(order)
        val minR = placed.minOf { p -> p.word.indices.minOf { p.cell(it).first } }
        val minC = placed.minOf { p -> p.word.indices.minOf { p.cell(it).second } }
        val norm = placed.map { it.copy(row = it.row - minR, col = it.col - minC) }
        val rows = norm.maxOf { p -> p.word.indices.maxOf { p.cell(it).first } } + 1
        val cols = norm.maxOf { p -> p.word.indices.maxOf { p.cell(it).second } } + 1
        val clue = def.words.toMap()
        val slots = norm.mapIndexed { i, p ->
            CrucigramaNeonSlotSpec(i + 1, p.word, clue.getValue(p.word), p.row, p.col, p.dir)
        }
        val letters = (order.flatMap { it.toList() } + def.extras.flatMap { it.toList() }).distinct()
        return CrucigramaNeonLevelSpec(rows, cols, letters, slots, def.extras)
    }

    // Dificultad creciente: niveles 1-4 con 4 palabras, +1 cada 4 niveles hasta 7
    // (5 palabras 5-8, 6 palabras 9-12, 7 palabras 13-20). Longitudes variadas (3-6).
    private val levelDefs: List<LevelDef> = listOf(
        // --- 4 palabras -----------------------------------------------------
        LevelDef(
            listOf(
                "AMOR" to "Sentimiento de afecto",
                "RAMO" to "Conjunto de flores",
                "MORA" to "Fruta del moral",
                "MAR" to "Gran masa de agua salada",
            ),
            extras = listOf("ROMA", "ORO", "ARO", "RAMA"),
        ),
        LevelDef(
            listOf(
                "CASA" to "Vivienda",
                "COSA" to "Objeto o asunto",
                "SACO" to "Bolsa grande",
                "OCA" to "Ave parecida al ganso",
            ),
            extras = listOf("ASCO", "CASO", "CAOS", "SACA", "OSA"),
        ),
        LevelDef(
            listOf(
                "PLATO" to "Vajilla donde comes",
                "PATO" to "Ave de agua",
                "PALO" to "Trozo de madera",
                "ALTO" to "De gran estatura",
            ),
            extras = listOf("LATA", "PALA", "LOSA", "PASO", "TOPA", "OLA"),
        ),
        LevelDef(
            listOf(
                "RATON" to "Roedor pequeño",
                "ROTA" to "Quebrada, dañada",
                "TORO" to "Macho de la vaca",
                "NORA" to "Rueda para sacar agua",
            ),
            extras = listOf("TORA", "NATO", "ORA", "ATO", "TARO"),
        ),
        // --- 5 palabras -----------------------------------------------------
        LevelDef(
            listOf(
                "RESTA" to "Operación de quitar",
                "REMO" to "Pala para impulsar un bote",
                "META" to "Objetivo o línea de llegada",
                "MESA" to "Mueble para comer",
                "MAR" to "Gran masa de agua salada",
            ),
            extras = listOf("MATE", "RETO", "TOMA", "SETA", "ROMA", "MAREO", "ESTO"),
        ),
        LevelDef(
            listOf(
                "PLATO" to "Vajilla donde comes",
                "SALTO" to "Brinco",
                "PALO" to "Trozo de madera",
                "SOPA" to "Plato con caldo",
                "ALTO" to "De gran estatura",
            ),
            extras = listOf("LATA", "PALA", "LOSA", "PASO", "TOPA", "OLA"),
        ),
        LevelDef(
            listOf(
                "CARTA" to "Escrito que se envía",
                "COSTA" to "Orilla del mar",
                "CARO" to "De precio elevado",
                "RATO" to "Momento breve",
                "ROSA" to "Flor con espinas",
            ),
            extras = listOf("SACO", "ARCO", "ORCA", "COSA", "CASO", "ROCA", "ACTOR"),
        ),
        LevelDef(
            listOf(
                "LIMON" to "Fruta cítrica amarilla",
                "LIMA" to "Fruta cítrica verde",
                "MANO" to "Parte final del brazo",
                "MONA" to "Hembra del mono",
                "ALMA" to "Espíritu de una persona",
            ),
            extras = listOf("LOMA", "MOLA", "MINA", "LINO", "MIL", "MAL", "OLA"),
        ),
        // --- 6 palabras -----------------------------------------------------
        LevelDef(
            listOf(
                "COSTA" to "Orilla del mar",
                "CARTA" to "Escrito que se envía",
                "CASA" to "Vivienda",
                "SACO" to "Bolsa grande",
                "RATO" to "Momento breve",
                "OSO" to "Animal grande y peludo",
            ),
            extras = listOf("CASO", "ROSA", "ROCA", "ARCO", "COSA", "ACTOR"),
        ),
        LevelDef(
            listOf(
                "PARTE" to "Porción de algo",
                "OPERA" to "Obra musical cantada",
                "PERA" to "Fruta verde o amarilla",
                "PATO" to "Ave de agua",
                "RETO" to "Desafío",
                "PARO" to "Detención del trabajo",
            ),
            extras = listOf("PERO", "ROPA", "PARE", "PATE", "TOPE", "RAPTO"),
        ),
        LevelDef(
            listOf(
                "SALON" to "Sala grande de una casa",
                "SANTO" to "Persona venerada",
                "TALON" to "Parte trasera del pie",
                "LATA" to "Envase metálico",
                "LONA" to "Tela fuerte de toldos",
                "NATA" to "Capa que se forma en la leche",
            ),
            extras = listOf("SALTO", "SANO", "LOSA", "ALTO", "SOLA", "NOTA"),
        ),
        LevelDef(
            listOf(
                "RATON" to "Roedor pequeño",
                "TANGO" to "Baile argentino",
                "RANGO" to "Nivel o categoría",
                "GATO" to "Felino doméstico",
                "GOTA" to "Pequeña porción de líquido",
                "TORO" to "Macho de la vaca",
            ),
            extras = listOf("NORA", "NATO", "GRANO", "TRAGO", "TORA", "ROTA"),
        ),
        // --- 7 palabras -----------------------------------------------------
        LevelDef(
            listOf(
                "PLATO" to "Vajilla donde comes",
                "SALTO" to "Brinco",
                "PALO" to "Trozo de madera",
                "SOPA" to "Plato con caldo",
                "ALTO" to "De gran estatura",
                "LAPSO" to "Periodo breve de tiempo",
                "POSTA" to "Parada o relevo en un trayecto",
            ),
            extras = listOf("TOPA", "PATO", "PASO", "SOLA", "LATA"),
        ),
        LevelDef(
            listOf(
                "CARTON" to "Papel grueso y rígido",
                "CANTO" to "Acción de cantar / borde",
                "RATON" to "Roedor pequeño",
                "CARO" to "De precio elevado",
                "RATO" to "Momento breve",
                "ARCO" to "Elemento curvo de una estructura",
                "ROCA" to "Masa de piedra sólida",
            ),
            extras = listOf("ACTOR", "TRONCO", "CORTA", "RONCA"),
        ),
        LevelDef(
            listOf(
                "MARINO" to "Relativo al mar",
                "NORMA" to "Regla a seguir",
                "MANO" to "Parte final del brazo",
                "RAMO" to "Conjunto de flores",
                "MINA" to "Excavación para extraer minerales",
                "RIMA" to "Repetición de sonidos en un verso",
                "AMOR" to "Sentimiento de afecto",
            ),
            extras = listOf("MORA", "ROMA", "NORIA", "MIRA", "ORO", "ARO"),
        ),
        LevelDef(
            listOf(
                "SARTEN" to "Utensilio para freír",
                "RESTA" to "Operación de quitar",
                "TENSA" to "En estado de tensión",
                "ANTES" to "En un momento previo",
                "ARNES" to "Equipo de sujeción para seguridad",
                "SERA" to "verbo ser: él ___ (futuro)",
                "RANA" to "Anfibio que croa",
            ),
            extras = listOf("TERNA", "SARNA", "ARTE", "RASA", "SETA"),
        ),
        LevelDef(
            listOf(
                "TROMPA" to "Nariz alargada del elefante",
                "RAMPA" to "Plano inclinado",
                "MAPA" to "Representación de un territorio",
                "ROMA" to "Capital de Italia",
                "PASO" to "Movimiento al andar",
                "AMOR" to "Sentimiento de afecto",
                "SOPA" to "Plato con caldo",
            ),
            extras = listOf("MORA", "RAMO", "ROPA", "TROPA", "PASTO", "PROSA"),
        ),
        LevelDef(
            listOf(
                "PALOMA" to "Ave símbolo de la paz",
                "MORAL" to "Relativo a la ética",
                "LORO" to "Ave que imita la voz",
                "RAMO" to "Conjunto de flores",
                "AMOR" to "Sentimiento de afecto",
                "LOMA" to "Colina baja",
                "MALO" to "Sin bondad o calidad",
            ),
            extras = listOf("ROMA", "PALMA", "PLOMO", "RAMPA", "MOLAR"),
        ),
        LevelDef(
            listOf(
                "MORAL" to "Relativo a la ética",
                "LORO" to "Ave que imita la voz",
                "RAMO" to "Conjunto de flores",
                "AMOR" to "Sentimiento de afecto",
                "LOMA" to "Colina baja",
                "MALO" to "Sin bondad o calidad",
                "ROMA" to "Capital de Italia",
            ),
            extras = listOf("AROMA", "MOLAR", "RAMAL", "ORAL", "LORA"),
        ),
        LevelDef(
            listOf(
                "CARTEL" to "Anuncio pegado en la pared",
                "RECTA" to "Línea sin curvas",
                "CARTA" to "Escrito que se envía",
                "LETRA" to "Signo del alfabeto",
                "CALLE" to "Vía urbana entre casas",
                "TELAR" to "Máquina para tejer",
                "ARTE" to "Expresión creativa",
            ),
            extras = listOf("LACRE", "CATRE", "CALAR", "REAL", "TRAE"),
        ),
    )

    private val levels: List<CrucigramaNeonLevelSpec> by lazy { levelDefs.map { buildSpec(it) } }

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
            extraWords = spec.extraWords,
        )
    }

    private fun indexFor(level: Int): Int {
        val zeroBased = (level - 1) % levels.size
        return if (zeroBased >= 0) zeroBased else zeroBased + levels.size
    }
}
