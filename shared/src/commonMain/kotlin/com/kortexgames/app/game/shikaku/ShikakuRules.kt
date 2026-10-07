package com.kortexgames.app.game.shikaku

/**
 * Veredicto de un rectángulo frente a las reglas.
 *
 * @property validity motivo por el que cumple o incumple.
 * @property targetNumber la pista contenida cuando hay exactamente una; `null` en otro caso.
 */
data class ShikakuVerdict(val validity: ShikakuRectValidity, val targetNumber: Int? = null)

/**
 * # ShikakuRules — reglas puras del tablero (Fase 2)
 *
 * Toda la lógica de Shikaku que no depende de corrutinas ni de ciclo de vida: evaluar un
 * rectángulo, colocarlo resolviendo solapes y calcular el veredicto global. Vive fuera del
 * `ShikakuViewModel` por dos razones:
 *
 *  - **Testabilidad.** Son funciones `(estado, entrada) → estado` que un test de `commonTest`
 *    ejercita sin `Dispatchers.Main` ni `viewModelScope`.
 *  - **Una sola fuente de verdad.** La vista previa del arrastre y el sellado del rectángulo
 *    pasan por el mismo [evaluate]; si cada uno tuviera su copia de las reglas, el badge podría
 *    prometer "válido" y el sellado decir lo contrario.
 */
object ShikakuRules {

    /**
     * Evalúa [bounds] contra la figura y las pistas.
     *
     * El orden de las comprobaciones es de más a menos grave: primero la geometría (si pisa un
     * agujero no tiene sentido hablar de su área), después cuántas pistas contiene y solo al final
     * si el área coincide.
     */
    fun evaluate(bounds: ShikakuRect, mask: BoardMask, clues: List<ShikakuCell>): ShikakuVerdict {
        if (!mask.covers(bounds)) return ShikakuVerdict(ShikakuRectValidity.OUT_OF_MASK)
        var target: ShikakuCell? = null
        for (clue in clues) {
            if (clue !in bounds) continue
            if (target != null) return ShikakuVerdict(ShikakuRectValidity.MULTIPLE_NUMBERS)
            target = clue
        }
        return when {
            target == null -> ShikakuVerdict(ShikakuRectValidity.NO_NUMBER)
            target.number == bounds.area -> ShikakuVerdict(ShikakuRectValidity.VALID, target.number)
            else -> ShikakuVerdict(ShikakuRectValidity.WRONG_AREA, target.number)
        }
    }

    /**
     * Coloca un rectángulo nuevo y devuelve la lista resultante.
     *
     * **Resolución de solapes: el nuevo gana.** Todo rectángulo existente que comparta alguna
     * celda con [bounds] se elimina. Es la convención habitual del género y la que hace que
     * "editar" no necesite un modo aparte: para corregir un rectángulo basta trazar el bueno
     * encima. La alternativa (rechazar el trazo con un error) obliga a borrar primero y castiga
     * justo el gesto con el que el jugador está rectificando. Como consecuencia, la lista nunca
     * contiene dos rectángulos solapados — invariante de la que depende
     * [ShikakuBoardValidation.isSolved].
     *
     * @param id identificador del nuevo rectángulo; lo aporta quien llama para que no se reutilice.
     * @param verdict resultado de [evaluate] para [bounds]. Debe ser distinto de
     *   [ShikakuRectValidity.OUT_OF_MASK]: un rectángulo que pisa agujeros no se coloca nunca.
     */
    fun place(
        rectangles: List<ShikakuRectangle>,
        bounds: ShikakuRect,
        id: Int,
        verdict: ShikakuVerdict,
    ): List<ShikakuRectangle> {
        require(verdict.validity != ShikakuRectValidity.OUT_OF_MASK) { "No se coloca fuera de la máscara: $bounds" }
        val kept = rectangles.filterNot { it.bounds.overlaps(bounds) }
        return kept + ShikakuRectangle(id, bounds, tintFor(bounds, kept), verdict.validity)
    }

    /** El rectángulo que cubre la celda ([x], [y]), o `null`. A lo sumo hay uno (sin solapes). */
    fun rectangleAt(rectangles: List<ShikakuRectangle>, x: Int, y: Int): ShikakuRectangle? =
        rectangles.firstOrNull { it.bounds.contains(x, y) }

    /**
     * Veredicto global del tablero.
     *
     * La cobertura se cuenta sobre una rejilla (y no sumando áreas) para que el resultado sea
     * correcto aunque alguien construya una lista con solapes sin pasar por [place]: una celda
     * cubierta dos veces cuenta una, de modo que un solape jamás puede "completar" el tablero.
     */
    fun validate(mask: BoardMask, rectangles: List<ShikakuRectangle>): ShikakuBoardValidation {
        val covered = BooleanArray(mask.columns * mask.rows)
        var count = 0
        for (rectangle in rectangles) {
            val b = rectangle.bounds
            for (y in b.top until b.bottom) {
                for (x in b.left until b.right) {
                    if (!mask.isEnabled(x, y)) continue
                    val index = y * mask.columns + x
                    if (!covered[index]) {
                        covered[index] = true
                        count++
                    }
                }
            }
        }
        return ShikakuBoardValidation(
            coveredCells = count,
            totalCells = mask.enabledCount,
            invalidCount = rectangles.count { it.validity != ShikakuRectValidity.VALID },
        )
    }

    /**
     * Elige el acento del nuevo rectángulo evitando, si se puede, el de los que lo tocan: dos
     * vecinos del mismo color se leen como una sola mancha y esconden la frontera que el jugador
     * acaba de trazar. Con tres tintes no siempre hay uno libre (no es un 4-coloreado), así que
     * en ese caso se toma el menos repetido entre los vecinos.
     */
    private fun tintFor(bounds: ShikakuRect, others: List<ShikakuRectangle>): ShikakuTint {
        // Crecer una celda por lado convierte "se tocan por un borde" en "se solapan".
        val halo = ShikakuRect(bounds.left - 1, bounds.top - 1, bounds.width + 2, bounds.height + 2)
        val neighbours = others.filter { it.bounds.overlaps(halo) }
        return ShikakuTint.entries.minBy { tint -> neighbours.count { it.tint == tint } }
    }
}
