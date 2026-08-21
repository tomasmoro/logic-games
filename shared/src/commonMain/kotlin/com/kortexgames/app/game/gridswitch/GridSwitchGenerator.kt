package com.kortexgames.app.game.gridswitch

import com.kortexgames.app.game.grid.GridPosition
import kotlin.random.Random

/**
 * # Generador de niveles de "Neon Grid Switch"
 *
 * ## Por qué "desorden invertido desde un estado resuelto" y no luces al azar
 *
 * Colocar luces encendidas al azar (cada celda `true`/`false` con probabilidad
 * 50%) NO garantiza que el puzzle tenga solución: Lights Out es, matemáticamente,
 * un sistema de ecuaciones lineales sobre GF(2) (cada celda es una ecuación, cada
 * toque una incógnita 0/1), y ese sistema tiene una matriz de coeficientes que
 * depende del tamaño del tablero. Para ciertos tamaños esa matriz NO es invertible
 * y existen patrones de luces sin ninguna combinación de toques que los apague —
 * comprobarlo exigiría álgebra lineal en tiempo de generación (o una tabla
 * precalculada por tamaño) solo para poder *rechazar* tableros, un coste y una
 * complejidad que no aportan nada al jugador.
 *
 * La alternativa —y la que usa este generador— evita el problema en vez de
 * resolverlo: en vez de elegir un patrón de luces y preguntar "¿tiene solución?",
 * se **construye** el patrón aplicando toques reales a un tablero ya resuelto.
 *
 * ## Por qué eso garantiza solución en O(N)
 *
 * La conmutación de Lights Out ([LightGrid.toggled]) es, sobre GF(2), una
 * **involución conmutativa**: tocar la misma celda dos veces la deja como estaba
 * (autocancelación) y el orden de los toques no cambia el resultado final (cada
 * celda del tablero termina invertida un número de veces igual a cuántas veces
 * cayó dentro del "área de efecto" de algún toque, y solo importa la PARIDAD de
 * ese conteo, no el orden). De ahí se sigue directamente que:
 *
 * > Si se parte del tablero resuelto y se aplican los toques `t₁, t₂, …, tₙ`
 * > (en cualquier orden), volver a aplicar exactamente esos mismos `n` toques
 * > —en cualquier orden— deja el tablero de nuevo resuelto.
 *
 * Es decir: la propia secuencia de scramble ES una solución válida del tablero
 * que genera, y encontrarla no requiere buscar nada — ya se conoce, porque es la
 * que se acaba de aplicar. Verificarlo (o resolverlo por fuerza bruta si hiciera
 * falta) cuesta `O(N)` toques, sin álgebra lineal ni backtracking, a diferencia
 * del generador de caminos de [com.kortexgames.app.game.grid.GridPathBuilder]
 * (NP-completo en general) que sí necesita heurísticas y redes de seguridad.
 *
 * ## Determinismo
 *
 * El mismo par (etapa, semilla) produce siempre el mismo tablero: la semilla
 * suele derivarse del número de etapa, igual que en el resto de generadores del
 * proyecto (ver [com.kortexgames.app.game.grid.GridPathBuilder]).
 */
object GridSwitchGenerator {

    /**
     * Reintentos con semillas derivadas distintas antes de recurrir al
     * [fallback][fallbackBoard]. Cubre el caso extremadamente improbable (pero no
     * imposible) de que la secuencia de toques se cancele a sí misma y deje el
     * tablero ya resuelto — ver [scramble].
     */
    private const val MAX_REGEN_ATTEMPTS = 5

    /**
     * Genera el tablero de la etapa [stage] (1-based): tamaño y toques de
     * desorden según [GridSwitchStages], desordenado a partir de [seed].
     *
     * Nunca devuelve un tablero ya resuelto (no habría puzzle que jugar): si una
     * secuencia de toques se cancelara a sí misma —posible en teoría, ver
     * [scramble]— se reintenta con una semilla derivada distinta, y si tras
     * [MAX_REGEN_ATTEMPTS] sigue sin salir un tablero desordenado, se cae a
     * [fallbackBoard], que por construcción NUNCA puede quedar resuelto.
     */
    fun generate(stage: Int, seed: Long): LightGrid {
        val size = GridSwitchStages.gridSizeForStage(stage)
        val touches = GridSwitchStages.scrambleTouchesForStage(stage)
        repeat(MAX_REGEN_ATTEMPTS) { attempt ->
            val grid = scramble(size, touches, Random(seed * 1_000_003L + attempt))
            if (!grid.isSolved) return grid
        }
        return fallbackBoard(size)
    }

    /**
     * Aplica [touches] toques aleatorios sobre un tablero resuelto de lado [size].
     *
     * Se evita repetir la MISMA celda en dos toques consecutivos: dos toques
     * idénticos seguidos se autocancelan (involución, ver KDoc de archivo) y
     * "gastarían" dos de los [touches] pedidos sin desordenar nada, dejando el
     * scramble efectivo por debajo de lo que pide [GridSwitchStages] para esa
     * etapa. No hace falta evitar repeticiones NO consecutivas (dos toques a la
     * misma celda separados por otros sí desordenan distinto que dos juntos,
     * porque de por medio cambiaron celdas vecinas compartidas).
     */
    private fun scramble(size: Int, touches: Int, random: Random): LightGrid {
        var grid = LightGrid.solved(size)
        var lastTouched: GridPosition? = null
        repeat(touches) {
            var pos: GridPosition
            do {
                pos = GridPosition(random.nextInt(size), random.nextInt(size))
            } while (pos == lastTouched)
            grid = grid.toggled(pos)
            lastTouched = pos
        }
        return grid
    }

    /**
     * Tablero de emergencia determinista: un único toque en la esquina (0,0) desde
     * el estado resuelto. Un toque siempre cambia al menos 3 celdas (la esquina y
     * sus 2 vecinas ortogonales existentes en cualquier tablero ≥ 2×2), así que el
     * resultado NUNCA puede ser el tablero resuelto — es la garantía de que
     * [generate] siempre devuelve un puzzle jugable, pase lo que pase con el azar.
     */
    private fun fallbackBoard(size: Int): LightGrid =
        LightGrid.solved(size).toggled(GridPosition(0, 0))
}
