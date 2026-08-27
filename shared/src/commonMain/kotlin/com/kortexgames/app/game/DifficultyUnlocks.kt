package com.kortexgames.app.game

import com.kortexgames.app.domain.model.GameProgress
import com.kortexgames.app.game.defuser.MineDifficulty
import com.kortexgames.app.game.neon2048.Neon2048Config
import com.kortexgames.app.game.neonsudoku.SudokuDifficulty
import com.kortexgames.app.game.quantummerge.QuantumDifficulty

/**
 * # Dificultades que se DESBLOQUEAN jugando
 *
 * Los juegos que dejan elegir dificultad antes de empezar (Neon Defuser, Neon Sudoku
 * Matrix, Neon Grid 2048) no ofrecen sus escalones abiertos de par en par: el jugador
 * arranca en el primero y va abriendo los siguientes conforme demuestra que domina el
 * anterior. Es el gancho de progresión que ya tienen los juegos LEVELED (el carril de
 * niveles de la antesala) trasladado a los juegos que no tienen niveles.
 *
 * ## De dónde sale el estado (sin tocar la base de datos)
 * **No hay tabla ni columna nueva**: el desbloqueo se **deriva del historial de partidas**
 * que la app ya guarda —`user_progress` en Supabase, `GameProgressEntity` en local—, donde
 * cada fila lleva su `difficulty_level` y su `score`. Un escalón está superado si existe
 * una partida suya que cumple el [DifficultyRequirement] del juego.
 *
 * Esto tiene tres consecuencias buenas y ninguna migración:
 *  - **Funciona en invitado y offline**, porque el historial es local-first.
 *  - **Viaja con la cuenta**: al iniciar sesión,
 *    [ProgressRepository.syncPending][com.kortexgames.app.domain.repository.ProgressRepository.syncPending]
 *    descarga el historial de la nube, así que un móvil nuevo recupera los desbloqueos
 *    solo (no hay que "reganarlos").
 *  - **Es retroactivo**: las partidas ya jugadas antes de existir esta pantalla cuentan,
 *    porque siempre se guardaron con su dificultad y su puntaje.
 *
 * ## Por qué es una lista explícita
 * Igual que [GameRankingScopes], escalonar la dificultad es una decisión de diseño de cada
 * juego, no algo que deba deducirse solo: un juego que empiece a variar su `difficulty_level`
 * no debería quedarse con la mitad de su contenido bajo llave sin que nadie lo decidiera.
 * Los rótulos salen de los `enum`/config de cada juego, no de una copia, para que no puedan
 * desincronizarse.
 */
object DifficultyUnlocks {

    /**
     * Umbrales de puntaje de Neon Grid 2048, uno por escalón (4×4, 5×5, 6×6, 7×7); el
     * último tablero no necesita umbral porque no abre ninguno más.
     *
     * Suben poco a poco a propósito: en 2048 un tablero más grande se sobrevive **mejor**
     * (más huecos libres), así que el puntaje crece solo al avanzar de escalón y un umbral
     * plano se volvería trivial. Los valores están calibrados sobre una corrida normal —una
     * partida decente en 4×4 ronda los 1.000–2.000 puntos—, de forma que el primer
     * desbloqueo llegue en las primeras partidas y no a las cincuenta.
     */
    private val NEON_2048_SCORE_GATES = listOf(1_000, 2_000, 3_500, 5_000)

    /**
     * Umbrales de Quantum Merge, uno por escalón: `[0]` es lo que hay que sacar en Grande (el
     * escalón inicial) para abrir Mediano, `[1]` lo que hay que sacar en Mediano para abrir
     * Pequeño (Pequeño no abre nada más). El orden de los escalones va de más exigente a más
     * manejable —ver KDoc de [com.kortexgames.app.game.quantummerge.QuantumDifficulty]—, así que
     * el índice `tier - 1` de esta lista ya no es "Pequeño, Mediano" sino "Grande, Mediano".
     *
     * A diferencia de Neon Grid 2048, aquí el puntaje **baja** al subir de escalón (esferas más
     * grandes llenan antes el contenedor), así que una tabla creciente como la de 2048 no tendría
     * sentido: el umbral es más alto en el escalón que exige una partida más larga, no en el que
     * puntúa mejor.
     *
     * ⚠️ Los dos valores son los que traía la calibración ORIGINAL (cuando el escalón `[0]` era
     * Pequeño, no Grande) simplemente reasignados a las nuevas posiciones — no se han vuelto a
     * simular. Pequeño tiene más cabida que Grande, así que es probable que 3.000 sea un listón
     * más alto en Grande de lo que era en Pequeño; conviene recalibrar con la misma metodología
     * original (lanzamientos automáticos, mira aleatoria, sin estrategia) o con partidas reales
     * antes de confiar en que el primer desbloqueo sigue llegando en las primeras partidas.
     */
    private val QUANTUM_MERGE_SCORE_GATES = listOf(3_000, 5_000)

    /**
     * Juego → su puerta de dificultad. Los juegos ausentes ofrecen todo abierto.
     */
    private val gates: Map<String, DifficultyGate> = mapOf(
        // Buscaminas y Sudoku comparten criterio: solo la VICTORIA abre el escalón
        // siguiente (ambos guardan `score = 0` al perder, ver sus ViewModels).
        GameIds.NEON_DEFUSER to DifficultyGate(
            tierNames = MineDifficulty.entries.map { it.displayName },
            requirement = DifficultyRequirement.Victory,
        ),
        GameIds.NEON_SUDOKU_MATRIX to DifficultyGate(
            tierNames = SudokuDifficulty.entries.map { it.displayName },
            requirement = DifficultyRequirement.Victory,
        ),
        // 2048 no se gana ni se pierde: toda corrida termina y toda corrida puntúa, así que
        // "haber jugado" no distingue a nadie y el criterio tiene que ser un puntaje mínimo.
        GameIds.NEON_2048 to DifficultyGate(
            tierNames = Neon2048Config.BOARD_SIZE_OPTIONS.map { "$it×$it" },
            requirement = DifficultyRequirement.MinScore(NEON_2048_SCORE_GATES),
        ),
        // Quantum Merge tampoco se gana ni se pierde (corrida ENDLESS hasta desbordar), mismo
        // criterio que 2048: el puntaje mínimo es lo único que distingue una partida real de
        // simplemente haber abierto el juego.
        GameIds.QUANTUM_MERGE to DifficultyGate(
            tierNames = QuantumDifficulty.entries.map { it.displayName },
            requirement = DifficultyRequirement.MinScore(QUANTUM_MERGE_SCORE_GATES),
        ),
    )

    /** Puerta de [gameId], o `null` si el juego no escalona su dificultad. */
    fun gateFor(gameId: String): DifficultyGate? = gates[gameId]

    /** ¿[gameId] esconde sus dificultades hasta ganárselas? */
    fun isGated(gameId: String): Boolean = gameId in gates

    /**
     * Cuántos escalones tiene abiertos el jugador (1-based, **nunca menos de 1**: el primero
     * siempre está disponible), a partir de su [history] de partidas.
     *
     * Abre hasta **uno por encima del escalón más alto superado**, sin exigir que los
     * anteriores también lo estén. En la práctica jugando es lo mismo que una escalera
     * estricta —no se puede superar un escalón sin haberlo desbloqueado antes—, pero evita
     * el absurdo con el historial YA EXISTENTE: quien ganó en Experto cuando todas las
     * dificultades estaban abiertas no debe encontrarse Medio bajo llave por no haberlo
     * jugado nunca.
     *
     * @param history historial del jugador; puede venir filtrado por juego
     *   ([ProgressRepository.observeHistory][com.kortexgames.app.domain.repository.ProgressRepository.observeHistory])
     *   o completo — se filtra por `gameId` igualmente.
     * @return el nº de escalones abiertos, o [Int.MAX_VALUE] si el juego no tiene puerta
     *   declarada. Ese valor (todo abierto) es el fallo seguro: quitar una puerta nunca puede
     *   dejar contenido bajo llave por accidente.
     */
    fun unlockedTiers(gameId: String, history: List<GameProgress>): Int {
        val gate = gates[gameId] ?: return Int.MAX_VALUE
        val highestCleared = history
            .filter { it.gameId == gameId }
            .filter { gate.requirement.isCleared(it.difficultyLevel, it.toAttempt()) }
            .maxOfOrNull { it.difficultyLevel }
            ?: 0
        // +1 (el escalón que ese logro abre), acotado al último que existe y con el primero
        // siempre disponible.
        return (highestCleared + 1).coerceIn(1, gate.tierNames.size)
    }

    /**
     * Frase que explica **qué falta** para abrir el siguiente escalón ("Gana en Fácil para
     * desbloquear Medio"), o `null` si ya están todos abiertos o el juego no tiene puerta.
     *
     * Vive aquí y no en la pantalla porque el texto depende del criterio de desbloqueo, que
     * es justo lo que declara este registro: la UI solo lo pinta.
     *
     * @param unlockedTiers escalones abiertos hoy (ver [unlockedTiers]).
     */
    fun nextUnlockHint(gameId: String, unlockedTiers: Int): String? {
        val gate = gates[gameId] ?: return null
        if (unlockedTiers >= gate.tierNames.size) return null
        val currentName = gate.tierNames.getOrNull(unlockedTiers - 1) ?: return null
        val nextName = gate.tierNames.getOrNull(unlockedTiers) ?: return null
        return gate.requirement.hint(unlockedTiers, currentName, nextName)
    }

    /**
     * ¿La partida que **acaba de terminar** —aún no está en ningún historial— abre un
     * escalón nuevo? Es la pregunta que hace el diálogo de fin de partida para ofrecer
     * "Jugar en Medio" en el momento exacto en que se desbloquea.
     *
     * Se evalúa **directamente sobre el resultado**, sin esperar a que
     * [PlayerProgressRepository][com.kortexgames.app.domain.repository.PlayerProgressRepository]
     * termine de guardarlo y el historial reactivo se actualice: guardar es asíncrono
     * (local-first, con subida a Supabase de por medio) y depender de esa vuelta introduciría
     * una carrera entre "la partida ya guardó" y "el diálogo ya se está pintando". Como el
     * criterio de cada juego ([DifficultyRequirement]) solo mira el propio resultado, se
     * puede responder al instante.
     *
     * @param unlockedBefore escalones que había abiertos ANTES de esta partida (el valor de
     *   [unlockedTiers] leído al empezar a jugar, no después).
     * @param attempt dificultad y puntaje de la partida recién terminada.
     * @return el rótulo del escalón recién abierto, o `null` si esta partida no lo abrió —no
     *   se jugó en la frontera actual, no cumplió el requisito, o ya estaba todo abierto—.
     */
    fun justUnlockedLabel(gameId: String, unlockedBefore: Int, attempt: DifficultyAttempt): String? {
        val gate = gates[gameId] ?: return null
        if (unlockedBefore >= gate.tierNames.size) return null // ya estaba todo abierto
        // Solo cuenta si se jugó justo en la frontera: rejugar un escalón ya superado no
        // abre nada nuevo (el siguiente ya lo estaba, o no lo está por otro motivo).
        if (attempt.difficultyLevel != unlockedBefore) return null
        if (!gate.requirement.isCleared(unlockedBefore, attempt)) return null
        return gate.tierNames.getOrNull(unlockedBefore) // índice 0-based = escalón unlockedBefore+1 (1-based)
    }

    private fun GameProgress.toAttempt() = DifficultyAttempt(difficultyLevel, score)
}

/**
 * Lo mínimo que un [DifficultyRequirement] necesita para evaluar un escalón: en qué
 * dificultad se jugó y qué puntaje sacó. Existe para desacoplar el requisito del modelo
 * completo de [GameProgress] —con sus campos de sincronización, tiempo, id remoto…— que no
 * aportan nada al criterio; así puede evaluarse tanto una fila ya guardada del historial
 * como una partida que ACABA DE TERMINAR y aún no se guardó en ningún sitio (ver
 * [DifficultyUnlocks.justUnlockedLabel]).
 */
data class DifficultyAttempt(val difficultyLevel: Int, val score: Int)

/**
 * Puerta de dificultad de un juego: sus escalones en orden y qué hay que hacer para abrir
 * el siguiente.
 *
 * @property tierNames rótulo de cada escalón, en el mismo orden que la enum/config del juego.
 *   El índice dentro de la lista es `difficultyLevel - 1`, que es como los juegos rellenan
 *   [GameResult.difficultyLevel][com.kortexgames.app.domain.model.GameResult.difficultyLevel].
 * @property requirement criterio que da un escalón por superado.
 */
data class DifficultyGate(
    val tierNames: List<String>,
    val requirement: DifficultyRequirement,
)

/**
 * Qué exige un escalón para darse por superado (y abrir el siguiente). Se evalúa contra un
 * [DifficultyAttempt] —dificultad + puntaje—, así que solo puede mirar lo que la BD ya
 * guarda de cada partida.
 */
sealed interface DifficultyRequirement {

    /**
     * ¿[attempt], jugado en el escalón [tier] (1-based), lo da por superado?
     */
    fun isCleared(tier: Int, attempt: DifficultyAttempt): Boolean

    /**
     * Frase de requisito para la antesala: qué hacer en [tierName] para abrir [nextTierName].
     */
    fun hint(tier: Int, tierName: String, nextTierName: String): String

    /**
     * **Ganar una partida** del escalón. Se detecta por `score > 0`: en los juegos con
     * victoria/derrota (Buscaminas, Sudoku) la derrota se guarda con puntaje 0 a propósito
     * —el tablero quedó sin resolver—, así que el propio puntaje ya distingue una cosa de
     * la otra sin necesitar un campo nuevo en la BD.
     */
    data object Victory : DifficultyRequirement {
        override fun isCleared(tier: Int, attempt: DifficultyAttempt): Boolean = attempt.score > 0

        override fun hint(tier: Int, tierName: String, nextTierName: String): String =
            "Gana en $tierName para desbloquear $nextTierName"
    }

    /**
     * **Llegar a cierto puntaje** en el escalón. Es el criterio de los juegos sin victoria
     * (corridas que siempre terminan y siempre puntúan), donde "haber jugado" no demuestra
     * nada.
     *
     * @property perTier umbral de cada escalón (índice = `tier - 1`). Un escalón sin umbral
     *   declarado se considera inalcanzable ([Int.MAX_VALUE]) en vez de gratis: es mejor que
     *   una lista incompleta deje una puerta cerrada —visible y reportable— a que abra todo
     *   el contenido en silencio.
     */
    data class MinScore(val perTier: List<Int>) : DifficultyRequirement {
        override fun isCleared(tier: Int, attempt: DifficultyAttempt): Boolean =
            attempt.score >= thresholdFor(tier)

        override fun hint(tier: Int, tierName: String, nextTierName: String): String =
            "Consigue ${thresholdFor(tier)} pts en $tierName para desbloquear $nextTierName"

        private fun thresholdFor(tier: Int): Int = perTier.getOrNull(tier - 1) ?: Int.MAX_VALUE
    }
}
