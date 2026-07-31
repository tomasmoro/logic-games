package com.kortexgames.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.game.GameCategory
import com.kortexgames.app.game.GameMotif
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Duración del resplandor de expansión al pulsar (ms). */
private const val EXPAND_MS = 520

/** Espera antes de navegar, para que se vea la expansión (ms). */
private const val EXPAND_NAV_DELAY_MS = 300L

/**
 * Velo de contraste que [CategoryMotifSurface] pinta sobre el motivo para que el
 * texto se lea por brillante que sea el fondo temático.
 *
 * Es un enum y no un booleano porque **un velo solo protege si cae debajo del
 * texto**: la variante correcta depende de dónde viva el texto en la tarjeta, y
 * oscurecerla entera para cubrir todos los casos mataría el motivo (que es
 * precisamente lo que identifica al juego).
 */
enum class MotifScrim {
    /** Sin velo: la tarjeta no lleva texto sobre el motivo. */
    None,

    /** Transparente → oscuro hacia ABAJO. Para tarjetas con el texto al pie. */
    Bottom,

    /**
     * Oscuro → transparente hacia la DERECHA. Para tarjetas **anchas** con el
     * texto a la izquierda y el motivo escorado a la derecha (fila del catálogo).
     */
    Start,
}

/**
 * Contenedor de tarjeta de categoría: fondo con degradado de acento + textura
 * temática ([CategoryTexture]) + **animación de expansión al pulsar** (un
 * resplandor radial que crece del centro hacia afuera y hace "brillar más" el
 * fondo y la tarjeta). El contenido se pasa como slot ([content]).
 *
 * La navegación ([onClick]) se retrasa [EXPAND_NAV_DELAY_MS] ms para que la
 * expansión sea visible antes de salir de la pantalla. Ignora toques repetidos
 * mientras la animación está en curso.
 *
 * @param enabled si false, no responde a clicks ni anima (p. ej. juego bloqueado).
 * @param bgTopAlpha opacidad del acento en la parte superior del degradado de fondo.
 * @param motif motivo visual propio del juego para el fondo; null = usa el de la
 *   categoría (útil para las tarjetas de categoría, que no representan un juego).
 * @param scrim velo de contraste bajo el texto; ver [MotifScrim].
 * @param squareMotif si true, el motivo se dibuja en un recuadro **cuadrado**
 *   anclado al borde derecho en vez de ocupar toda la tarjeta. Las texturas de
 *   [CategoryTexture] colocan sus elementos como fracción del ANCHO pero los
 *   escalan con `minDimension`: en una tarjeta ancha eso los dispersa
 *   horizontalmente sin agrandarlos y el arte pierde cohesión. Acotarlo a un
 *   cuadrado le devuelve la proporción ~1:1 para la que fue diseñado y, de paso,
 *   despeja la mitad izquierda para el texto.
 * @param content contenido de la tarjeta (icono, textos…), ya con su propio padding.
 */
@Composable
fun CategoryMotifSurface(
    category: GameCategory,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    bgTopAlpha: Float = 0.18f,
    motif: GameMotif? = null,
    scrim: MotifScrim = MotifScrim.None,
    squareMotif: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val glow = remember { Animatable(0f) } // 0 = reposo; anima 0→1 al pulsar
    var busy by remember { mutableStateOf(false) }

    val clickable = if (enabled) {
        Modifier.bounceClick {
            if (!busy) {
                busy = true
                scope.launch {
                    // La expansión corre en paralelo a la espera previa a navegar.
                    launch {
                        glow.snapTo(0f)
                        glow.animateTo(1f, tween(EXPAND_MS, easing = FastOutSlowInEasing))
                        glow.snapTo(0f)
                    }
                    delay(EXPAND_NAV_DELAY_MS)
                    onClick()
                    busy = false
                }
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(category.accent.copy(alpha = bgTopAlpha), LogicColors.SurfaceDark),
                ),
            )
            .then(clickable),
    ) {
        // Fondo temático; se ilumina con el "boost" durante la expansión.
        CategoryTexture(
            category = category,
            modifier = if (squareMotif) {
                Modifier.fillMaxHeight().aspectRatio(1f).align(Alignment.CenterEnd)
            } else {
                Modifier.matchParentSize()
            },
            boost = glow.value,
            motif = motif,
        )

        // Velo de contraste: protege la legibilidad del texto sobre el motivo,
        // dejando transparente la zona donde vive el arte (ver [MotifScrim]).
        when (scrim) {
            MotifScrim.None -> Unit

            MotifScrim.Bottom -> Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Transparent,
                            1f to LogicColors.BackgroundDark.copy(alpha = 0.72f),
                        ),
                    ),
            )

            MotifScrim.Start -> Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to LogicColors.BackgroundDark.copy(alpha = 0.80f),
                            0.55f to LogicColors.BackgroundDark.copy(alpha = 0.30f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
        }

        // Resplandor de expansión: círculo radial que crece del centro y se apaga.
        Canvas(modifier = Modifier.matchParentSize()) {
            val p = glow.value
            if (p > 0f && p < 1f) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = (size.maxDimension * 0.85f * p).coerceAtLeast(1f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            category.accent.copy(alpha = (1f - p) * 0.5f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
        }

        content()
    }
}
