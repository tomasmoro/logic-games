package com.kortexgames.app.game.physics

/**
 * # FrameClock — reloj de delta-time para bucles de física (`withFrameNanos`)
 *
 * Pieza **compartida** por los juegos que integran física frame a frame (hoy *Atracción
 * Geométrica* / Polarity Collision y *Hypergate*). Encapsula el único trozo de lógica de bucle
 * que ambos motores tenían idéntico y que —de copiarse— sería fácil de romper de forma sutil:
 * derivar un `dt` (delta de tiempo en segundos) sano a partir de los timestamps monotónicos que
 * entrega `androidx.compose.runtime.withFrameNanos`.
 *
 * ## Por qué existe (unificación de la lógica de movimiento)
 * Los dos juegos tienen físicas de movimiento DISTINTAS —Polarity integra fuerzas (gravedad +
 * magnetismo) en cartesiano; Hypergate resta distancia radial en polar— así que fusionar el
 * "paso de física" completo sería forzar una abstracción equivocada. Lo que sí es palabra por
 * palabra común, y por tanto lo correcto a unificar, es la **base temporal del bucle**:
 *
 *  - Guardar el timestamp del frame anterior y calcular `dt = (ahora - anterior) / 1e9`.
 *  - **Descartar el primer frame** tras arrancar/reanudar: no hay "anterior", así que no hay un
 *    `dt` válido (devolver `null`). Sin esto, el primer delta sería enorme o basura.
 *  - **Recortar el `dt`** a [maxDtSec]: si la app se congela o va a segundo plano, el frame
 *    siguiente traería un salto de cientos de ms que teletransportaría los proyectiles a través
 *    del escudo (túnel de colisión). Recortar mantiene la simulación estable y determinista.
 *  - **Reset en pausa**: al reanudar tratamos el frame como "primero" para no acumular el tiempo
 *    que la partida estuvo pausada.
 *
 * No es thread-safe por diseño: se invoca solo desde el hilo del bucle de frames (Main), igual
 * que el resto del estado del motor.
 *
 * @property maxDtSec cota superior del delta devuelto, en segundos. El valor por defecto
 *   (`0.05` = 50 ms ≈ 20 fps) es el mismo que usaba Polarity: por debajo de eso la física es
 *   fiel; por encima preferimos "cámara lenta" a un salto que rompa colisiones.
 */
class FrameClock(private val maxDtSec: Float = DEFAULT_MAX_DT_SEC) {

    /**
     * Timestamp (ns) del último frame procesado, o `null` si el próximo debe tratarse como el
     * primero (recién arrancado o reanudado). Es el único estado mutable de la clase.
     */
    private var lastFrameNanos: Long? = null

    /**
     * Registra el frame [frameNanos] y devuelve el delta en segundos transcurrido desde el frame
     * anterior, o `null` cuando no debe avanzarse la simulación este frame.
     *
     * Devuelve `null` en dos casos, ambos "no integres este frame":
     *  - es el primer frame tras [reset]/arranque (no hay referencia previa), o
     *  - el delta resultó 0 (dos callbacks con el mismo timestamp): integrar con `dt = 0` no
     *    cambia nada y solo genera una copia de estado inútil.
     *
     * En cualquier otro caso devuelve un `dt` ya recortado a `[0, maxDtSec]`, listo para
     * multiplicar por velocidades/aceleraciones.
     *
     * @param frameNanos tiempo monotónico del frame actual (el que entrega `withFrameNanos`).
     * @return delta en segundos en `(0, maxDtSec]`, o `null` si este frame no debe simularse.
     */
    fun tick(frameNanos: Long): Float? {
        val previous = lastFrameNanos
        lastFrameNanos = frameNanos
        if (previous == null) return null

        val dtSec = ((frameNanos - previous) / NANOS_PER_SECOND).coerceIn(0f, maxDtSec)
        return if (dtSec == 0f) null else dtSec
    }

    /**
     * Reinicia el reloj para que el próximo [tick] cuente como "primer frame" y devuelva `null`.
     * Debe llamarse al arrancar (`onStart`) y al reanudar (`onResume`/`onPause`) para descartar
     * el tiempo transcurrido fuera del estado RUNNING y evitar un salto de física.
     */
    fun reset() {
        lastFrameNanos = null
    }

    private companion object {
        /** Nanosegundos por segundo, en `Float` para no promover a `Double` en el `dt`. */
        const val NANOS_PER_SECOND = 1_000_000_000f

        /** 50 ms: fidelidad por debajo, "cámara lenta" segura por encima. Ver KDoc de clase. */
        const val DEFAULT_MAX_DT_SEC = 0.05f
    }
}
