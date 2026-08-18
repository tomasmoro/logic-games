package com.kortexgames.app.game.watersort

import kotlinx.serialization.Serializable

/**
 * # Ordena las Pociones — partida en curso guardada
 *
 * Subconjunto serializable de una partida a medias que [WaterSortEngine] persiste al
 * salir y que la antesala ofrece como "Continuar" (ver `SavedGameStateRepository`,
 * mismo mecanismo que Crucigrama Neón y Neon Hyper-Cube).
 *
 * ## Por qué no basta con serializar [WaterSortState]
 * El estado que observa la UI no lleva todo lo que el motor necesita para seguir
 * jugando exactamente igual tras reanudar: el tablero **inicial** del intento (para
 * que "Reiniciar" siga volviendo al tablero correcto, con los tubos extra ya
 * concedidos) y el **historial** de vertidos (para que "Deshacer" no se quede mudo
 * justo después de reanudar) viven como variables privadas del motor, no en
 * [WaterSortState]. `minMoves` tampoco está en el estado —solo lo usa el cálculo de
 * puntaje/precisión al terminar— pero hace falta guardarlo para que el resultado de
 * una partida reanudada puntúe igual que si nunca se hubiera salido.
 *
 * @property game estado del tablero tal como lo pintaría la UI, con
 *   [WaterSortState.lastPour] a `null`: es un disparador de animación de una sola
 *   vez, no algo que tenga sentido "reanudar".
 * @property initialTubes tablero inicial del intento (incluye los tubos extra ya
 *   concedidos por anuncio), base de [WaterSortEngine.restart].
 * @property minMoves longitud de la solución de referencia del nivel (ver
 *   [WaterSortGenerator]), para que el puntaje/precisión de una partida reanudada se
 *   calculen igual que si no se hubiera guardado.
 * @property history pila de tableros previos a cada vertido, de más antiguo a más
 *   reciente; es la pila de deshacer.
 */
@Serializable
data class WaterSortSavedState(
    val game: WaterSortState,
    val initialTubes: List<Tube>,
    val minMoves: Int,
    val history: List<List<Tube>>,
)
