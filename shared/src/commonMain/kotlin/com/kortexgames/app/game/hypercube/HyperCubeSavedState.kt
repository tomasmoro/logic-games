package com.kortexgames.app.game.hypercube

import kotlinx.serialization.Serializable

/**
 * # Neon Hyper-Cube — partida en curso guardada
 *
 * Subconjunto **serializable** de una partida a medias, que se persiste al salir y se ofrece como
 * "Continuar" en la antesala (ver `SavedGameStateRepository`), igual que en Neon Grid 2048 y Neon
 * Sudoku Matrix.
 *
 * ## Qué se guarda y por qué no el estado entero
 * No se serializa [HyperCubeUiState] tal cual: mezcla lo que hay que restaurar con cosas que no
 * tiene sentido reanudar (la cámara es preferencia del momento, el `status` sería PAUSED según
 * cuándo se salió, y `gameOver` es siempre `null` mientras se juega). Aquí está exactamente lo
 * necesario para dejar el cubo como estaba y que la partida siga contando igual.
 *
 * ## Espejo del dominio, no el dominio anotado
 * Los tipos del modelo ([Cubie], [Sticker], [LayerTurn]) se copian aquí en versiones planas en vez
 * de anotarlos con `@Serializable`. Dos motivos: el dominio se mantiene libre de dependencias de
 * serialización (como declara su KDoc), y —más importante— este formato es un **contrato de
 * persistencia**: si algún día el modelo cambia de forma, el guardado antiguo se sigue leyendo o
 * se descarta limpiamente, sin que un refactor del dominio invalide partidas sin querer. Los
 * enums viajan por **nombre** y no por ordinal, para que reordenarlos no corrompa nada.
 *
 * @property cubies configuración exacta de las 27 piezas.
 * @property turns jugadas del jugador desde que acabó la mezcla, en orden. Es la **pila de
 *   deshacer**: se guarda para que "Deshacer" siga funcionando tras reanudar, en vez de que
 *   retomar la partida borre en silencio esa posibilidad.
 * @property level nivel en juego (irrelevante si [isFreeMode]).
 * @property isFreeMode si la partida es el modo libre (sin nivel ni récord).
 * @property moves movimientos contabilizados hasta ahora.
 * @property scrambleDepth profundidad de la mezcla con la que arrancó, base del par y del puntaje.
 * @property elapsedMs tiempo ya jugado. Se guarda —y se le suma al cronómetro al reanudar— para
 *   cerrar el atajo evidente: sin esto, salir y volver a entrar pondría el reloj a cero y
 *   regalaría un "mejor tiempo" del nivel que nunca se hizo.
 * @property undosUsed cuántas veces se ha deshecho (penaliza el puntaje, como cualquier ayuda).
 * @property freeUndoUsed si ya se gastó el deshacer gratuito de esta partida.
 */
@Serializable
data class HyperCubeSavedState(
    val cubies: List<SavedCubie>,
    val turns: List<SavedTurn>,
    val level: Int,
    val isFreeMode: Boolean,
    val moves: Int,
    val scrambleDepth: Int,
    val elapsedMs: Long,
    val undosUsed: Int,
    val freeUndoUsed: Boolean,
)

/** Una pieza guardada: su posición en el retículo y las pegatinas que lleva. */
@Serializable
data class SavedCubie(
    val x: Int,
    val y: Int,
    val z: Int,
    val stickers: List<SavedSticker>,
)

/** Una pegatina guardada: hacia dónde mira y de qué color es ([FaceColor] por nombre). */
@Serializable
data class SavedSticker(
    val nx: Int,
    val ny: Int,
    val nz: Int,
    val color: String,
)

/** Un giro guardado ([Axis] y [TurnDirection] por nombre). */
@Serializable
data class SavedTurn(
    val axis: String,
    val layer: Int,
    val direction: String,
)

/** Vuelca el cubo a su forma serializable. */
fun CubeState.toSaved(): List<SavedCubie> = cubies.map { cubie ->
    SavedCubie(
        x = cubie.position.x,
        y = cubie.position.y,
        z = cubie.position.z,
        stickers = cubie.stickers.map { sticker ->
            SavedSticker(
                nx = sticker.normal.x,
                ny = sticker.normal.y,
                nz = sticker.normal.z,
                color = sticker.color.name,
            )
        },
    )
}

/**
 * Reconstruye el cubo desde su forma serializable, o `null` si el guardado no describe un cubo
 * válido.
 *
 * Valida de verdad (nº de piezas, colores conocidos, normales que sean ejes unitarios) en lugar de
 * confiar en el JSON: un guardado corrupto o de una versión anterior debe degradar a "no hay
 * partida pendiente", nunca dibujar un cubo imposible con el que además no se podría jugar.
 */
fun List<SavedCubie>.toCubeStateOrNull(): CubeState? {
    if (size != EXPECTED_CUBIES) return null
    val cubies = map { saved ->
        val stickers = saved.stickers.map { sticker ->
            val color = FaceColor.entries.firstOrNull { it.name == sticker.color } ?: return null
            val normal = IntVec3(sticker.nx, sticker.ny, sticker.nz)
            if (!normal.isUnitAxis()) return null
            Sticker(normal, color)
        }
        val position = IntVec3(saved.x, saved.y, saved.z)
        if (!position.isLatticePoint()) return null
        Cubie(position, stickers)
    }
    return CubeState(cubies)
}

/** Convierte una jugada a su forma serializable. */
fun LayerTurn.toSaved(): SavedTurn = SavedTurn(axis.name, layer, direction.name)

/** Recupera una jugada guardada, o `null` si sus nombres no corresponden al dominio actual. */
fun SavedTurn.toLayerTurnOrNull(): LayerTurn? {
    val parsedAxis = Axis.entries.firstOrNull { it.name == axis } ?: return null
    val parsedDirection = TurnDirection.entries.firstOrNull { it.name == direction } ?: return null
    if (layer !in -1..1) return null
    return LayerTurn(parsedAxis, layer, parsedDirection)
}

/** ¿Es uno de los seis ejes unitarios (una normal de cara válida)? */
private fun IntVec3.isUnitAxis(): Boolean =
    (x * x + y * y + z * z) == 1

/** ¿Está dentro del retículo `{-1, 0, 1}³` de posiciones de cubie? */
private fun IntVec3.isLatticePoint(): Boolean =
    x in -1..1 && y in -1..1 && z in -1..1

/** Las 27 piezas de un 3×3×3: cualquier otra cantidad es un guardado inválido. */
private const val EXPECTED_CUBIES = 27
