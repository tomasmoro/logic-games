package com.kortexgames.app.game.neon2048

import androidx.lifecycle.viewModelScope
import com.kortexgames.app.core.audio.AudioAndHapticManager
import com.kortexgames.app.core.audio.SoundEffect
import com.kortexgames.app.core.mvi.MviViewModel
import com.kortexgames.app.domain.model.GameResult
import com.kortexgames.app.domain.repository.PlayerProgressRepository
import com.kortexgames.app.domain.repository.ProgressRepository
import com.kortexgames.app.domain.repository.SavedGameStateRepository
import com.kortexgames.app.game.GameIds
import com.kortexgames.app.game.GameStatus
import com.kortexgames.app.game.toGameOverInfo
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * # Neon Grid 2048 — motor de colapso y fusión (ViewModel, FASE 2)
 *
 * ViewModel MVI del juego. Igual que Neon Pulse —y a diferencia de los juegos que
 * delegan en un `BaseGameEngine`—, **toda la lógica vive aquí**: 2048 es un juego
 * por turnos sin bucle temporal, así que un motor separado solo añadiría una capa
 * de reenvío sin ganar nada. Cada swipe es una transición pura
 * `(tablero, dirección) → tablero nuevo`, que es exactamente la forma de un
 * reducer MVI.
 *
 * Responsabilidades:
 *  - **Colapsar y fusionar** el tablero en cada [Neon2048Intent.Swipe] ([collapse]).
 *  - Generar la ficha nueva tras cada jugada **válida** ([spawnTile]).
 *  - Detectar victoria (2048) y fin de partida (sin movimientos posibles).
 *  - Emitir el feedback sensorial como Effects one-shot.
 *  - Persistir el [GameResult] al terminar, con estrategia local-first.
 *
 * @param progress repositorio local-first: guarda la partida y devuelve percentil
 *   (además actualiza la progresión/récord por dentro).
 * @param playerProgress progresión por juego; solo se **observa** para pintar el
 *   récord (`bestScore`) en la cabecera desde el primer frame.
 * @param savedGameState partida en curso guardada al salir (back / "SALIR" del
 *   menú de pausa): [Neon2048Intent.StartGame] la reanuda si existe (ver
 *   [requestExit] y [Neon2048SavedState]). A diferencia de un juego LEVELED como
 *   Crucigrama Neón, aquí no hay nivel con el que emparejar el guardado — 2048 es
 *   ENDLESS, así que un guardado es simplemente "la última corrida sin terminar".
 * @param audio manager de sonido/háptica. El feedback fino viaja como
 *   [Neon2048Effect]; aquí se usa directamente solo para el remate de fin de
 *   partida, que ocurre ya dentro de una corrutina de guardado.
 * @param difficulty dificultad 1..5 que se reporta en el [GameResult]. No altera
 *   las reglas: en 2048 el tablero es siempre 4×4 (cambiarlo sería otro juego).
 * @param random inyectable para tests deterministas del spawn.
 */
class Neon2048ViewModel(
    private val progress: ProgressRepository,
    playerProgress: PlayerProgressRepository,
    private val savedGameState: SavedGameStateRepository,
    private val audio: AudioAndHapticManager,
    private val difficulty: Int = 1,
    private val random: Random = Random.Default,
) : MviViewModel<Neon2048Intent, Neon2048UiState, Neon2048Effect>(Neon2048UiState()) {

    // --- Estado interno del motor (NO es estado de UI) ---------------------------
    // Vive fuera del UiState porque la pantalla no lo dibuja: meterlo en el State
    // solo provocaría recomposiciones inútiles.

    /** Contador monotónico que asigna los [Tile.id]. Único requisito: no repetirse
     *  dentro de una partida. Se reinicia en cada partida nueva. */
    private var nextTileId = 0L

    /** Jugadas válidas (las que cambiaron el tablero). Denominador de la precisión. */
    private var moves = 0

    /** Jugadas válidas que además produjeron al menos una fusión. Numerador de la
     *  precisión: mide cuánta de la planificación del jugador acabó en progreso
     *  real, que es la habilidad que entrena el juego. */
    private var productiveMoves = 0

    // Cronómetro que descuenta pausas (mismo criterio que BaseGameEngine: el
    // `completion_time_ms` no debe inflarse si el jugador deja el juego pausado).
    private var runMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var accumulated: Duration = Duration.ZERO

    /**
     * true una vez que el jugador **usó** la segunda oportunidad (vio el anuncio y
     * revivió) en esta sesión de juego. La oferta de revivir es de **una sola vez**:
     * tras consumirla no se vuelve a ofrecer, ni siquiera al empezar una partida nueva.
     * Por eso NO se reinicia en [startGame] — persiste mientras viva el ViewModel.
     * Rechazar la oferta no la consume (no vio el anuncio).
     */
    private var reviveConsumed = false

    init {
        // Récord previo: se pinta en la cabecera antes incluso de la primera jugada.
        // Se toma el máximo con lo que ya haya en el estado para que una emisión
        // tardía del flow no "baje" un récord recién batido en esta misma partida.
        playerProgress.observe(GameIds.NEON_2048)
            .onEach { p -> setState { copy(bestScore = maxOf(bestScore, p?.bestMetric ?: 0)) } }
            .launchIn(viewModelScope)
        // Corrida guardada al salir: la antesala la ofrece como "Continuar". Se
        // observa (en vez de leerla una vez) para que el botón desaparezca solo al
        // reanudarla o al terminar la partida, que es cuando se borra la fila.
        savedGameState.observe(GameIds.NEON_2048)
            .onEach { json -> setState { copy(savedScore = json?.let(::decodeSaved)?.score) } }
            .launchIn(viewModelScope)
        // No se arranca aquí: se queda en IDLE mostrando la antesala/intro y la
        // partida empieza con StartGame / ResumeSaved (patrón del resto de juegos).
    }

    override fun onIntent(intent: Neon2048Intent) {
        when (intent) {
            // StartGame ("Empezar de nuevo" en la antesala) y RestartGame (HUD /
            // overlay de victoria-derrota) arrancan siempre de cero; ResumeSaved
            // retoma la corrida guardada. Ninguna reanuda por accidente: la elección
            // es explícita del jugador (§ antesala Continuar vs. Empezar de nuevo).
            Neon2048Intent.StartGame,
            Neon2048Intent.RestartGame -> startGame()
            Neon2048Intent.ResumeSaved -> resumeSaved()

            is Neon2048Intent.Swipe -> onSwipe(intent.direction)
            Neon2048Intent.ContinueAfterWin -> setState { copy(keepPlaying = true) }
            Neon2048Intent.Revive -> grantRevive()
            Neon2048Intent.DeclineRevive -> declineRevive()
            Neon2048Intent.Pause -> pause()
            Neon2048Intent.Resume -> resume()
            is Neon2048Intent.SelectBoardSize -> selectBoardSize(intent.size)
        }
    }

    /**
     * El anuncio recompensado concedió el trato: **limpia todas las fichas 2 y 4**,
     * liberando casillas para que la corrida continúe (conserva puntuación y récord).
     * Marca la segunda oportunidad como consumida: no se vuelve a ofrecer en la sesión.
     */
    private fun grantRevive() {
        reviveConsumed = true
        setState {
            copy(
                tiles = tiles.filterNot { it.value == 2 || it.value == 4 },
                ghosts = emptyList(),
                awaitingRevive = false,
            )
        }
    }

    /**
     * El jugador rechazó la oferta (o el anuncio se cerró / no había): fin de partida
     * real. No consume la segunda oportunidad (no vio el anuncio).
     */
    private fun declineRevive() {
        setState { copy(awaitingRevive = false) }
        finish()
    }

    /** Cambia el tamaño elegido en la antesala; no-op fuera de IDLE (ver KDoc del intent). */
    private fun selectBoardSize(size: Int) {
        if (currentState.status != GameStatus.IDLE) return
        if (size !in Neon2048Config.BOARD_SIZE_OPTIONS) return
        setState { copy(boardSize = size) }
    }

    // ---------------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------------

    /**
     * Retoma la corrida guardada al salir. El guardado se consume (se borra) al
     * reanudar: a partir de ahí la partida vuelve a estar viva y el próximo guardado
     * será el de esta sesión. Restaura también el contador de ids y los contadores
     * de precisión (ver KDoc de [Neon2048SavedState]). No-op si no hay ninguna.
     */
    private fun resumeSaved() {
        viewModelScope.launch {
            val saved = savedGameState.load(GameIds.NEON_2048)?.let(::decodeSaved) ?: return@launch
            savedGameState.clear(GameIds.NEON_2048)
            nextTileId = saved.nextTileId
            moves = saved.moves
            productiveMoves = saved.productiveMoves
            accumulated = Duration.ZERO
            runMark = TimeSource.Monotonic.markNow()
            setState {
                Neon2048UiState(
                    tiles = saved.tiles,
                    ghosts = saved.ghosts,
                    score = saved.score,
                    bestScore = maxOf(bestScore, saved.bestScore),
                    status = GameStatus.RUNNING,
                    hasWon = saved.hasWon,
                    keepPlaying = saved.keepPlaying,
                    boardSize = saved.boardSize,
                )
            }
        }
    }

    /**
     * Decodifica un guardado. Tolerante a fallos: un JSON de una versión anterior
     * del estado se trata como "no hay partida" en vez de romper la pantalla.
     */
    private fun decodeSaved(json: String): Neon2048SavedState? =
        runCatching { Json.decodeFromString<Neon2048SavedState>(json) }.getOrNull()

    /**
     * Punto único de salida "en juego" (back del sistema vía
     * [com.kortexgames.app.ui.components.GameExitGuard] o "SALIR" del menú de
     * pausa): si hay una corrida en curso ([GameStatus.RUNNING] o
     * [GameStatus.PAUSED] — el menú de pausa deja el estado en PAUSED) la guarda
     * antes de navegar atrás. En el resto de estados (antesala, fin de partida) no
     * hay progreso que perder, así que [onExit] se llama directo.
     */
    fun requestExit(onExit: () -> Unit) {
        val s = currentState
        // Durante la oferta de revivir el tablero está sin movimientos (partida de hecho
        // perdida): guardarlo dejaría una corrida bloqueada al reanudar. Se sale sin
        // guardar (se abandona la oferta), como en cualquier estado no jugable.
        if (s.awaitingRevive || (s.status != GameStatus.RUNNING && s.status != GameStatus.PAUSED)) {
            onExit()
            return
        }
        viewModelScope.launch {
            val saved = Neon2048SavedState(
                tiles = s.tiles,
                ghosts = s.ghosts,
                score = s.score,
                bestScore = s.bestScore,
                hasWon = s.hasWon,
                keepPlaying = s.keepPlaying,
                boardSize = s.boardSize,
                nextTileId = nextTileId,
                moves = moves,
                productiveMoves = productiveMoves,
            )
            savedGameState.save(GameIds.NEON_2048, Json.encodeToString(saved))
            onExit()
        }
    }

    /**
     * Reinicia el estado y reparte las fichas iniciales. Descarta cualquier corrida
     * guardada: llegar aquí siempre es una decisión explícita ("Empezar de nuevo" en
     * la antesala, o reiniciar), y dejar el guardado vivo lo reofrecería como
     * "Continuar" una partida que el jugador acaba de abandonar. Para retomarla está
     * [resumeSaved].
     */
    private fun startGame() {
        viewModelScope.launch { savedGameState.clear(GameIds.NEON_2048) }
        nextTileId = 0L
        moves = 0
        productiveMoves = 0
        accumulated = Duration.ZERO
        runMark = TimeSource.Monotonic.markNow()

        // El tamaño de tablero es una preferencia del jugador (elegida en la
        // antesala, ver `selectBoardSize`): sobrevive al reinicio del resto del
        // estado igual que el récord, y solo cambia si vuelve a elegir otra.
        val boardSize = currentState.boardSize
        var tiles = emptyList<Tile>()
        repeat(Neon2048Config.INITIAL_TILES) {
            tiles = tiles + (spawnTile(tiles, boardSize) ?: return@repeat)
        }
        // El récord sobrevive al reinicio (es histórico, no de la partida).
        val best = currentState.bestScore
        setState {
            Neon2048UiState(
                tiles = tiles,
                bestScore = best,
                status = GameStatus.RUNNING,
                boardSize = boardSize,
            )
        }
    }

    /** Congela la partida (la UI atenúa el tablero e ignora los gestos). */
    private fun pause() {
        if (currentState.status != GameStatus.RUNNING) return
        accumulated += runMark?.elapsedNow() ?: Duration.ZERO
        runMark = null
        setState { copy(status = GameStatus.PAUSED) }
    }

    /** Reanuda tras pausa; el cronómetro vuelve a correr desde ahora. */
    private fun resume() {
        if (currentState.status != GameStatus.PAUSED) return
        runMark = TimeSource.Monotonic.markNow()
        setState { copy(status = GameStatus.RUNNING) }
    }

    // ---------------------------------------------------------------------------
    // Jugada
    // ---------------------------------------------------------------------------

    /**
     * Procesa un deslizamiento: colapsa, puntúa, genera ficha nueva y evalúa el
     * fin de partida.
     *
     * Regla clásica que conviene no perder de vista: **un swipe que no cambia el
     * tablero no consume turno**. No suma jugada, no genera ficha y no puede
     * provocar un game over; solo devuelve una vibración de error para que el
     * jugador note "por aquí no hay nada" sin apartar la vista del tablero.
     */
    private fun onSwipe(direction: Direction) {
        val s = currentState
        if (s.status != GameStatus.RUNNING) return

        val move = collapse(s.tiles, direction, s.boardSize)
        if (!move.moved) {
            sendEffect(Neon2048Effect.Vibrate.Error)
            return
        }

        moves++
        if (move.mergeCount > 0) productiveMoves++

        // La ficha nueva se calcula sobre el tablero YA colapsado: nunca puede
        // aparecer en una casilla que la jugada acaba de liberar a medias.
        val spawned = spawnTile(move.tiles, s.boardSize)
        val tiles = if (spawned != null) move.tiles + spawned else move.tiles
        val newScore = s.score + move.gainedScore
        val justWon = !s.hasWon && move.highestMerge >= Neon2048Config.WINNING_VALUE

        setState {
            copy(
                tiles = tiles,
                ghosts = move.ghosts,
                score = newScore,
                bestScore = maxOf(bestScore, newScore),
                hasWon = hasWon || justWon,
            )
        }

        emitMoveFeedback(move, justWon)

        // El game over se evalúa DESPUÉS de generar la ficha nueva: es justo esa
        // ficha la que puede dejar el tablero sin jugadas posibles. Antes de terminar,
        // ofrece revivir una vez por sesión, PERO solo si hay fichas 2 o 4 que limpiar
        // (si no, la recompensa no despejaría nada y el tablero seguiría bloqueado).
        if (!hasMovesLeft(tiles, s.boardSize)) {
            val hasClearable = tiles.any { it.value == 2 || it.value == 4 }
            if (!reviveConsumed && hasClearable) {
                setState { copy(awaitingRevive = true) }
            } else {
                finish()
            }
        }
    }

    /**
     * Tabla única jugada → feedback sensorial. Centralizarla (en vez de repartir
     * `sendEffect` por el reducer) hace trivial reajustar la "textura" del juego.
     *
     * Criterio: la fusión pisa al movimiento —si en la misma jugada hay fusión,
     * suena solo esa— para no encadenar dos sonidos en 100 ms. Y la vibración
     * fuerte se reserva a los hitos ≥ 128: si todo vibrara fuerte, nada se
     * sentiría importante.
     */
    private fun emitMoveFeedback(move: MoveOutcome, justWon: Boolean) {
        if (justWon) {
            sendEffect(Neon2048Effect.PlaySound.Win)
            sendEffect(Neon2048Effect.Vibrate.Heavy)
            sendEffect(Neon2048Effect.ShowWinCelebration)
            return
        }
        if (move.mergeCount > 0) {
            sendEffect(Neon2048Effect.PlaySound.Merge)
            val heavy = move.highestMerge >= Neon2048Config.HEAVY_HAPTIC_THRESHOLD
            sendEffect(if (heavy) Neon2048Effect.Vibrate.Heavy else Neon2048Effect.Vibrate.Tick)
            if (move.highestMerge >= Neon2048Config.FIREWORKS_MERGE_THRESHOLD) {
                sendEffect(Neon2048Effect.ShowMergeCelebration(move.highestMerge))
            }
        } else {
            sendEffect(Neon2048Effect.PlaySound.Move)
            sendEffect(Neon2048Effect.Vibrate.Tick)
        }
    }

    // ---------------------------------------------------------------------------
    // Spawn y fin de partida
    // ---------------------------------------------------------------------------

    /**
     * Coloca una ficha nueva en una casilla libre al azar.
     *
     * @param tiles tablero sobre el que buscar hueco (ya colapsado).
     * @param boardSize lado del tablero de esta partida.
     * @return la ficha creada, o `null` si no queda ninguna casilla libre. El
     *   `null` no es un caso de error: en la última jugada el tablero puede
     *   llenarse por completo, y quien decide si eso es game over es
     *   [hasMovesLeft] (aún podrían quedar fusiones posibles).
     */
    private fun spawnTile(tiles: List<Tile>, boardSize: Int): Tile? {
        val occupied = tiles.mapTo(HashSet()) { it.position }
        val free = ArrayList<GridPos>(boardSize * boardSize)
        for (row in 0 until boardSize) {
            for (col in 0 until boardSize) {
                val pos = GridPos(row, col)
                if (pos !in occupied) free += pos
            }
        }
        if (free.isEmpty()) return null

        val pos = free[random.nextInt(free.size)]
        val lucky = random.nextFloat() < Neon2048Config.SPAWN_LUCKY_PROBABILITY
        return Tile(
            id = nextTileId++,
            value = if (lucky) Neon2048Config.SPAWN_VALUE_LUCKY else Neon2048Config.SPAWN_VALUE,
            row = pos.row,
            col = pos.col,
            isNew = true,
        )
    }


    /**
     * Cierra la partida y persiste el resultado (local-first).
     *
     * Idempotente: si ya está FINISHED no hace nada, para blindarse ante un doble
     * disparo (p. ej. un swipe rezagado que llegue tras el último).
     */
    private fun finish() {
        if (currentState.status == GameStatus.FINISHED) return
        accumulated += runMark?.elapsedNow() ?: Duration.ZERO
        runMark = null
        // Se conservan las fichas para que el overlay de resultado se dibuje sobre
        // el tablero final; los fantasmas sí se limpian (ya cumplieron su animación).
        setState { copy(status = GameStatus.FINISHED, ghosts = emptyList()) }

        val result = GameResult(
            gameId = GameIds.NEON_2048,
            score = currentState.score,
            completionTimeMs = accumulated.inWholeMilliseconds,
            // "Precisión" en 2048 = jugadas que produjeron fusión sobre el total.
            // No hay aciertos y fallos como tal, pero esta ratio sí mide lo que el
            // juego entrena: cuántos movimientos sirvieron para algo.
            accuracyPercentage = if (moves == 0) 0.0 else productiveMoves.toDouble() / moves * 100.0,
            difficultyLevel = difficulty,
            reachedMetric = currentState.score,  // ENDLESS por puntaje (ver GameProgressions)
        )
        viewModelScope.launch {
            // Corrida terminada: un guardado de esta partida (si quedó alguno) es
            // "fantasma" a partir de aquí, ya se registró el resultado final.
            savedGameState.clear(GameIds.NEON_2048)
            val outcome = progress.saveResult(result)
            audio.playSound(SoundEffect.LEVEL_UP)
            setState { copy(gameOver = outcome.toGameOverInfo(result)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Álgebra del tablero (funciones puras)
// ---------------------------------------------------------------------------
// Viven a nivel de fichero —junto al ViewModel que las usa, no en un motor
// aparte— pero fuera de la clase y como `internal`: no necesitan ni un solo
// campo del ViewModel, y sacarlas de él las hace **testeables sin instanciarlo**
// (instanciar un ViewModel obligaría a montar repositorios falsos solo para
// comprobar una fusión). Al no depender de estado, cada una cumple: mismas
// entradas → mismas salidas.

/**
 * Resultado de colapsar el tablero en una dirección. Se devuelve todo junto (y
 * no como varios campos mutables) para que [collapse] pueda ser pura: mismas
 * fichas + misma dirección → mismo resultado, sin tocar el estado. Eso evita
 * además que la UI llegue a ver un tablero a medio colapsar.
 *
 * @property tiles fichas resultantes, ya en sus casillas finales.
 * @property ghosts fichas absorbidas en las fusiones, con su origen y destino
 *   explícitos para poder animarlas viajando (ver [Ghost] y [Neon2048UiState.ghosts]).
 * @property gainedScore puntos ganados: la suma de los **valores resultantes**
 *   de cada fusión (2+2 → +4), como en el 2048 original.
 * @property mergeCount número de fusiones de la jugada (decide el SFX).
 * @property highestMerge mayor valor creado en la jugada (decide háptica y
 *   victoria); 0 si no hubo fusiones.
 * @property moved true si el tablero cambió — única condición que hace válida
 *   la jugada.
 */
internal data class MoveOutcome(
    val tiles: List<Tile>,
    val ghosts: List<Ghost>,
    val gainedScore: Int,
    val mergeCount: Int,
    val highestMerge: Int,
    val moved: Boolean,
)

/**
 * ## Por qué se pasa por estructuras intermedias y no se mueve ficha a ficha
 *
 * La tentación es recorrer las fichas y empujarlas una a una hasta chocar.
 * No funciona: el resultado depende del **orden** en que se procesen (una
 * ficha empujada podría fusionarse con otra que aún no se ha movido) y
 * además hay que garantizar la regla de "cada ficha se fusiona **una sola
 * vez** por turno" —sin ella, una fila `[2,2,4,·]` haría 2+2=4 y ese 4 se
 * volvería a fusionar con el 4 vecino dando un 8 de un solo swipe, que es
 * jugablemente incorrecto—.
 *
 * Por eso el algoritmo trabaja en tres pasos con estructuras intermedias:
 *
 *  1. **Indexar** las fichas por casilla (`byPos`), para poder leer el
 *     tablero como una rejilla sin perder la identidad de cada ficha.
 *  2. **Proyectar** cada línea (fila o columna, según el eje) a una lista
 *     compacta ordenada *desde la pared de destino hacia afuera*. Al
 *     compactar desaparecen los huecos, que es justo lo que hace el
 *     deslizamiento; y al orientar la lista hacia la pared, las cuatro
 *     direcciones se resuelven con **el mismo código** (solo cambia el orden
 *     de las casillas), en vez de cuatro variantes casi iguales donde es muy
 *     fácil que se cuele un fallo en una sola de ellas.
 *  3. **Recorrer la lista una vez**, en pares: si dos consecutivas son
 *     iguales se fusionan y se saltan **ambas** (`i += 2`). Ese salto *es* la
 *     regla de "una fusión por ficha y turno", garantizada por construcción y
 *     sin necesidad de banderas `alreadyMerged` que haya que limpiar después.
 *
 * Solo al final se construye la lista nueva de fichas. El estado nunca ve un
 * tablero a medio colapsar: pasa del anterior al siguiente de una vez, que es
 * lo que MVI espera y lo que permite a Compose animar la transición completa.
 *
 * Las **identidades** se conservan con intención: en una fusión sobrevive el
 * `id` de la ficha más cercana a la pared (la que "espera") y la que llega se
 * convierte en fantasma. Así la superviviente recorre la distancia corta y el
 * ojo lee el encuentro como una absorción, no como un intercambio.
 */
internal fun collapse(tiles: List<Tile>, direction: Direction, boardSize: Int): MoveOutcome {
    val byPos = tiles.associateBy { it.position }
    val result = ArrayList<Tile>(tiles.size)
    val ghosts = ArrayList<Ghost>()
    var gained = 0
    var mergeCount = 0
    var highestMerge = 0
    var moved = false

    for (index in 0 until boardSize) {
        val lane = lanePositions(direction, index, boardSize)
        val present = lane.mapNotNull(byPos::get)   // paso 2: línea compacta

        var slot = 0
        var i = 0
        while (i < present.size) {
            val current = present[i]
            val next = present.getOrNull(i + 1)
            val target = lane[slot]

            if (next != null && next.value == current.value) {
                val mergedValue = current.value * 2
                result += current.copy(
                    value = mergedValue,
                    row = target.row,
                    col = target.col,
                    isNew = false,
                    isMerged = true,
                )
                // La absorbida se anima viajando de SU casilla original al
                // destino: si solo se guardara el destino (como en una versión
                // previa), Compose la vería como una ficha "nueva" sin historial
                // de posición y la dibujaría ya plantada en el destino desde el
                // primer frame, visible por detrás mientras la superviviente aún
                // viaja hacia allí — justo el bug de la ficha "fantasma estática".
                ghosts += Ghost(id = next.id, value = next.value, from = next.position, to = target)
                gained += mergedValue
                mergeCount++
                highestMerge = maxOf(highestMerge, mergedValue)
                moved = true    // una fusión SIEMPRE cambia el tablero
                i += 2          // paso 3: ninguna de las dos vuelve a fusionar
            } else {
                result += current.copy(
                    row = target.row,
                    col = target.col,
                    isNew = false,
                    isMerged = false,
                )
                if (current.row != target.row || current.col != target.col) moved = true
                i++
            }
            slot++
        }
    }
    return MoveOutcome(result, ghosts, gained, mergeCount, highestMerge, moved)
}

/**
 * Casillas de la línea [index] ordenadas **desde la pared de destino hacia
 * afuera**. Es la pieza que permite que [collapse] trate las cuatro
 * direcciones con el mismo código: la primera casilla de la lista es siempre
 * donde acabará la ficha más adelantada.
 *
 * @param direction dirección del swipe; decide el eje y el sentido.
 * @param index fila (movimientos horizontales) o columna (verticales).
 * @param boardSize lado del tablero de esta partida.
 */
internal fun lanePositions(direction: Direction, index: Int, boardSize: Int): List<GridPos> {
    val steps = 0 until boardSize
    return when (direction) {
        Direction.LEFT -> steps.map { GridPos(index, it) }
        Direction.RIGHT -> steps.reversed().map { GridPos(index, it) }
        Direction.UP -> steps.map { GridPos(it, index) }
        Direction.DOWN -> steps.reversed().map { GridPos(it, index) }
    }
}
/**
 * ¿Queda alguna jugada posible?
 *
 * Dos condiciones, en orden de coste: si hay hueco libre, cualquier dirección
 * mueve algo y no hace falta mirar más. Con el tablero lleno, basta con que
 * existan **dos vecinas iguales** (a la derecha o abajo de alguna casilla):
 * esa pareja se fusionaría al deslizar en su eje. Solo se miran esos dos
 * vecinos por casilla porque la adyacencia es simétrica — mirar también
 * izquierda y arriba comprobaría cada pareja dos veces.
 */
internal fun hasMovesLeft(tiles: List<Tile>, boardSize: Int): Boolean {
    if (tiles.size < boardSize * boardSize) return true
    val byPos = tiles.associateBy { it.position }
    for (row in 0 until boardSize) {
        for (col in 0 until boardSize) {
            val value = byPos[GridPos(row, col)]?.value ?: return true
            if (byPos[GridPos(row, col + 1)]?.value == value) return true
            if (byPos[GridPos(row + 1, col)]?.value == value) return true
        }
    }
    return false
}
