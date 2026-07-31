package com.kortexgames.app.game.bubblemath

import kotlin.random.Random

/**
 * Núcleo **puro** (sin corrutinas ni Compose) de "Burbujas de Cálculo": el modelo
 * de las operaciones y el generador de rondas. Se aísla del motor para poder
 * testearlo de forma determinista (inyectando un [Random] con semilla) — igual
 * que el modelo de "Ordena las Pociones".
 *
 * Regla de diseño del juego: en cada ronda cae un grupo de burbujas, cada una con
 * una operación (p. ej. "8 × 3"); abajo se muestra un **objetivo** (p. ej. "24") y
 * el jugador debe explotar la ÚNICA burbuja cuyo resultado coincide con él antes
 * de que toque el suelo.
 */

/**
 * Operación aritmética que puede llevar una burbuja. El [symbol] usa signos
 * tipográficos (× ÷ −) en vez de los ASCII (x / -) para que se lean nítidos y
 * "de matemáticas de verdad" en la burbuja.
 */
enum class MathOp(val symbol: Char) {
    ADD('+'),
    SUB('−'),
    MUL('×'),
    DIV('÷'),
}

/**
 * Una operación concreta "a op b" con su [value] ya resuelto (se calcula una vez
 * al generarla; la burbuja no recalcula nada al pulsarse).
 *
 * @property text representación lista para pintar, p. ej. "50 ÷ 2".
 */
data class MathExpression(
    val a: Int,
    val op: MathOp,
    val b: Int,
    val value: Int,
) {
    val text: String get() = "$a ${op.symbol} $b"
}

/**
 * Especificación de una ronda: el número [target] que se muestra en la base y las
 * [options] que caen. **Invariante**: exactamente una opción tiene `value == target`
 * (la correcta) y el resto son distractores con valores distintos entre sí y del
 * objetivo. El orden ya viene barajado (la correcta no está en una posición fija).
 */
data class RoundSpec(
    val target: Int,
    val options: List<MathExpression>,
)

/**
 * Generador de rondas. Deriva la dificultad del número de ronda ([round], 1-based):
 * conforme se avanza entran operaciones más difíciles (primero + y −, luego ×, al
 * final ÷), crecen los operandos y aumenta el número de burbujas simultáneas.
 *
 * Los distractores se acotan a una banda alrededor del objetivo para que el jugador
 * tenga que **calcular de verdad** (no le vale con localizar "el número más grande").
 */
object BubbleMathGenerator {

    /** Nº de burbujas de la ronda (incluida la correcta). Sube poco a poco, tope 6. */
    fun optionCount(round: Int): Int = (3 + round / 4).coerceIn(3, 6)

    /**
     * Duración de la caída (ms) de la ronda [round]: el tiempo que tarda una burbuja
     * en ir de la línea de aparición al suelo.
     *
     * La velocidad **solo acelera durante las primeras rondas** y a partir de
     * [SPEED_CAP_ROUND] queda congelada en [MIN_FALL_MS]. El porqué: la dificultad
     * de este juego crece por DOS ejes a la vez —cálculos más duros (entra ×, luego
     * ÷, operandos mayores) y más burbujas simultáneas que leer (hasta 6)—. Si además
     * se recortaba el tiempo ronda tras ronda, los tres ejes se multiplicaban y la
     * curva se volvía injugable enseguida. Congelando el reloj, a partir de la ronda 5
     * el reto es puramente cognitivo: mismo tiempo, cuentas más difíciles.
     */
    fun fallDurationMs(round: Int): Long =
        BASE_FALL_MS - (round.coerceIn(1, SPEED_CAP_ROUND) - 1) * FALL_STEP_MS

    /** Tiempo de caída en la ronda 1, el más holgado (ms). */
    const val BASE_FALL_MS = 7_000L

    /** Recorte de tiempo de caída por ronda mientras la velocidad aún sube (ms). */
    const val FALL_STEP_MS = 280L

    /** Ronda a partir de la cual la velocidad deja de subir (queda congelada). */
    const val SPEED_CAP_ROUND = 5

    /** Caída más rápida del juego: la de [SPEED_CAP_ROUND] en adelante (ms). */
    const val MIN_FALL_MS = BASE_FALL_MS - (SPEED_CAP_ROUND - 1) * FALL_STEP_MS

    /** Operaciones habilitadas según la ronda (curva de dificultad progresiva). */
    fun opsFor(round: Int): List<MathOp> = when {
        round < 3 -> listOf(MathOp.ADD, MathOp.SUB)
        round < 6 -> listOf(MathOp.ADD, MathOp.SUB, MathOp.MUL)
        else -> listOf(MathOp.ADD, MathOp.SUB, MathOp.MUL, MathOp.DIV)
    }

    /**
     * Genera la ronda [round]. Primero crea la operación correcta (su resultado es
     * el objetivo) y luego rellena con distractores cercanos y de valores únicos.
     *
     * @param random fuente de aleatoriedad; inyectable para tests deterministas.
     */
    fun generateRound(round: Int, random: Random = Random.Default): RoundSpec {
        val ops = opsFor(round)
        val correct = randomExpression(ops.random(random), round, random)
        val target = correct.value

        // Banda de tolerancia: los distractores caen "cerca" del objetivo para
        // forzar el cálculo. Escala con el objetivo y la ronda, con un mínimo.
        val band = (6 + round + target / 2).coerceAtMost(30)
        val count = optionCount(round)

        val usedValues = mutableSetOf(target)
        val options = mutableListOf(correct)

        // Intentos acotados: generar al azar y aceptar si es único y está en banda.
        var attempts = 0
        while (options.size < count && attempts < MAX_ATTEMPTS) {
            attempts++
            val expr = randomExpression(ops.random(random), round, random)
            if (expr.value in usedValues) continue
            if (kotlin.math.abs(expr.value - target) > band) continue
            usedValues += expr.value
            options += expr
        }

        // Red de seguridad: si la aleatoriedad no llenó el cupo (valores repetidos
        // o fuera de banda), completamos con distractores triviales garantizados
        // (v = (v+1) − 1) de valores aún no usados y no negativos.
        var delta = 1
        while (options.size < count) {
            val v = target + delta
            delta = if (delta > 0) -delta else -delta + 1 // 1, -1, 2, -2, 3...
            if (v < 0 || v in usedValues) continue
            usedValues += v
            options += MathExpression(v + 1, MathOp.SUB, 1, v)
        }

        return RoundSpec(target, options.shuffled(random))
    }

    /**
     * Crea una operación al azar del tipo [op] con operandos acordes a la [round].
     * Garantiza resultados **enteros y no negativos** (nada de restas negativas ni
     * divisiones inexactas): las divisiones se construyen desde el cociente hacia
     * el dividendo, y las restas eligen el sustraendo dentro del minuendo.
     */
    private fun randomExpression(op: MathOp, round: Int, random: Random): MathExpression {
        // Cota de operandos para + y −; crece con la ronda pero con techo razonable.
        val maxN = (9 + round * 2).coerceAtMost(40)
        // Cota de factores para × y ÷ (tablas): más contenida para no dar cifras enormes.
        val maxF = (5 + round / 2).coerceIn(5, 12)

        return when (op) {
            MathOp.ADD -> {
                val a = random.nextInt(1, maxN)
                val b = random.nextInt(1, maxN)
                MathExpression(a, op, b, a + b)
            }
            MathOp.SUB -> {
                val a = random.nextInt(2, maxN + 1)
                val b = random.nextInt(1, a) // b < a ⇒ resultado ≥ 1
                MathExpression(a, op, b, a - b)
            }
            MathOp.MUL -> {
                val a = random.nextInt(2, maxF + 1)
                val b = random.nextInt(2, maxF + 1)
                MathExpression(a, op, b, a * b)
            }
            MathOp.DIV -> {
                val b = random.nextInt(2, maxF + 1)      // divisor
                val q = random.nextInt(2, maxF + 1)      // cociente = resultado
                MathExpression(b * q, op, b, q)          // (b*q) ÷ b = q, exacto
            }
        }
    }

    /** Tope de intentos para llenar la ronda antes de recurrir al relleno seguro. */
    private const val MAX_ATTEMPTS = 200
}
