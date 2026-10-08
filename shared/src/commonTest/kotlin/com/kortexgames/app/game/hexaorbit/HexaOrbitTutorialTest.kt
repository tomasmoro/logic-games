package com.kortexgames.app.game.hexaorbit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El tutorial de Hexa Orbit no es una animación dibujada: simula un tablero concreto con las
 * reglas del juego y deriva de ahí su guion (ver [HexaOrbitTutorial]). Estos tests comprueban que
 * ese tablero **sigue contando la historia que cuentan los textos**. Si alguien cambia un patrón
 * de pieza, la longitud de una curva o [HexaOrbitBalance.ESCAPE_ALERT_TILES], el tutorial podría
 * seguir compilando y enseñar otra cosa (o reventar al abrir la antesala); aquí se detecta.
 */
class HexaOrbitTutorialTest {

    private val script = HexaOrbitTutorial.SCRIPT

    /** Estado del haz cuando el puntero entra en su tramo nº [step], sobre el tablero [board]. */
    private fun beamAt(board: HexBoard, step: Int): LookaheadPath =
        board.project(script.steps[step].coord, script.steps[step].entryEdge)

    private fun stepAt(seconds: Float): Int = script.startsAt.indexOfLast { it <= seconds }.coerceAtLeast(0)

    @Test
    fun losHitosDelGuionOcurrenEnOrden() {
        assertTrue(script.orbTapAt < script.orbAt, "el orbe se recoge después del primer giro")
        assertTrue(script.orbAt < script.alarmAt, "la alarma salta después de recoger el orbe")
        assertTrue(script.alarmAt < script.rescueTapAt, "el segundo giro llega con la alarma ya encendida")
    }

    @Test
    fun ningunPasoQuedaRecortadoAlMinimo() {
        // `ms()` recorta a 2 s cualquier duración derivada demasiado corta: si pasa, el guion
        // ya no cuadra con el recorrido y hay que revisar el tablero.
        HexaOrbitTutorial.tutorial.steps.forEach { assertTrue(it.durationMs > 2_400, "paso de ${it.durationMs} ms") }
    }

    @Test
    fun elHazNoAlarmaAntesDeTiempo() {
        for (step in 0 until stepAt(script.alarmAt)) {
            val board = if (script.startsAt[step] >= script.orbTapAt) script.boardAfterOrbTap else script.initialBoard
            assertFalse(beamAt(board, step).imminent, "alarma prematura en el tramo $step")
        }
    }

    @Test
    fun elHazSePoneRojoYElSegundoGiroLoArregla() {
        val alarmStep = stepAt(script.alarmAt)
        val rescueStep = stepAt(script.rescueTapAt)
        for (step in alarmStep..rescueStep) {
            assertTrue(beamAt(script.boardAfterOrbTap, step).imminent, "sin alarma en el tramo $step")
        }
        assertFalse(beamAt(script.boardAfterRescue, rescueStep).imminent, "el giro no apaga la alarma")
        // Y el puntero ya no se sale: el recorrido simulado dura mucho más que el tutorial.
        assertFalse(
            script.boardAfterRescue.project(script.steps[rescueStep].coord, script.steps[rescueStep].entryEdge, maxTiles = 40).escapes,
        )
    }

    @Test
    fun elDedoNuncaGiraLaPiezaQueElPunteroOcupa() {
        // El motor bloquea el giro de la pieza ocupada: un tutorial que la girase mentiría.
        val rotatedFirst = script.boardAfterOrbTap.tiles.values.first { it != script.initialBoard.tileAt(it.coord) }.coord
        val rotatedSecond = script.boardAfterRescue.tiles.values.first { it != script.boardAfterOrbTap.tileAt(it.coord) }.coord
        assertTrue(script.steps[stepAt(script.orbTapAt)].coord != rotatedFirst)
        assertTrue(script.steps[stepAt(script.rescueTapAt)].coord != rotatedSecond)
    }

    @Test
    fun elPunteroPasaPorElOrbe() {
        val crossing = stepAt(script.orbAt) - 1
        val end = pointOnStep(script.steps[crossing], 1f)
        assertTrue(end.distanceTo(script.orb.position) <= HexaOrbitBalance.COLLECT_RADIUS)
    }
}
