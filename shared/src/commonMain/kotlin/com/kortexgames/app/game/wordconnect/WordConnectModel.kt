package com.kortexgames.app.game.wordconnect

import kotlin.random.Random

/**
 * # Palabras Conectadas — modelo y generador
 *
 * Juego estilo *Word Connect / Wordscapes*: un anillo de letras del que el jugador
 * **une (arrastra) letras** para formar palabras. Cada nivel define un conjunto de
 * letras y las palabras objetivo que se pueden formar con ellas; al acertar, la
 * palabra se revela con brillo neón en su ranura superior.
 *
 * ## Decisión de diseño: ranuras apiladas (no crucigrama entrelazado)
 * A diferencia del [com.kortexgames.app.game.crucigrama] (que resuelve un
 * crucigrama con letras que se cruzan en una rejilla), aquí cada palabra vive en su
 * **propia fila de casillas**. Se eligió así a propósito:
 *  - la mecánica que pidió el usuario es la *rueda de arrastre*, no la escritura;
 *  - evita la fragilidad de cuadrar coordenadas de cruce, permitiendo listas de
 *    palabras más ricas por nivel;
 *  - es un layout canónico de Word Connect y deja el juego claramente diferenciado
 *    del crucigrama pese a compartir el dominio "forma palabras con estas letras".
 */

/** Una letra del anillo. El [index] es estable y **identifica el nodo** durante el
 *  trazo (dos posiciones podrían compartir carácter; el índice las distingue). */
data class WheelLetter(val index: Int, val char: Char)

/**
 * Definición estática de un nivel: el conjunto de letras del anillo y las palabras
 * objetivo formables con ellas.
 *
 * La validación en [init] es la red de seguridad del contenido: si una palabra usa
 * letras (o repeticiones) que el conjunto no ofrece, el nivel falla al construirse
 * en vez de dejar una palabra imposible de encontrar en tiempo de juego. El test
 * [WordConnectGeneratorTest] recorre todos los niveles por este mismo motivo.
 */
data class WordConnectLevelSpec(
    val letters: List<Char>,
    val words: List<String>,
) {
    init {
        require(letters.isNotEmpty()) { "El anillo necesita al menos una letra." }
        require(words.isNotEmpty()) { "El nivel debe incluir al menos una palabra." }

        val pool = letters.groupingBy { it }.eachCount()
        words.forEach { word ->
            require(word.isNotBlank()) { "Las palabras no pueden estar vacías." }
            require(word == word.uppercase()) {
                "Las palabras deben estar en mayúsculas para conservar consistencia visual."
            }
            require(word.length >= 2) { "Las palabras objetivo tienen al menos 2 letras." }
            // Multiconjunto: la palabra no puede usar una letra más veces de las que
            // el anillo la ofrece (p. ej. "CASA" es inválida en el conjunto {C,A,S,O}).
            val need = word.groupingBy { it }.eachCount()
            need.forEach { (letter, count) ->
                val available = pool[letter] ?: 0
                require(count <= available) {
                    "La palabra '$word' necesita $count '$letter' pero el anillo ofrece $available."
                }
            }
        }
        require(words.distinct().size == words.size) { "Hay palabras objetivo duplicadas." }
    }
}

/** Estado de una palabra objetivo durante la partida. */
data class WordSlotState(
    val answer: String,
    val solved: Boolean = false,
    /** Tick de resolución; dispara la animación de revelado. `null` si no resuelta. */
    val solvedAtTick: Long? = null,
)

/** Puzzle listo para pintar: letras barajadas para el anillo y ranuras de palabras. */
data class WordConnectPuzzle(
    val letters: List<WheelLetter>,
    val slots: List<WordSlotState>,
)

/**
 * Generador de niveles de Palabras Conectadas.
 *
 * El set base es finito y luego cicla (igual que el resto de juegos LEVELED) para
 * mantener progreso continuo sin quedarse sin contenido.
 */
object WordConnectGenerator {
    private val levels: List<WordConnectLevelSpec> = listOf(
        WordConnectLevelSpec(
            letters = listOf('A', 'M', 'O', 'R'),
            words = listOf("MAR", "ARO", "RAMO", "MORA", "ROMA", "AMOR"),
        ),
        WordConnectLevelSpec(
            letters = listOf('C', 'A', 'S', 'O'),
            words = listOf("OCA", "ASCO", "COSA", "SACO", "CAOS", "CASO"),
        ),
        WordConnectLevelSpec(
            letters = listOf('L', 'I', 'M', 'O', 'N'),
            words = listOf("MIL", "LIMO", "LINO", "LIMON"),
        ),
    )

    /**
     * Genera el puzzle del nivel solicitado (1-based, cíclico).
     *
     * Las ranuras se ordenan por longitud y luego alfabéticamente: el jugador ve
     * primero las palabras cortas y la más larga queda destacada al final, guiando
     * la dificultad de menor a mayor (convención de Word Connect).
     */
    fun generate(level: Int, random: Random = Random.Default): WordConnectPuzzle {
        val spec = levels[indexFor(level)]
        val wheel = spec.letters.shuffled(random).mapIndexed { i, c -> WheelLetter(i, c) }
        val slots = spec.words
            .sortedWith(compareBy({ it.length }, { it }))
            .map { WordSlotState(answer = it) }
        return WordConnectPuzzle(letters = wheel, slots = slots)
    }

    /** Nº de letras del anillo del nivel dado; útil para dimensionar la UI del intro. */
    fun letterCount(level: Int): Int = levels[indexFor(level)].letters.size

    private fun indexFor(level: Int): Int {
        val zeroBased = (level - 1) % levels.size
        return if (zeroBased >= 0) zeroBased else zeroBased + levels.size
    }
}
