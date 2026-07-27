package com.example.kortexgames.data.repository

import com.example.kortexgames.data.local.LocalSudokuPuzzleDataSource
import com.example.kortexgames.data.remote.RemoteSudokuPuzzleDataSource
import com.example.kortexgames.game.neonsudoku.SudokuBank
import com.example.kortexgames.game.neonsudoku.SudokuDifficulty
import com.example.kortexgames.game.neonsudoku.SudokuPuzzle
import com.example.kortexgames.game.neonsudoku.SudokuPuzzleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kortexgames.shared.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.time.Clock

/**
 * # Banco de puzzles de Sudoku — implementación local-first (FASE 2)
 *
 * La **caché local** ([LocalSudokuPuzzleDataSource], SQLDelight) es la fuente de
 * verdad que sirve las partidas: la UI nunca depende de la red. La caché se llena
 * en dos etapas, mismo espíritu que [ProgressRepositoryImpl]:
 *
 *  1. **Seed empaquetado (offline garantizado):** la primera vez que se pide una
 *     dificultad y la caché está vacía de ella, se siembra desde el recurso CSV
 *     versionado ([SudokuBank]). Así el modo invitado 100% offline siempre tiene
 *     puzzles aunque nunca haya habido conexión.
 *  2. **Enriquecimiento remoto (más variedad):** en segundo plano y una sola vez
 *     por dificultad y sesión, se piden más puzzles a Supabase
 *     ([RemoteSudokuPuzzleDataSource]) y se fusionan en la caché (dedup por id).
 *     Si no hay red, falla en silencio y se sigue sirviendo del seed — nunca
 *     bloquea ni rompe el arranque de la partida.
 *
 * La **rotación "no repetir"** vive en SQL (`servedAt`, ver `SudokuPuzzle.sq`): el
 * repositorio solo marca el puzzle servido; la consulta elige el menos reciente.
 *
 * @param local caché SQLDelight (fuente que sirve y rota).
 * @param remote catálogo remoto de solo lectura (enriquece la caché).
 * @param scope scope de aplicación para el enriquecimiento en segundo plano (no
 *   se ata al ciclo de vida de una pantalla: si el jugador sale, la descarga
 *   puede completarse igual para la próxima partida).
 * @param clock reloj inyectable para la marca de rotación (`servedAt`); por
 *   defecto el del sistema. Inyectable para pruebas deterministas (mismo patrón
 *   que [ProgressRepositoryImpl]).
 */
class SudokuPuzzleRepositoryImpl(
    private val local: LocalSudokuPuzzleDataSource,
    private val remote: RemoteSudokuPuzzleDataSource,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : SudokuPuzzleRepository {

    private val seedMutex = Mutex()

    /** Seed empaquetado ya parseado y agrupado; `null` hasta la primera lectura. */
    private var seedByDifficulty: Map<SudokuDifficulty, List<SudokuPuzzle>>? = null

    /** Dificultades cuyo enriquecimiento remoto ya se intentó en esta sesión: se
     *  hace una sola vez por dificultad para no golpear la red en cada partida. */
    private val remoteAttempted = mutableSetOf<SudokuDifficulty>()

    override suspend fun randomPuzzle(difficulty: SudokuDifficulty): SudokuPuzzle {
        ensureSeeded(difficulty)
        maybeEnrichFromRemote(difficulty)

        val puzzle = local.nextPuzzle(difficulty)
            ?: return EMERGENCY_PUZZLE.copy(difficulty = difficulty)
        local.markServed(puzzle.id, clock.now().toEpochMilliseconds())
        return puzzle
    }

    /**
     * Siembra la caché desde el seed empaquetado si aún no hay puzzles de
     * [difficulty]. Un fallo al leer/parsear el recurso se traga a propósito: la
     * caché se queda vacía y [randomPuzzle] cae al puzzle de emergencia — nunca
     * debe reventar el arranque de una partida por un recurso ausente/corrupto.
     */
    private suspend fun ensureSeeded(difficulty: SudokuDifficulty) {
        if (local.countByDifficulty(difficulty) > 0) return
        val seed = runCatching { loadSeed()[difficulty].orEmpty() }.getOrDefault(emptyList())
        if (seed.isNotEmpty()) local.insertAll(seed)
    }

    /**
     * Lanza (una vez por dificultad y sesión) el enriquecimiento remoto en segundo
     * plano. No se espera (`launch`, no `await`): la partida se sirve ya desde el
     * seed; los puzzles nuevos quedan disponibles para las siguientes. Cualquier
     * fallo de red se traga a propósito — es una mejora, no un requisito.
     */
    private suspend fun maybeEnrichFromRemote(difficulty: SudokuDifficulty) {
        val alreadyTried = seedMutex.withLock {
            if (difficulty in remoteAttempted) true else { remoteAttempted += difficulty; false }
        }
        if (alreadyTried) return
        scope.launch {
            runCatching {
                val fresh = remote.fetchByDifficulty(difficulty, REMOTE_FETCH_LIMIT)
                val known = local.idsByDifficulty(difficulty)
                val novel = fresh.filter { it.id !in known }
                if (novel.isNotEmpty()) local.insertAll(novel)
            }
        }
    }

    /** Lee y parsea el CSV empaquetado (una vez, memoizado). `Res.readBytes` es `suspend`. */
    @OptIn(ExperimentalResourceApi::class)
    private suspend fun loadSeed(): Map<SudokuDifficulty, List<SudokuPuzzle>> {
        seedByDifficulty?.let { return it }
        return seedMutex.withLock {
            seedByDifficulty ?: run {
                val csv = Res.readBytes(SudokuBank.SEED_RESOURCE_PATH).decodeToString()
                SudokuBank.parse(csv).groupBy { it.difficulty }.also { seedByDifficulty = it }
            }
        }
    }

    private companion object {
        /** Cuántos puzzles pedir a Supabase por dificultad al enriquecer. Cubre el
         *  banco remoto completo actual (~40/dificultad) en una sola llamada. */
        const val REMOTE_FETCH_LIMIT = 100

        /**
         * Puzzle de emergencia (FACIL, solución única) para el caso "imposible" de
         * que ni la caché ni el seed aporten uno (recurso ausente/corrupto y base
         * vacía). Preferimos arrancar una partida jugable a lanzar una excepción en
         * pleno CTA de "Comenzar".
         */
        val EMERGENCY_PUZZLE = SudokuPuzzle(
            id = "emergency-facil",
            difficulty = SudokuDifficulty.FACIL,
            puzzle = "210806004906547031745000609608001070300200065000760390801005900560400803030018026",
            solution = "213896754986547231745123689698351472374289165152764398821635947569472813437918526",
        )
    }
}
