package com.kortexgames.app.game

/**
 * Contrato **opcional** para motores que soportan reanudar una partida guardada al
 * salir (en vez de regenerar el nivel desde cero). No todos los juegos lo necesitan
 * (p. ej. los ENDLESS, donde cada corrida empieza de cero por diseño); un
 * [BaseGameEngine] se apunta a esta función implementándolo además de su motor base,
 * igual que un juego se apunta a la pausa compartida cableando
 * [com.kortexgames.app.ui.components.GamePauseControls] en su pantalla.
 *
 * @param S mismo tipo de estado que [GameEngine.state].
 */
interface ResumableGameEngine<S : Any> {
    /**
     * Reanuda la partida desde [saved] en vez de generar un nivel nuevo. Debe dejar
     * el motor en [GameStatus.RUNNING] (normalmente delegando en [GameEngine.start]),
     * de modo que el resto del ciclo de vida (cronómetro, `finish()`, etc.) se
     * comporte igual que cualquier partida arrancada normalmente.
     */
    fun resumeFrom(saved: S)
}
