package com.kortexgames.app.ui.events

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Reloj de composición para las cuentas atrás de los torneos: un [Instant] que se
 * actualiza solo con el ritmo que pida [countdownTickMillis].
 *
 * Por qué no un tick fijo de un segundo: la tarjeta de torneo vive en la Home, que
 * es la pantalla más transitada de la app. Un `delay(1000)` permanente la
 * recompondría 3.600 veces por hora para cambiar un texto que dice "2 d 4 h" —
 * batería y frames gastados en nada. El periodo se recalcula en cada vuelta a
 * partir de lo que falta, así que el segundero solo aparece en el último minuto,
 * que es cuando aporta tensión.
 *
 * Se le pasan los DOS hitos del torneo y no un objetivo ya elegido porque el hito
 * relevante cambia solo: mientras no ha empezado se cuenta a la apertura, y en
 * cuanto abre, al cierre. Resolverlo dentro del bucle es lo que hace que un torneo
 * que arranca con la Home abierta pase de "empieza en 1 min" a "termina en 3 h"
 * sin que nadie recargue la pantalla.
 *
 * @param startsAt apertura del torneo.
 * @param endsAt cierre del torneo.
 */
@Composable
fun rememberEventNow(startsAt: Instant, endsAt: Instant): State<Instant> {
    val now = remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(startsAt, endsAt) {
        while (true) {
            val current = Clock.System.now()
            now.value = current
            val remaining = (if (current < startsAt) startsAt else endsAt) - current
            // No se sale del bucle al llegar a cero: la fase del torneo la decide quien
            // pinta, y un evento que acaba de cerrar debe repintarse como "finalizado"
            // sin esperar a que el usuario vuelva a entrar. Pasado el objetivo el
            // periodo vuelve al minuto (ver countdownTickMillis).
            delay(countdownTickMillis(remaining))
        }
    }
    return now
}
