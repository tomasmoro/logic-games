package com.kortexgames.app.ui.games

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameCatalog
import com.kortexgames.app.game.GameCategory
import com.kortexgames.app.game.GameInfo
import com.kortexgames.app.game.GameProgressions
import com.kortexgames.app.ui.components.CategoryMotifSurface
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.MotifScrim
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.NewBadge
import com.kortexgames.app.ui.components.bounceClick
import com.kortexgames.app.ui.navigation.Routes
import kotlinx.coroutines.delay

/**
 * Catálogo de juegos **agrupado por categoría**: pills de filtro arriba y, debajo,
 * una sección por categoría (rótulo + contador) con sus juegos en una fila
 * horizontal. Antes era una única columna con los ~29 juegos mezclados uno debajo
 * de otro; con el catálogo creciendo hacia los 30 de la visión de producto esa
 * lista se volvía larga y no comunicaba de un vistazo "cuánto hay de cada cosa" (ver
 * CLAUDE.md §1). Agrupar por [GameCategory] resuelve ambas cosas sin tocar el
 * catálogo de dominio: toda la información (categoría, color, icono, motivo,
 * "NUEVO"...) ya vivía en [GameCatalog]/[GameInfo], así que este archivo es
 * puramente de presentación.
 *
 * El lenguaje visual de cada tarjeta ([CompactGameCard]) es el mismo que tenía la
 * fila ancha que reemplaza: acento de categoría, motivo temático propio del juego,
 * insignia "NUEVO", candado + "Próximamente" en lo no jugable, récord del jugador.
 * Solo cambia el REPARTO (vertical y compacta, para caber varias por fila) y se cae
 * la etiqueta de categoría repetida en cada tarjeta —ya la dice el rótulo de la
 * sección— y el chevrón —la propia insignia/motivo ya comunica "esto es un juego,
 * tócalo"—.
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
    // toda la pantalla y se indexa por gameId; cada tarjeta lee el suyo.
    val records by graph.playerProgressRepository.observeAll()
        .collectAsStateWithLifecycle(emptyList())
    val bestByGame = remember(records) { records.associate { it.gameId to it.bestMetric } }

    // Tarjetas cuya animación de entrada YA se reprodujo. Vive en la pantalla y no
    // dentro de [CompactGameCard] a propósito: las `LazyRow` destruyen los ítems que
    // salen de vista, así que un flag interno se perdería y la cascada se
    // relanzaría cada vez que una tarjeta vuelve a entrar en pantalla al scrollear.
    val revealed = remember { mutableSetOf<String>() }

    // Agrupado por categoría y ordenado por VOLUMEN (más juegos primero): así las
    // pills y las secciones abren con lo que más contenido tiene para ofrecer, en
    // vez del orden arbitrario de declaración de la enum [GameCategory] — no el
    // orden "mezclado a propósito" de [GameCatalog.allGames] tampoco, pensado para
    // una lista plana y que aquí no aporta nada. `sortedByDescending` es estable:
    // dos categorías con el mismo número de juegos conservan el orden de la enum
    // entre sí, así que el resultado es determinista. Solo entran categorías con al
    // menos un juego publicado: una categoría vacía no tiene sección que mostrar.
    val gamesByCategory = remember(games) { games.groupBy { it.category } }
    val categoriesWithGames = remember(gamesByCategory) {
        GameCategory.entries
            .filter { gamesByCategory.containsKey(it) }
            .sortedByDescending { gamesByCategory.getValue(it).size }
    }

    // null = "Todos" (todas las secciones); si no, se muestra solo la categoría elegida.
    var selectedCategory by remember { mutableStateOf<GameCategory?>(null) }
    val sections = if (selectedCategory == null) categoriesWithGames else listOf(selectedCategory!!)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Catálogo", style = MaterialTheme.typography.headlineLarge, color = LogicColors.OnDark)
                Text(
                    "Elige un juego y entrena una habilidad",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.OnDarkMuted,
                )
            }
        }

        // Pills de filtro: "Todos" + una por categoría CON juegos (filtrar antes de
        // pintar, no al pulsar, evita un pill que llevaría a una sección vacía).
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "all") {
                    CategoryFilterPill(
                        label = "Todos",
                        // Sin categoría propia a la que asociarse, toma el morado de
                        // marca secundaria (CLAUDE.md §9.2) en vez de un color robado
                        // a una categoría concreta.
                        color = LogicColors.Violet,
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                    )
                }
                items(categoriesWithGames, key = { it.name }) { category ->
                    CategoryFilterPill(
                        label = category.displayName,
                        color = category.accent,
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                    )
                }
            }
        }

        sections.forEach { category ->
            val categoryGames = gamesByCategory.getValue(category)
            item(key = "header-${category.name}") {
                CategorySectionHeader(
                    category = category,
                    count = categoryGames.size,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            item(key = "row-${category.name}") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Clave estable para no heredar el estado de cascada de otra
                    // tarjeta al reciclar (los títulos son únicos en el catálogo).
                    itemsIndexed(categoryGames, key = { _, game -> game.id ?: game.title }) { index, game ->
                        val key = game.id ?: game.title
                        CompactGameCard(
                            game = game,
                            index = index,
                            recordText = game.id
                                ?.let { id -> bestByGame[id]?.let { GameProgressions.forId(id)?.formatRecord(it) } },
                            alreadyRevealed = key in revealed,
                            onRevealed = { revealed += key },
                            onOpen = { Routes.gameRoute(game.id)?.let(onOpenGame) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sombra sutil detrás del texto de la tarjeta: un halo oscuro que refuerza el
 * contraste del título sobre el motivo del fondo (además del velo inferior de
 * [CategoryMotifSurface]). El desenfoque le da un borde suave, no una sombra dura
 * tipo "drop shadow".
 */
private val TextScrimShadow = Shadow(
    color = LogicColors.BackgroundDark,
    offset = Offset(0f, 1f),
    blurRadius = 6f,
)

/** Ancho fijo de [CompactGameCard]: caben ~2.2 por pantalla, como el resto de filas horizontales de la app. */
private val CardWidth = 152.dp

/** Alto fijo de [CompactGameCard] (ver "Cómo se evita que el texto se corte" en su KDoc). */
private val CardHeight = 178.dp

/**
 * Pill de filtro de categoría: **seleccionado** → relleno sólido en [color] con
 * texto oscuro (mismo lenguaje que un CTA); **no seleccionado** → contorno fino en
 * [color] sobre un fondo translúcido de ese mismo color, texto en [color]. Mismo
 * criterio activo/inactivo que [com.kortexgames.app.ui.components.AudioToggle] del
 * menú de pausa: el estado se lee por color y relleno, no por un check aparte.
 */
@Composable
private fun CategoryFilterPill(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) LogicColors.BackgroundDark else color,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) color else color.copy(alpha = 0.14f))
            .border(BorderStroke(1.dp, color.copy(alpha = if (selected) 0f else 0.4f)), shape)
            .bounceClick(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/**
 * Rótulo de sección de categoría: punto de color + nombre + contador de juegos, el
 * contador teñido con el acento (mismo criterio que el récord de [CompactGameCard]:
 * el color se reserva para lo que aporta información).
 */
@Composable
private fun CategorySectionHeader(
    category: GameCategory,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(category.accent),
        )
        Text(
            category.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (count == 1) "1 juego" else "$count juegos",
            style = MaterialTheme.typography.bodyMedium,
            color = category.accent,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Tarjeta compacta y VERTICAL de un juego, para vivir en la fila horizontal de su
 * categoría. Mismo lenguaje visual que tenía la fila ancha que reemplaza —acento de
 * categoría, motivo temático propio del juego ([CategoryMotifSurface]), insignia
 * "NUEVO", candado + "Próximamente" en lo no jugable, récord del jugador—, solo que
 * el reparto es de arriba a abajo (emblema → título → récord) para caber varias por
 * pantalla, y el motivo ocupa TODA la tarjeta de fondo (no un recuadro a la
 * derecha: aquí no hay "mitad de texto" que despejar, el texto vive abajo del todo
 * protegido por [MotifScrim.Bottom]).
 *
 * No repite la etiqueta de categoría (ya la dice el rótulo de [CategorySectionHeader])
 * ni el chevrón "entra aquí" de la fila ancha original: en una tarjeta que ya es,
 * ella entera, la superficie clicable, un tercer elemento de afordancia sería ruido.
 *
 * @param alreadyRevealed true si esta tarjeta ya se reveló antes (volvió a entrar en
 *   vista tras salir): nace visible y no repite la animación de entrada.
 * @param onRevealed aviso a la pantalla de que la entrada ya se reprodujo, para que
 *   recuerde el estado más allá de la vida del ítem en su `LazyRow`.
 */
@Composable
private fun CompactGameCard(
    game: GameInfo,
    index: Int,
    recordText: String?,
    alreadyRevealed: Boolean,
    onRevealed: () -> Unit,
    onOpen: () -> Unit,
) {
    // Aparición escalonada, UNA sola vez por tarjeta y acotada a las primeras de
    // CADA fila (no del catálogo entero): con varias filas horizontales visibles a
    // la vez, escalonar por posición global haría esperar a las tarjetas de abajo
    // el turno de las de arriba sin motivo.
    var revealed by remember { mutableStateOf(alreadyRevealed) }
    LaunchedEffect(Unit) {
        if (!revealed) {
            delay(if (index < CASCADE_ITEMS_PER_ROW) index * CASCADE_STEP_MS else 0L)
            revealed = true
            onRevealed()
        }
    }
    val reveal by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(350),
        label = "catalogCardReveal",
    )

    val accent = game.category.accent
    val shape = RoundedCornerShape(22.dp)
    CategoryMotifSurface(
        category = game.category,
        shape = shape,
        onClick = onOpen,
        enabled = game.playable,
        bgTopAlpha = 0.32f,
        motif = game.motif,
        scrim = MotifScrim.Bottom,
        modifier = Modifier
            .width(CardWidth)
            .height(CardHeight)
            .graphicsLayer {
                // Entrada + atenuado de "no jugable" en la MISMA capa: una sola capa
                // de composición por tarjeta en vez de dos (`alpha` crearía otra).
                alpha = reveal * (if (game.playable) 1f else 0.55f)
                translationY = (1f - reveal) * 18.dp.toPx()
            }
            .shadow(elevation = 6.dp, shape = shape, clip = false),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
        ) {
            // Emblema: el icono de la categoría sobre una placa tenue de su acento.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                NeonIcon(icon = game.category.icon, tint = accent, size = 22.dp, glow = false)
            }

            // El título y el récord se anclan al PIE de la tarjeta (weight en el
            // hueco de encima); el emblema queda fijo arriba, mismo ancla visual
            // que daba ritmo a la fila ancha original.
            Spacer(modifier = Modifier.weight(1f))

            Text(
                game.title,
                style = MaterialTheme.typography.titleMedium.copy(shadow = TextScrimShadow),
                color = LogicColors.OnDark,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            when {
                !game.playable -> Text(
                    "Próximamente",
                    style = MaterialTheme.typography.labelMedium,
                    color = LogicColors.OnDarkMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 6.dp),
                )
                // Récord del jugador teñido con el acento de la categoría:
                // recompensa visible sin saturar (el neón brilla por escaso).
                recordText != null -> Text(
                    recordText,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(LogicColors.SurfaceVariantDark)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        // Insignia "NUEVO" (variante compacta, pensada para tarjetas estrechas como
        // esta) o candado si aún no es jugable — mutuamente excluyentes en los datos
        // reales del catálogo (ver [GameInfo.isNew]), así que comparten esquina.
        if (game.isNew) {
            NewBadge(
                compact = true,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 10.dp),
            )
        } else if (!game.playable) {
            NeonIcon(
                icon = KortexIcons.Lock,
                tint = LogicColors.OnDarkMuted,
                size = 16.dp,
                glow = false,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp),
            )
        }
    }
}

/**
 * Nº de tarjetas que participan en la cascada de entrada de CADA fila: aproximadamente
 * las que caben en pantalla al abrir el catálogo. Más allá, escalonar no se percibe
 * como cascada (la tarjeta entra por el borde ya empezada) sino como lentitud al
 * scrollear.
 */
private const val CASCADE_ITEMS_PER_ROW = 3

/** Retardo entre dos tarjetas consecutivas de la cascada de entrada (ms). */
private const val CASCADE_STEP_MS = 45L
