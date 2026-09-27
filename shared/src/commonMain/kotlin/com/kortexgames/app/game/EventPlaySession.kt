package com.kortexgames.app.game

import com.kortexgames.app.domain.model.GameEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * # "Esta partida es de torneo"
 *
 * Marca, durante el tiempo que dura una partida, que lo que se está jugando
 * pertenece a un [GameEvent]. Lo consultan el ViewModel del juego (para fijar el
 * reto y la dificultad, y para enviar el resultado al torneo) y su antesala (para
 * no ofrecer opciones que el torneo no permite).
 *
 * ## Por qué un objeto de sesión y no un argumento de navegación
 * Las rutas del proyecto no llevan argumentos a propósito (ver
 * [com.kortexgames.app.ui.navigation.Routes.AUTH_ONBOARDING]): la API de argumentos
 * difiere entre plataformas en Compose Multiplatform. Serializar el evento en la
 * ruta, además, obligaría a cada una de las 23 rutas de juego a declarar un
 * parámetro opcional que 22 de ellas ignorarían.
 *
 * ## Por qué NO vive en el estado de ninguna pantalla
 * Porque cruza pantallas: lo abre la pantalla del torneo, lo lee la del juego y lo
 * cierra la navegación al salir de la partida. Un `remember` en cualquiera de las
 * tres lo perdería justo en el salto.
 *
 * ## Ciclo de vida
 * `begin` al lanzar la partida desde el torneo; `end` al salir de la ruta de juego
 * (lo hace un único punto en `App.kt`, el mismo que ya detecta "el jugador volvió
 * al menú" para los anuncios). Cerrarlo siempre al salir es lo que garantiza que
 * la siguiente partida suelta del mismo juego NO se cuente como intento del
 * torneo.
 *
 * No se persiste: si el proceso muere a mitad de partida, esa partida se pierde
 * igualmente (los juegos guardan su estado, pero un intento de torneo a medias no
 * se reanuda — ver el modo torneo de `NeonSudokuViewModel`).
 */
class EventPlaySession {

    private val _active = MutableStateFlow<EventPlay?>(null)

    /** Torneo en juego ahora mismo, o null si es una partida normal. */
    val active: StateFlow<EventPlay?> = _active.asStateFlow()

    /**
     * Abre el modo torneo para [event]. Reemplaza cualquier sesión previa.
     *
     * @param attemptsUsed intentos que el jugador YA había gastado al entrar. Lo
     *   aporta quien lanza la partida (la pantalla del torneo, que acaba de leer la
     *   clasificación) en vez de volver a preguntárselo al backend desde el juego:
     *   es el mismo dato, recién traído, y ahorra una llamada en el arranque de la
     *   partida. Sirve para decirle al jugador cuántos le quedarán si abandona.
     * @param attemptsAllowed cupo TOTAL: los libres del torneo más los extras que
     *   ya haya desbloqueado con anuncios. null = sin tope.
     */
    fun begin(event: GameEvent, attemptsUsed: Int, attemptsAllowed: Int?) {
        _active.value = EventPlay(event, attemptsUsed, attemptsAllowed)
    }

    /** Cierra el modo torneo. Idempotente. */
    fun end() {
        _active.value = null
    }

    /**
     * Torneo activo **si es de este juego**, o null.
     *
     * La comprobación del juego no es paranoia: si un torneo quedara abierto por un
     * fallo de navegación, sin ella la siguiente partida de OTRO juego intentaría
     * puntuar en él. El backend lo rechazaría (el evento fija su `game_id`), pero
     * el jugador ya habría jugado creyendo que competía.
     */
    fun activeFor(gameId: String): EventPlay? = _active.value?.takeIf { it.event.gameId == gameId }
}

/**
 * La partida de torneo en curso.
 *
 * @property event torneo que se está jugando.
 * @property attemptsUsed intentos gastados ANTES de esta partida. No se actualiza
 *   sobre la marcha: es una foto del momento de entrar, que es cuando el jugador
 *   decidió jugar y el único instante en que el dato es relevante.
 * @property attemptsAllowed cupo total del jugador (libres + extras por anuncio),
 *   o null si el torneo no limita. Es el cupo YA resuelto y no `event.attemptsLimit`
 *   precisamente porque los extras no están en el evento: son del jugador.
 */
data class EventPlay(
    val event: GameEvent,
    val attemptsUsed: Int,
    val attemptsAllowed: Int?,
)
