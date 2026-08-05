package com.kortexgames.app.game.neoncircuit

import com.kortexgames.app.game.grid.GridPathBuilder
import com.kortexgames.app.game.grid.GridPosition
import kotlin.math.abs
import kotlin.random.Random

/**
 * # Generador procedural de niveles de "Neon Circuit Flow"
 *
 * Cada nivel se **genera** (no se diseña a mano) a partir del número de nivel,
 * usándolo como semilla determinista: pedir el mismo [forNumber] siempre
 * devuelve el mismo tablero, igual que si viniera de un catálogo fijo, pero sin
 * tener que dibujar niveles uno a uno para cubrir una progresión infinita.
 *
 * ## Curva de dificultad
 *
 * Un **escalón** de dificultad cada [LEVELS_PER_GRID_SIZE] niveles; cada escalón
 * fija a la vez el lado del tablero y el nº de canales (ver [DIFFICULTY_TIERS]):
 *
 *  | Escalón | Niveles | Tablero | Canales |
 *  |--------:|:-------:|:-------:|:-------:|
 *  | 0       | 1–2     | 5×5     | 4       |
 *  | 1       | 3–4     | 6×6     | 4       |
 *  | 2       | 5–6     | 7×7     | 5       |
 *  | 3       | 7–8     | 8×8     | 6       |
 *  | 4       | 9–10    | 9×9     | 7       |
 *  | 5       | 11–12   | 9×9     | 8       |
 *
 * El tablero crece de lado en lado (nunca salta al máximo ni decrece) y los
 * canales suben de forma escalonada, no uno por nivel (saturaría los tableros
 * pequeños). Al tocar el techo (9×9 con 8 canales, escalón 5) el TAMAÑO se
 * estabiliza ahí para siempre — no hay más tablero que agrandar ni más canales
 * que meter sin desbordar los [WireColor] disponibles.
 *
 * ## Dificultad dentro de un escalón: la SEPARACIÓN de los nodos
 *
 * Fijar el tamaño no fija la dificultad. Lo que de verdad decide si un nivel se
 * resuelve de un vistazo es **cuánto separa a cada par de nodos** ([separation]):
 *
 *  - **Nodos cerca** → el cable de ese color se resuelve en una esquina, sin tocar a
 *    nadie. El color deja de participar en el puzzle y el tablero se descompone en
 *    zonas obvias, cada una con su dueño.
 *  - **Nodos lejos** → el cable tiene que cruzar el tablero y estorbar a los demás.
 *    Esa interdependencia entre colores es justo lo que hace difícil un Flow: obliga a
 *    resolver todos los cables a la vez, no uno detrás de otro.
 *
 * Por eso el generador **busca activamente** la máxima separación, en los dos ejes que
 * la determinan —qué camino hamiltoniano y por dónde se corta— y no solo entre unas
 * pocas semillas (ver [buildCandidate] y [bestSplit]). No son ejes intercambiables: un
 * camino muy enroscado no da pares lejanos por bien que se corte.
 *
 * > **Corrección de una métrica anterior.** Antes se rankeaba por "cuánto rodea el
 * > cable respecto a la línea recta entre sus nodos" (pasos ÷ distancia). Como la
 * > distancia está en el denominador, esa fórmula daba su puntuación MÁXIMA justo a los
 * > pares con los nodos pegados, y el segundo nivel de cada escalón —el que debía ser
 * > el difícil— recibía sistemáticamente el reparto más fácil. Medido antes del cambio:
 * > la separación mínima era de **1 celda** (nodos vecinos) en la mitad de los niveles,
 * > con una media de 4,3 sobre un tope de 16 en el tablero de 9×9. Después: mínimo 5–7
 * > y media ~8.
 *
 * Por cada escalón se generan [LEVELS_PER_GRID_SIZE] repartos candidatos (mismo
 * tablero, distinta semilla), se ordenan por separación media y el nivel N dentro del
 * escalón recibe el que le toca por posición: el primero es el reparto más recogido y
 * el último el más extendido. Así el nivel 1 y el 2 pueden compartir tablero y aun así
 * el 2 ser más difícil, en vez de que la única palanca sea agrandar el tablero. La
 * semilla de cada escalón NO se topa al llegar al escalón 5 (a diferencia del tamaño de
 * tablero, que sí): cada nuevo par de niveles post-techo sigue sorteando un reparto
 * fresco, así la dificultad post-techo no se repite siempre igual, solo dentro del
 * mismo rango acotado.
 *
 * ## Cómo se garantiza que el nivel sea resoluble (el "porqué" del algoritmo)
 *
 * Generar pares de nodos al azar y esperar que exista una solución sin cruces
 * es frágil (hay que resolver el nivel para comprobarlo, y muchas
 * combinaciones no tienen solución). En su lugar se construye el nivel **al
 * revés, desde una solución garantizada**:
 *
 *  1. Se traza un **camino hamiltoniano** sobre el tablero: una ruta que visita
 *     cada celda exactamente una vez ([GridPathBuilder.hamiltonianPath]). Al
 *     cubrir el 100% de las celdas, cualquier sub-tramo contiguo de ese camino es,
 *     por construcción, un cable válido sin cruces.
 *  2. Se **corta** ese camino en tantos tramos contiguos como canales necesite
 *     el nivel ([splitIntoSegments]). Cada tramo es la solución de un color, y de
 *     entre muchos cortes posibles se elige el que más separa los nodos ([bestSplit]).
 *  3. Los **nodos** del nivel son solo los dos extremos de cada tramo; el
 *     trazo intermedio es justo lo que el jugador debe redescubrir jugando.
 *
 * Como el camino hamiltoniano cubre el tablero entero, la solución de
 * referencia también deja el tablero 100% lleno: todo nivel generado admite
 * tanto la victoria mínima (pares conectados) como la bonus (tablero lleno).
 *
 * La búsqueda del camino (Warnsdorff + backtracking, con patrón serpenteante como
 * red de seguridad) **no vive aquí**: es [GridPathBuilder], en `game.grid`, porque
 * la misma primitiva la necesita "Línea Neón"
 * ([com.kortexgames.app.game.neonline.NeonLineLevels]) —que en vez de trocear el
 * camino usa su complemento como obstáculos— y la necesitará cualquier juego de
 * trazado futuro. Este generador solo aporta lo que es SUYO: la curva de
 * dificultad, el troceado en canales y el reparto de colores.
 */
object NeonCircuitLevels {

    /** Nº de niveles que dura cada escalón de dificultad antes de subir. */
    private const val LEVELS_PER_GRID_SIZE = 2

    /**
     * Cortes distintos del mismo camino que se prueban buscando el que más separa los
     * nodos ([bestSplit]). Es alto a propósito: cada intento es una operación trivial
     * (repartir longitudes y medir extremos) comparada con generar el camino
     * hamiltoniano, que ya está hecho y se reutiliza en todos ellos.
     */
    private const val SPLIT_ATTEMPTS = 400

    /** Caminos hamiltonianos distintos que se prueban por reparto (ver [buildCandidate]). */
    private const val PATH_ATTEMPTS = 6

    /**
     * Escalones de dificultad en orden (ver tabla en el KDoc de la clase): cada
     * uno fija (lado de tablero, nº de canales). El último se mantiene para
     * siempre una vez alcanzado, garantizando una progresión infinita.
     */
    private val DIFFICULTY_TIERS = listOf(
        DifficultyTier(gridSize = 5, pairCount = 4),
        DifficultyTier(gridSize = 6, pairCount = 4),
        DifficultyTier(gridSize = 7, pairCount = 5),
        DifficultyTier(gridSize = 8, pairCount = 6),
        DifficultyTier(gridSize = 9, pairCount = 7),
        DifficultyTier(gridSize = 9, pairCount = 8),
    )

    /**
     * Nivel [number] (1-based), generado de forma determinista.
     *
     * "Siguiente nivel" nunca revienta: la progresión es infinita (el tamaño de
     * tablero y el nº de canales se estabilizan al llegar al máximo, pero la
     * dificultad del reparto sigue variando dentro de cada escalón, ver el KDoc
     * de la clase), y la puntuación sigue reflejando el nº de nivel real jugado.
     */
    fun forNumber(number: Int): CircuitLevel {
        val n = number.coerceAtLeast(1)
        val chosen = chosenCandidate(n)
        return CircuitLevel(number = n, gridSize = tierForLevel(n).gridSize, nodes = chosen.nodes)
    }

    /**
     * Candidato que le toca al nivel [number] tras rankear el pool de su escalón por
     * dificultad (ver el KDoc de la clase). `internal` —no `private`— solo para que los
     * tests puedan comprobar el orden de dificultad ([Candidate.hardnessScore]) sin
     * duplicar esta lógica de selección.
     */
    internal fun chosenCandidate(number: Int): Candidate {
        val n = number.coerceAtLeast(1)
        val tier = tierForLevel(n)
        val tierIndex = rawTierIndex(n) // sin topar: sigue variando el pool tras el techo
        val position = (n - 1) % LEVELS_PER_GRID_SIZE

        val pool = (0 until LEVELS_PER_GRID_SIZE).map { candidateIndex ->
            val seed = tierIndex.toLong() * 1_000_003L + candidateIndex
            buildCandidate(tier.gridSize, tier.pairCount, seed)
        }
        return pool.sortedBy { it.hardnessScore }[position.coerceIn(0, pool.lastIndex)]
    }

    /** Un reparto candidato con su puntaje de dificultad relativa (ver [separation]). */
    internal data class Candidate(val nodes: List<Node>, val hardnessScore: Double)

    /**
     * Genera un único reparto (tablero, canales) para la [seed] dada.
     *
     * Se prueban [PATH_ATTEMPTS] caminos hamiltonianos distintos y, de cada uno, el
     * mejor corte ([bestSplit]); gana el reparto que más separa los nodos. Buscar en
     * los dos ejes (qué camino y por dónde cortarlo) importa porque no son
     * intercambiables: hay caminos que se enroscan sobre sí mismos y por mucho que se
     * corten bien nunca dan pares lejanos.
     */
    private fun buildCandidate(gridSize: Int, pairCount: Int, seed: Long): Candidate {
        var best: List<List<GridPosition>>? = null
        var bestMin = -1
        var bestMean = -1.0

        repeat(PATH_ATTEMPTS) { attempt ->
            val pathSeed = seed * 31L + attempt
            val path = GridPathBuilder.hamiltonianPath(gridSize, pathSeed)
            val segments = bestSplit(path, pairCount, pathSeed)
            val separations = segments.map(::separation)
            val min = separations.min()
            val mean = separations.average()
            if (min > bestMin || (min == bestMin && mean > bestMean)) {
                best = segments
                bestMin = min
                bestMean = mean
            }
        }

        val segments = best!!
        val colors = WireColor.entries.take(pairCount).shuffled(Random(seed * 31 + 7))
        val nodes = segments.flatMapIndexed { index, segment ->
            val color = colors[index]
            listOf(Node(color, segment.first()), Node(color, segment.last()))
        }
        return Candidate(nodes, hardnessScore = bestMean)
    }

    /**
     * Elige, entre [SPLIT_ATTEMPTS] cortes al azar del mismo camino, el que deja los
     * nodos **más separados** (ver el porqué en el KDoc de la clase).
     *
     * Se ordena primero por la separación MÍNIMA y solo se desempata por la media: lo
     * que arruina un nivel no es que la media sea mediocre, es que **un** canal tenga
     * sus dos nodos pegados. Ese canal se resuelve de un vistazo y deja de participar
     * en el puzzle, así que basta uno para aflojar el tablero entero.
     *
     * Cortar es baratísimo (repartir longitudes y mirar los extremos), así que probar
     * cientos de cortes cuesta menos que buscar otro camino hamiltoniano: se aprovecha
     * el mismo camino, mucho más caro de conseguir.
     */
    private fun bestSplit(path: List<GridPosition>, count: Int, seed: Long): List<List<GridPosition>> {
        var best: List<List<GridPosition>>? = null
        var bestMin = -1
        var bestMean = -1.0

        repeat(SPLIT_ATTEMPTS) { attempt ->
            val segments = splitIntoSegments(path, count, seed * 1_000_003L + attempt)
            val separations = segments.map(::separation)
            val min = separations.min()
            val mean = separations.average()
            if (min > bestMin || (min == bestMin && mean > bestMean)) {
                best = segments
                bestMin = min
                bestMean = mean
            }
        }
        return best ?: splitIntoSegments(path, count, seed)
    }

    /**
     * Distancia (Manhattan) entre los dos nodos de un canal: cuántas celdas separan al
     * par que el jugador tiene que unir. Es la señal de dificultad que no depende de
     * agrandar el tablero (ver el KDoc de la clase).
     */
    private fun separation(segment: List<GridPosition>): Int {
        val start = segment.first()
        val end = segment.last()
        return abs(start.row - end.row) + abs(start.col - end.col)
    }

    /** Índice de escalón SIN topar al máximo (a diferencia de [tierForLevel]); ver su uso como semilla. */
    private fun rawTierIndex(number: Int): Int = (number - 1) / LEVELS_PER_GRID_SIZE

    /**
     * Escalón de dificultad ([gridSize], [pairCount]) del nivel [number]: sube uno
     * cada [LEVELS_PER_GRID_SIZE] niveles y se estanca en el último tramo definido
     * (progresión infinita).
     */
    private fun tierForLevel(number: Int): DifficultyTier =
        DIFFICULTY_TIERS[rawTierIndex(number).coerceIn(0, DIFFICULTY_TIERS.lastIndex)]

    /**
     * Un escalón de la curva de dificultad: lado del tablero y nº de canales que
     * comparten los niveles de ese tramo.
     *
     * @property gridSize lado del tablero cuadrado ([MIN_GRID_SIZE]..[MAX_GRID_SIZE]).
     * @property pairCount nº de pares de nodos (canales) del nivel; ≤ nº de [WireColor].
     */
    private data class DifficultyTier(val gridSize: Int, val pairCount: Int)

    /**
     * Corta [path] en [count] tramos contiguos (de al menos 2 celdas cada uno,
     * para que un tramo siempre tenga dos extremos distintos), repartiendo el
     * resto de celdas al azar entre los tramos según [seed].
     */
    private fun splitIntoSegments(
        path: List<GridPosition>,
        count: Int,
        seed: Long,
    ): List<List<GridPosition>> {
        val random = Random(seed * 7 + 13)
        val lengths = IntArray(count) { 2 }
        var remaining = path.size - 2 * count
        while (remaining > 0) {
            lengths[random.nextInt(count)]++
            remaining--
        }
        lengths.shuffle(random)

        val segments = ArrayList<List<GridPosition>>(count)
        var offset = 0
        for (length in lengths) {
            segments.add(path.subList(offset, offset + length))
            offset += length
        }
        return segments
    }
}
