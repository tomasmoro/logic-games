package com.kortexgames.app.game.bubblemath

import kotlin.random.Random

/**
 * Núcleo **puro** (sin corrutinas ni Compose) de la **nube de ecuación**: el reto
 * relámpago que aparece cada cierto tiempo durante la partida de "Burbujas de
 * Cálculo" y que, si se resuelve, devuelve una vida.
 *
 * Regla del reto: la nube muestra una ecuación **incompleta** y abajo aparecen las
 * fichas que hay que ir colocando. A una ecuación le faltan **o solo símbolos o solo
 * números, nunca ambos a la vez** ([EquationBlankKind]): mezclar las dos cosas
 * convierte un reto de 7 segundos en un rompecabezas, y la nube está pensada como
 * recompensa alcanzable, no como examen.
 *
 * Se aísla del motor (igual que [BubbleMathGenerator]) para poder testear de forma
 * determinista el invariante crítico: **la solución ofrecida es única**. Sin esa
 * garantía el jugador podría fallar una respuesta matemáticamente correcta.
 */

/** Qué le falta a la ecuación de la nube. Nunca se combinan los dos tipos. */
enum class EquationBlankKind {
    /** Faltan operandos: `? × 4 = 24`. */
    NUMBER,

    /** Faltan operadores: `6 ? 4 = 24`. */
    SYMBOL,
}

/**
 * Pieza de la ecuación tal y como se pinta, de izquierda a derecha. La UI recorre la
 * lista y dibuja cada pieza: el texto fijo como tal y el hueco como una casilla
 * pulsable.
 */
sealed interface EquationSlot {
    /** Trozo ya resuelto de la ecuación: un número, un símbolo, un paréntesis o el `=`. */
    data class Fixed(val text: String) : EquationSlot

    /** Hueco a rellenar; [index] es su posición dentro de [EquationPuzzle.solution]. */
    data class Blank(val index: Int) : EquationSlot
}

/**
 * Ficha pulsable de la bandeja inferior. Lleva [id] propio (y no solo la etiqueta)
 * porque puede haber dos fichas con el mismo texto —una ecuación puede necesitar el
 * mismo símbolo dos veces— y hay que poder distinguir cuál se ha usado ya.
 */
data class EquationToken(val id: Int, val label: String)

/**
 * Ecuación incompleta de la nube, lista para pintar y resolver.
 *
 * **Invariante** (garantizado por [EquationCloudGenerator]): con las [options] dadas
 * existe **una única** combinación de etiquetas que satisface la ecuación, y es
 * exactamente [solution]. Por eso el motor valida comparando etiquetas en vez de
 * volver a evaluar aritmética: no hay respuestas alternativas válidas que perder.
 *
 * @property solution etiqueta correcta de cada hueco, en orden de lectura.
 * @property options fichas de la bandeja: las de la solución más distractores,
 *   ya barajadas.
 */
data class EquationPuzzle(
    val kind: EquationBlankKind,
    val slots: List<EquationSlot>,
    val solution: List<String>,
    val options: List<EquationToken>,
) {
    /** Nº de huecos a rellenar. */
    val blankCount: Int get() = solution.size
}

/**
 * Evalúa la cadena `n0 op0 n1 [op1 n2]` de **izquierda a derecha** (así se pinta,
 * con paréntesis explícitos en el primer par cuando hay tres términos, para que no
 * haya ambigüedad de precedencia).
 *
 * @return el resultado, o `null` si la cadena no es válida en el dominio del juego:
 *   división inexacta o por cero, o un resultado intermedio negativo. Devolver
 *   `null` es lo que permite descartar combinaciones "imposibles" al comprobar la
 *   unicidad de la solución.
 */
internal fun evalChain(numbers: List<Int>, ops: List<MathOp>): Int? {
    var acc = numbers.first()
    ops.forEachIndexed { i, op ->
        val n = numbers[i + 1]
        acc = when (op) {
            MathOp.ADD -> acc + n
            MathOp.SUB -> acc - n
            MathOp.MUL -> acc * n
            MathOp.DIV -> if (n == 0 || acc % n != 0) return null else acc / n
        }
        if (acc < 0) return null
    }
    return acc
}

/**
 * Generador de las ecuaciones de la nube. Escala con la ronda igual que el resto del
 * juego (operandos mayores, y a partir de cierta ronda ecuaciones de tres términos con
 * dos huecos), pero **más contenido que las burbujas**: la nube regala una vida, así
 * que debe poder resolverse en los segundos que dura.
 */
object EquationCloudGenerator {

    /**
     * Genera una ecuación incompleta para la ronda [round].
     *
     * Trabaja por **generar y verificar**: construye un candidato al azar y lo acepta
     * solo si sus fichas admiten una única solución (si un distractor también
     * cuadrara, el jugador podría acertar y que el juego se lo diera por malo). Si
     * tras [MAX_ATTEMPTS] intentos no sale ninguno —muy improbable—, cae en un
     * candidato fijo ya verificado, misma red de seguridad que el relleno de
     * distractores de [BubbleMathGenerator.generateRound].
     *
     * @param random fuente de aleatoriedad; inyectable para tests deterministas.
     */
    fun generate(round: Int, random: Random = Random.Default): EquationPuzzle {
        repeat(MAX_ATTEMPTS) {
            val candidate = buildCandidate(round, random)
            if (candidate != null && candidate.hasUniqueSolution()) {
                return candidate.toPuzzle(random)
            }
        }
        return FALLBACK.toPuzzle(random)
    }

    /**
     * Ecuación candidata con su estructura aún accesible (números, operadores y qué
     * posiciones se ocultan). Es privada porque solo hace falta para **verificar** la
     * unicidad; lo que sale del generador es el [EquationPuzzle] ya "aplanado".
     *
     * @property blanks posiciones ocultas: índices dentro de [numbers] si [kind] es
     *   NUMBER, o dentro de [ops] si es SYMBOL. Siempre ordenadas (= orden de lectura).
     * @property labels etiquetas de la bandeja: las de la solución más distractores.
     */
    private class Candidate(
        val numbers: List<Int>,
        val ops: List<MathOp>,
        val result: Int,
        val kind: EquationBlankKind,
        val blanks: List<Int>,
        val labels: List<String>,
    ) {
        /** Etiqueta correcta de cada hueco, en orden de lectura. */
        val solution: List<String> = blanks.map { i ->
            if (kind == EquationBlankKind.SYMBOL) ops[i].symbol.toString() else numbers[i].toString()
        }
    }

    /** Construye un candidato al azar, o `null` si la aritmética no cuadró. */
    private fun buildCandidate(round: Int, random: Random): Candidate? {
        val threeTerms = round >= THREE_TERM_ROUND && random.nextInt(100) < THREE_TERM_CHANCE
        val opsPool = BubbleMathGenerator.opsFor(round)
        val maxN = maxTerm(round)
        val maxF = maxFactor(round)

        // Se construye hacia adelante eligiendo cada término *después* del operador,
        // de forma que la operación siempre sea válida (resta no negativa, división
        // exacta). Generar números al azar y descartar lo inválido desperdiciaría la
        // mayoría de los intentos en cuanto entra la división.
        val numbers = mutableListOf(random.nextInt(2, maxN + 1))
        val ops = mutableListOf<MathOp>()
        var acc = numbers.first()
        repeat(if (threeTerms) 2 else 1) {
            val op = opsPool.random(random)
            val n = nextTerm(acc, op, maxN, maxF, random) ?: return null
            acc = evalChain(listOf(acc, n), listOf(op)) ?: return null
            numbers += n
            ops += op
        }
        if (acc < 1) return null // un resultado 0 hace la ecuación demasiado tramposa

        val kind = if (random.nextBoolean()) EquationBlankKind.SYMBOL else EquationBlankKind.NUMBER
        val blanks = when (kind) {
            // Faltan símbolos: se ocultan TODOS (uno con dos términos, dos con tres).
            EquationBlankKind.SYMBOL -> ops.indices.toList()
            // Faltan números: nunca el resultado (sin él la ecuación no se puede razonar).
            EquationBlankKind.NUMBER -> {
                val k = if (threeTerms && random.nextBoolean()) 2 else 1
                numbers.indices.shuffled(random).take(k).sorted()
            }
        }

        val solution = blanks.map { i ->
            if (kind == EquationBlankKind.SYMBOL) ops[i].symbol.toString() else numbers[i].toString()
        }
        val labels = buildLabels(kind, solution, random) ?: return null
        return Candidate(numbers, ops, acc, kind, blanks, labels)
    }

    /**
     * Elige el término que va **después** de [op] para que la operación sea válida en
     * el dominio del juego (sin negativos ni divisiones inexactas).
     *
     * @return el término, o `null` si con este acumulado no hay ninguno posible
     *   (p. ej. dividir 7 exactamente, o restar de un acumulado 1).
     */
    private fun nextTerm(acc: Int, op: MathOp, maxN: Int, maxF: Int, random: Random): Int? = when (op) {
        MathOp.ADD -> random.nextInt(2, maxN + 1)
        MathOp.SUB -> if (acc < 3) null else random.nextInt(1, acc) // deja resultado ≥ 1
        MathOp.MUL -> random.nextInt(2, maxF + 1)
        MathOp.DIV -> (2..minOf(acc, maxF)).filter { acc % it == 0 }.randomOrNull(random)
    }

    /**
     * Fichas de la bandeja: las de la solución más [DISTRACTORS] distractores.
     *
     * Puede devolver `null` si no logra reunir distractores suficientes (con símbolos
     * solo hay cuatro posibles); en ese caso se descarta el candidato entero.
     */
    private fun buildLabels(
        kind: EquationBlankKind,
        solution: List<String>,
        random: Random,
    ): List<String>? {
        val labels = solution.toMutableList()
        val wanted = labels.size + DISTRACTORS

        when (kind) {
            EquationBlankKind.SYMBOL ->
                MathOp.entries.map { it.symbol.toString() }
                    .filter { it !in labels }
                    .shuffled(random)
                    .take(wanted - labels.size)
                    .forEach { labels += it }

            EquationBlankKind.NUMBER -> {
                // Distractores "cerca" del número que ocultamos: obligan a calcular en
                // vez de a descartar por tamaño (mismo criterio que la banda de las burbujas).
                var guard = 0
                while (labels.size < wanted && guard++ < DISTRACTOR_ATTEMPTS) {
                    val base = solution.random(random).toInt()
                    val delta = random.nextInt(1, 4) * (if (random.nextBoolean()) 1 else -1)
                    val value = base + delta
                    if (value < 1) continue
                    val label = value.toString()
                    if (label in labels) continue
                    labels += label
                }
            }
        }
        return labels.takeIf { it.size == wanted }
    }

    /**
     * ¿Las fichas ofrecidas admiten **una sola** respuesta válida? Prueba todas las
     * asignaciones ordenadas de fichas distintas a huecos y cuenta cuántas
     * **combinaciones de etiquetas** distintas satisfacen la ecuación (por etiquetas y
     * no por fichas: si hay dos fichas con el mismo texto, cambiarlas de sitio no es
     * una respuesta diferente).
     */
    private fun Candidate.hasUniqueSolution(): Boolean {
        val valid = mutableSetOf<List<String>>()
        orderedPicks(labels.size, blanks.size).forEach { pick ->
            val attempt = pick.map { labels[it] }
            if (satisfies(attempt)) valid += attempt
        }
        return valid.size == 1
    }

    /** ¿La ecuación cuadra si se rellenan los huecos con [attempt] (en orden)? */
    private fun Candidate.satisfies(attempt: List<String>): Boolean {
        val nums = numbers.toMutableList()
        val operators = ops.toMutableList()
        blanks.forEachIndexed { blank, position ->
            when (kind) {
                EquationBlankKind.NUMBER -> nums[position] = attempt[blank].toIntOrNull() ?: return false
                EquationBlankKind.SYMBOL -> operators[position] = symbolToOp(attempt[blank]) ?: return false
            }
        }
        return evalChain(nums, operators) == result
    }

    /** Operador cuyo símbolo es [label], o `null` si no es un símbolo conocido. */
    private fun symbolToOp(label: String): MathOp? =
        MathOp.entries.firstOrNull { it.symbol.toString() == label }

    /**
     * Todas las formas ordenadas de escoger [k] índices **distintos** de `0..size-1`
     * (variaciones sin repetición). Con `size ≤ 4` y `k ≤ 2` son como mucho 12 casos,
     * así que la fuerza bruta es de sobra barata.
     */
    private fun orderedPicks(size: Int, k: Int): List<List<Int>> {
        if (k == 0) return listOf(emptyList())
        return orderedPicks(size, k - 1).flatMap { prefix ->
            (0 until size).filter { it !in prefix }.map { prefix + it }
        }
    }

    /**
     * Aplana el candidato al [EquationPuzzle] que consume la UI: la ecuación como
     * lista de piezas de izquierda a derecha y las fichas ya barajadas.
     *
     * Con tres términos se pintan **paréntesis explícitos** en el primer par para que
     * el orden de evaluación sea el mismo que lee el jugador (izquierda a derecha) sin
     * depender de la precedencia de × y ÷.
     */
    private fun Candidate.toPuzzle(random: Random): EquationPuzzle {
        fun number(i: Int): EquationSlot =
            if (kind == EquationBlankKind.NUMBER && i in blanks) EquationSlot.Blank(blanks.indexOf(i))
            else EquationSlot.Fixed(numbers[i].toString())

        fun operator(i: Int): EquationSlot =
            if (kind == EquationBlankKind.SYMBOL && i in blanks) EquationSlot.Blank(blanks.indexOf(i))
            else EquationSlot.Fixed(ops[i].symbol.toString())

        val threeTerms = numbers.size == 3
        val slots = buildList {
            if (threeTerms) add(EquationSlot.Fixed("("))
            add(number(0))
            add(operator(0))
            add(number(1))
            if (threeTerms) {
                add(EquationSlot.Fixed(")"))
                add(operator(1))
                add(number(2))
            }
            add(EquationSlot.Fixed("="))
            add(EquationSlot.Fixed(result.toString()))
        }

        return EquationPuzzle(
            kind = kind,
            slots = slots,
            solution = solution,
            options = labels.shuffled(random).mapIndexed { i, label -> EquationToken(i, label) },
        )
    }

    /**
     * Candidato fijo de emergencia: `6 ? 3 = 9`. Verificado a mano —ninguno de los
     * otros tres operadores da 9 (3, 18 y 2)—, así que cumple el invariante de
     * solución única sin depender del azar.
     */
    private val FALLBACK = Candidate(
        numbers = listOf(6, 3),
        ops = listOf(MathOp.ADD),
        result = 9,
        kind = EquationBlankKind.SYMBOL,
        blanks = listOf(0),
        labels = MathOp.entries.map { it.symbol.toString() },
    )

    /**
     * Cota de operandos de la nube. **Sí tiene techo**, a diferencia de la de las
     * burbujas: la nube se resuelve contra un cronómetro de segundos y es una
     * recompensa, no el eje de dificultad de la partida.
     */
    private fun maxTerm(round: Int): Int = (7 + round).coerceIn(9, 30)

    /** Cota de factores para × y ÷ dentro de la nube (mismo criterio contenido). */
    private fun maxFactor(round: Int): Int = (4 + round / 3).coerceIn(4, 10)

    /** Ronda a partir de la cual pueden salir ecuaciones de tres términos (dos huecos). */
    private const val THREE_TERM_ROUND = 5

    /** Probabilidad (%) de que una nube elegible sea de tres términos. */
    private const val THREE_TERM_CHANCE = 45

    /** Fichas sobrantes en la bandeja además de las de la solución. */
    private const val DISTRACTORS = 2

    /** Tope de intentos al buscar distractores numéricos únicos. */
    private const val DISTRACTOR_ATTEMPTS = 40

    /** Tope de candidatos a probar antes de recurrir a [FALLBACK]. */
    private const val MAX_ATTEMPTS = 60
}
