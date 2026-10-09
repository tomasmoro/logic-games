package com.kortexgames.app.game.legion

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.CategoryPalette
import com.kortexgames.app.core.theme.LogicColors
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.legion_hud_enemy
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/*
 * # Efectos y piezas nuevas de Neon Legion
 *
 * Lo que la pantalla ganó en el rediseño visual y no existía antes: la **pista** que corre bajo la
 * legión, los **rótulos flotantes** de tropas ganadas/perdidas, la explosión de impacto común y la
 * **barra de fuerzas** del HUD. Las capas que ya existían (puertas, enjambres, láseres) siguen en
 * `LegionScreen.kt`, junto a las constantes de geometría que comparten.
 *
 * Todo son funciones puras: reciben el estado y el reloj, no guardan nada.
 */

/** Separación entre los travesaños de la pista, en unidades de mundo (fracción del alto). */
private const val TRACK_RUNG_SPACING = 0.117f

/** Largo de un trazo de la línea divisoria de carril y de su hueco, en unidades de mundo. */
private const val TRACK_DASH = 0.055f

/** Vida del rótulo flotante de tropas, en segundos. */
internal const val TROOP_DELTA_LIFE_SEC = 0.95f

/**
 * La pista: el suelo sobre el que avanza la legión.
 *
 * Antes eran solo unas líneas verticales tenues sobre el vacío, y la carrera no se sentía carrera:
 * las puertas bajaban pero el "suelo" estaba quieto. Ahora el suelo **corre a la misma velocidad
 * que las puertas** ([scroll] avanza con la velocidad real de la ronda), así que puertas y pista se
 * mueven como un único mundo que viene hacia el jugador.
 *
 * Tres elementos, todos tenues (§9.1: el neón protagonista es el de las puertas y las naves):
 *  - **raíles** laterales de neón que delimitan la pista;
 *  - **divisorias** de carril a trazos, que se desplazan;
 *  - **travesaños** horizontales, que son los que más venden la velocidad.
 *
 * Todo se desvanece hacia arriba (el "horizonte"): da profundidad y evita que la pista compita con
 * las puertas que entran por ahí.
 *
 * @param scroll desplazamiento acumulado de la pista en unidades de mundo. La pantalla solo lo
 *   avanza durante la carrera, así que en el combate, el examen y el duelo la pista se detiene —
 *   igual que la legión.
 */
internal fun DrawScope.drawTrack(lanes: Int, scroll: Float, glowPulse: Float) {
    val accent = CategoryPalette.MentalSpeed
    val w = size.width
    val h = size.height
    val laneWidth = w / lanes

    // Suelo: un baño del acento que crece hacia el jugador.
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            0.35f to accent.copy(alpha = 0.025f),
            1f to accent.copy(alpha = 0.09f),
        ),
    )

    // Travesaños: bajan con la pista. Alfa creciente hacia abajo (más cerca = más visible).
    var y = (scroll - floor(scroll / TRACK_RUNG_SPACING) * TRACK_RUNG_SPACING)
    while (y < 1f) {
        drawLine(
            color = accent.copy(alpha = 0.03f + 0.11f * y * y),
            start = Offset(0f, y * h),
            end = Offset(w, y * h),
            strokeWidth = 1.dp.toPx(),
        )
        y += TRACK_RUNG_SPACING
    }

    // Divisorias a trazos entre carriles.
    val period = TRACK_DASH * 2f
    val dashOffset = scroll - floor(scroll / period) * period
    for (lane in 1 until lanes) {
        val x = lane * laneWidth
        var top = dashOffset - period
        while (top < 1f) {
            val from = top.coerceAtLeast(0f)
            val to = (top + TRACK_DASH).coerceAtMost(1f)
            if (to > from) {
                drawLine(
                    color = accent.copy(alpha = (0.08f + 0.34f * to) * glowPulse),
                    start = Offset(x, from * h),
                    end = Offset(x, to * h),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            top += period
        }
    }

    // Raíles laterales: tubo de neón (halo → nítido, §9.7) que nace del horizonte.
    for (x in listOf(0f, w)) {
        val rail = Brush.verticalGradient(0f to Color.Transparent, 0.5f to accent.copy(alpha = 0.55f), 1f to accent)
        drawLine(
            brush = Brush.verticalGradient(0f to Color.Transparent, 1f to accent.copy(alpha = 0.22f * glowPulse)),
            start = Offset(x, 0f),
            end = Offset(x, h),
            strokeWidth = 12.dp.toPx(),
        )
        drawLine(brush = rail, start = Offset(x, 0f), end = Offset(x, h), strokeWidth = 2.5.dp.toPx())
    }
}

/**
 * Un cambio de tropas a celebrar (o lamentar) con un rótulo flotante sobre la legión.
 *
 * @property amount tropas ganadas (positivo) o perdidas (negativo).
 * @property laneX posición horizontal de la legión cuando ocurrió, en unidades de carril.
 * @property born instante del reloj de la pantalla en que nació, en segundos.
 */
internal class TroopDelta(val amount: Int, val laneX: Float, val born: Float)

/**
 * Rótulos flotantes de tropas: el "+14" verde que sube al cruzar una buena puerta, o el "−9" rojo
 * de una mala o de un láser.
 *
 * Es el feedback que le faltaba a la decisión central del juego: el contador sobre el enjambre ya
 * cambiaba, pero el jugador tenía que restar de cabeza para saber cuánto había ganado. Aquí el
 * resultado de su elección se le dice explícitamente, con el color semántico del §9.2 — y sale
 * DESPUÉS de cruzar, así que no delata la puerta buena (ver `GATE_COLOR` en `LegionScreen.kt`).
 *
 * Sube frenando y se desvanece; entra con un pequeño "pop" de escala.
 */
internal fun DrawScope.drawTroopDeltas(
    deltas: List<TroopDelta>,
    time: Float,
    lanes: Int,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    val laneWidth = size.width / lanes
    for (delta in deltas) {
        val t = (time - delta.born) / TROOP_DELTA_LIFE_SEC
        if (t < 0f || t > 1f) continue
        val rise = 1f - (1f - t) * (1f - t)
        val color = if (delta.amount > 0) LogicColors.NeonGreen else LogicColors.Error
        // "−" tipográfico (U+2212): el guion normal se ve corto y bajo junto a los dígitos.
        val label = if (delta.amount > 0) "+${delta.amount}" else "−${-delta.amount}"
        val pop = if (t < 0.15f) 0.6f + 0.4f * (t / 0.15f) else 1f
        val layout = measurer.measure(
            AnnotatedString(label),
            style.copy(color = color.copy(alpha = (1f - t * t).coerceIn(0f, 1f)), fontSize = style.fontSize * pop),
        )
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                x = (delta.laneX + 0.5f) * laneWidth + laneWidth * 0.20f,
                y = LegionBalance.PLAYER_Y * size.height - 70.dp.toPx() - 46.dp.toPx() * rise,
            ),
        )
    }
}

/**
 * Explosión de una nave alcanzada: fogonazo, anillo de choque y cuatro chispas en cruz.
 *
 * Es el mismo efecto para el choque de ejércitos, el fuego de la nave del barrido y las andanadas
 * del Jefe (antes cada uno repetía su par de círculos), así que las tres bajas se leen igual.
 *
 * @param age progreso 0..1 de la explosión.
 * @param popRadius radio máximo del anillo, en píxeles.
 * @param seed semilla estable (índice de la nave) para girar las chispas de cada explosión.
 */
internal fun DrawScope.drawImpactPop(pos: Offset, age: Float, popRadius: Float, seed: Int) {
    val fade = 1f - age
    drawCircle(
        brush = Brush.radialGradient(
            listOf(LogicColors.Amber.copy(alpha = 0.55f * fade), Color.Transparent),
            center = pos,
            radius = popRadius * 1.3f,
        ),
        radius = popRadius * 1.3f,
        center = pos,
    )
    drawCircle(
        color = LogicColors.Amber.copy(alpha = 0.85f * fade),
        radius = popRadius * (0.35f + 0.65f * age),
        center = pos,
        style = Stroke(width = 2.dp.toPx()),
    )
    drawCircle(color = Color.White.copy(alpha = 0.95f * fade * fade), radius = popRadius * 0.36f * fade, center = pos)
    val base = seed * 1.7f
    for (k in 0 until 4) {
        val a = base + k * (PI.toFloat() / 2f)
        val inner = popRadius * (0.5f + 0.7f * age)
        val outer = inner + popRadius * 0.45f * fade
        drawLine(
            color = Color.White.copy(alpha = 0.8f * fade),
            start = Offset(pos.x + cos(a) * inner, pos.y + sin(a) * inner),
            end = Offset(pos.x + cos(a) * outer, pos.y + sin(a) * outer),
            strokeWidth = 1.4.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/**
 * **Barra de fuerzas**: tu legión contra el ejército enemigo, en una sola lectura.
 *
 * El HUD enseñaba "Enemigo 20" en una píldora y las tropas propias sobre el enjambre: el jugador
 * tenía que comparar dos números en dos sitios mientras elegía puerta. La barra hace esa cuenta
 * por él — el verde es su parte del total y la muesca central es el empate: **si el verde pasa de
 * la muesca, va ganando**. Convierte "cruza puertas" en "empuja la barra", que es un objetivo que
 * se entiende sin leer nada.
 *
 * El relleno se anima con resorte (§9.4): cada puerta cruzada "empuja" la barra a la vista.
 *
 * @param player tropas actuales de la legión.
 * @param enemy tropas del ejército enemigo de la ronda.
 */
@Composable
internal fun LegionVersusBar(player: Int, enemy: Int, modifier: Modifier = Modifier) {
    val total = (player + enemy).coerceAtLeast(1)
    val share by animateFloatAsState(
        targetValue = player.toFloat() / total,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "legionVersusShare",
    )
    val winning = player > enemy
    val enemyLabel = stringResource(Res.string.legion_hud_enemy, enemy.toString())
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = player.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = LogicColors.NeonGreen,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 34.dp),
        )
        Canvas(
            Modifier
                .weight(1f)
                .height(10.dp),
        ) {
            val corner = CornerRadius(size.height / 2f)
            val split = size.width * share.coerceIn(0.03f, 0.97f)
            // Fondo = la parte del enemigo; encima, la parte de la legión.
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(LogicColors.Magenta.copy(alpha = 0.55f), LogicColors.Magenta)),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = LogicColors.NeonGreen.copy(alpha = 0.30f),
                topLeft = Offset(0f, -3.dp.toPx()),
                size = Size(split, size.height + 6.dp.toPx()),
                cornerRadius = CornerRadius(size.height),
            )
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(LogicColors.NeonGreen, lerp(LogicColors.NeonGreen, Color.White, 0.35f)),
                    endX = split,
                ),
                size = Size(split, size.height),
                cornerRadius = corner,
            )
            // Muesca del empate: se enciende cuando la legión la ha superado.
            val mid = size.width / 2f
            drawLine(
                color = Color.White.copy(alpha = if (winning) 0.95f else 0.55f),
                start = Offset(mid, -4.dp.toPx()),
                end = Offset(mid, size.height + 4.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        Text(
            text = enemy.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = LogicColors.Magenta,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier
                .widthIn(min = 34.dp)
                .semantics { contentDescription = enemyLabel },
        )
    }
}
