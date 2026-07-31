package com.kortexgames.app.ui.games

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.game.GameCatalog
import com.kortexgames.app.game.GameInfo
import com.kortexgames.app.game.GameProgressions
import com.kortexgames.app.ui.components.CategoryMotifSurface
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.MotifScrim
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.navigation.Routes
import kotlinx.coroutines.delay

/**
 * Catálogo de juegos en **lista de una columna**: cada juego es una fila a todo el
 * ancho con el color de su categoría, bordes muy redondeados y ligera elevación.
 * Al abrirse la pantalla, las tarjetas **aparecen escalonadas** con fade-in +
 * deslizamiento hacia arriba (efecto "cascada") — CLAUDE.md §9.4.
 *
 * **Por qué una columna y no la cuadrícula de dos:** con dos columnas cada tarjeta
 * disponía de ~150dp de ancho para el texto, así que categorías largas
 * ("Atención y Concentración") y títulos largos ("Ordena las Pociones") envolvían a
 * varias líneas y desbordaban el alto fijo de la tarjeta, recortando el título o
 * empujando fuera la etiqueta de récord. A todo el ancho el texto dispone del doble
 * de espacio y, además, el layout en fila lo blinda: ver [GameCard].
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

    // Tarjetas cuya animación de entrada YA se reprodujo. Vive en la pantalla y no
    // dentro de [GameCard] a propósito: LazyColumn destruye los ítems que salen de
    // vista, así que un flag interno se perdería y la cascada se relanzaría cada vez
    // que una fila vuelve a entrar en pantalla al scrollear.
    // No necesita ser snapshot state: solo se lee al componer una tarjeta por primera
    // vez (para decidir si nace ya visible), nunca para disparar una recomposición.
    val revealed = remember { mutableSetOf<String>() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Catálogo", style = MaterialTheme.typography.headlineLarge, color = LogicColors.OnDark)
                Text(
                    "Elige un juego y entrena una habilidad",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.OnDarkMuted,
                )
            }
        }

        // La clave estable evita que, al reciclar filas en el scroll, una tarjeta
        // herede el estado de animación de otra (los títulos son únicos en el catálogo).
        itemsIndexed(games, key = { _, game -> game.id ?: game.title }) { index, game ->
            val key = game.id ?: game.title
            GameCard(
                game = game,
                index = index,
                // Texto del récord ("Nivel máx 7" / "Mejor 320 ms") o null si aún
                // no hay marca. Se resuelve con la progresión del juego.
                recordText = game.id
                    ?.let { id -> bestByGame[id]?.let { GameProgressions.forId(id)?.formatRecord(it) } },
                alreadyRevealed = key in revealed,
                onRevealed = { revealed += key },
                onOpen = { Routes.gameRoute(game.id)?.let(onOpenGame) },
            )
        }
    }
}

/**
 * Sombra sutil detrás del texto de la tarjeta: un halo oscuro que refuerza el
 * contraste del título/categoría sobre el motivo del fondo (además del velo
 * inferior de [CategoryMotifSurface]). El desenfoque le da un borde suave, no una
 * sombra dura tipo "drop shadow".
 */
private val TextScrimShadow = Shadow(
    color = LogicColors.BackgroundDark,
    offset = Offset(0f, 1f),
    blurRadius = 6f,
)

/**
 * Fila de juego a todo el ancho. Colorea el fondo con el acento de la categoría
 * (degradado sutil), redondea fuerte y eleva. Anima su entrada con un retardo
 * proporcional al [index] para lograr el efecto cascada. Los juegos aún no
 * disponibles se muestran atenuados, con etiqueta "Próximamente" y candado.
 *
 * ## Reparto horizontal (izquierda → derecha)
 *
 * `[emblema] · [categoría / título / récord] · [chevrón]`
 *
 * El motivo temático del juego queda **detrás, escorado a la derecha**
 * (`squareMotif`), donde no compite con el texto; el velo [MotifScrim.Start] apaga
 * su mitad izquierda para asegurar el contraste. Así el arte sigue identificando al
 * juego de un vistazo sin robarle espacio al texto, que es lo que se rompía antes.
 *
 * ## Cómo se evita que el texto se corte
 *
 * El alto de la tarjeta es fijo (150dp), así que el contenido no puede crecer sin
 * límite. Tres reglas lo garantizan sin depender del tamaño de pantalla ni de la
 * escala de fuente del sistema:
 *  1. La categoría va a **1 línea** con elipsis (nunca envuelve, por larga que sea:
 *     "Reconocimiento de Patrones" es el peor caso).
 *  2. El título va a **2 líneas** con elipsis.
 *  3. El título es el único elemento **elástico** de la columna (lleva el `weight`):
 *     cuando el alto no da para todo, cede él —y se recorta con "…"— en vez de
 *     empujar la etiqueta de récord fuera de la tarjeta. Categoría y récord, que
 *     son de alto fijo y conocido, se dibujan siempre.
 *
 * ## Por qué la entrada se anima en una capa y no con `AnimatedVisibility`
 *
 * La tarjeta **siempre ocupa sus 150dp**, aun sin revelar: la cascada solo mueve
 * `alpha` y `translationY` dentro de un [graphicsLayer], que no altera el layout.
 * Con `AnimatedVisibility` el ítem medía 0 mientras estaba oculto y, como
 * `LazyColumn` compone las filas al entrar en vista, al subir el scroll la fila que
 * asomaba por arriba nacía con alto 0 y crecía a 150dp, desplazando todo lo de
 * debajo: la lista daba saltos y parecía "volver al inicio".
 *
 * @param alreadyRevealed true si esta tarjeta ya se reveló antes (volvió a entrar en
 *   vista tras salir): nace visible y no repite la animación de entrada.
 * @param onRevealed aviso a la pantalla de que la entrada ya se reprodujo, para que
 *   recuerde el estado más allá de la vida del ítem en la lista.
 */
@Composable
private fun GameCard(
    game: GameInfo,
    index: Int,
    recordText: String?,
    alreadyRevealed: Boolean,
    onRevealed: () -> Unit,
    onOpen: () -> Unit,
) {
    // Aparición escalonada, UNA sola vez por tarjeta. Solo entran en la cascada las
    // primeras filas (las que caben en pantalla al abrir); las de más abajo se
    // revelan sin retardo, porque ahí el escalonado no se percibe como cascada sino
    // como lentitud al scrollear.
    var revealed by remember { mutableStateOf(alreadyRevealed) }
    LaunchedEffect(Unit) {
        if (!revealed) {
            delay(if (index < CASCADE_ITEMS) index * CASCADE_STEP_MS else 0L)
            revealed = true
            onRevealed()
        }
    }
    val reveal by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(350),
        label = "gameCardReveal",
    )

    val accent = game.category.accent
    val shape = RoundedCornerShape(24.dp)
    CategoryMotifSurface(
        category = game.category,
        shape = shape,
        onClick = onOpen,
        enabled = game.playable,
        bgTopAlpha = 0.32f,
        motif = game.motif,
        scrim = MotifScrim.Start,
        squareMotif = true,
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .graphicsLayer {
                // Entrada + atenuado de "no jugable" en la MISMA capa: una sola capa
                // de composición por tarjeta en vez de dos (`alpha` crearía otra).
                alpha = reveal * (if (game.playable) 1f else 0.55f)
                translationY = (1f - reveal) * 24.dp.toPx()
            }
            .shadow(elevation = 8.dp, shape = shape, clip = false),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Emblema: el icono de la categoría sobre una placa tenue de su acento.
            // Ancla visual fija a la izquierda de TODAS las filas, que es lo que da
            // ritmo a la lista al hacer scroll.
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                NeonIcon(icon = game.category.icon, tint = accent, size = 30.dp)
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    game.category.displayName.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(shadow = TextScrimShadow),
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    game.title,
                    // Un paso por encima del titleMedium de tarjeta: a todo el ancho
                    // el título es el elemento protagonista de la fila.
                    style = MaterialTheme.typography.titleLarge.copy(shadow = TextScrimShadow),
                    color = LogicColors.OnDark,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // fill = false: reclama solo lo que necesita, pero nunca más del
                    // hueco libre (ver "Cómo se evita que el texto se corte").
                    modifier = Modifier.weight(1f, fill = false),
                )
                when {
                    !game.playable -> Text(
                        "Próximamente",
                        style = MaterialTheme.typography.labelMedium,
                        color = LogicColors.OnDarkMuted,
                        maxLines = 1,
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
                            .clip(RoundedCornerShape(8.dp))
                            .background(LogicColors.SurfaceVariantDark)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            // Afordancia de fila: chevrón "entra aquí" en los jugables, candado en
            // los que aún no lo son (refuerza el "Próximamente" sin repetir texto).
            NeonIcon(
                icon = if (game.playable) KortexIcons.ChevronRight else KortexIcons.Lock,
                tint = if (game.playable) accent else LogicColors.OnDarkMuted,
                size = 24.dp,
                glow = game.playable,
                contentDescription = null,
            )
        }
    }
}

/**
 * Nº de tarjetas que participan en la cascada de entrada: aproximadamente las que
 * caben en pantalla al abrir el catálogo. Más allá, escalonar no se percibe como
 * cascada (la fila entra por el borde ya empezada) sino como lentitud al scrollear.
 */
private const val CASCADE_ITEMS = 6

/** Retardo entre dos tarjetas consecutivas de la cascada de entrada (ms). */
private const val CASCADE_STEP_MS = 45L
