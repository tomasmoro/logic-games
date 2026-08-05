package com.kortexgames.app.game.neonline

import com.kortexgames.app.game.grid.GridPathBuilder
import com.kortexgames.app.game.grid.GridPosition
import com.kortexgames.app.game.grid.inStableOrder
import com.kortexgames.app.game.grid.orthogonalNeighbors
import kotlin.random.Random

/**
 * # Generador procedural de niveles de "Línea Neón"
 *
 * Cada nivel se **genera** (no se diseña a mano) a partir del número de nivel,
 * usándolo como semilla determinista: pedir el mismo [forNumber] siempre devuelve el
 * mismo tablero, igual que si viniera de un catálogo fijo, pero sin tener que dibujar
 * niveles uno a uno para cubrir una progresión infinita.
 *
 * ## El problema y por qué NO se generan los obstáculos "al revés"
 *
 * El juego pide un **camino hamiltoniano** sobre las celdas libres: pasar por todas
 * exactamente una vez. Decidirlo para un tablero cualquiera es **NP-completo**, así
 * que sembrar obstáculos al azar y comprobar después si hay solución es inviable.
 *
 * El atajo clásico es construir el nivel al revés: trazar un paseo aleatorio y
 * declarar obstáculo todo lo que el paseo no pisó. Es elegante —el nivel nace
 * resoluble sin verificar nada— pero produce **malos puzzles**, y conviene entender
 * por qué: la búsqueda del paseo usa la heurística de Warnsdorff, que barre el
 * tablero de forma compacta pegándose a los bordes, de modo que lo que sobra queda
 * casi siempre junto, **en un pegote a un lado**. Y un pegote no es un obstáculo: es
 * un tablero más pequeño con forma rara, que se sigue resolviendo serpenteando. La
 * dificultad no está en cuántas celdas se bloquean, sino en **dónde**.
 *
 * ## La construcción que sí produce puzzles (el "porqué" del algoritmo)
 *
 * Por eso aquí el orden se invierte: **primero se colocan los obstáculos, con las
 * propiedades que hacen difícil el nivel, y después se verifica buscando el camino**.
 *
 *  1. **Cuotas por color de ajedrez.** Todo movimiento en cruz cambia de color, así
 *     que un camino alterna colores y necesita los dos casi empatados; si al quitar
 *     los obstáculos un color queda con 2 celdas de ventaja, el nivel es imposible
 *     por mucho que parezca razonable (ver [GridPathBuilder.hasHamiltonianParity]).
 *     En vez de descartar esos tableros a posteriori, se reparte de antemano cuántos
 *     obstáculos van en cada color ([colorQuota]) para que la paridad cuadre **por
 *     construcción**. Sin esto, la mayoría de repartos nacerían condenados y la
 *     búsqueda se pasaría el rato explorando callejones.
 *  2. **Dispersión obligatoria.** Ningún obstáculo puede ser vecino ortogonal de
 *     otro ([SEPARATION]): nada de muros ni pegotes. Un bloque suelto en mitad del
 *     tablero obliga a rodearlo y a decidir por qué lado, que es exactamente el reto
 *     de lógica que se busca.
 *  3. **Sesgo hacia el interior.** Un obstáculo pegado al borde apenas estorba —solo
 *     recorta la silueta del tablero—, mientras que uno central parte el paso y
 *     fuerza detours. La selección favorece el interior ([INTERIOR_BIAS]) sin
 *     imponerlo, para que los tableros no salgan todos iguales.
 *  4. **Verificación real.** Con los obstáculos puestos se busca el camino
 *     ([GridPathBuilder.hamiltonianPathIn]). Si no aparece, ese reparto **se
 *     descarta entero** y se prueba otro.
 *
 * La garantía de solubilidad sigue siendo total, pero cambia de naturaleza: antes era
 * "por construcción" (el paseo era la solución), ahora es "por verificación" (solo se
 * publica un reparto del que se ha encontrado un camino completo). Un nivel que sale
 * de aquí tiene, como mínimo, la solución que se halló al generarlo.
 *
 * ## Qué pasa si un escalón se resiste
 *
 * Buscar repartos válidos a ciegas puede salir caro (los descartes son la parte lenta),
 * y esto se ejecuta al abrir un nivel, así que la latencia hay que acotarla. Se hace en
 * dos peldaños:
 *
 *  1. **Aflojar la densidad, no la calidad.** Tras [SCATTER_ATTEMPTS_PER_DENSITY]
 *     descartes se quita un bloque y se sigue buscando repartos igual de dispersos
 *     (hasta [MAX_DENSITY_RELAXATIONS] veces). Un bloque de menos no lo nota nadie; un
 *     pegote, sí. Por eso el nº de bloques de la tabla es el **objetivo**, no una
 *     promesa exacta.
 *  2. **Red de seguridad final.** Si ni así, se rellena con el método antiguo
 *     (complemento de un paseo, [buildWalkCandidate]), que no puede fallar. Produce
 *     tableros más blandos, y por eso es el último recurso: hay un test que comprueba
 *     que en los 60 primeros niveles no se llega a usar.
 *
 * ## Curva de dificultad
 *
 * Un **escalón** cada [LEVELS_PER_TIER] niveles; cada escalón fija el lado del
 * tablero y cuántos bloques lleva (ver [DIFFICULTY_TIERS]):
 *
 *  | Escalón | Niveles | Tablero | Bloques | Libres | Densidad |
 *  |--------:|:-------:|:-------:|:-------:|:------:|:--------:|
 *  | 0       | 1–2     | 4×4     | 2       | 14     | 13 %     |
 *  | 1       | 3–4     | 4×4     | 4       | 12     | 25 %     |
 *  | 2       | 5–6     | 5×5     | 4       | 21     | 16 %     |
 *  | 3       | 7–8     | 5×5     | 6       | 19     | 24 %     |
 *  | 4       | 9–10    | 6×6     | 6       | 30     | 17 %     |
 *  | 5       | 11–12   | 6×6     | 7       | 29     | 19 %     |
 *  | 6       | 13–14   | 7×7     | 6       | 43     | 12 %     |
 *  | 7       | 15–16   | 7×7     | 8       | 41     | 16 %     |
 *  | 8       | 17–18   | 8×8     | 7       | 57     | 11 %     |
 *  | 9       | 19+     | 8×8     | 8       | 56     | 13 %     |
 *
 * **Desde el nivel 1 hay bloques.** Un tablero despejado se resuelve serpenteando sin
 * pensar y enseña la mecánica equivocada ("da igual por dónde vaya"); con bloques desde
 * el principio el jugador adquiere el hábito de mirar el tablero antes de arrancar, que
 * es lo que necesita para el resto del juego. La curva sube alternando las dos palancas:
 * **agrandar el tablero** alarga el trazo (más que planificar) y **subir la densidad**
 * lo retuerce (más recovecos donde encerrarse).
 *
 * La densidad se mueve en la banda del **11–25 %** de celdas bloqueadas, que es donde
 * juega este género. No es una cifra tímida por casualidad: pasada esa banda, la
 * inmensa mayoría de repartos dispersos **deja de tener solución** (medido: en un 8×8
 * con 12 bloques solo el 2 % de los repartos admite camino), así que subir la densidad
 * no daría niveles más difíciles sino un generador que descarta casi todo lo que
 * produce. Lo que sí escala sin ese techo es DÓNDE caen los bloques, y de eso se ocupa
 * el ranking por [interiorRatio].
 *
 * Al tocar el techo (escalón 9) el tamaño se estabiliza ahí para siempre: la
 * progresión sigue siendo infinita, pero variando el reparto.
 *
 * ## Dificultad dentro de un escalón (sin cambiar el tablero)
 *
 * Con los MISMOS parámetros, un reparto que deja los bloques arrimados al borde es
 * mucho más blando que uno que los clava en el centro. Esa diferencia se mide gratis
 * con [interiorRatio], sin resolver nada. Por cada escalón se generan
 * [LEVELS_PER_TIER] repartos candidatos, se ordenan por esa dificultad y el nivel N
 * del escalón recibe el que le toca por posición: el primero es el más suave y el
 * último el más agresivo.
 */
object NeonLineLevels {

    /** Nº de niveles que dura cada escalón de dificultad antes de subir. */
    private const val LEVELS_PER_TIER = 2

    /**
     * Escalones de dificultad en orden (ver tabla en el KDoc de la clase). El último
     * se mantiene para siempre una vez alcanzado, garantizando progresión infinita.
     */
    private val DIFFICULTY_TIERS = listOf(
        DifficultyTier(gridSize = 4, obstacles = 2),
        DifficultyTier(gridSize = 4, obstacles = 4),
        DifficultyTier(gridSize = 5, obstacles = 4),
        DifficultyTier(gridSize = 5, obstacles = 6),
        DifficultyTier(gridSize = 6, obstacles = 6),
        DifficultyTier(gridSize = 6, obstacles = 7),
        DifficultyTier(gridSize = 7, obstacles = 6),
        DifficultyTier(gridSize = 7, obstacles = 8),
        DifficultyTier(gridSize = 8, obstacles = 7),
        DifficultyTier(gridSize = 8, obstacles = 8),
    )

    /**
     * Distancia mínima (en pasos ortogonales) entre dos bloques. Con `1` basta: dos
     * bloques en diagonal no forman muro —siguen dejando pasar la línea entre ellos—
     * y crean pasos estrechos interesantes, mientras que dos pegados de lado sí
     * empiezan a comportarse como una pared.
     */
    private const val SEPARATION = 1

    /**
     * Cuánto se favorece el interior del tablero al sortear posiciones de bloque
     * (0 = nada, 1 = casi siempre). Es un sesgo y no una regla: si fuera obligatorio,
     * todos los tableros de un escalón se parecerían entre sí.
     */
    private const val INTERIOR_BIAS = 0.55

    /** Repartos dispersos que se prueban a cada densidad antes de aflojar una unidad. */
    private const val SCATTER_ATTEMPTS_PER_DENSITY = 60

    /**
     * Cuántos bloques como máximo se puede rebajar un escalón si su densidad nominal no
     * da repartos válidos (ver [buildPoolUncached]). Tres es de sobra: la proporción de
     * repartos con solución sube muy deprisa al quitar bloques —en un 8×8, del 14 % con
     * 8 bloques al 40 % con 6—, así que el primer o segundo escalón de alivio resuelve
     * prácticamente siempre.
     */
    private const val MAX_DENSITY_RELAXATIONS = 3

    /**
     * Perfil de la búsqueda que **verifica** un reparto: deliberadamente **impaciente**
     * —dos arranques y muy poco presupuesto— en vez de exhaustiva.
     *
     * Parece al revés de lo razonable, pero la asimetría del problema lo justifica:
     *
     *  - **Rendirse antes de tiempo no cuesta nada.** Si se descarta un reparto que sí
     *    tenía solución, se prueba otra semilla y listo; el jugador nunca lo nota. Un
     *    falso negativo aquí es gratis.
     *  - **Insistir sí cuesta.** Los repartos que fallan es que **no tienen camino**, y
     *    demostrarlo obliga a explorar el árbol entero. Medido sobre 300 semillas de un
     *    8×8, ampliar la búsqueda de 24 a 120 arranques apenas subió los repartos
     *    válidos (12 → 25): casi todo ese esfuerzo extra se gastó en confirmar
     *    imposibles.
     *  - **Los buenos se encuentran enseguida.** Con Warnsdorff y el arranque por celdas
     *    de menor grado, un reparto con solución la suelta en los primeros pasos.
     *
     * Recortar este perfil de (4 arranques, 40 pasos/celda) a (2, 10) bajó la generación
     * del peor nivel de 375 ms a 137 ms **sin** que ningún nivel tuviera que aflojar su
     * densidad ni caer en la red de seguridad.
     */
    private const val VERIFY_ATTEMPTS = 2

    /** Presupuesto de nodos por celda de la búsqueda de verificación (ver [VERIFY_ATTEMPTS]). */
    private const val VERIFY_BUDGET_PER_CELL = 10

    /**
     * Tope de decisiones del backtracking que coloca los bloques. Acota el peor caso
     * (colocar N bloques con separación y sin estrangular es un problema combinatorio)
     * sin que un escalón exigente pueda colgar la carga de un nivel: si se agota, ese
     * reparto se descarta y se prueba otra semilla, que es más barato que insistir.
     */
    private const val PLACEMENT_BUDGET = 20_000

    /**
     * Nivel [number] (1-based), generado de forma determinista.
     *
     * "Siguiente nivel" nunca revienta: la progresión es infinita (tablero y nº de
     * bloques se estabilizan al llegar al techo, pero el reparto sigue variando) y la
     * puntuación sigue reflejando el nivel real.
     */
    fun forNumber(number: Int): NeonLineLevel {
        val n = number.coerceAtLeast(1)
        return NeonLineLevel(
            number = n,
            gridSize = tierForLevel(n).gridSize,
            obstacles = chosenCandidate(n).obstacles,
        )
    }

    /**
     * Candidato que le toca al nivel [number] tras rankear el pool de su escalón por
     * dificultad (ver el KDoc de la clase). `internal` —no `private`— solo para que
     * los tests puedan comprobar el orden de dificultad ([Candidate.hardnessScore]) y
     * si se usó la red de seguridad ([Candidate.scattered]) sin duplicar esta lógica.
     */
    internal fun chosenCandidate(number: Int): Candidate {
        val n = number.coerceAtLeast(1)
        val tier = tierForLevel(n)
        val tierIndex = rawTierIndex(n) // sin topar: sigue variando el pool tras el techo
        val position = (n - 1) % LEVELS_PER_TIER

        val pool = buildPool(tier, tierIndex)
        return pool.sortedBy { it.hardnessScore }[position.coerceIn(0, pool.lastIndex)]
    }

    /**
     * Un reparto candidato: dónde van los bloques, su dificultad relativa y si salió
     * del método disperso o de la red de seguridad.
     */
    internal data class Candidate(
        val obstacles: Set<GridPosition>,
        val hardnessScore: Double,
        val scattered: Boolean,
    )

    /**
     * Reúne [LEVELS_PER_TIER] candidatos válidos para el escalón, probando repartos
     * dispersos con semillas sucesivas y completando con la red de seguridad si hiciera
     * falta (ver el KDoc de la clase).
     */
    private fun buildPool(tier: DifficultyTier, tierIndex: Int): List<Candidate> {
        cachedPool?.let { (index, pool) -> if (index == tierIndex) return pool }
        return buildPoolUncached(tier, tierIndex).also { cachedPool = tierIndex to it }
    }

    /**
     * Memoria del último escalón generado. Armar un pool puede costar cientos de
     * repartos descartados (ver [SCATTER_ATTEMPTS]), y se pide **repetidamente** con el
     * mismo escalón: los dos niveles de un tramo comparten pool, y "Reintentar" y
     * "Siguiente nivel" lo vuelven a pedir. Con una sola entrada basta —el jugador
     * avanza por escalones contiguos— y el resultado es idéntico al recalculado, porque
     * todo el generador es determinista: la caché solo ahorra trabajo, nunca cambia un
     * tablero. Por eso tampoco necesita sincronización: en el peor caso dos hilos
     * calculan lo mismo.
     */
    private var cachedPool: Pair<Int, List<Candidate>>? = null

    private fun buildPoolUncached(tier: DifficultyTier, tierIndex: Int): List<Candidate> {
        val pool = ArrayList<Candidate>(LEVELS_PER_TIER)

        // Si a la densidad nominal cuesta encontrar repartos, se AFLOJA LA DENSIDAD, no
        // la calidad: un bloque menos, y a seguir buscando repartos dispersos. Es la
        // degradación que menos se nota —nadie cuenta los bloques, pero sí se ve un
        // pegote— y además acota la latencia: sin ella, un escalón con mala suerte podía
        // encadenar cientos de descartes caros y tardar medio segundo en cargar.
        for (relaxation in 0..MAX_DENSITY_RELAXATIONS) {
            val relaxed = tier.copy(
                obstacles = (tier.obstacles - relaxation).coerceAtLeast(1),
            )
            var attempt = 0
            while (pool.size < LEVELS_PER_TIER && attempt < SCATTER_ATTEMPTS_PER_DENSITY) {
                val seed = (tierIndex.toLong() * 1_000_003L + relaxation * 7_919L) * 97L + attempt
                buildScatteredCandidate(relaxed, seed)?.let(pool::add)
                attempt++
            }
            if (pool.size == LEVELS_PER_TIER) return pool
        }

        // Red de seguridad final: el complemento de un paseo nunca falla (ver KDoc).
        var fallback = 0
        while (pool.size < LEVELS_PER_TIER) {
            pool.add(buildWalkCandidate(tier, tierIndex.toLong() * 7_919L + fallback))
            fallback++
        }
        return pool
    }

    /**
     * Intenta un reparto disperso para la [seed] dada: coloca los bloques y verifica
     * que el tablero resultante tenga camino. Devuelve `null` si el reparto no admite
     * solución (o no se pudieron colocar todos los bloques con la separación exigida),
     * que es la señal para probar otra semilla.
     */
    private fun buildScatteredCandidate(tier: DifficultyTier, seed: Long): Candidate? {
        val random = Random(seed)
        val obstacles = placeObstacles(tier, random) ?: return null

        val playable = buildSet {
            for (row in 0 until tier.gridSize) {
                for (col in 0 until tier.gridSize) {
                    val cell = GridPosition(row, col)
                    if (cell !in obstacles) add(cell)
                }
            }
        }
        // La verificación: sin camino, no hay nivel. Aquí es donde este generador
        // paga el precio de colocar los obstáculos primero, y el motivo de que las
        // cuotas por color y las podas de hamiltonianPathIn importen tanto.
        GridPathBuilder.hamiltonianPathIn(
            region = playable,
            seed = seed,
            attempts = VERIFY_ATTEMPTS,
            stepBudgetPerCell = VERIFY_BUDGET_PER_CELL,
        ) ?: return null

        return Candidate(
            obstacles = obstacles,
            hardnessScore = interiorRatio(obstacles, tier.gridSize),
            scattered = true,
        )
    }

    /**
     * Sortea posiciones de bloque respetando las cuotas por color de ajedrez y la
     * separación mínima. Devuelve `null` si no logra colocarlos todos —lo normal
     * cuando el escalón pide muchos bloques en un tablero pequeño—, sin insistir: es
     * más barato probar otra semilla que forzar una colocación.
     */
    private fun placeObstacles(tier: DifficultyTier, random: Random): Set<GridPosition>? {
        val gridSize = tier.gridSize
        val (evenQuota, oddQuota) = colorQuota(gridSize, tier.obstacles, random)
        val candidates = orderedCandidates(gridSize, random)

        val chosen = HashSet<GridPosition>(tier.obstacles)
        var even = 0
        var odd = 0
        var budget = PLACEMENT_BUDGET

        // Backtracking sobre la lista de candidatas: por cada una, o se bloquea o se
        // descarta. Un "primero que quepa" sin vuelta atrás no sirve aquí — cada bloque
        // inhabilita a sus cuatro vecinas, así que las primeras elecciones estrechan
        // brutalmente el resto y el reparto se acorrala sin llegar a la cuota. Medido:
        // con reparto greedy, un 8×8 con 9 bloques solo salía en el 5% de las semillas.
        fun place(index: Int): Boolean {
            if (even == evenQuota && odd == oddQuota) return true
            if (index >= candidates.size) return false
            if (--budget <= 0) return false

            val cell = candidates[index]
            val isEven = (cell.row + cell.col) % 2 == 0
            val needed = if (isEven) evenQuota - even else oddQuota - odd
            val fits = needed > 0 &&
                chosen.none { it.distanceTo(cell) <= SEPARATION } &&
                !stranglesANeighbour(cell, chosen, gridSize)

            if (fits) {
                chosen.add(cell)
                if (isEven) even++ else odd++
                if (place(index + 1)) return true
                chosen.remove(cell)
                if (isEven) even-- else odd--
            }
            return place(index + 1)
        }

        return if (place(0)) chosen else null
    }

    /**
     * Todas las celdas del tablero en orden aleatorio con sesgo al interior: es el
     * orden en que [placeObstacles] las va probando, y por tanto lo que determina el
     * aspecto del reparto.
     *
     * La clave de ordenación se calcula UNA vez por celda. Generarla dentro del
     * comparador (`sortedBy { random.nextDouble() … }`) sería un bug real: el algoritmo
     * de ordenación consulta la misma celda varias veces, obtendría un valor distinto
     * cada vez y la comparación dejaría de ser consistente — la JVM lo detecta y lanza.
     *
     * Se parte de [inStableOrder] para que el resultado sea idéntico en Android y en
     * iOS con la misma semilla.
     */
    private fun orderedCandidates(gridSize: Int, random: Random): List<GridPosition> =
        buildSet {
            for (row in 0 until gridSize) {
                for (col in 0 until gridSize) add(GridPosition(row, col))
            }
        }.inStableOrder()
            .map { cell ->
                val bias = if (cell.isInterior(gridSize)) INTERIOR_BIAS else 0.0
                cell to (random.nextDouble() - bias)
            }
            .sortedBy { it.second }
            .map { it.first }

    /**
     * Cuántos bloques van en cada color del tablero de ajedrez para que la paridad de
     * la zona jugable permita un camino (ver [GridPathBuilder.hasHamiltonianParity]).
     *
     * Si el tablero tiene `C0` celdas de un color y `C1` del otro, y se quitan `k0` y
     * `k1`, hace falta `|(C0 - k0) - (C1 - k1)| ≤ 1`. Con `k0 + k1 = k` fijo, eso deja
     * a lo sumo tres valores posibles para la diferencia `k0 - k1`, y solo los que
     * comparten paridad con `k` son alcanzables; entre esos se elige al azar para que
     * el reparto no salga siempre idéntico.
     *
     * @return par (cuota del color par, cuota del color impar).
     */
    private fun colorQuota(gridSize: Int, obstacles: Int, random: Random): Pair<Int, Int> {
        val total = gridSize * gridSize
        val evenCells = (total + 1) / 2 // (row + col) par: el color de la esquina (0,0)
        val oddCells = total / 2
        val boardDelta = evenCells - oddCells // 0 en tableros pares, 1 en impares

        // Diferencias (k0 - k1) que dejan la zona jugable equilibrada y son
        // alcanzables con `obstacles` bloques (misma paridad que el total).
        val viable = (boardDelta - 1..boardDelta + 1)
            .filter { (it + obstacles) % 2 == 0 }
            .filter { delta ->
                val even = (obstacles + delta) / 2
                even in 0..obstacles && even <= evenCells && obstacles - even <= oddCells
            }
        // `viable` nunca está vacío: entre tres enteros consecutivos siempre hay uno
        // de cada paridad, y los cupos por color sobran para los tableros del juego.
        val delta = viable.random(random)
        val even = (obstacles + delta) / 2
        return even to (obstacles - even)
    }

    /**
     * ¿Poner un bloque en [cell] dejaría a alguna celda libre con menos de dos
     * salidas? Es la comprobación que hace viable colocar los obstáculos primero.
     *
     * Una celda libre con una sola salida es un callejón, y un camino solo admite dos
     * (sus extremos); con tres, el nivel es irresoluble y hay que tirar el reparto
     * entero. Ese era, con diferencia, el motivo más común de descarte: sortear
     * posiciones sin mirar el vecindario estrangula celdas constantemente y la
     * búsqueda se comía el presupuesto rechazando tableros condenados.
     *
     * Comprobarlo antes de colocar cuesta O(1) —solo las cuatro vecinas de [cell]
     * pierden una salida al bloquearla— y sube muchísimo la proporción de repartos
     * válidos. Se exige el mínimo estricto de **dos** salidas para todas: aceptar
     * hasta dos callejones sería legal, pero apurar ese margen vuelve a disparar los
     * descartes por un puñado de tableros que tampoco aportan nada especial.
     */
    private fun stranglesANeighbour(
        cell: GridPosition,
        placed: Set<GridPosition>,
        gridSize: Int,
    ): Boolean {
        val blocked = placed + cell
        return cell.orthogonalNeighbors(gridSize)
            .filter { it !in blocked }
            .any { neighbour ->
                neighbour.orthogonalNeighbors(gridSize).count { it !in blocked } < 2
            }
    }

    /** Celdas del tablero cuyo color de ajedrez es [color] (0 = par, 1 = impar). */
    private fun cellsOfColor(gridSize: Int, color: Int): List<GridPosition> = buildSet {
        for (row in 0 until gridSize) {
            for (col in 0 until gridSize) {
                if ((row + col) % 2 == color) add(GridPosition(row, col))
            }
        }
    }.inStableOrder()

    /** Distancia de Manhattan entre dos celdas (pasos ortogonales). */
    private fun GridPosition.distanceTo(other: GridPosition): Int =
        kotlin.math.abs(row - other.row) + kotlin.math.abs(col - other.col)

    /** ¿La celda queda separada de los cuatro bordes del tablero? */
    private fun GridPosition.isInterior(gridSize: Int): Boolean =
        row in 1 until gridSize - 1 && col in 1 until gridSize - 1

    /**
     * Fracción de bloques que caen en el interior del tablero (0.0..1.0): la señal de
     * dificultad que no depende de agrandar el tablero ni de meter más bloques.
     *
     * Un bloque pegado al borde apenas restringe —la línea lo bordea sin desviarse de
     * su recorrido natural— mientras que uno interior corta el paso y obliga a decidir
     * por qué lado rodearlo, con el riesgo de dejarse celdas detrás. Se mide sobre la
     * geometría ya generada, sin resolver el nivel: coste O(bloques).
     *
     * Sustituye a la densidad de bifurcaciones que se usaba antes, que resultó ser una
     * mala señal: un tablero despejado tiene bifurcaciones por todas partes y aun así
     * se resuelve serpenteando sin pensar.
     */
    private fun interiorRatio(obstacles: Set<GridPosition>, gridSize: Int): Double {
        if (obstacles.isEmpty()) return 0.0
        return obstacles.count { it.isInterior(gridSize) }.toDouble() / obstacles.size
    }

    /**
     * Red de seguridad: reparto por el método antiguo —trazar un paseo de tantas
     * celdas como libres deba haber y bloquear el resto—, que no puede fallar porque
     * el propio paseo es la solución. Produce tableros más blandos (los bloques
     * tienden a agruparse, ver el KDoc de la clase), así que solo se usa si el método
     * disperso no logra llenar el cupo del escalón.
     */
    private fun buildWalkCandidate(tier: DifficultyTier, seed: Long): Candidate {
        val gridSize = tier.gridSize
        val playable = GridPathBuilder.simplePath(
            gridSize = gridSize,
            length = tier.playableCells(),
            seed = seed,
        ).toSet()

        val obstacles = buildSet {
            for (row in 0 until gridSize) {
                for (col in 0 until gridSize) {
                    val cell = GridPosition(row, col)
                    if (cell !in playable) add(cell)
                }
            }
        }
        return Candidate(
            obstacles = obstacles,
            hardnessScore = interiorRatio(obstacles, gridSize),
            scattered = false,
        )
    }

    /** Índice de escalón SIN topar al máximo (a diferencia de [tierForLevel]); se usa como semilla. */
    private fun rawTierIndex(number: Int): Int = (number - 1) / LEVELS_PER_TIER

    /**
     * Escalón de dificultad del nivel [number]: sube uno cada [LEVELS_PER_TIER]
     * niveles y se estanca en el último tramo definido (progresión infinita).
     */
    private fun tierForLevel(number: Int): DifficultyTier =
        DIFFICULTY_TIERS[rawTierIndex(number).coerceIn(0, DIFFICULTY_TIERS.lastIndex)]

    /**
     * Un escalón de la curva: lado del tablero y cuántos bloques lleva.
     *
     * @property gridSize lado del tablero cuadrado ([MIN_GRID_SIZE]..[MAX_GRID_SIZE]).
     * @property obstacles nº de celdas bloqueadas; el resto es zona jugable.
     */
    private data class DifficultyTier(val gridSize: Int, val obstacles: Int) {

        init {
            require(gridSize in MIN_GRID_SIZE..MAX_GRID_SIZE) {
                "Escalón inválido: gridSize $gridSize fuera de $MIN_GRID_SIZE..$MAX_GRID_SIZE"
            }
            require(obstacles >= 1) {
                "Escalón inválido: todo nivel lleva al menos un bloque (ver KDoc de NeonLineLevels)"
            }
            require(gridSize * gridSize - obstacles >= MIN_PLAYABLE_CELLS) {
                "Escalón inválido: $obstacles bloques no dejan sitio para trazar en ${gridSize}x$gridSize"
            }
        }

        /** Nº de celdas libres del escalón. */
        fun playableCells(): Int = gridSize * gridSize - obstacles
    }
}
