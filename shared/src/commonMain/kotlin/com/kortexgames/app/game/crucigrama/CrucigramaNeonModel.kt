package com.kortexgames.app.game.crucigrama

import kotlinx.serialization.Serializable
import kotlin.random.Random

/** Dirección de escritura de una palabra en la rejilla. */
@Serializable
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
                val r =
                    if (slot.direction == CrucigramaDirection.VERTICAL) slot.row + index else slot.row
                val c =
                    if (slot.direction == CrucigramaDirection.HORIZONTAL) slot.col + index else slot.col
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
@Serializable
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
@Serializable
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
    private data class Placed(
        val word: String,
        val row: Int,
        val col: Int,
        val dir: CrucigramaDirection
    ) {
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
                if (grid[r to c] == null) {
                    c++; continue
                }
                val sb = StringBuilder()
                while (c <= maxC && grid[r to c] != null) {
                    sb.append(grid[r to c]); c++
                }
                if (sb.length >= 2) runs.add(sb.toString())
            }
        }
        for (c in minC..maxC) {
            var r = minR
            while (r <= maxR) {
                if (grid[r to c] == null) {
                    r++; continue
                }
                val sb = StringBuilder()
                while (r <= maxR && grid[r to c] != null) {
                    sb.append(grid[r to c]); r++
                }
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
                    val row =
                        if (dir == CrucigramaDirection.VERTICAL) cell.first - k else cell.first
                    val col =
                        if (dir == CrucigramaDirection.HORIZONTAL) cell.second - k else cell.second
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
        val letters =
            (order.flatMap { it.toList() } + def.extras.flatMap { it.toList() }).distinct()
        return CrucigramaNeonLevelSpec(rows, cols, letters, slots, def.extras)
    }

    // Dificultad creciente: niveles 1-4 con 4 palabras, +1 cada 4 niveles hasta 7
    // (5 palabras 5-8, 6 palabras 9-12, 7 palabras 13-20). Longitudes variadas (3-6).
    private val levelDefs: List<LevelDef> = listOf(
        LevelDef(
            listOf(
                "BANANA" to "Fruto alargado de cáscara amarilla, blando y muy rico en potasio",
                "ANANA" to "Fruta tropical de pulpa amarilla y corona de hojas, también llamada piña",
                "BABA" to "Líquido espeso y viscoso que se segrega en la boca o que sueltan algunos animales",
                "NANA" to "Canto suave, lento y melodioso que se utiliza para arrullar y dormir a los bebés",
            ),
            extras = listOf("ABA"),
        ),
        LevelDef(
            listOf(
                "AMOR" to "Sentimiento de afecto",
                "RAMO" to "Conjunto de flores",
                "MORA" to "Fruta del moral",
                "MAR" to "Gran masa de agua salada",
                "ORO" to "Metal precioso",
            ),
            extras = listOf("ROMA", "ARO", "RAMA", "MARA", "ARMA"),
        ),
        LevelDef(
            listOf(
                "ACTA" to "Documento escrito donde se registra lo sucedido en una reunión",
                "CATA" to "Acción de probar o degustar un alimento o bebida para examinar su calidad",
                "ATACA" to "Acomete, embiste o inicia una acción ofensiva contra alguien o algo",
                "ACATA" to "Respeta, obedece o acepta una orden, norma o autoridad",
                "ATA" to "Amarra",
            ),
            extras = emptyList(),
        ),
        LevelDef(
            listOf(
                "TRATAR" to "Intentar",
                "RATA" to "Roedor",
                "ARAR" to "Labrar la tierra",
                "TARTA" to "Pastel",
                "ATAR" to "Amarrar",
            ),
            extras = listOf("RARA", "TARAR"),
        ),
        LevelDef(
            listOf(
                "CASA" to "Vivienda",
                "COSA" to "Objeto o asunto",
                "SACO" to "Bolsa grande",
                "OCA" to "Ave parecida al ganso",
                "CAOS" to "Desorden",
            ),
            extras = listOf("ASCO", "CASO", "SACA", "OSO"),
        ),
        LevelDef(
            listOf(
                "ROMERO" to "Planta aromática",
                "ERROR" to "Fallo",
                "REMO" to "Pala para impulsar una embarcación",
                "OREO" to "Galleta de chocolate con relleno",
                "REO" to "Prisionero"
            ),
            extras = listOf("REMERO", "MORRO", "ROER", "MEMO", "ORO", "MERO"),
        ),
        LevelDef(
            listOf(
                "TODO" to "Que se considera por entero, sin excluir ninguna de sus partes",
                "TORO" to "Mamífero rumiante macho, fuerte y con cuernos, de gran presencia",
                "ROTO" to "Que está quebrado, estropeado o dividido en piezas",
                "OTRO" to "Que es distinto, diferente o que se añade a lo ya mencionado",
                "ROTOR" to "Pieza giratoria de una máquina, como el eje que mueve las hélices",
                "TORDO" to "Ave negra y pequeña"
            ),
            extras = listOf("DOTO", "RODO", "ORO"),
        ),
        LevelDef(
            listOf(
                "KILO" to "Unidad de masa del Sistema Internacional equivalente a mil gramos",
                "LISO" to "Que tiene la superficie suave, uniforme y libre de arrugas o relieves",
                "SILO" to "Construcción o depósito grande para almacenar granos u otros materiales",
                "KOI" to "Pez ornamental de origen japonés, muy apreciado por su belleza y colores vivos",
                "SOL" to "Estrella que ilumina el cielo",
            ),
            extras = listOf("LOS", "ISO"),
        ),
        LevelDef(
            listOf(
                "CAPO" to "Jefe de una organización",
                "COPA" to "Vaso con pie para beber",
                "CAPA" to "Prenda de vestir larga y suelta",
                "POCO" to "Que es escaso o existe en pequeña cantidad",
                "OPACO" to "Que no deja pasar la luz",
                "CACAO" to "Materia prima del chocolate"
            ),
            extras = listOf("COCO", "COPO", "POPA", "ACA", "PACO", "COCA", "OCA", "PAPA"),
        ),
        LevelDef(
            listOf(
                "ZAPATO" to "Calzado que cubre el pie y habitualmente no pasa de la garganta",
                "POZO" to "Excavación profunda en la tierra para extraer agua, petróleo u otros líquidos",
                "PATO" to "Ave acuática de pico ancho y plano y patas con membrana interdigital",
                "AZOTA" to "Golpear con fuerza y repetidamente",
                "TAZA" to "Recipiente pequeño con asa para beber líquidos calientes",
            ),
            extras = listOf("TOPAZ", "OPTA", "APTO"),
        ),
        LevelDef(
            listOf(
                "MOLINO" to "Maquina que produce energía con el viento",
                "LIMON" to "Fruto cítrico",
                "MIO" to "Que pertenece a mí",
                "MONO" to "Animal primate",
                "LINO" to "Planta textil de la que se obtienen hilos y telas frescas",
                "MILLON" to "100 x 100 x 100"
            ),
            extras = listOf("MIMO", "LILO", "ION", "LIO", "OLMO"),
        ),
        LevelDef(
            listOf(
                "SONIDO" to "Sensación percibida por el oído producida por la vibración de la materia",
                "NIDO" to "Estructura que construyen las aves para poner sus huevos y criar a sus polluelos",
                "DIOS" to "Ser supremo al que se rinde culto en distintas religiones como creador del universo",
                "OIDO" to "Órgano de la audición o sentido corporal con el que se perciben los sonidos",
                "SINO" to "Destino o fuerza que según algunas creencias determina la vida de las personas",
                "DON" to "Habilidad especial"
            ),
            extras = listOf( "DOS", "SIDO", "DINOS", "DIN"),
        ),
        LevelDef(
            listOf(
                "HACHE" to "Nombre de la octava letra del abecedario español",
                "HACE" to "Forma del verbo hacer, referente a realizar, fabricar o ejecutar algo",
                "ECHA" to "Forma del verbo echar, referente a tirar, expulsar o verter algo",
                "HACHA" to "Herramienta con hoja metálica afilada empleada para talar o cortar leña",
                "CHECA" to "Interjección coloquial utilizada para llamar la atención de una persona",
                "CACHE" to "Memoria de acceso rápido en un sistema informático que almacena datos temporalmente para mejorar el rendimiento"
            ),
            extras = listOf("ECHE"),
        ),
        LevelDef(
            listOf(
                "AGUA" to "Líquido vital, transparente e inodoro que forma la lluvia, los ríos y los mares",
                "RUEGA" to "Pide algo con encarecimiento, mucha humildad o en forma de oración",
                "GRUA" to "Máquina con un brazo móvil que sirve para levantar y mover cargas muy pesadas",
                "AREA" to "Espacio de tierra comprendido dentro de ciertos límites, o medida de superficie",
                "RUGE" to "Emite un sonido profundo y fuerte el león u otro animal salvaje",
                "GUERRA" to "Conflicto armado entre dos o más países o grupos"
            ),
            extras = listOf("AGUAR", "GARUA", "GUERA", "ERRAR", "GUAU", "ERA", "REA","REGAR","RARA"),
        ),
        LevelDef(
            listOf(
                "PUEDE" to "Tiene la capacidad, facultad",
                "DUQUE" to "Título nobiliario de la más alta categoría",
                "QUEDE" to "Se mantenga en una situación o estado",
                "DUDE" to "Tenga dudas, vacile",
                "QUE" to "Pronombre o conjunción que introduce una idea o una oración subordinada",
            ),
            extras = listOf("PUDU", "DE"),
        ),
        LevelDef(
            listOf(
                "AHORA" to "En este momento o en el tiempo presente",
                "HORA" to "Medida de tiempo que equivale a exactamente sesenta minutos",
                "AHORRO" to "Acción de guardar dinero o recursos económicos para el futuro",
                "RARO" to "Que es poco común, extraordinario o diferente a lo habitual",
                "ARO" to "Pieza circular de metal, plástico o madera que está hueca por dentro",
                "HORROR" to "Sentimiento de miedo intenso"
            ),
            extras = listOf("ORAR"),
        ),
        LevelDef(
            listOf(
                "SALON" to "Habitación principal de una casa destinada a recibir visitas",
                "SOLA" to "Que no tiene compañía (fem)",
                "SANO" to "Que goza de buena salud",
                "LONA" to "Tela fuerte, gruesa e impermeable que sirve para cubrir o proteger objetos",
                "ASNO" to "Similar al burro",
                "SOL" to "Estrella que ilumina el cielo",
                "SAL" to "Cloruro de sodio"
            ),
            extras = listOf("SON", "LOSA", "SOLO", "LANA", "SALA"),
        ),
        LevelDef(
            listOf(
                "TOMATE" to "Fruto rojo y jugoso",
                "MATE" to "Infusión de yerba mate",
                "TOTEM" to "Símbolo religioso o cultural",
                "TEMA" to "Asunto o materia de que se trata",
                "MOTO" to "Vehículo de dos ruedas",
                "ATOMO" to "Partícula elemental"
            ),
            extras = listOf("AMO", "MATEO", "MOTE", "META", "TEMO", "TOMO"),
        ),
        LevelDef(
            listOf(
                "ROCA" to "Materia mineral sólida y dura que forma la corteza de la Tierra",
                "RICO" to "Que posee una gran fortuna económica o tiene un sabor muy agradable",
                "ARCO" to "Porción de una línea curva, o arma usada para disparar flechas",
                "ORCA" to "Mamífero marino de gran tamaño, también llamado ballena asesina",
                "CARO" to "Que tiene un precio elevado o que goza de un gran aprecio afectivo",
                "CORO" to "Grupo de personas que cantan simultáneamente una pieza musical",
                "CIRCO" to "Espectáculo público que combina acrobacias, payasos y animales adiestrados",
            ),
            extras = listOf( "CRIO", "ROCIO", "CRIA", "RICA", "RIO", "OIR", "ACARO", "COCO"),
        ),
        LevelDef(
            listOf(
                "ACERCA" to "Poner algo cerca de otra cosa o aproximar ideas o conceptos",
                "CREAR" to "Producir algo de la nada",
                "CERA" to "Sustancia sólida y blanda que fabrican las abejas para formar sus panales",
                "ARCE" to "Árbol de madera dura cuya hoja lobulada es el símbolo nacional de Canadá",
                "ACRE" to "Medida de superficie anglosajona, o un olor y gusto muy áspero y picante",
                "CAER" to "Desplazarse un cuerpo de arriba hacia abajo por la acción de su propio peso",

            ),
            extras = listOf("RARA", "ARAR", "ERA", "RECAER", "RECREA", "CERCA", "CARA"),
        ),
        LevelDef(
            listOf(
                "SUERTE" to "Encadenamiento de sucesos fortuitos o causa a la que se atribuye la buena fortuna",
                "SURTE" to "Forma del verbo surtir, referente a proveer o abastecer de lo necesario",
                "SUTURA" to "Costura quirúrgica empleada para unir los bordes de una herida y facilitar su curación",
                "TUS" to "Pronombre posesivo de segunda persona plural",
                "ERUTE" to "Forma del verbo erutar",
                "RUTA" to "Camino o itinerario que se sigue para ir de un lugar a otro",
                "SUR" to "Punto cardinal opuesto al norte",
            ),
            extras = listOf("SET", "RES", "SER", "USE", "TES"),
        ),
        LevelDef(
            listOf(
                "QUERER" to "Tener el deseo, la voluntad o el cariño por alguien o algo",
                "ARQUEAR" to "Dar forma de arco a un objeto o encorvar una parte del cuerpo",
                "AURA" to "Atmósfera ideológica o energía sutil que se dice que rodea a un ser vivo",
                "ERA" to "Período histórico extenso que se computa a partir de un hecho importante",
                "ARRE" to "Voz que se utiliza popularmente para hacer caminar a los animales de carga",
                "ERRAR" to "Cometer un error o desviarse del camino correcto",
            ),
            extras = listOf("REA", "ARAR", "RARA", "ARREAR")
        ),
        LevelDef(
            listOf(
                "TINA" to "Recipiente para bañarse",
                "TINTA" to "Líquido coloreado que se utiliza para escribir, dibujar o imprimir",
                "ANIS" to "Planta aromática de la que se obtiene un licor dulce y transparente",
                "SANTA" to "Mujer de gran virtud y bondad, o declarada venerable por la Iglesia",
                "TITAN" to "Satélite natural de Saturno, o persona de gran fuerza y poder",
                "SATIN" to "Tejido de textura densa y superficie bastante brillante, parecido al raso",
            ),
            extras = listOf( "NATA", "SANA", "SITIA", "ANTI", "TANTA", "TAN", "SIN"),
        ),

    )

    private val levels: List<CrucigramaNeonLevelSpec> by lazy { levelDefs.map { buildSpec(it) } }

    /** Genera el puzzle del nivel solicitado (1-based, cíclico). */
    fun generate(level: Int, random: Random = Random.Default): CrucigramaNeonPuzzle {
        val spec = levels[indexFor(level)]
        val cellMap = linkedMapOf<Pair<Int, Int>, CrucigramaNeonCellState>()
        val slots = spec.slots.map { slot ->
            val cellIndices = slot.answer.mapIndexed { i, letter ->
                val r =
                    if (slot.direction == CrucigramaDirection.VERTICAL) slot.row + i else slot.row
                val c =
                    if (slot.direction == CrucigramaDirection.HORIZONTAL) slot.col + i else slot.col
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
