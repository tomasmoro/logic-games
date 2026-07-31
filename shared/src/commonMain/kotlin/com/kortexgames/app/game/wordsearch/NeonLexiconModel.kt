package com.kortexgames.app.game.wordsearch

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
 * @property traps señuelos: sílabas o palabras parecidas a las [words] reales
 *           (mismo prefijo, casi el mismo largo, etc.) que [NeonLexiconGenerator]
 *           esconde igual que una palabra real pero SIN registrarlas como
 *           [TargetWord] jugable. Al no existir como entrada en
 *           `NeonLexiconGameState.words`, una selección que las recorra nunca
 *           puede matchear en `NeonLexiconEngine.commitSelection` (que compara
 *           solo contra esa lista) — quedan en la rejilla puramente para
 *           confundir al ojo, nunca como opción válida.
 */
data class NeonLexiconLevelSpec(
    val rows: Int,
    val cols: Int,
    val words: List<String>,
    val traps: List<String> = emptyList()
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
        traps.forEach { t ->
            require(t.isNotBlank()) { "Las trampas no pueden estar vacías." }
            require(t == t.uppercase()) { "La trampa '$t' debe ir en mayúsculas." }
            require(t.length <= maxLen) {
                "La trampa '$t' (${t.length}) no cabe en una rejilla ${rows}x$cols."
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
 *  - Las trampas ([NeonLexiconLevelSpec.traps]) se colocan DESPUÉS de todas las
 *    palabras reales, con el mismo algoritmo de encaje/cruce, y nunca entran al
 *    [NeonLexiconPuzzle.words] resultante: quedan en la rejilla como letras
 *    "sueltas" indistinguibles del resto hasta que el jugador las traza, momento
 *    en el que el motor las rechaza por no ser una palabra objetivo (ver KDoc de
 *    [NeonLexiconLevelSpec.traps]). Que no encajen no es un error de generación
 *    —a diferencia de una palabra real— porque son puro adorno de dificultad.
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
        NeonLexiconLevelSpec(
            rows = 7,
            cols = 7,
            words = listOf("PERA", "LIMON", "COCO", "UVA", "KIWI"),
            traps = listOf("PE", "PER", "LI", "LIM", "CO", "WI", "KI")
        ),
        NeonLexiconLevelSpec(
            8,
            8,
            listOf("SOL", "LUNA", "MAR", "RIO", "CIELO"),
            listOf("LUN", "CIEL")
        ),
        NeonLexiconLevelSpec(
            rows = 8,
            cols = 8,
            words = listOf("PIANO", "FLAUTA", "BAJO", "CORO", "RITMO"),
            traps = listOf("PI", "PIA", "FLA", "BA", "BAJ", "CO", "RI")
        ),
        NeonLexiconLevelSpec(
            rows = 8,
            cols = 8,
            words = listOf("TENIS", "FUTBOL", "REMO", "BOXEO", "GOLF"),
            traps = listOf("TE", "TEN", "FU", "FUT", "RE", "REM", "BO", "GO")
        ),
        NeonLexiconLevelSpec(
            9,
            9,
            listOf("GATO", "PERRO", "PEZ", "AVE", "RANA", "OSO"),
            listOf("GAT", "PER")
        ),
        NeonLexiconLevelSpec(
            rows = 9,
            cols = 9,
            words = listOf("CAFE", "EXPRESO", "LECHE", "MOKA", "GRANO", "TARTA"),
            traps = listOf("CAF", "EXP", "LEC", "MOK", "GRA", "TAR", "EX", "LE")
        ),
        NeonLexiconLevelSpec(
            rows = 9,
            cols = 9,
            words = listOf("ARBOL", "HOJA", "RIO", "FLOR", "BOSQUE", "PRADO"),
            traps = listOf("AR", "ARB", "HO", "HOJ", "RI", "FLO", "BOS", "PRA")
        ),
        NeonLexiconLevelSpec(
            10,
            10,
            listOf("VERDE", "AZUL", "ROJO", "ROSA", "GRIS", "NEGRO"),
            listOf("VER", "AZU")
        ),
        NeonLexiconLevelSpec(
            10,
            10,
            listOf("LUNES", "MARTES", "JUEVES", "SABADO", "DOMINGO"),
            listOf("LUN", "MAR")
        ),
        NeonLexiconLevelSpec(
            rows = 10,
            cols = 10,
            words = listOf("GALAXIA", "PLANETA", "COMETA", "METEORO", "LUNA"),
            traps = listOf("LAXI", "PLAET", "MEORO", "COMATO")
        ),
        NeonLexiconLevelSpec(
            rows = 10,
            cols = 10,
            words = listOf("ACTOR", "FILM", "GUION", "LUCES", "RODAJE", "CORTE"),
            traps = listOf("AC", "ACT", "FI", "FIL", "GUI", "LUC", "ROD", "COR")
        ),
        NeonLexiconLevelSpec(
            rows = 10,
            cols = 10,
            words = listOf("REDES", "DATOS", "CLAVE", "PIXEL", "BOTON", "CHIP"),
            traps = listOf("RED", "DAT", "CLA", "PIX", "BOT", "CHI", "RE", "PI")
        ),
        NeonLexiconLevelSpec(
            11,
            11,
            listOf("PIANO", "VIOLIN", "TAMBOR", "ARPA", "FLAUTA", "GUITARRA"),
            listOf("PIA", "VIO", "FLA", "TARRA")
        ),
        NeonLexiconLevelSpec(
            rows = 11,
            cols = 11,
            words = listOf("SOLAR", "LUNA", "ORBITA", "ASTRO", "ROVER", "POLO"),
            traps = listOf("SOL", "LUN", "ORB", "AST", "ROV", "POL", "SO", "OR")
        ),
        NeonLexiconLevelSpec(
            rows = 11,
            cols = 11,
            words = listOf("COSTA", "MONTE", "ISLA", "VALLE", "OCEANO", "LAGO"),
            traps = listOf("COS", "MON", "ISL", "VAL", "OCE", "LAG", "CO", "MO")
        ),
        NeonLexiconLevelSpec(
            12,
            12,
            listOf("ESPANA", "FRANCIA", "ITALIA", "PERU", "CHILE", "BRASIL", "CANADA"),
            listOf("ESP", "FRA", "ILE", "NADA")
        ),
        NeonLexiconLevelSpec(
            rows = 12,
            cols = 12,
            words = listOf("CASCO", "RUTA", "VIAJE", "MOTOR", "RUEDA", "PAISAJE"),
            traps = listOf("CAS", "RU", "VIA", "MOTR", "EDAS", "PAIS")
        ),
        NeonLexiconLevelSpec(
            rows = 12,
            cols = 12,
            words = listOf("CAMARA", "VIDEO", "AUDIO", "LUCES", "TOMA", "EDICION", "CLIP"),
            traps = listOf(
                "CAMA",
                "VID",
                "AUD",
                "LUCR",
                "OMA"
            )
        ),
        NeonLexiconLevelSpec(
            rows = 12,
            cols = 12,
            words = listOf("NORTE", "SUR", "QUEBRADA", "GLACIAR", "VALLE", "SIERRAS", "LLANURA"),
            traps = listOf(
                "NOR",
                "SU",
                "UEBR",
                "GLA",
                "LLE",
                "RRA"
            )
        ),
        NeonLexiconLevelSpec(
            rows = 13,
            cols = 13,
            words = listOf("CARPA", "FUEGO", "BOSQUE", "MAPA", "LINTERNA", "BOLSA", "BRUJULA"),
            traps = listOf(
                "CAR",
                "FUE",
                "QUE",
                "MAP",
                "TERN",
                "UJUL"
            )
        ),
        NeonLexiconLevelSpec(
            rows = 13,
            cols = 13,
            words = listOf("CADENA", "FRENOS", "ACEITE", "FILTRO", "EMBRAGUE", "BUJIA", "ESCAPE"),
            traps = listOf(
                "ADEN",
                "ESCA",
                "FIL",
                "BRA",
                "FREN"
            )
        ),
        NeonLexiconLevelSpec(
            rows = 13,
            cols = 13,
            words = listOf("SERVIDOR", "CLIENTE", "NUBE", "TOKEN", "CACHE", "ROUTER", "SISTEMA"),
            traps = listOf(
                "IDO",
                "CLI",
                "OKE",
                "CHE",
                "OUT",
                "SIS"
            )
        ),
        NeonLexiconLevelSpec(
            rows = 13,
            cols = 13,
            words = listOf(
                "DIRECTOR",
                "MONTAJE",
                "RODAJE",
                "ENFOQUE",
                "ESCENA",
                "SONIDO",
                "ENCADRE",
                "GUION"
            ),
            traps = listOf("RECT", "AJE", "RODA", "QUE", "SCEN", "IDO", "CAD")
        ),
        NeonLexiconLevelSpec(
            rows = 13,
            cols = 13,
            words = listOf(
                "OLIMPO",
                "MEDUSA",
                "ORACULO",
                "MINOTAURO",
                "CENTAURO",
                "HERCULES",
                "TITANES",
                "TROYA"
            ),
            traps = listOf(
                "IMP",
                "MED",
                "CUL",
                "MIN",
                "ENTA",
                "HER",
                "OYA",
                "TAU",
                "ORA"
            )
        ),
        NeonLexiconLevelSpec(
            rows = 13,
            cols = 13,
            words = listOf(
                "CYBORG",
                "HOLOGRAMA",
                "SINTETICO",
                "MATRIZ",
                "HACKER",
                "AVATAR",
                "CIRCUITO",
                "NEURONAL"
            ),
            traps = listOf(
                "YB",
                "GRAM",
                "TET",
                "MAT",
                "HA",
                "HA",
                "CK"
            )
        )

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

        // Trampas: se colocan con la MISMA lógica de encaje/cruce que las
        // palabras reales (así se camuflan igual de bien), pero su TargetWord
        // se descarta en vez de sumarse a `placed`. No formar parte de la lista
        // de palabras objetivo es lo que las hace "incorrectas" — el motor
        // (`NeonLexiconEngine.commitSelection`) valida un acierto comparando
        // contra esa lista, así que una trampa jamás puede matchear por más que
        // el jugador la trace exacta. Si una trampa no encuentra hueco tras los
        // reintentos se omite sin más: son decorativas, no bloquean el nivel.
        val orderedTraps = spec.traps.sortedByDescending { it.length }
        for (trap in orderedTraps) {
            val target = placeWord(trap, spec, grid, random) ?: continue
            target.cells.forEachIndexed { i, c -> grid[c.row][c.col] = trap[i] }
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
