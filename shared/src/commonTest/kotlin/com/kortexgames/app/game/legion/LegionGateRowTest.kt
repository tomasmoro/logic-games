package com.kortexgames.app.game.legion

import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.HapticFeedback
import com.kortexgames.app.core.audio.SoundEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests del **generador de filas de puertas** de Neon Legion: las tres garantías que sostienen
 * la dificultad del juego (signos coherentes por fila, opciones distinguibles y escala acotada).
 *
 * Se prueba a través de la API pública del motor —arrancar una partida y leer las filas que
 * publica el estado— y no llamando al generador, que es privado: así el test valida lo que el
 * jugador recibe de verdad y no se rompe si la generación se reorganiza por dentro.
 *
 * El [Random] va sembrado para que un fallo sea reproducible, pero se recorren muchas semillas
 * para que los tests hablen del generador y no de una partida afortunada.
 */
class LegionGateRowTest {

    /** Audio de usar y tirar: al motor le basta con que exista, estos tests no miran el sonido. */
    private object FakeAudio : AudioAndHapticManager {
        override fun preload() = Unit
        override fun playSound(effect: SoundEffect) = Unit
        override fun hapticFeedback(type: HapticFeedback) = Unit
        override fun startMusic(fileName: String, loop: Boolean) = Unit
        override fun stopMusic() = Unit
        override fun release() = Unit
    }

    private fun engineWith(seed: Int): LegionEngine = LegionEngine(
        scope = CoroutineScope(Dispatchers.Default),
        audio = FakeAudio,
        random = Random(seed),
    ).also { it.start() }

    /** Las filas de la ronda 1 tal y como las publica el motor recién arrancado. */
    private fun firstRoundRows(seed: Int): List<GateRow> = engineWith(seed).state.value.gateRows

    @Test
    fun `una fila positiva no contiene ninguna puerta que reste`() {
        forEachRow { row, _ ->
            if (row.kind == GateRowKind.POSITIVE) {
                assertTrue(
                    row.gates.all { it.operation.isPositive },
                    "Fila POSITIVE con una puerta negativa: ${row.gates.map { it.operation.label }}",
                )
            }
        }
    }

    @Test
    fun `una fila negativa no contiene ninguna puerta que sume`() {
        forEachRow { row, _ ->
            if (row.kind == GateRowKind.NEGATIVE) {
                assertTrue(
                    row.gates.none { it.operation.isPositive },
                    "Fila NEGATIVE con una puerta positiva: ${row.gates.map { it.operation.label }}",
                )
            }
        }
    }

    @Test
    fun `una fila mixta ofrece al menos una que suma y al menos una que resta`() {
        forEachRow { row, _ ->
            if (row.kind == GateRowKind.MIXED) {
                val labels = row.gates.map { it.operation.label }
                assertTrue(row.gates.any { it.operation.isPositive }, "MIXED sin ninguna suma: $labels")
                assertTrue(row.gates.any { !it.operation.isPositive }, "MIXED sin ningún castigo: $labels")
            }
        }
    }

    @Test
    fun `la mejor puerta de una fila nunca aniquila al ejercito`() {
        // Ya NO hay suelo de 1 tropa: una puerta de castigo puede terminar la partida. Lo que el
        // generador sí garantiza es que exista salida — la mejor puerta de la fila deja viva a la
        // legión—, o la fila sería una sentencia sin contrajugada (ver KDoc de `negativeRow`).
        //
        // Se evalúa con la proyección para la que se generó la fila, que es el tamaño de ejército
        // contra el que el generador dimensionó sus valores.
        forEachProjectedRow { row, projected, seed ->
            val best = row.gates.maxOf { it.operation.apply(projected) }
            assertTrue(
                best >= 1,
                "Semilla $seed: fila sin salida con $projected tropas " +
                    "(${row.gates.map { it.operation.label }} dejan como mucho $best)",
            )
        }
    }

    @Test
    fun `las tres clases de fila aparecen en una partida`() {
        // Con el reparto 40 / 40 / 20 las tres clases deben salir sobradamente en unas cuantas
        // rondas. Es la red de seguridad de que ningún cambio de pesos deje una clase muerta.
        val seen = mutableSetOf<GateRowKind>()
        for (seed in 1..40) {
            firstRoundRows(seed).forEach { seen += it.kind }
        }
        assertEquals(GateRowKind.entries.toSet(), seen, "Faltan clases de fila: $seen")
    }

    @Test
    fun `las opciones de una fila nunca dan exactamente el mismo resultado`() {
        // Se evalúa cada fila con la proyección para la que fue GENERADA (las tropas que
        // tendría un jugador perfecto al llegar a ella), no con las de inicio: los valores de
        // las puertas se escalan a ese tamaño, así que juzgarlas todas con 10 tropas mediría
        // una fila que el jugador nunca ve.
        forEachProjectedRow { row, projected, seed ->
            val results = row.gates.map { it.operation.apply(projected).coerceAtLeast(1) }
            val duplicates = results.size - results.toSet().size
            assertEquals(
                0,
                duplicates,
                "Semilla $seed: fila empatada con $projected tropas: ${row.gates.map { it.operation.label }}",
            )
        }
    }

    @Test
    fun `el recorrido optimo de una ronda nunca se sale de la escala legible`() {
        // El techo de 1000 es lo que mantiene las cifras leíbles de un vistazo en movimiento
        // (ver LegionBalance.TROOPS_SOFT_CAP). Se recorre la ronda eligiendo siempre la mejor
        // puerta, que es la cota superior de lo que un jugador puede alcanzar. Se parte de
        // `roundStartTroops` (no de LegionBalance.INITIAL_TROOPS) porque cada partida sortea su
        // propia semilla de tropas (ver LegionEngine.seedTroops); el máximo objetivo posible
        // (semilla 20 → 80 tropas objetivo en la ronda 1) sigue muy por debajo del techo.
        for (seed in 1..40) {
            val engine = engineWith(seed)
            var projected = engine.state.value.roundStartTroops
            for (row in engine.state.value.gateRows) {
                projected = row.gates.maxOf { it.operation.apply(projected).coerceAtLeast(1) }
            }
            assertTrue(
                projected <= LegionBalance.TROOPS_SOFT_CAP,
                "El óptimo de la ronda 1 (semilla $seed) llegó a $projected, por encima del techo",
            )
        }
    }

    @Test
    fun `una fila positiva obliga a comparar y no a reconocer un simbolo`() {
        // El emparejamiento buscado es "x2 contra +n": si el jugador pudiera descartar una
        // opción por orden de magnitud no habría cálculo. Se exige que, cuando una fila positiva
        // trae un producto, exista una suma cuyo resultado quede a menos del 45 % de distancia
        // — evaluado, otra vez, con la proyección para la que se generó la fila.
        var pairingsFound = 0
        forEachProjectedRow { row, projected, _ ->
            if (row.kind != GateRowKind.POSITIVE) return@forEachProjectedRow
            val multiply = row.gates.firstOrNull { it.operation is GateOperation.Multiply }
                ?: return@forEachProjectedRow
            val product = multiply.operation.apply(projected)
            val closest = row.gates
                .filter { it !== multiply }
                .minOf { abs(it.operation.apply(projected) - product) }
            assertTrue(
                closest <= product * 0.45f,
                "Producto ${multiply.operation.label} sin rival cercano con $projected tropas " +
                    "(dista $closest de $product)",
            )
            pairingsFound++
        }
        assertTrue(pairingsFound > 0, "Ninguna fila positiva llegó a emparejar un producto")
    }

    /** Recorre las filas de la primera ronda de varias semillas y aplica [check] a cada una. */
    private fun forEachRow(check: (row: GateRow, seed: Int) -> Unit) {
        for (seed in 1..40) {
            firstRoundRows(seed).forEach { check(it, seed) }
        }
    }

    /**
     * Igual que [forEachRow] pero entregando además la **proyección óptima** con la que se
     * generó cada fila: se recorre la ronda eligiendo siempre la mejor puerta, que es
     * exactamente lo que hace el generador al construirla. Sin ese contexto, cualquier
     * afirmación sobre los valores de una fila avanzada mide un ejército que no existe.
     */
    private fun forEachProjectedRow(check: (row: GateRow, projected: Int, seed: Int) -> Unit) {
        for (seed in 1..40) {
            val engine = engineWith(seed)
            // Parte de la semilla de tropas real de ESTA partida, no de una constante fija: cada
            // motor sortea la suya en `onStart` (ver LegionEngine.seedTroops).
            var projected = engine.state.value.roundStartTroops
            for (row in engine.state.value.gateRows) {
                check(row, projected, seed)
                projected = row.gates.maxOf { it.operation.apply(projected).coerceAtLeast(1) }
            }
        }
    }
}
