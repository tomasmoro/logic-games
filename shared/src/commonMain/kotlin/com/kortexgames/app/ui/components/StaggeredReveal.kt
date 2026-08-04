package com.kortexgames.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Retardo por posición (ms): lo que separa la entrada de una tarjeta de la siguiente. */
private const val STAGGER_STEP_MS = 80L

/** Desplazamiento vertical inicial: la tarjeta "sube" a su sitio al aparecer. */
private val RISE = 26.dp

/**
 * Entrada **escalonada** de un elemento de una lista/columna: aparece con opacidad,
 * un empujón hacia arriba y un rebote de resorte, retrasado según su [index].
 *
 * El porqué: que toda la pantalla aparezca de golpe la hace sentir estática. Con el
 * escalonado el ojo recorre la Home de arriba abajo mientras se compone, que es
 * justo el orden en que queremos que la lea. El resorte (no `tween`) da el peso
 * orgánico que pide §9.4.
 *
 * La animación se reproduce **una sola vez por instancia**: se recuerda con
 * [rememberSaveable], así que volver a la pestaña de Inicio desde el catálogo no
 * repite el baile (sería lento y repetitivo); solo lo ve quien llega desde la
 * splash, que es el momento en que aporta.
 *
 * @param index posición del elemento (0 = primero, sin retardo).
 * @param enabled si es false, el contenido se muestra ya asentado (sin animación).
 */
@Composable
fun StaggeredReveal(
    index: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    var alreadyRevealed by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (alreadyRevealed || !enabled) 1f else 0f) }

    LaunchedEffect(enabled) {
        if (!enabled || alreadyRevealed) return@LaunchedEffect
        delay(index * STAGGER_STEP_MS)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
        alreadyRevealed = true
    }

    Box(
        modifier = modifier.graphicsLayer {
            val p = progress.value
            alpha = p.coerceIn(0f, 1f)
            translationY = (1f - p) * RISE.toPx()
            // La escala arranca en 0.94: un "acercamiento" mínimo. Más sería invasivo.
            scaleX = 0.94f + 0.06f * p
            scaleY = 0.94f + 0.06f * p
        },
    ) {
        content()
    }
}
