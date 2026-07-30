package com.kortexgames.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.ui.components.clickableNoRipple

/**
 * Barra de navegación inferior **personalizada** (no `NavigationBar`) para
 * controlar cada micro-animación (CLAUDE.md §9.4). Al seleccionar una pestaña:
 *
 *  - aparece un **indicador** (píldora neón) encima del icono, cuyo ancho se anima,
 *  - el **icono** crece ligeramente y transiciona a color de acento (con glow),
 *  - la **etiqueta** cambia de color hacia el mismo acento.
 *
 * Usa iconos vectoriales (nunca emojis).
 *
 * @param currentRoute ruta activa; determina qué pestaña está seleccionada.
 * @param onSelect se invoca con la pestaña tocada (la navegación real la hace el host).
 */
@Composable
fun AnimatedBottomBar(
    currentRoute: String?,
    onSelect: (TopLevelTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = LogicColors.SurfaceDark,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // La Surface exterior llena hasta el borde físico (para que su color
                // de fondo tape la barra de navegación de 3 botones, que con
                // enableEdgeToEdge() es transparente); el contenido en cambio se
                // levanta por encima de esa barra para no quedar tapado por los
                // botones del sistema.
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevelTab.entries.forEach { tab ->
                BottomBarItem(
                    tab = tab,
                    selected = currentRoute == tab.route,
                    onClick = { onSelect(tab) },
                )
            }
        }
    }
}

/** Ítem individual de la barra, con sus animaciones de selección. */
@Composable
private fun BottomBarItem(
    tab: TopLevelTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = LogicColors.NeonCyan

    // 1) Indicador superior: crece de 0dp a 26dp de ancho al seleccionar.
    val indicatorWidth by animateDpAsState(
        targetValue = if (selected) 26.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tabIndicatorWidth",
    )
    // 2) El icono crece (spring → sensación táctil).
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.18f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "tabIconScale",
    )
    // 3) Icono y etiqueta transicionan a color de acento.
    val contentColor by animateColorAsState(
        targetValue = if (selected) accent else LogicColors.OnDarkMuted,
        label = "tabContentColor",
    )

    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickableNoRipple(interaction, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Indicador (píldora neón) encima del icono.
        Box(
            modifier = Modifier
                .width(indicatorWidth)
                .height(3.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(LogicGradients.energy)),
        )
        Spacer(Modifier.height(6.dp))
        Box(contentAlignment = Alignment.Center) {
            // Halo neón sutil solo cuando está activa.
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(accent.copy(alpha = 0.28f), androidx.compose.ui.graphics.Color.Transparent),
                            ),
                        ),
                )
            }
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = contentColor,
                modifier = Modifier
                    .size(26.dp)
                    .scale(iconScale),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = tab.label,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
