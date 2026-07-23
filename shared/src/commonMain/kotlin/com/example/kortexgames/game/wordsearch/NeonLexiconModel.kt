package com.example.kortexgames.game.wordsearch

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * # Modelos de dominio de "Neon Lexicon" (Sopa de Letras Neón)
 *
 * Categoría **Lenguaje y Vocabulario**. El dominio es puro (sin dependencias de
 * Compose ni de plataforma): describe QUÉ es una sopa de letras —una rejilla de
 * caracteres con palabras escondidas en línea recta— y deja el CÓMO se juega
 * (mapear gestos a celdas, "snap" de la línea, validación) para el ViewModel de
 * la Fase 2 y el pintado neón para la UI de la Fase 3.
 *
 * Decisiones clave:
 *  - Todo se indexa por [Coordinate] (fila/columna de la rejilla), **nunca por
 *    píxeles**. La conversión píxel→celda es responsabilidad de la capa de UI
 *    (es geometría de layout, suya); el dominio solo conoce celdas.
 *  - Las 8 direcciones legales viven en [LineDirection]. Que el enum sea cerrado
 *    es lo que, en Fase 2, permite hacer el "snap" a una recta válida: una
 *    selección solo es palabra si su trazo coincide con uno de estos 8 vectores.
 */

/**
 * Posición de una celda en la rejilla. Es el "átomo" de coordenadas del juego:
 * toda la lógica (selección, palabras, validación) se expresa con esto y jamás
 * con offsets de píxel, que dependen del tamaño de pantalla.
 *
 * @property row fila 0-based (crece hacia abajo).
 * @property col columna 0-based (crece hacia la derecha).
 */
@Serializable
data class Coordinate(val row: Int, val col: Int)

/**
 * Las 8 direcciones en las que puede esconderse (y seleccionarse) una palabra:
 * horizontal, vertical y las dos diagonales, en ambos sentidos.
 *
 * El paso ([rowStep], [colStep]) es el vector unitario de avance entre letras
 * consecutivas. Recorrer una palabra desde su origen es, por tanto,
 * `origen + paso * i`. Este mismo vector es la base del "snap" de la Fase 2:
 * dado el dedo en una celda destino, una selección solo es una recta legal si el
 * desplazamiento (Δfila, Δcol) respecto al origen encaja en exactamente uno de
 * estos vectores (misma dirección y magnitud proporcional), lo que descarta
 * "curvas" o saltos de caballo.
 *
 * @property rowStep desplazamiento de fila por letra (−1, 0 o +1).
 * @property colStep desplazamiento de columna por letra (−1, 0 o +1).
 */
@Serializable
enum class LineDirection(val rowStep: Int, val colStep: Int) {
    EAST(0, 1),
    WEST(0, -1),
    SOUTH(1, 0),
    NORTH(-1, 0),
    SOUTH_EAST(1, 1),
    SOUTH_WEST(1, -1),
    NORTH_EAST(-1, 1),
    NORTH_WEST(-1, -1);

    companion object {
        /** Las 4 direcciones "positivas": suficientes para COLOCAR palabras sin
         *  duplicar orientaciones (una palabra de W a E es la misma línea que de
         *  E a W leída al revés). La selección del jugador sí usa las 8. */
        val forPlacement: List<LineDirection> = listOf(EAST, SOUTH, SOUTH_EAST, NORTH_EAST)
    }
}

/**
 * Una celda concreta de la rejilla ya resuelta: su posición y la letra que
 * muestra. La letra puede ser parte de una palabra objetivo o "relleno" aleatorio.
 */
data class LetterCell(val position: Coordinate, val letter: Char)

/**
 * La cuadrícula de letras. Fuente de verdad de "qué letra hay en cada celda".
 *
 * Se guarda como matriz de filas para pintado directo; [letterAt] e [isInside]
 * encapsulan los accesos seguros para que el resto del código no repita
 * comprobaciones de límites.
 *
 * @property rows número de filas.
 * @property cols número de columnas.
 * @property letters matriz `[fila][columna]` de caracteres en mayúscula.
 */
@Serializable
data class WordSearchGrid(
    val rows: Int = 0,
    val cols: Int = 0,
    val letters: List<List<Char>> = emptyList(),
) {
    /** Letra en [coordinate], o null si cae fuera de la rejilla. */
    fun letterAt(coordinate: Coordinate): Char? =
        letters.getOrNull(coordinate.row)?.getOrNull(coordinate.col)

    /** true si [coordinate] es una celda válida dentro de los límites. */
    fun isInside(coordinate: Coordinate): Boolean =
        coordinate.row in 0 until rows && coordinate.col in 0 until cols
}

/**
 * Una palabra objetivo **colocada** en la rejilla: qué texto es, dónde empieza y
 * en qué dirección se extiende. Las [cells] se derivan del origen + dirección y
 * son la clave para dos cosas de fases siguientes: comparar contra la selección
 * del jugador (Fase 2) y pintar el resaltado al acertar (Fase 3).
 *
 * @property text palabra en MAYÚSCULA (consistencia visual con el resto de juegos
 *           de lenguaje; ver Crucigrama Neón).
 * @property start coordenada de la primera letra.
 * @property direction eje sobre el que se despliega.
 */
@Serializable
data class TargetWord(
    val text: String,
    val start: Coordinate,
    val direction: LineDirection,
) {
    /** Coordenadas de cada letra, en orden de lectura. Derivadas: `start + paso*i`. */
    val cells: List<Coordinate> = text.indices.map { i ->
        Coordinate(
            row = start.row + direction.rowStep * i,
            col = start.col + direction.colStep * i,
        )
    }

    /** Coordenada de la última letra (útil para el trazo de selección). */
    val end: Coordinate get() = cells.last()
}

/**
 * Estado en partida de una palabra objetivo: la palabra estática [word] más si el
 * jugador ya la encontró.
 *
 * Separar la definición estática ([TargetWord]) de su estado de juego sigue el
 * patrón Spec/State del proyecto (ver `CrucigramaNeonSlotSpec` vs
 * `CrucigramaNeonSlotState`): permite reconstruir el nivel sin perder progreso.
 *
 * @property found true cuando la selección del jugador coincidió con [word].
 * @property foundAtTick tick en que se encontró; la UI lo usa para escalonar la
 *           animación de "salto + brillo" de las letras recién resueltas.
 */
@Serializable
data class WordEntry(
    val word: TargetWord,
    val found: Boolean = false,
    val foundAtTick: Long? = null,
) {
    /** Atajo al texto de la palabra (lo consulta la UI del panel lateral). */
    val text: String get() = word.text
}

/**
 * Puzzle generado listo para jugar: la rejilla ya rellena y las palabras
 * colocadas dentro de ella.
 *
 * @property grid cuadrícula completa (palabras + relleno aleatorio).
 * @property words palabras escondidas, con su posición y dirección.
 */
data class NeonLexiconPuzzle(
    val grid: WordSearchGrid,
    val words: List<TargetWord>,
)

/**
 * Definición estática de un nivel: tamaño de la rejilla y las palabras a esconder.
 * La colocación concreta la resuelve [NeonLexiconGenerator] (puede variar entre
 * partidas con distinta semilla).
 *
 * @property words palabras a esconder; se normalizan a mayúscula y se validan
 *           contra el tamaño de la rejilla en `init`.
 */
data class NeonLexiconLevelSpec(
    val rows: Int,
    val cols: Int,
    val words: List<String>,
) {
    init {
        require(rows > 0 && cols > 0) { "La rejilla debe tener tamaño positivo." }
        require(words.isNotEmpty()) { "El nivel necesita al menos una palabra." }
        val maxLen = maxOf(rows, cols)
        words.forEach { w ->
            require(w.isNotBlank()) { "Las palabras no pueden estar vacías." }
            require(w == w.uppercase()) { "La palabra '$w' debe ir en mayúsculas." }
            // Una palabra más larga que la mayor dimensión no cabe en ninguna
            // orientación (ni siquiera en diagonal, que usa min(rows, cols)).
            require(w.length <= maxLen) {
                "La palabra '$w' (${w.length}) no cabe en una rejilla ${rows}x$cols."
            }
        }
    }
}

/**
 * Generador de sopas de letras.
 *
 * > **Nota de fase:** por indicación del enunciado, este generador es *simple*
 * > (mock funcional), no el algoritmo definitivo. Coloca cada palabra probando
 * > posiciones/direcciones al azar y respetando cruces (una celda compartida es
 * > legal solo si la letra coincide), y rellena el resto con letras aleatorias.
 * > Es suficiente para que las Fases 2 y 3 tengan un tablero jugable de verdad.
 *
 * Reglas de colocación (el "porqué"):
 *  - Se colocan primero las palabras **más largas**: son las que menos hueco
 *    tienen, así que ubicarlas antes reduce los reintentos globales.
 *  - Solo se usan las [LineDirection.forPlacement] (4 orientaciones): E, S, SE y
 *    NE. Las 8 direcciones serían redundantes al colocar —una palabra y su
 *    reverso ocupan la misma línea— y el jugador igualmente puede trazarla en
 *    cualquiera de los dos sentidos, cosa que resuelve la validación de Fase 2.
 *  - El relleno usa letras uniformes A–Z. Un generador real sesgaría el relleno
 *    hacia la frecuencia del idioma para que "camufle" mejor las palabras; se
 *    deja fuera del mock a propósito.
 */
object NeonLexiconGenerator {

    /** Reintentos máximos al ubicar una palabra antes de rehacer toda la rejilla. */
    private const val MAX_WORD_ATTEMPTS = 300

    /** Reintentos de rejilla completa si alguna palabra no encaja. */
    private const val MAX_GRID_ATTEMPTS = 50

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    // Dificultad creciente: rejillas y nº de palabras que van a más. El set es
    // finito y luego cicla (igual que el Crucigrama) para no cortar el progreso.
    private val levelSpecs: List<NeonLexiconLevelSpec> = listOf(
        NeonLexiconLevelSpec(8, 8, listOf("SOL", "LUNA", "MAR", "RIO", "CIELO")),
        NeonLexiconLevelSpec(9, 9, listOf("GATO", "PERRO", "PEZ", "AVE", "RANA", "OSO")),
        NeonLexiconLevelSpec(10, 10, listOf("VERDE", "AZUL", "ROJO", "ROSA", "GRIS", "NEGRO")),
        NeonLexiconLevelSpec(10, 10, listOf("LUNES", "MARTES", "JUEVES", "SABADO", "DOMINGO")),
        NeonLexiconLevelSpec(11, 11, listOf("PIANO", "VIOLIN", "TAMBOR", "ARPA", "FLAUTA", "GUITARRA")),
        NeonLexiconLevelSpec(12, 12, listOf("ESPANA", "FRANCIA", "ITALIA", "PERU", "CHILE", "BRASIL", "CANADA")),
    )

    /**
     * Genera el puzzle del [level] pedido (1-based, cíclico sobre [levelSpecs]).
     *
     * @param random inyectable para tests reproducibles (semilla fija ⇒ mismo tablero).
     * @throws IllegalStateException si, tras [MAX_GRID_ATTEMPTS], no logra colocar
     *         todas las palabras (síntoma de un nivel mal dimensionado).
     */
    fun generate(level: Int, random: Random = Random.Default): NeonLexiconPuzzle {
        val spec = levelSpecs[indexFor(level)]
        // Colocar las largas primero minimiza los reintentos (ver KDoc de la clase).
        val ordered = spec.words.sortedByDescending { it.length }

        repeat(MAX_GRID_ATTEMPTS) {
            tryBuild(spec, ordered, random)?.let { return it }
        }
        error("No se pudo generar el nivel $level con las palabras: ${spec.words}")
    }

    /** Un intento completo de rejilla; null si alguna palabra no encontró sitio. */
    private fun tryBuild(
        spec: NeonLexiconLevelSpec,
        ordered: List<String>,
        random: Random,
    ): NeonLexiconPuzzle? {
        // ' ' marca celda libre; se rellena con letras al final.
        val grid = MutableList(spec.rows) { MutableList(spec.cols) { ' ' } }
        val placed = mutableListOf<TargetWord>()

        for (word in ordered) {
            val target = placeWord(word, spec, grid, random) ?: return null
            target.cells.forEachIndexed { i, c -> grid[c.row][c.col] = word[i] }
            placed += target
        }

        // Relleno aleatorio de los huecos restantes.
        for (r in 0 until spec.rows) {
            for (c in 0 until spec.cols) {
                if (grid[r][c] == ' ') grid[r][c] = ALPHABET[random.nextInt(ALPHABET.length)]
            }
        }

        val gridModel = WordSearchGrid(spec.rows, spec.cols, grid.map { it.toList() })
        return NeonLexiconPuzzle(gridModel, placed)
    }

    /**
     * Busca una posición+dirección legal para [word] probando al azar hasta
     * [MAX_WORD_ATTEMPTS] veces. Una colocación es legal si toda la palabra cae
     * dentro de la rejilla y cada celda está libre o ya contiene la MISMA letra
     * (cruce válido, como en el Crucigrama). Devuelve null si no encuentra sitio.
     */
    private fun placeWord(
        word: String,
        spec: NeonLexiconLevelSpec,
        grid: List<List<Char>>,
        random: Random,
    ): TargetWord? {
        repeat(MAX_WORD_ATTEMPTS) {
            val dir = LineDirection.forPlacement[random.nextInt(LineDirection.forPlacement.size)]
            val start = Coordinate(random.nextInt(spec.rows), random.nextInt(spec.cols))
            val candidate = TargetWord(word, start, dir)
            if (fits(candidate, spec, grid)) return candidate
        }
        return null
    }

    /** Comprueba límites y cruces de una colocación tentativa. */
    private fun fits(
        candidate: TargetWord,
        spec: NeonLexiconLevelSpec,
        grid: List<List<Char>>,
    ): Boolean {
        candidate.cells.forEachIndexed { i, c ->
            if (c.row !in 0 until spec.rows || c.col !in 0 until spec.cols) return false
            val existing = grid[c.row][c.col]
            if (existing != ' ' && existing != candidate.text[i]) return false
        }
        return true
    }

    /** Índice cíclico y seguro (incluso con niveles negativos por robustez). */
    private fun indexFor(level: Int): Int {
        val zeroBased = (level - 1) % levelSpecs.size
        return if (zeroBased >= 0) zeroBased else zeroBased + levelSpecs.size
    }
}
