package com.kortexgames.app.game

import com.kortexgames.app.game.defuser.MineDifficulty
import com.kortexgames.app.game.hypercube.MAX_LEVEL
import com.kortexgames.app.game.neon2048.Neon2048Config
import com.kortexgames.app.game.neonsudoku.SudokuDifficulty

/**
 * Qué juegos separan su **ranking mundial por dificultad** y cómo se llama cada
 * nivel de dificultad de cara al jugador.
 *
 * ## El problema que resuelve
 * En los juegos donde el jugador ELIGE la dificultad antes de empezar, una tabla
 * única premia jugar en fácil. Neon Defuser y Neon Sudoku Matrix usan el mismo
 * `BASE_SCORE = 1000` para sus cuatro dificultades y solo descuentan tiempo (y
 * errores/ayudas), así que un tablero fácil resuelto en un minuto puntúa por encima
 * de un experto resuelto en cinco: quien acepta el reto de verdad queda
 * estructuralmente fuera del top. La solución es la de toda la vida en Buscaminas y
 * Sudoku — **el récord de Experto es una tabla aparte de la de Principiante**.
 *
 * ## Por qué es una lista explícita y no automática
 * Se podría deducir del propio `difficulty_level` de cada partida, pero entonces
 * cualquier juego que empezara a variarlo partiría su tabla en cuatro sin que nadie
 * lo hubiera decidido. Separar el ranking es una decisión de diseño de cada juego,
 * así que se declara aquí, a la vista, y el backend se limita a obedecer
 * (`get_game_ranking(p_difficulty_level)`, migración 0028).
 *
 * Los rótulos salen de los propios `enum` de dificultad de cada juego, no de una
 * copia: así no pueden desincronizarse si mañana se renombra un nivel o se añade uno.
 *
 * El mismo mecanismo separa tablas por **nivel** en los juegos LEVELED (Hyper-Cube,
 * Water Sort): ahí `difficulty_level` no es una dificultad elegida, sino el nivel
 * jugado, pero el problema y la solución son el mismo — ver [difficultyNames] y
 * [openLevelRankedGames] respectivamente, según si el juego tiene un último nivel o
 * no.
 */
object GameRankingScopes {

    /**
     * Juego → nombres de sus dificultades, en el mismo orden que su `enum`. El índice
     * dentro de la lista es `difficultyLevel - 1`, que es como ambos juegos rellenan
     * `GameResult.difficultyLevel` (`difficulty.ordinal + 1`).
     */
    private val difficultyNames: Map<String, List<String>> = mapOf(
        GameIds.NEON_DEFUSER to MineDifficulty.entries.map { it.displayName },
        GameIds.NEON_SUDOKU_MATRIX to SudokuDifficulty.entries.map { it.displayName },
        // Neon Hyper-Cube separa por NIVEL, que es su eje de dificultad: cada nivel mezcla el cubo
        // con un giro más, así que un nivel 1 (dos giros) y un nivel 8 (nueve) no son la misma
        // prueba ni de lejos. Con tabla única —y rankeando por tiempo— el top mundial sería
        // sencillamente quien haya jugado el nivel 1, resuelto en tres segundos.
        GameIds.HYPER_CUBE to HYPER_CUBE_LEVEL_NAMES,
        // Neon Grid 2048 separa por TAMAÑO DE TABLERO, que es su eje de dificultad: un
        // tablero grande da más casillas donde fusionar y alcanza puntajes muchísimo
        // más altos que uno pequeño (el mismo problema de fondo que Defuser/Sudoku,
        // solo que aquí la "dificultad" fácil de ganar es jugar en el tablero MÁS
        // GRANDE desbloqueado, no en el más chico). Con tabla única, el top mundial
        // sería siempre quien tenga abierto el 8×8, por floja que fuera esa partida.
        GameIds.NEON_2048 to Neon2048Config.BOARD_SIZE_OPTIONS.map { "${it}×$it" },
    )

    /**
     * Juegos LEVELED de progresión **abierta** (sin nivel máximo) cuyo ranking también se
     * separa por nivel, pero cuyo rótulo no se puede precalcular en una lista fija como
     * [difficultyNames] porque no hay un último nivel que cerrar la lista. Su rótulo es
     * siempre `"Nivel N"` (ver [difficultyLabel]).
     *
     * ## Por qué Water Sort lo necesita
     * `WaterSortEngine.calculateScore` ya hace el puntaje monótono en el nivel para que una
     * tabla ÚNICA ordene sin invertirse, pero eso solo resuelve "quién llegó más lejos": un
     * jugador que resuelve el nivel 4 de forma impecable queda igualmente sepultado bajo
     * cualquiera que ya superó el nivel 5 con la peor partida posible. Separar por nivel (mismo
     * criterio que Hyper-Cube) hace que cada tabla compare partidas del MISMO reto, que es la
     * pregunta que de verdad le importa al jugador de un nivel concreto.
     */
    private val openLevelRankedGames: Set<String> = setOf(GameIds.WATER_SORT)

    /**
     * Juegos cuyo ranking mundial se ordena por **tiempo** (gana el más rápido) en vez de por
     * puntos.
     *
     * Tiene sentido solo cuando el reto es idéntico para todos los que comparten tabla: en el
     * Hyper-Cube, dentro de un nivel, todo el mundo resuelve una mezcla de la misma profundidad,
     * así que el tiempo es la comparación limpia y es además la que el jugador espera de un cubo
     * (el mundo del speedcubing lleva décadas midiéndose así). Rankear por puntos mezclaría ahí
     * velocidad con estilo —eficiencia de movimientos, ayudas usadas— y dejaría de responder a la
     * pregunta que se hace todo el mundo: "¿quién lo hace más rápido?".
     */
    private val rankedByTime: Set<String> = setOf(GameIds.HYPER_CUBE)

    /** ¿El ranking de [gameId] se ordena por tiempo (menor = mejor)? Ver [rankedByTime]. */
    fun isRankedByTime(gameId: String): Boolean = gameId in rankedByTime

    /**
     * ¿El ranking de [gameId] se separa por dificultad (o nivel)? Si es false, todas
     * las partidas del juego compiten en una única tabla (el caso normal: la inmensa
     * mayoría arranca siempre en `difficultyLevel = 1`).
     */
    fun isRankedByDifficulty(gameId: String): Boolean =
        gameId in difficultyNames || gameId in openLevelRankedGames

    /**
     * Nombre de la dificultad/nivel [difficultyLevel] (1-based) de [gameId], para
     * rotular el ranking ("Ranking · Experto", "Ranking · Nivel 12").
     *
     * @return null si el juego no separa por dificultad, o si el nivel cae fuera del
     *   enum (partidas antiguas o datos corruptos): en ese caso la UI simplemente no
     *   pone rótulo, que es preferible a inventar uno. Los juegos de [openLevelRankedGames]
     *   no tienen ese límite: cualquier nivel ≥ 1 tiene rótulo, porque no hay lista que
     *   se les pueda quedar corta.
     */
    fun difficultyLabel(gameId: String, difficultyLevel: Int): String? = when {
        gameId in openLevelRankedGames -> "Nivel $difficultyLevel".takeIf { difficultyLevel >= 1 }
        else -> difficultyNames[gameId]?.getOrNull(difficultyLevel - 1)
    }
}

/**
 * Rótulos de las "dificultades" del Neon Hyper-Cube: sus [MAX_LEVEL] niveles y, al final, el modo
 * libre.
 *
 * El modo libre ocupa la posición `MAX_LEVEL + 1` porque necesita su propio universo de ranking:
 * mezcla el cubo a fondo (20 giros) y compararlo con un nivel de la rampa no significaría nada. En
 * la práctica su tabla ni se muestra —el juego oculta el ranking al terminar en modo libre—, pero
 * el rótulo existe para que una partida suya nunca caiga por error en la tabla de un nivel.
 */
private val HYPER_CUBE_LEVEL_NAMES: List<String> =
    (1..MAX_LEVEL).map { "Nivel $it" } + "Modo libre"
