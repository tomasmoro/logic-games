package com.example.kortexgames.game.starport

/**
 * # Catálogo de niveles de "Neon Starport Escape"
 *
 * Niveles **diseñados a mano y verificados por solver**: cada disposición se
 * validó con una búsqueda BFS exhaustiva del espacio de estados (fuera del
 * código de producción) que garantiza dos cosas que el `init` de
 * [StarportLevel] no puede comprobar:
 *
 *  1. **Resolubilidad**: existe una secuencia de deslizamientos que saca a la
 *     VIP (ningún nivel "trampa" llega a producción).
 *  2. **[StarportLevel.optimalMoves] exacto**: es el mínimo real de
 *     movimientos (métrica clásica de Rush Hour: 1 movimiento = 1 nave
 *     deslizada cualquier distancia), no una estimación. La puntuación y la
 *     precisión del motor penalizan contra este mínimo, así que inflarlo
 *     regalaría puntos y desinflarlo haría imposible el 100%.
 *
 * La dificultad crece con el óptimo (2 → 12) y el nº de naves (2 → 9). Todos
 * los niveles usan la esclusa a la DERECHA de la fila 2 (la convención del
 * género); variar la esclusa es posible sin tocar el motor, que es genérico.
 */
object StarportLevels {

    /** Esclusa común a todo el catálogo actual: borde derecho, fila 2. */
    private val EXIT = HangarExit(ExitSide.RIGHT, index = 2)

    /** Nº de niveles diseñados. La UI lo usa para dimensionar el selector. */
    val count: Int get() = levels.size

    /**
     * Nivel [number] (1-based). Más allá del catálogo se **cicla** sobre las
     * disposiciones existentes (nivel 11 = tablero del 1, etc.): así "Siguiente
     * nivel" nunca revienta y la puntuación sigue creciendo con el número de
     * nivel mientras se añaden niveles nuevos al catálogo.
     */
    fun forNumber(number: Int): StarportLevel {
        val n = number.coerceAtLeast(1)
        val base = levels[(n - 1) % levels.size]
        // Se re-numera para que score/récord reflejen el nivel REAL jugado,
        // aunque la disposición sea reciclada.
        return base.copy(number = n)
    }

    /** Azúcar para declarar niveles sin repetir la esclusa común. */
    private fun level(number: Int, optimalMoves: Int, vararg ships: Ship) =
        StarportLevel(number, EXIT, ships.toList(), optimalMoves)

    private val levels: List<StarportLevel> = listOf(
        // Nivel 1 (tutorial): un solo carguero cruzado — óptimo BFS: 2.
        level(1, optimalMoves = 2,
            Ship(0, Orientation.HORIZONTAL, 2, row = 2, col = 0, isVip = true),
            Ship(1, Orientation.VERTICAL, 2, row = 2, col = 4),
        ),
        // Nivel 2 — óptimo BFS: 3.
        level(2, optimalMoves = 3,
            Ship(0, Orientation.HORIZONTAL, 2, row = 2, col = 1, isVip = true),
            Ship(1, Orientation.VERTICAL, 2, row = 2, col = 5),
            Ship(2, Orientation.VERTICAL, 2, row = 3, col = 2),
            Ship(3, Orientation.VERTICAL, 2, row = 2, col = 3),
        ),
        // Nivel 3 — óptimo BFS: 4.
        level(3, optimalMoves = 4,
            Ship(0, Orientation.HORIZONTAL, 2, row = 2, col = 0, isVip = true),
            Ship(1, Orientation.HORIZONTAL, 3, row = 3, col = 2),
            Ship(2, Orientation.HORIZONTAL, 2, row = 5, col = 0),
            Ship(3, Orientation.VERTICAL, 3, row = 0, col = 3),
            Ship(4, Orientation.VERTICAL, 2, row = 1, col = 5),
        ),
        // Nivel 4 — óptimo BFS: 5.
        level(4, optimalMoves = 5,
            Ship(0, Orientation.HORIZONTAL, 2, row = 2, col = 0, isVip = true),
            Ship(1, Orientation.HORIZONTAL, 2, row = 3, col = 2),
            Ship(2, Orientation.VERTICAL, 3, row = 0, col = 2),
            Ship(3, Orientation.VERTICAL, 2, row = 3, col = 0),
            Ship(4, Orientation.VERTICAL, 2, row = 4, col = 5),
            Ship(5, Orientation.HORIZONTAL, 3, row = 4, col = 1),
        ),
        // Nivel 5 — óptimo BFS: 6.
        level(5, optimalMoves = 6,
            Ship(0, Orientation.HORIZONTAL, 2, row = 2, col = 0, isVip = true),
            Ship(1, Orientation.VERTICAL, 3, row = 0, col = 2),
            Ship(2, Orientation.VERTICAL, 3, row = 2, col = 4),
            Ship(3, Orientation.HORIZONTAL, 2, row = 1, col = 3),
            Ship(4, Orientation.HORIZONTAL, 2, row = 4, col = 2),
            Ship(5, Orientation.VERTICAL, 3, row = 0, col = 5),
            Ship(6, Orientation.VERTICAL, 2, row = 2, col = 3),
        ),
        // Nivel 6 — óptimo BFS: 7.
        level(6, optimalMoves = 7,
            Ship(0, Orientation.HORIZONTAL, 2, row = 2, col = 0, isVip = true),
            Ship(1, Orientation.HORIZONTAL, 3, row = 0, col = 1),
            Ship(2, Orientation.VERTICAL, 2, row = 1, col = 4),
            Ship(3, Orientation.VERTICAL, 2, row = 2, col = 5),
            Ship(4, Orientation.VERTICAL, 2, row = 0, col = 5),
            Ship(5, Orientation.VERTICAL, 3, row = 1, col = 2),
            Ship(6, Orientation.HORIZONTAL, 3, row = 4, col = 3),
        ),
        // Nivel 7 — óptimo BFS: 8.
        level(7, optimalMoves = 8,
            Ship(0, Orientation.HORIZONTAL, 2, row = 2, col = 0, isVip = true),
            Ship(1, Orientation.HORIZONTAL, 2, row = 4, col = 0),
            Ship(2, Orientation.VERTICAL, 3, row = 1, col = 2),
            Ship(3, Orientation.HORIZONTAL, 3, row = 3, col = 3),
            Ship(4, Orientation.VERTICAL, 2, row = 0, col = 4),
            Ship(5, Orientation.HORIZONTAL, 3, row = 5, col = 0),
            Ship(6, Orientation.VERTICAL, 2, row = 4, col = 3),
            Ship(7, Orientation.HORIZONTAL, 2, row = 0, col = 2),
        ),
        // Nivel 8 — óptimo BFS: 9.
        level(8, optimalMoves = 9,
            Ship(0, Orientation.HORIZONTAL, 2, row = 2, col = 0, isVip = true),
            Ship(1, Orientation.HORIZONTAL, 3, row = 0, col = 3),
            Ship(2, Orientation.HORIZONTAL, 2, row = 3, col = 3),
            Ship(3, Orientation.HORIZONTAL, 3, row = 5, col = 1),
            Ship(4, Orientation.VERTICAL, 2, row = 1, col = 4),
            Ship(5, Orientation.VERTICAL, 3, row = 1, col = 5),
            Ship(6, Orientation.VERTICAL, 2, row = 3, col = 0),
            Ship(7, Orientation.VERTICAL, 3, row = 1, col = 2),
        ),
        // Nivel 9 — óptimo BFS: 10.
        level(9, optimalMoves = 10,
            Ship(0, Orientation.HORIZONTAL, 2, row = 2, col = 0, isVip = true),
            Ship(1, Orientation.HORIZONTAL, 2, row = 0, col = 3),
            Ship(2, Orientation.VERTICAL, 2, row = 4, col = 5),
            Ship(3, Orientation.HORIZONTAL, 2, row = 4, col = 1),
            Ship(4, Orientation.HORIZONTAL, 2, row = 5, col = 3),
            Ship(5, Orientation.VERTICAL, 3, row = 1, col = 3),
            Ship(6, Orientation.VERTICAL, 2, row = 0, col = 2),
            Ship(7, Orientation.HORIZONTAL, 2, row = 4, col = 3),
            Ship(8, Orientation.HORIZONTAL, 2, row = 3, col = 4),
        ),
        // Nivel 10 — óptimo BFS: 12.
        level(10, optimalMoves = 12,
            Ship(0, Orientation.HORIZONTAL, 2, row = 2, col = 0, isVip = true),
            Ship(1, Orientation.HORIZONTAL, 3, row = 3, col = 1),
            Ship(2, Orientation.VERTICAL, 2, row = 1, col = 2),
            Ship(3, Orientation.VERTICAL, 3, row = 3, col = 4),
            Ship(4, Orientation.VERTICAL, 2, row = 1, col = 3),
            Ship(5, Orientation.VERTICAL, 2, row = 3, col = 5),
            Ship(6, Orientation.VERTICAL, 2, row = 0, col = 4),
            Ship(7, Orientation.HORIZONTAL, 2, row = 0, col = 2),
            Ship(8, Orientation.VERTICAL, 2, row = 0, col = 0),
        ),
    )
}
