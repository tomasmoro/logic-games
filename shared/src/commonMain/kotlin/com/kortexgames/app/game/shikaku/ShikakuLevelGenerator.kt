package com.kortexgames.app.game.shikaku

import kotlin.random.Random

/**
 * Parámetros de generación de un nivel.
 *
 * Está separado del número de nivel para que la curva de dificultad ([ShikakuLevelGenerator.configFor])
 * sea una tabla que se puede retocar sin tocar el algoritmo, y para que los tests puedan pedir
 * configuraciones concretas (p. ej. forzar una figura).
 *
 * @property columns ancho de la caja contenedora.
 * @property rows alto de la caja contenedora.
 * @property shapes figuras admitidas; el generador elige una al azar por intento.
 * @property maxArea área máxima de un rectángulo, es decir, la pista más alta posible. Es la
 *   palanca principal de dificultad: un 5 solo admite 1×5, mientras que un 12 admite 1×12, 2×6 y
 *   3×4 en ambas orientaciones, así que cada pista grande multiplica las hipótesis a descartar.
 * @property stopChance probabilidad de dejar de cortar una región que ya cabe en la MITAD de
 *   [maxArea]. Alta ⇒ piezas medianas; baja ⇒ el tablero se desmenuza en doses y treses.
 * @property bigStopChance la misma probabilidad para regiones por encima de la mitad de [maxArea].
 *   Va aparte porque sin ella los tableros difíciles degeneran: una cruz de 12×12 puede quedar en
 *   cuatro brazos de 24 celdas, que es un nivel de cuatro pistas. Manteniéndola baja, los números
 *   grandes aparecen como excepción (son los que más hipótesis abren) y no como norma.
 */
data class ShikakuLevelConfig(
    val columns: Int,
    val rows: Int,
    val shapes: List<ShikakuMaskShape>,
    val maxArea: Int,
    val stopChance: Float,
    val bigStopChance: Float,
) {
    init {
        require(columns >= 2 && rows >= 2) { "Tablero demasiado pequeño: ${columns}x$rows" }
        require(shapes.isNotEmpty()) { "Hace falta al menos una figura" }
        // Con tope < 4 una región sólida de 2×2 no podría ni quedarse entera ni partirse sin
        // generar piezas de área 1, que son justo lo que el generador intenta evitar.
        require(maxArea >= MIN_SPLITTABLE_AREA) { "maxArea debe ser ≥ $MIN_SPLITTABLE_AREA" }
    }
}

/**
 * # ShikakuLevelGenerator — generación procedural por ingeniería inversa
 *
 * Resolver un Shikaku es **NP-completo** (Takenaga et al., 2013), así que un nivel no se obtiene
 * "sorteando números y comprobando si sale algo": se construye al revés. Primero se fabrica la
 * solución —una partición de la figura en rectángulos— y después se esconde, dejando una pista
 * por rectángulo con su área. El nivel es resoluble por construcción.
 *
 * ## 1. El problema de partir una máscara que no es un rectángulo
 *
 * El BSP clásico (Binary Space Partitioning) corta un rectángulo en dos rectángulos, y cada mitad
 * vuelve a ser un problema idéntico. Sobre una figura en L, una cruz o una rosca eso deja de ser
 * cierto: la región jugable es un **polígono rectilíneo** (posiblemente con agujeros o partido en
 * islas) y un corte recto puede dejar a cada lado algo que tampoco es un rectángulo.
 *
 * La adaptación consiste en no cortar la figura sino su **caja contenedora**, y clasificar cada
 * nodo del árbol según la máscara que le cae dentro:
 *
 *  - **vacío** (ninguna celda habilitada) → se poda; ahí no hay tablero;
 *  - **sólido** (todas habilitadas) → es un rectángulo legítimo: puede ser hoja o seguir cortándose;
 *  - **mixto** (unas sí y otras no) → *no puede* ser hoja; hay que cortarlo obligatoriamente.
 *
 * Dos propiedades hacen que esto sea correcto y termine siempre:
 *
 *  - *Terminación.* Cada corte produce dos hijos de área estrictamente menor, y un nodo de 1×1
 *    nunca es mixto (una sola celda está habilitada o no lo está). Por tanto todo nodo mixto
 *    acaba descomponiéndose en sólidos y vacíos; en el peor caso, celda a celda.
 *  - *Exactitud.* Las hojas sólidas son disjuntas (los cortes no solapan) y su unión son
 *    exactamente las celdas habilitadas (solo se descartan nodos vacíos). Es una partición válida
 *    para **cualquier** máscara, también disconexa — de ahí que agujeros e islas no necesiten
 *    tratamiento especial.
 *
 * ## 2. Dónde cortar un nodo mixto (lo que evita el "confeti")
 *
 * Cortar un nodo mixto al azar funciona, pero trocea el borde de la figura en hilos de 1×1: cada
 * escalón de la máscara fuerza un corte, y si ese corte cae a destiempo deja restos sueltos. El
 * generador puntúa cada corte posible por la **longitud de frontera de máscara que recorre** (los
 * pares de celdas vecinas, a uno y otro lado de la línea, con distinto estado) y sortea con peso
 * cuadrático. Así se "pelan" primero los bloques inhabilitados grandes: una L queda en dos
 * rectángulos sólidos con un solo corte, y una rosca en cuatro con tres. Todo nodo mixto tiene al
 * menos un corte con puntuación positiva (si conviven celdas de ambos estados, en algún punto dos
 * vecinas difieren), así que el sorteo nunca se queda sin candidatos.
 *
 * En un nodo sólido la única regla es no fabricar piezas de área 1 (pista "1": un rectángulo que
 * se resuelve solo). Se corta preferentemente por el lado largo para que las piezas no degeneren
 * en tiras, y una pasada final ([mergeSingles]) funde las celdas sueltas que aun así hayan
 * quedado en bordes muy irregulares con un vecino con el que formen rectángulo.
 *
 * ## 3. Coste y límites del método
 *
 * - **Partición:** con una tabla de áreas acumuladas (`SummedArea`) clasificar un nodo es O(1).
 *   Puntuar los cortes de un nodo mixto de `w × h` cuesta O(w·h), y cada celda participa en un
 *   nodo por nivel del árbol, así que el total es O(n · profundidad) ≤ O(n²) para `n` celdas. Con
 *   n ≤ 144 es despreciable.
 * - **Qué particiones puede producir:** solo las *guillotina* (las que se obtienen con cortes de
 *   lado a lado). Su número crece exponencialmente con el de rectángulos, de modo que hay variedad
 *   de sobra, pero quedan fuera las disposiciones en "molinillo" (cuatro rectángulos girando
 *   alrededor de uno central), que ningún corte recto separa. Se acepta el sesgo a cambio de un
 *   algoritmo lineal y sin retroceso; la fusión de la pasada final rompe en parte ese patrón.
 * - **Unicidad:** que la partición exista no implica que sea la única. Dos fichas 1×2 lado a lado
 *   admiten también la lectura en horizontal si las pistas caen en diagonal. Comprobarlo exige
 *   **contar soluciones**, que es el problema NP-completo de arriba: [ShikakuSolver] lo hace por
 *   retroceso, acotado por un presupuesto de nodos para que el peor caso no congele la generación.
 *
 * ## 4. Reparación dirigida de ambigüedades
 *
 * Cuando el solver encuentra dos soluciones no se tira el nivel: se comparan y se mueven **solo
 * las pistas de los rectángulos en los que difieren** (una pista bien colocada "clava" su
 * rectángulo; en el ejemplo anterior basta alinear las dos pistas en la misma fila). Es mucho más
 * barato que regenerar y converge en pocas rondas. Si aun así no se logra, se prueba otra
 * partición; y si se agotan los intentos se entrega el mejor candidato con
 * [ShikakuLevel.hasUniqueSolution] en `false`. Eso nunca rompe la partida: la victoria se valida
 * contra las reglas, no contra la solución guardada.
 *
 * ## Determinismo
 *
 * Todo el azar sale de un `Random(seed)` (algoritmo fijo y multiplataforma en Kotlin), y la
 * semilla por defecto deriva del nivel ([seedFor]). El nivel N es, por tanto, **el mismo tablero
 * para todos los jugadores y en cada plataforma**: los tiempos son comparables en el ranking y
 * no hace falta persistir el tablero para reanudar una partida, basta con regenerarlo.
 *
 * La generación es CPU pura: llámala desde `Dispatchers.Default`, nunca desde el hilo principal.
 */
object ShikakuLevelGenerator {

    /** Particiones distintas que se prueban antes de conformarse con el mejor candidato. */
    private const val PARTITION_ATTEMPTS = 16

    /** Rondas de reparación de pistas por partición (cada una cuesta una resolución completa). */
    private const val REPAIR_ROUNDS = 24

    /** Intentos de fabricar una figura con suficiente superficie antes de caer al rectángulo. */
    private const val MASK_ATTEMPTS = 8

    /** Fracción mínima de celdas habilitadas: por debajo, la figura deja muy poco tablero. */
    private const val MIN_FILL = 0.55f

    /** Sal de la semilla ("SHIKAKU" en ASCII): desacopla estos tableros de otros juegos por nivel. */
    private const val SEED_SALT = 0x5348494B414B55L

    /**
     * Curva de dificultad.
     *
     * Sube **una sola cosa cada vez** y en escalones de dos o tres niveles: primero crece el
     * tablero sobre rejilla completa, luego aparecen los recortes, y solo con el 10×10 llegan
     * las figuras que rompen de verdad las plantillas mentales (L, rosca). La primera versión
     * saltaba de 5×5 a 10×10 en tres niveles y el jugador se encontraba un tablero de experto
     * sin haber practicado antes ni el tamaño ni las máscaras.
     *
     * | Niveles | Lado | Figura | Pista máx. |
     * |---------|------|--------|------------|
     * | 1–2     | 5    | rectángulo | 5–6 |
     * | 3–4     | 6    | rectángulo | 6–8 |
     * | 5–6     | 7    | esquinas cortadas | 8–9 |
     * | 7–8     | 8    | esquinas cortadas, L | 10–12 |
     * | 9       | 9    | esquinas cortadas, L | 12 |
     * | 10–15   | 10   | L, rosca (cruz e irregular desde el 13) | 14–16 |
     * | 16–21   | 11   | todas las complejas | 17–19 |
     * | 22+     | 12   | todas las complejas | 20–24 |
     */
    fun configFor(level: Int): ShikakuLevelConfig {
        val n = level.coerceAtLeast(1)
        val side = when {
            n <= 2 -> 5
            n <= 4 -> 6
            n <= 6 -> 7
            n <= 8 -> 8
            n == 9 -> 9
            n <= 15 -> 10
            n <= 21 -> 11
            else -> 12
        }
        val shapes = when {
            n <= 4 -> listOf(ShikakuMaskShape.RECTANGLE)
            n <= 6 -> listOf(ShikakuMaskShape.CUT_CORNERS)
            n <= 9 -> listOf(ShikakuMaskShape.CUT_CORNERS, ShikakuMaskShape.L_SHAPE)
            n <= 12 -> listOf(ShikakuMaskShape.L_SHAPE, ShikakuMaskShape.CENTER_HOLE)
            else -> listOf(
                ShikakuMaskShape.CROSS,
                ShikakuMaskShape.CENTER_HOLE,
                ShikakuMaskShape.L_SHAPE,
                ShikakuMaskShape.IRREGULAR,
            )
        }
        // Pista máxima: la palanca fina. Hasta el nivel 9 va a mano (cada punto se nota mucho
        // en tableros chicos: 12 es el primer número con tres factorizaciones); después sube
        // uno cada dos niveles.
        val maxArea = when (n) {
            1 -> 5
            2, 3 -> 6
            4, 5 -> 8
            6 -> 9
            7 -> 10
            8, 9 -> 12
            else -> (14 + (n - 10) / 2).coerceAtMost(24)
        }
        // Al principio se deja de cortar pronto (piezas "redondas", pocas pistas); luego se
        // desmenuza más y los números grandes pasan a ser la excepción.
        val (stop, bigStop) = when {
            n <= 2 -> 0.8f to 0.8f
            n <= 4 -> 0.7f to 0.5f
            n <= 9 -> 0.6f to 0.35f
            n <= 15 -> 0.5f to 0.25f
            else -> 0.5f to 0.2f
        }
        return ShikakuLevelConfig(side, side, shapes, maxArea, stop, bigStop)
    }

    /** Semilla canónica de [level]: la que hace que todos los jugadores reciban el mismo tablero. */
    fun seedFor(level: Int): Long = level.toLong() * -7046029254386353131L xor SEED_SALT

    /**
     * Genera el tablero de [level].
     *
     * @param seed semilla del azar; por defecto la canónica del nivel. Pasar otra sirve para
     *   ofrecer "otro tablero de la misma dificultad" sin cambiar de nivel.
     */
    fun generate(level: Int, seed: Long = seedFor(level)): ShikakuLevel =
        generate(configFor(level), level, seed)

    /**
     * Genera un tablero con una configuración explícita.
     *
     * @param level número con el que se etiqueta el resultado; no influye en la generación.
     */
    fun generate(config: ShikakuLevelConfig, level: Int, seed: Long): ShikakuLevel {
        val rng = Random(seed)
        var best = draft(config, rng)
        var attempt = 1
        // Un candidato perfecto (única solución y sin pistas "1") corta la búsqueda; si no llega,
        // se queda el mejor visto. El orden de preferencia es unicidad primero: una pista "1" es
        // un detalle de pulido, una solución ambigua es un defecto de diseño.
        while (!best.isIdeal && attempt < PARTITION_ATTEMPTS) {
            val other = draft(config, rng)
            if (other.isBetterThan(best)) best = other
            attempt++
        }
        return best.toLevel(level)
    }

    /** Candidato a nivel: una partición con sus pistas y el veredicto del solver. */
    private class Draft(
        val columns: Int,
        val rows: Int,
        val enabled: BooleanArray,
        val rects: List<ShikakuRect>,
        val clueX: IntArray,
        val clueY: IntArray,
        val unique: Boolean,
    ) {
        val singles: Int = rects.count { it.area == 1 }
        val isIdeal: Boolean get() = unique && singles == 0

        fun isBetterThan(other: Draft): Boolean =
            if (unique != other.unique) unique else singles < other.singles

        fun toLevel(level: Int) = ShikakuLevel(
            level = level,
            mask = BoardMask(columns, rows, enabled.toList()),
            clues = rects.indices.map { ShikakuCell(clueX[it], clueY[it], rects[it].area) },
            solution = rects,
            hasUniqueSolution = unique,
        )
    }

    /** Un intento completo: figura → partición → pistas → reparación de ambigüedades. */
    private fun draft(config: ShikakuLevelConfig, rng: Random): Draft {
        val columns = config.columns
        val rows = config.rows
        val enabled = buildMask(config, rng)
        val area = SummedArea(columns, rows) { enabled[it] }

        val rects = ArrayList<ShikakuRect>()
        split(0, 0, columns, rows, enabled, columns, area, config, rng, rects)
        mergeSingles(rects, config.maxArea)

        val clueX = IntArray(rects.size)
        val clueY = IntArray(rects.size)
        val values = IntArray(rects.size) { rects[it].area }

        // Mueve la pista del rectángulo i a otra de sus celdas (a la misma si solo tiene una).
        fun reroll(i: Int, avoidCurrent: Boolean) {
            val rect = rects[i]
            var x: Int
            var y: Int
            do {
                x = rect.left + rng.nextInt(rect.width)
                y = rect.top + rng.nextInt(rect.height)
            } while (avoidCurrent && rect.area > 1 && x == clueX[i] && y == clueY[i])
            clueX[i] = x
            clueY[i] = y
        }
        for (i in rects.indices) reroll(i, avoidCurrent = false)

        var unique = false
        for (round in 0 until REPAIR_ROUNDS) {
            val result = ShikakuSolver(columns, rows, enabled, area, clueX, clueY, values).solve()
            if (result.isUnique) {
                unique = true
                break
            }
            val first = result.first
            val second = result.second
            if (first != null && second != null) {
                // Solo las pistas cuyo rectángulo cambia entre las dos soluciones son culpables.
                for (i in rects.indices) if (first[i] != second[i]) reroll(i, avoidCurrent = true)
            } else {
                // Presupuesto agotado sin dos soluciones que comparar: no hay diagnóstico, se
                // baraja todo para sacar al solver de la zona patológica.
                for (i in rects.indices) reroll(i, avoidCurrent = false)
            }
        }
        return Draft(columns, rows, enabled, rects, clueX, clueY, unique)
    }

    // ---------------------------------------------------------------------------------------
    // Partición BSP sobre la caja contenedora
    // ---------------------------------------------------------------------------------------

    /**
     * Nodo del BSP: clasifica la región `[x, x+w) × [y, y+h)` y la descarta, la emite como hoja o
     * la corta en dos (ver §1 de la cabecera).
     */
    private fun split(
        x: Int, y: Int, w: Int, h: Int,
        enabled: BooleanArray, columns: Int, area: SummedArea,
        config: ShikakuLevelConfig, rng: Random, out: MutableList<ShikakuRect>,
    ) {
        val cells = w * h
        val live = area.count(x, y, w, h)
        if (live == 0) return

        val solid = live == cells
        if (solid && cells <= config.maxArea) {
            // Por debajo de MIN_SPLITTABLE_AREA cualquier corte fabricaría una pieza de área 1.
            val stopChance = if (cells * 2 > config.maxArea) config.bigStopChance else config.stopChance
            if (cells < MIN_SPLITTABLE_AREA || rng.nextFloat() < stopChance) {
                out += ShikakuRect(x, y, w, h)
                return
            }
        }

        val cut = if (solid) solidCut(w, h, rng) else maskCut(x, y, w, h, enabled, columns, rng)
        if (cut.vertical) {
            split(x, y, cut.at, h, enabled, columns, area, config, rng, out)
            split(x + cut.at, y, w - cut.at, h, enabled, columns, area, config, rng, out)
        } else {
            split(x, y, w, cut.at, enabled, columns, area, config, rng, out)
            split(x, y + cut.at, w, h - cut.at, enabled, columns, area, config, rng, out)
        }
    }

    /** Corte de un nodo: [at] celdas desde su borde izquierdo ([vertical]) o superior. */
    private class Cut(val vertical: Boolean, val at: Int)

    /**
     * Corte de un nodo sólido. Se elige el eje con probabilidad proporcional al cuadrado del lado
     * (cortar el largo mantiene las piezas compactas, pero sin determinismo para no generar
     * siempre la misma retícula) y se excluyen las posiciones que dejarían una pieza de área 1,
     * que solo existen cuando el nodo es una tira de una celda de grosor.
     */
    private fun solidCut(w: Int, h: Int, rng: Random): Cut {
        // En una tira (grosor 1) los cortes a una celda del extremo dejan un 1 suelto.
        val vMargin = if (h == 1) 2 else 1
        val hMargin = if (w == 1) 2 else 1
        val vOptions = (w - 2 * vMargin + 1).coerceAtLeast(0)
        val hOptions = (h - 2 * hMargin + 1).coerceAtLeast(0)
        val vertical = when {
            hOptions == 0 -> true
            vOptions == 0 -> false
            else -> rng.nextInt(w * w + h * h) < w * w
        }
        return if (vertical) Cut(true, vMargin + rng.nextInt(vOptions)) else Cut(false, hMargin + rng.nextInt(hOptions))
    }

    /**
     * Corte de un nodo mixto, sorteado con peso = (frontera de máscara recorrida)². Ver §2 de la
     * cabecera: es lo que hace que la figura se pele en bloques grandes en vez de en confeti.
     */
    private fun maskCut(
        x: Int, y: Int, w: Int, h: Int,
        enabled: BooleanArray, columns: Int, rng: Random,
    ): Cut {
        // Los w-1 cortes verticales primero y los h-1 horizontales después, en un solo array.
        val weights = IntArray(w - 1 + h - 1)
        var total = 0
        for (p in 1 until w) {
            var edge = 0
            for (j in 0 until h) {
                val row = (y + j) * columns + x + p
                if (enabled[row - 1] != enabled[row]) edge++
            }
            weights[p - 1] = edge * edge
            total += edge * edge
        }
        for (p in 1 until h) {
            var edge = 0
            for (i in 0 until w) {
                val below = (y + p) * columns + x + i
                if (enabled[below - columns] != enabled[below]) edge++
            }
            weights[w - 1 + p - 1] = edge * edge
            total += edge * edge
        }
        var pick = rng.nextInt(total)
        var index = 0
        while (pick >= weights[index]) {
            pick -= weights[index]
            index++
        }
        return if (index < w - 1) Cut(true, index + 1) else Cut(false, index - (w - 1) + 1)
    }

    /**
     * Funde cada rectángulo de área 1 con un vecino con el que forme un rectángulo mayor que no
     * supere [maxArea] (comparten un lado completo). Elige la unión más pequeña para no gastar el
     * tope de área en tragarse un resto. Los que no encuentran pareja se quedan como pista "1":
     * es legal en Shikaku, solo menos interesante.
     */
    private fun mergeSingles(rects: MutableList<ShikakuRect>, maxArea: Int) {
        var i = 0
        while (i < rects.size) {
            val single = rects[i]
            if (single.area != 1) {
                i++
                continue
            }
            var bestJ = -1
            var bestUnion: ShikakuRect? = null
            for (j in rects.indices) {
                if (j == i) continue
                val union = unionIfRectangle(single, rects[j]) ?: continue
                if (union.area <= maxArea && (bestUnion == null || union.area < bestUnion.area)) {
                    bestJ = j
                    bestUnion = union
                }
            }
            if (bestUnion == null) {
                i++
                continue
            }
            rects[bestJ] = bestUnion
            rects.removeAt(i)
            // Sin avanzar i: el elemento que ocupa ahora este hueco aún no se ha revisado.
        }
    }

    /** Unión de [a] y [b] si están pegados por un lado completo; `null` si no forman rectángulo. */
    private fun unionIfRectangle(a: ShikakuRect, b: ShikakuRect): ShikakuRect? = when {
        a.top == b.top && a.height == b.height && (a.right == b.left || b.right == a.left) ->
            ShikakuRect(minOf(a.left, b.left), a.top, a.width + b.width, a.height)

        a.left == b.left && a.width == b.width && (a.bottom == b.top || b.bottom == a.top) ->
            ShikakuRect(a.left, minOf(a.top, b.top), a.width, a.height + b.height)

        else -> null
    }

    // ---------------------------------------------------------------------------------------
    // Figuras (máscaras)
    // ---------------------------------------------------------------------------------------

    /**
     * Construye la máscara (por filas) de una de las figuras de [config]. Reintenta si la figura
     * deja menos de [MIN_FILL] del tablero y, como última red, devuelve el rectángulo completo.
     */
    private fun buildMask(config: ShikakuLevelConfig, rng: Random): BooleanArray {
        val columns = config.columns
        val rows = config.rows
        repeat(MASK_ATTEMPTS) {
            val cells = BooleanArray(columns * rows) { true }
            carveShape(config.shapes[rng.nextInt(config.shapes.size)], cells, columns, rows, rng)
            removeIsolated(cells, columns, rows)
            if (cells.count { it } >= columns * rows * MIN_FILL) return cells
        }
        return BooleanArray(columns * rows) { true }
    }

    private fun carveShape(shape: ShikakuMaskShape, cells: BooleanArray, columns: Int, rows: Int, rng: Random) {
        // Inhabilita un bloque, recortándolo a la rejilla.
        fun carve(left: Int, top: Int, w: Int, h: Int) {
            for (y in maxOf(top, 0) until minOf(top + h, rows)) {
                for (x in maxOf(left, 0) until minOf(left + w, columns)) cells[y * columns + x] = false
            }
        }
        // Bloque de w×h pegado a la esquina indicada (0..3, en sentido de lectura).
        fun carveCorner(corner: Int, w: Int, h: Int) =
            carve(if (corner % 2 == 0) 0 else columns - w, if (corner < 2) 0 else rows - h, w, h)

        fun carveHole() {
            // Margen de 2 celdas: con 1 quedaría un pasillo de grosor 1 alrededor del agujero,
            // que solo admite tiras y vuelve el borde trivial.
            if (columns < 6 || rows < 6) return
            val w = rng.nextInt(maxOf(1, columns / 5), columns / 3 + 1).coerceAtMost(columns - 4)
            val h = rng.nextInt(maxOf(1, rows / 5), rows / 3 + 1).coerceAtMost(rows - 4)
            val left = ((columns - w) / 2 + rng.nextInt(-1, 2)).coerceIn(2, columns - w - 2)
            val top = ((rows - h) / 2 + rng.nextInt(-1, 2)).coerceIn(2, rows - h - 2)
            carve(left, top, w, h)
        }

        val side = minOf(columns, rows)
        when (shape) {
            ShikakuMaskShape.RECTANGLE -> Unit

            ShikakuMaskShape.CUT_CORNERS -> {
                val maxBite = maxOf(1, side / 4)
                for (corner in 0 until 4) {
                    carveCorner(corner, rng.nextInt(1, maxBite + 1), rng.nextInt(1, maxBite + 1))
                }
            }

            ShikakuMaskShape.L_SHAPE -> carveCorner(
                rng.nextInt(4),
                rng.nextInt(columns * 2 / 5, columns * 3 / 5 + 1),
                rng.nextInt(rows * 2 / 5, rows * 3 / 5 + 1),
            )

            ShikakuMaskShape.CROSS -> {
                val w = rng.nextInt(maxOf(1, columns / 4), maxOf(1, columns / 3) + 1)
                val h = rng.nextInt(maxOf(1, rows / 4), maxOf(1, rows / 3) + 1)
                for (corner in 0 until 4) carveCorner(corner, w, h)
            }

            ShikakuMaskShape.CENTER_HOLE -> carveHole()

            ShikakuMaskShape.IRREGULAR -> {
                val maxDepth = maxOf(2, side / 4)
                repeat(rng.nextInt(3, 6)) {
                    val depth = rng.nextInt(1, maxDepth + 1)
                    // Mordisco apoyado en un borde: `depth` hacia dentro, `length` a lo largo.
                    when (rng.nextInt(4)) {
                        0 -> rng.nextInt(2, columns / 2 + 1).let { carve(rng.nextInt(columns - it + 1), 0, it, depth) }
                        1 -> rng.nextInt(2, columns / 2 + 1).let { carve(rng.nextInt(columns - it + 1), rows - depth, it, depth) }
                        2 -> rng.nextInt(2, rows / 2 + 1).let { carve(0, rng.nextInt(rows - it + 1), depth, it) }
                        else -> rng.nextInt(2, rows / 2 + 1).let { carve(columns - depth, rng.nextInt(rows - it + 1), depth, it) }
                    }
                }
                if (rng.nextBoolean()) carveHole()
            }
        }
    }

    /**
     * Inhabilita las celdas que se han quedado sin ningún vecino habilitado: serían una pista "1"
     * forzosa, aislada del resto del tablero, que no aporta ninguna decisión.
     */
    private fun removeIsolated(cells: BooleanArray, columns: Int, rows: Int) {
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                val i = y * columns + x
                if (!cells[i]) continue
                val hasNeighbour = (x > 0 && cells[i - 1]) || (x < columns - 1 && cells[i + 1]) ||
                    (y > 0 && cells[i - columns]) || (y < rows - 1 && cells[i + columns])
                // Quitar una celda aislada no puede aislar a otra (no tenía vecinas), así que una
                // sola pasada basta.
                if (!hasNeighbour) cells[i] = false
            }
        }
    }
}

/** Área mínima que se puede partir en dos piezas de área ≥ 2. */
private const val MIN_SPLITTABLE_AREA = 4

/**
 * Tabla de áreas acumuladas (*summed-area table*) sobre una rejilla de banderas: tras un
 * precálculo O(n), cuenta cuántas celdas marcadas hay en cualquier rectángulo en O(1).
 *
 * Es lo que hace baratos tanto el BSP (clasificar un nodo como vacío/sólido/mixto) como el solver
 * (descartar un rectángulo candidato que pisa un agujero o contiene dos pistas).
 */
internal class SummedArea(columns: Int, rows: Int, flag: (index: Int) -> Boolean) {
    private val stride = columns + 1
    private val sums = IntArray(stride * (rows + 1))

    init {
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                sums[(y + 1) * stride + x + 1] = (if (flag(y * columns + x)) 1 else 0) +
                    sums[y * stride + x + 1] + sums[(y + 1) * stride + x] - sums[y * stride + x]
            }
        }
    }

    /** Celdas marcadas dentro de `[x, x+w) × [y, y+h)`. */
    fun count(x: Int, y: Int, w: Int, h: Int): Int =
        sums[(y + h) * stride + x + w] - sums[y * stride + x + w] - sums[(y + h) * stride + x] + sums[y * stride + x]
}

/**
 * Contador de soluciones de un Shikaku, usado por el generador para certificar unicidad.
 *
 * Búsqueda por retroceso con dos podas que la hacen viable en tableros de hasta 12×12:
 *
 *  1. **Candidatos precalculados por pista.** Para una pista de valor `v` solo sirven los
 *     rectángulos de área `v` (una forma por cada divisor) que la contienen, no pisan agujeros y
 *     no incluyen otra pista. Suelen ser un puñado, no los O(n²) rectángulos del tablero.
 *  2. **Rama por la primera celda libre.** En lugar de elegir "qué pista coloco", se toma la
 *     primera celda habilitada sin cubrir y se prueban solo los candidatos que la cubren. Toda
 *     solución debe cubrirla, así que no se pierde ninguna, y una celda sin candidato viable
 *     corta la rama al instante.
 *
 * El peor caso sigue siendo exponencial (el problema es NP-completo), de ahí [NODE_BUDGET]: al
 * agotarlo se devuelve "no demostrado" y el generador decide.
 *
 * @param clueX columna de cada pista.
 * @param clueY fila de cada pista.
 * @param values valor (área exigida) de cada pista.
 */
internal class ShikakuSolver(
    private val columns: Int,
    rows: Int,
    private val enabled: BooleanArray,
    enabledArea: SummedArea,
    clueX: IntArray,
    clueY: IntArray,
    values: IntArray,
) {
    /**
     * Resultado de [solve].
     *
     * @property count soluciones halladas, saturado en 2 (basta saber si hay más de una).
     * @property exhausted `true` si se recorrió todo el árbol; `false` si cortó el presupuesto.
     * @property first candidato elegido para cada pista en la primera solución, o `null`.
     * @property second ídem para la segunda; comparar ambos señala las pistas ambiguas.
     */
    class Result(val count: Int, val exhausted: Boolean, val first: IntArray?, val second: IntArray?) {
        /** Unicidad DEMOSTRADA: exactamente una solución tras agotar la búsqueda. */
        val isUnique: Boolean get() = count == 1 && exhausted
    }

    private val clueCount = values.size
    private val cellCount = columns * rows

    // Candidatos en arrays paralelos (pista dueña + geometría) para no crear objetos en la búsqueda.
    private val candClue: IntArray
    private val candLeft: IntArray
    private val candTop: IntArray
    private val candWidth: IntArray
    private val candHeight: IntArray

    /** Para cada celda, los candidatos que la cubren. */
    private val covering: Array<IntArray>

    private val occupied = BooleanArray(cellCount)
    private val used = BooleanArray(clueCount)
    private val chosen = IntArray(clueCount)
    private var nodes = 0
    private var solutions = 0
    private var aborted = false
    private var first: IntArray? = null
    private var second: IntArray? = null

    init {
        val isClue = BooleanArray(cellCount)
        for (k in 0 until clueCount) isClue[clueY[k] * columns + clueX[k]] = true
        val clueArea = SummedArea(columns, rows) { isClue[it] }

        val owner = ArrayList<Int>()
        val geometry = ArrayList<Int>()
        val perCell = Array(cellCount) { ArrayList<Int>() }
        for (k in 0 until clueCount) {
            val value = values[k]
            for (w in 1..minOf(value, columns)) {
                if (value % w != 0) continue
                val h = value / w
                if (h > rows) continue
                for (left in maxOf(0, clueX[k] - w + 1)..minOf(clueX[k], columns - w)) {
                    for (top in maxOf(0, clueY[k] - h + 1)..minOf(clueY[k], rows - h)) {
                        if (enabledArea.count(left, top, w, h) != value) continue
                        if (clueArea.count(left, top, w, h) != 1) continue
                        val id = owner.size
                        owner += k
                        geometry += left; geometry += top; geometry += w; geometry += h
                        for (y in top until top + h) for (x in left until left + w) perCell[y * columns + x] += id
                    }
                }
            }
        }
        candClue = owner.toIntArray()
        candLeft = IntArray(owner.size) { geometry[it * 4] }
        candTop = IntArray(owner.size) { geometry[it * 4 + 1] }
        candWidth = IntArray(owner.size) { geometry[it * 4 + 2] }
        candHeight = IntArray(owner.size) { geometry[it * 4 + 3] }
        covering = Array(cellCount) { perCell[it].toIntArray() }
    }

    /** Cuenta soluciones (hasta 2). Pensado para llamarse una sola vez por instancia. */
    fun solve(): Result {
        search(0)
        return Result(solutions, !aborted, first, second)
    }

    private fun search(from: Int) {
        var cell = from
        while (cell < cellCount && (!enabled[cell] || occupied[cell])) cell++
        if (cell == cellCount) {
            // Todo cubierto. Cada rectángulo contiene exactamente una pista (la suya), así que
            // cubrir todas las celdas implica haber usado todas las pistas: es solución completa.
            if (solutions == 0) first = chosen.copyOf() else second = chosen.copyOf()
            solutions++
            return
        }
        if (++nodes > NODE_BUDGET) {
            aborted = true
            return
        }
        for (cand in covering[cell]) {
            val clue = candClue[cand]
            if (used[clue] || !fits(cand)) continue
            mark(cand, true)
            used[clue] = true
            chosen[clue] = cand
            search(cell + 1)
            used[clue] = false
            mark(cand, false)
            if (solutions >= 2 || aborted) return
        }
    }

    private fun fits(cand: Int): Boolean {
        for (y in candTop[cand] until candTop[cand] + candHeight[cand]) {
            val row = y * columns
            for (x in candLeft[cand] until candLeft[cand] + candWidth[cand]) if (occupied[row + x]) return false
        }
        return true
    }

    private fun mark(cand: Int, value: Boolean) {
        for (y in candTop[cand] until candTop[cand] + candHeight[cand]) {
            val row = y * columns
            for (x in candLeft[cand] until candLeft[cand] + candWidth[cand]) occupied[row + x] = value
        }
    }

    private companion object {
        /**
         * Tope de nodos explorados. Un tablero bien condicionado de 12×12 se resuelve en unos
         * cientos; llegar aquí significa que las pistas dejan demasiada libertad, y en ese caso
         * interesa más barajarlas que seguir buscando.
         */
        const val NODE_BUDGET = 60_000
    }
}
