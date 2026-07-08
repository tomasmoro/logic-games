package com.example.kortexgames.ui.games

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.GameCatalog
import com.example.kortexgames.game.GameInfo
import com.example.kortexgames.game.GameProgressions
import com.example.kortexgames.ui.components.CategoryMotifSurface
import com.example.kortexgames.ui.components.NeonIcon
import com.example.kortexgames.ui.navigation.Routes
import kotlinx.coroutines.delay

/**
 * Catálogo de juegos en cuadrícula. Cada tarjeta lleva el color de su categoría,
 * bordes muy redondeados y una ligera elevación. Al abrirse la pantalla, las
 * tarjetas **aparecen escalonadas** con fade-in + deslizamiento hacia arriba
 * (efecto "cascada") — CLAUDE.md §9.4.
 *
 * @param onOpenGame recibe la RUTA de navegación del juego tocado (solo jugables).
 */
@Composable
fun GameListScreen(
    graph: AppGraph,
    onOpenGame: (String) -> Unit,
) {
    val games = remember { GameCatalog.games }

    // Récord por juego (local-first, sincronizado). Se observa una sola vez para
    // toda la cuadrícula y se indexa por gameId; cada tarjeta lee el suyo.
    val records by graph.playerProgressRepository.observeAll()
        .collectAsStateWithLifecycle(emptyList())
    val bestByGame = remember(records) { records.associate { it.gameId to it.bestMetric } }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Cabecera a todo el ancho (ocupa las dos columnas).
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Catálogo", style = MaterialTheme.typography.headlineLarge, color = LogicColors.OnDark)
                Text(
                    "Elige un juego y entrena una habilidad",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.OnDarkMuted,
                )
            }
        }

        itemsIndexed(games) { index, game ->
            GameCard(
                game = game,
                index = index,
                // Texto del récord ("Nivel máx 7" / "Mejor 320 ms") o null si aún
                // no hay marca. Se resuelve con la progresión del juego.
                recordText = game.id
                    ?.let { id -> bestByGame[id]?.let { GameProgressions.forId(id)?.formatRecord(it) } },
                onOpen = { Routes.gameRoute(game.id)?.let(onOpenGame) },
            )
        }
    }
}

/**
 * Tarjeta de juego. Colorea el fondo con el acento de la categoría (degradado
 * sutil), redondea fuerte y eleva. Anima su entrada con un retardo proporcional
 * al [index] para lograr el efecto cascada. Los juegos aún no disponibles se
 * muestran atenuados y con etiqueta "Próximamente".
 */
@Composable
private fun GameCard(
    game: GameInfo,
    index: Int,
    recordText: String?,
    onOpen: () -> Unit,
) {
    // Aparición escalonada: cada tarjeta se revela un poco después que la anterior.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 45L)
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 4 },
    ) {
        val accent = game.category.accent
        val shape = RoundedCornerShape(24.dp)
        CategoryMotifSurface(
            category = game.category,
            shape = shape,
            onClick = onOpen,
            enabled = game.playable,
            bgTopAlpha = 0.32f,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .shadow(elevation = 8.dp, shape = shape, clip = false)
                .alpha(if (game.playable) 1f else 0.55f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                NeonIcon(icon = game.category.icon, tint = accent, size = 32.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        game.category.displayName.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        game.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = LogicColors.OnDark,
                    )
                    when {
                        !game.playable -> Text(
                            "Próximamente",
                            style = MaterialTheme.typography.labelMedium,
                            color = LogicColors.OnDarkMuted,
                        )
                        // Récord del jugador teñido con el acento de la categoría:
                        // recompensa visible sin saturar (el neón brilla por escaso).
                        recordText != null -> Text(
                            recordText,
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(LogicColors.SurfaceVariantDark)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}
