package com.kortexgames.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.kortexgames.app.core.theme.LogicColors

/**
 * Una opción del [DifficultyGateSelector].
 *
 * @property label rótulo del escalón ("Experto", "5×5").
 * @property details líneas cortas bajo el rótulo con lo que el jugador se va a encontrar
 *   ("10×14", "37 minas"). Van como **líneas cortas fijas** y no como un único texto con
 *   separadores porque una línea larga se parte donde el layout decida —a veces a mitad de
 *   un número— y descuadra la altura de los chips, que deben quedar todos iguales.
 */
data class DifficultyOption(
    val label: String,
    val details: List<String> = emptyList(),
)

/**
 * **Selector de dificultad con escalones bloqueados**, común a los juegos que dejan elegir
 * antes de empezar (Neon Defuser, Neon Sudoku Matrix, Neon Grid 2048). Se pinta dentro del
 * `configContent` de [GameIntroScreen], justo encima del CTA.
 *
 * Los escalones por encima de [unlockedTiers] se muestran **apagados y con candado**, con el
 * mismo lenguaje visual que los niveles bloqueados del carril de la antesala (gris en vez de
 * acento, candado, sin respuesta táctil): el jugador ve lo que le espera —y por tanto tiene
 * motivo para volver— sin poder saltárselo. Debajo, una línea explica qué falta para abrir el
 * siguiente ([hint]); sin ella, un chip con candado sería una puerta sin instrucciones.
 *
 * Es un componente **sin estado de negocio**: quién está desbloqueado lo decide
 * [com.kortexgames.app.game.DifficultyUnlocks] a partir del historial de partidas, y el
 * ViewModel lo pasa ya resuelto.
 *
 * @param title rótulo del bloque ("DIFICULTAD", "TAMAÑO DEL TABLERO").
 * @param options escalones en orden, del más fácil al más difícil.
 * @param selectedIndex índice (0-based) del escalón elegido.
 * @param unlockedTiers cuántos escalones están abiertos (1-based: `1` = solo el primero).
 * @param onSelect se invoca con el índice (0-based) de un escalón **desbloqueado**; los
 *   bloqueados no son pulsables, así que nunca llega uno por aquí.
 * @param accent color de acento del juego (el del selector elegido).
 * @param hint qué falta para abrir el siguiente escalón; `null` = ya están todos.
 * @param equalWidth true reparte el ancho a partes iguales entre los chips (necesario cuando
 *   llevan [DifficultyOption.details] de longitud dispar: si cada chip se ajusta a su propio
 *   contenido, el más largo envuelve en varias líneas y descuadra la tarjeta).
 */
@Composable
fun DifficultyGateSelector(
    title: String,
    options: List<DifficultyOption>,
    selectedIndex: Int,
    unlockedTiers: Int,
    onSelect: (Int) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    hint: String? = null,
    equalWidth: Boolean = false,
) {
    Column(
        modifier = modifier
            .background(LogicColors.SurfaceDark.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
            .border(BorderStroke(1.dp, LogicColors.SurfaceVariantDark), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = LogicColors.OnDarkMuted,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.then(if (equalWidth) Modifier.fillMaxWidth() else Modifier)
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { index, option ->
                key(option.label) {
                    DifficultyGateChip(
                        option = option,
                        selected = index == selectedIndex,
                        locked = index + 1 > unlockedTiers,
                        accent = accent,
                        onClick = { onSelect(index) },
                        modifier = if (equalWidth) Modifier.weight(1f) else Modifier,
                    )
                }
            }
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelMedium,
                color = LogicColors.OnDarkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * Un chip del [DifficultyGateSelector]: acento cuando está elegido, gris apagado con candado
 * cuando está bloqueado.
 *
 * El bloqueado **conserva el mismo contenido** (rótulo y detalles) atenuado, con el candado
 * junto al rótulo, en vez de sustituirlo por un icono suelto: así todos los chips de la fila
 * miden exactamente igual —cambiar el contenido cambiaría su altura— y el jugador sigue
 * viendo a qué reto está optando.
 */
@Composable
private fun DifficultyGateChip(
    option: DifficultyOption,
    selected: Boolean,
    locked: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    // Bloqueado: sin acento y a media luz — todavía no forma parte del "juego" (mismo
    // criterio que los niveles bloqueados del carril de la antesala).
    val labelColor = when {
        locked -> LogicColors.OnDarkMuted.copy(alpha = 0.55f)
        selected -> accent
        else -> LogicColors.OnDarkMuted
    }
    val detailColor = if (locked) LogicColors.OnDarkMuted.copy(alpha = 0.4f) else LogicColors.OnDarkMuted
    Column(
        modifier = modifier
            // `bounceClick` (scale) va ANTES de clip/background/border —igual que
            // `AnimatedGameButton`—: si el scale queda detrás de esos modificadores de
            // dibujo en la cadena, su capa (graphicsLayer) los deja fuera y el borde/fondo
            // puede quedarse pintado con el valor viejo al cambiar `selected`.
            .bounceClick(enabled = !locked, onClick = onClick)
            .clip(shape)
            .background(
                when {
                    locked -> LogicColors.SurfaceVariantDark.copy(alpha = 0.5f)
                    selected -> accent.copy(alpha = 0.22f)
                    else -> LogicColors.SurfaceVariantDark
                },
            )
            .border(
                BorderStroke(
                    width = if (selected && !locked) 1.5.dp else 1.dp,
                    color = when {
                        locked -> LogicColors.OnDarkMuted.copy(alpha = 0.12f)
                        selected -> accent
                        else -> LogicColors.OnDarkMuted.copy(alpha = 0.2f)
                    },
                ),
                shape,
            )
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (locked) {
                NeonIcon(
                    icon = KortexIcons.Lock,
                    tint = LogicColors.OnDarkMuted.copy(alpha = 0.55f),
                    size = 12.dp,
                    glow = false,
                    contentDescription = "Bloqueado",
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                option.label,
                style = MaterialTheme.typography.labelLarge,
                color = labelColor,
                fontWeight = if (selected && !locked) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
            )
        }
        option.details.forEach { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.labelMedium,
                color = detailColor,
                maxLines = 1,
            )
        }
    }
}
