package com.kortexgames.app.ui.onboarding

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.kortexgames.app.game.FirstRunGames

/**
 * # Bienvenida jugable (primera apertura)
 *
 * Estado del recorrido que hace un jugador recién instalado: la **pantalla de
 * bienvenida** ([FirstRunWelcomeScreen]) hace de **hub** entre cada uno de los
 * [FirstRunGames] —cada juego entra por su antesala habitual
 * ([com.kortexgames.app.ui.components.GameIntroScreen])— y, al despachar el último,
 * ese mismo hub lleva a la puerta de sesión. Primero se juega, después se pide la
 * cuenta.
 *
 * ## Por qué un hub y no encadenar juego→juego (ESCALABILIDAD)
 * El patrón de navegación es siempre el mismo, sin importar cuántos juegos tenga
 * [routes]: **juego → hub → siguiente juego (o login si no queda ninguno)**. Nadie
 * —ni `App.kt`, ni el hub— necesita saber que hoy son tres: el hub lee `step` y
 * `totalSteps` de esta clase y decide qué mostrar y a dónde ir. Añadir un cuarto
 * juego a [com.kortexgames.app.game.FirstRunGames.sequence] no toca ni una línea de
 * navegación.
 *
 * Es un simple contenedor de estado de UI, sin lógica de negocio: quién persiste el
 * avance y qué significa "terminar" lo decide `App.kt` a través de las lambdas.
 *
 * ## Por qué existe [LocalFirstRunFlow]
 * Los 19 juegos llaman a `GameIntroScreen` por su cuenta; la navegación no puede
 * inyectarles nada. Publicar el flujo como `CompositionLocal` permite que la antesala
 * —sea la del juego que sea— añada sola lo que la bienvenida necesita (aviso legal e
 * indicador de paso) sin tocar ni una línea de los juegos, y sin que ninguno de ellos
 * tenga que enterarse de que existe una bienvenida.
 *
 * @property routes rutas de navegación de los juegos, en orden.
 * @param initialStep cuántos juegos quedaron atrás en sesiones anteriores.
 * @param legalAlreadyAccepted si ya hay constancia de aceptación de las condiciones
 *        (reinstalación sobre una versión que sí pasó por el alta): en ese caso no se
 *        vuelve a mostrar el aviso.
 * @param onStepCompleted persiste que el juego con ese índice (0-based) quedó atrás.
 * @param onLegalAccepted registra la aceptación de condiciones y privacidad.
 * @param onSkipped persiste que el jugador renunció a la bienvenida entera.
 */
@Stable
class FirstRunFlow(
    val routes: List<String>,
    initialStep: Int,
    legalAlreadyAccepted: Boolean,
    private val onStepCompleted: (Int) -> Unit,
    private val onLegalAccepted: () -> Unit,
    private val onSkipped: () -> Unit,
) {
    /** Índice del juego que se está presentando; `>= routes.size` = bienvenida terminada. */
    var step by mutableStateOf(initialStep)
        private set

    /**
     * Si la antesala debe mostrar el aviso de condiciones/privacidad. Se apaga en
     * cuanto el jugador pulsa "Comenzar" (ese toque **es** la aceptación, patrón
     * *clickwrap*), no antes: hasta entonces el aviso tiene que seguir a la vista.
     */
    var needsLegalNotice by mutableStateOf(!legalAlreadyAccepted)
        private set

    /** Total de juegos de la bienvenida (el "de 3" del indicador). */
    val totalSteps: Int get() = routes.size

    /** `true` mientras queden juegos de bienvenida por presentar. */
    val isActive: Boolean get() = step < routes.size

    /** Ruta del juego actual, o `null` si la bienvenida ya terminó. */
    val currentRoute: String? get() = routes.getOrNull(step)

    /**
     * Índice (0-based) del juego que se **acaba de** despachar, para que el hub
     * dispare su animación de celebración justo una vez; `null` en cualquier otro
     * momento (incluida una reapertura en frío, ver [advance]). Se apaga con
     * [celebrationShown] cuando el hub ya reprodujo la animación.
     */
    var justCompletedStep: Int? by mutableStateOf(null)
        private set

    /**
     * El jugador aceptó la bienvenida ("Empezar a jugar"). Ese es, en la práctica, el
     * **primer botón que pulsa en la app**, así que es donde queda constancia de que
     * aceptó las condiciones: las tenía a la vista, junto al botón.
     */
    fun onWelcomeAccepted() = acceptLegalOnce()

    /**
     * El jugador pulsó "Comenzar" en la antesala de un juego. Normalmente el aviso ya
     * se resolvió en la bienvenida; se cubre igual por si llega aquí sin haber pasado
     * por ella (por ejemplo, retomando la bienvenida a medias tras cerrar la app).
     */
    fun onGameStarted() = acceptLegalOnce()

    /**
     * El jugador prefiere iniciar sesión ya: la bienvenida se da por despachada
     * entera. No se registra aceptación legal aquí —la pantalla de sesión tiene la
     * suya, con su casilla o su aviso—, solo se cierra el recorrido.
     */
    fun skip() {
        if (!isActive) return
        step = routes.size
        onSkipped()
    }

    /** Registra la aceptación una sola vez y retira el aviso. */
    private fun acceptLegalOnce() {
        if (!needsLegalNotice) return
        needsLegalNotice = false
        onLegalAccepted()
    }

    /**
     * Da el juego actual por despachado, pasa al siguiente y marca
     * [justCompletedStep] para que el hub, al recibirlo de vuelta, celebre ESE
     * juego concreto.
     *
     * Se llama al **salir** del juego, no al completar un nivel: la bienvenida es un
     * gancho, no una jaula. Si el jugador se va sin jugar, avanza igual — retenerlo a
     * la fuerza en un juego que no le interesa sería peor que perder ese paso.
     */
    fun advance() {
        if (!isActive) return
        val completed = step
        step = completed + 1
        justCompletedStep = completed
        onStepCompleted(completed)
    }

    /**
     * El hub ya reprodujo la celebración de [justCompletedStep]: la apaga para que
     * una recomposición posterior no la repita.
     */
    fun celebrationShown() {
        justCompletedStep = null
    }
}

/**
 * Flujo de bienvenida en curso, o `null` (lo habitual: cualquier apertura que no sea
 * la primera). Ver el KDoc de [FirstRunFlow] para el porqué de este seam.
 *
 * `static` a propósito: el valor cambia una vez por proceso como mucho, mientras que
 * lo que sí cambia —el paso actual, el aviso legal— vive en estados observables
 * dentro del propio [FirstRunFlow].
 */
val LocalFirstRunFlow = staticCompositionLocalOf<FirstRunFlow?> { null }
