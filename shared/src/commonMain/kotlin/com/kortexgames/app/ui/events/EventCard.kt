package com.kortexgames.app.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.domain.model.EventPhase
import com.kortexgames.app.domain.model.GameEvent
import com.kortexgames.app.game.GameCatalog
import com.kortexgames.app.game.GameInfo
import com.kortexgames.app.ui.components.GameMotifIcon
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.bounceClick
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.event_state_finished
import kortexgames.shared.generated.resources.event_state_live
import kortexgames.shared.generated.resources.event_state_upcoming
import org.jetbrains.compose.resources.stringResource

/**
 * Tarjeta de torneo para la Home: qué juego, en qué estado, cuánto queda y un
 * toque para entrar.
 *
 * ## Decisiones
 * - **Se ve el juego, no un trofeo genérico.** El motivo propio del juego en
 *   disputa ([GameMotifIcon], el mismo arte que su tarjeta del catálogo) va de
 *   **fondo, sangrando por el borde derecho**: identifica el torneo de un vistazo
 *   sin robarle sitio al texto ni convertirse en un segundo elemento que mirar. Un
 *   icono de torneo habría dicho "esto es una competición" pero no *de qué*, que es
 *   lo que decide si el jugador entra. Si el juego no tuviera motivo propio se cae
 *   al icono de su categoría.
 * - **Se lee como un botón.** Una tarjeta con borde y texto es un cartel; lo que
 *   dice "esto se toca" es la **flecha de la derecha**, el mismo gesto visual que
 *   ya usan las filas navegables de la app. Va con halo neón sobre el motivo, así
 *   que se separa del fondo sin necesitar un botón propio — y el objetivo del
 *   toque sigue siendo la tarjeta entera, que es mucho más fácil de acertar que
 *   una píldora.
 * - **Acento del juego, no un color de "torneos".** El color sale de la categoría
 *   del juego (`GameCategory.accent`), igual que en el catálogo: el jugador
 *   reconoce de qué va el torneo por el color antes de leer.
 * - **Sin `pulse()` ni `softGlow()`.** El latido está reservado al CTA principal de
 *   la Home ("jugar"): dos elementos latiendo compiten por la atención y ninguno
 *   gana (§9.4, regla 5). Aquí el movimiento lo pone la cuenta atrás.
 * - **La cuenta atrás manda sobre la fase.** El estado se recalcula con el reloj de
 *   composición, así que un torneo que abre mientras la Home está en pantalla pasa
 *   de "PRÓXIMO" a "EN DIRECTO" —y el botón de "ver" a "entrar"— sin que el usuario
 *   haga nada.
 *
 * @param event torneo a anunciar.
 * @param onOpen abre el detalle. La tarjeta NO lanza la partida directamente: el
 *   jugador debe poder leer las reglas (tablero fijo, intentos) antes de gastar uno.
 */
@Composable
fun EventCard(
    event: GameEvent,
    onOpen: (GameEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now by rememberEventNow(startsAt = event.startsAt, endsAt = event.endsAt)
    val phase = event.phaseAt(now)
    val game = GameCatalog.byId(event.gameId)
    val accent = game?.category?.accent ?: LogicColors.Amber

    Box(
        modifier = modifier
            .fillMaxWidth()
            .bounceClick { onOpen(event) }
            .clip(CARD_SHAPE)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        LogicColors.SurfaceDark,
                        accent.copy(alpha = ACCENT_WASH_ALPHA),
                    ),
                ),
            )
            .border(1.dp, accent.copy(alpha = BORDER_ALPHA), CARD_SHAPE),
    ) {
        // Fondo: el arte del juego, grande y cortado por el borde. El `clip` de la
        // tarjeta lo recorta, así que la sangría no se sale de la esquina redonda.
        EventMotifBackdrop(game = game, accent = accent, modifier = Modifier.align(Alignment.CenterEnd))

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PhaseChip(phase = phase, accent = accent)

                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = LogicColors.OnDark,
                )

                event.subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LogicColors.OnDarkMuted,
                    )
                }

                event.countdownLabel(now)?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                    )
                }
            }

            // La señal de "esto se abre". Con halo (`glow`) para despegarla del
            // motivo que tiene detrás; es lo único de la tarjeta pintado con el
            // acento a plena saturación, y por eso donde cae el ojo (§9.1).
            NeonIcon(
                icon = KortexIcons.ChevronRight,
                tint = accent,
                size = CHEVRON_SIZE,
                contentDescription = null,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/**
 * El arte del juego como fondo de la tarjeta: grande, tenue y sangrando por el
 * borde derecho.
 *
 * Reutiliza el motivo del catálogo en vez de un icono propio porque el jugador ya
 * asocia ese dibujo con ese juego; estrenar una ilustración distinta para el mismo
 * juego solo rompería ese reconocimiento.
 *
 * La opacidad es baja a propósito: por encima van el título, la cuenta atrás y la
 * flecha, y un fondo que compita con ellos convierte la tarjeta en ruido. Es
 * ambientación, no contenido — quien no lo mire no se pierde nada.
 */
@Composable
private fun EventMotifBackdrop(game: GameInfo?, accent: Color, modifier: Modifier = Modifier) {
    val motif = game?.motif
    Box(
        modifier = modifier
            .size(MOTIF_SIZE)
            // Desplazado hacia fuera: se ve un trozo del dibujo, no una estampa
            // centrada que pareciera un segundo elemento de la interfaz.
            .offset(x = MOTIF_BLEED)
            .alpha(MOTIF_ALPHA),
        contentAlignment = Alignment.Center,
    ) {
        when {
            motif != null -> GameMotifIcon(motif = motif, accent = accent, modifier = Modifier.fillMaxSize())
            game != null -> NeonIcon(icon = game.category.icon, tint = accent, size = MOTIF_SIZE * 0.45f, glow = false, contentDescription = null)
            // Torneo de un juego que este cliente no conoce (catálogo más nuevo en el
            // backend): el trofeo al menos dice "competición" en vez de dejar un hueco.
            else -> NeonIcon(icon = KortexIcons.Trophy, tint = accent, size = MOTIF_SIZE * 0.45f, glow = false, contentDescription = null)
        }
    }
}

/** Etiqueta de estado: el dato que decide si esto es urgente o solo un aviso. */
@Composable
private fun PhaseChip(phase: EventPhase, accent: Color) {
    val (label, color) = when (phase) {
        // "En directo" toma el acento del juego a plena intensidad; es lo único de la
        // tarjeta, junto al CTA, que pide acción AHORA.
        EventPhase.LIVE -> stringResource(Res.string.event_state_live) to accent
        EventPhase.UPCOMING -> stringResource(Res.string.event_state_upcoming) to LogicColors.OnDarkMuted
        EventPhase.FINISHED -> stringResource(Res.string.event_state_finished) to LogicColors.OnDarkMuted
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = CHIP_BG_ALPHA), RoundedCornerShape(12.dp))
            .padding(PaddingValues(horizontal = 10.dp, vertical = 4.dp)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
            ),
            color = color,
        )
    }
}

/** Radio de tarjeta del sistema de diseño (§9.6: tarjetas a 24dp). */
private val CARD_SHAPE = RoundedCornerShape(24.dp)

/** Tamaño del arte de fondo. Generoso: va a verse cortado por el borde. */
private val MOTIF_SIZE = 150.dp

/** Cuánto se sale por la derecha (sangrado). */
private val MOTIF_BLEED = 34.dp

/** La flecha: grande para que se lea como afordancia, no como decoración. */
private val CHEVRON_SIZE = 28.dp

/** Lavado de color del acento sobre la superficie: presencia sin gritar (§9.1). */
private const val ACCENT_WASH_ALPHA = 0.18f
private const val BORDER_ALPHA = 0.45f
private const val CHIP_BG_ALPHA = 0.16f

/** Opacidad del arte de fondo: presente, nunca protagonista. */
private const val MOTIF_ALPHA = 0.22f
