package com.example.kortexgames.ui.components

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.core.theme.LogicGradients
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Datos del **selector de niveles** para la pantalla de intro de un juego LEVELED.
 * La pantalla es "tonta": recibe el récord y el nivel elegido y notifica los toques;
 * es el llamador (la pantalla del juego) quien mantiene el estado del nivel elegido.
 *
 * Convención de progreso (igual que el récord del jugador): [maxUnlocked] es el nivel
 * máximo COMPLETADO (0 = ninguno). Se pueden rejugar los niveles `1..maxUnlocked`; la
 * **frontera** = `maxUnlocked + 1` es el próximo nivel nuevo; a partir de ahí, bloqueados.
 *
 * @property maxUnlocked nivel máximo ya superado (récord); 0 si aún no superó ninguno.
 * @property selected nivel actualmente elegido (el que lanzará "Comenzar").
 * @property onSelect se invoca al tocar un nivel jugable (desbloqueado o la frontera).
 * @property lockedPreview cuántos niveles bloqueados mostrar por delante de la frontera.
 */
data class LevelStripState(
    val maxUnlocked: Int,
    val selected: Int,
    val onSelect: (Int) -> Unit,
    val lockedPreview: Int = 6,
)

/**
 * **Pantalla de intro** común a todos los juegos: la antesala que se muestra antes de
 * empezar a jugar (petición del usuario, inspirada en apps tipo Impulse). Presenta la
 * identidad del juego con estética neón (§9): icono, título, descripción y —si el juego
 * tiene niveles— un **carril horizontal de niveles** con el nivel actual resaltado; un
 * botón de **ayuda** (por ahora sin acción) y el CTA **Comenzar**.
 *
 * Es deliberadamente reutilizable y sin estado propio de negocio: los juegos LEVELED le
 * pasan [levels] (y arrancan el nivel elegido en [onStart]); los ENDLESS lo dejan en
 * `null` y [onStart] simplemente inicia la partida.
 *
 * Respeta la regla de "un solo bucle de atención" (§9.4): la única animación en bucle es
 * el latido del CTA; el resto (icono, carril) es estático para no competir por la vista.
 *
 * @param title nombre del juego (titular).
 * @param description frase corta que explica de qué va el juego.
 * @param accent color de acento de la categoría (halo/borde neón, número de nivel).
 * @param onStart lanza la partida (el nivel elegido si [levels] no es null).
 * @param onExit vuelve atrás (sale a la lista de juegos).
 * @param icon icono del juego; **null** = placeholder vacío (aún sin diseñar, por petición).
 * @param heroImage arte del juego que rellena el recuadro "héroe"; tiene prioridad sobre
 *        [icon]. **null** = se usa [icon] (o placeholder vacío si ambos son null).
 * @param levels datos del carril de niveles; **null** en juegos sin niveles (ENDLESS).
 * @param onHelp acción del botón de ayuda; por ahora un no-op (pendiente de contenido).
 * @param startLabel texto del CTA (por defecto "Comenzar").
 * @param background capa ambiental opcional detrás del contenido (fondo temático del juego).
 */
@Composable
fun GameIntroScreen(
    title: String,
    description: String,
    accent: Color,
    onStart: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    heroImage: DrawableResource? = null,
    levels: LevelStripState? = null,
    onHelp: () -> Unit = {},
    startLabel: String = "Comenzar",
    background: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        // Capa ambiental temática del juego (muro arcade, skyline…), si la hay.
        background?.invoke()

        Column(modifier = Modifier.fillMaxSize()) {
            // Cabecera: volver (izq.) y ayuda (der.), como en el mockup.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(
                    icon = KortexIcons.ChevronRight,
                    tint = LogicColors.OnDark,
                    onClick = onExit,
                    // Espejado horizontal → apunta a la izquierda (volver).
                    iconModifier = Modifier.graphicsLayer(scaleX = -1f),
                )
                Spacer(Modifier.weight(1f))
                CircleIconButton(
                    icon = KortexIcons.Help,
                    tint = LogicColors.OnDarkMuted,
                    onClick = onHelp,
                )
            }

            // Cuerpo desplazable: icono + título + descripción + carril de niveles. Se
            // hace scroll aquí para que el CTA quede siempre anclado abajo aunque el
            // contenido no quepa (pantallas bajas o muchos niveles visibles).
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                GameIconHero(icon = icon, heroImage = heroImage, accent = accent)

                Spacer(Modifier.height(24.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = LogicColors.OnDark,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LogicColors.OnDarkMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )

                if (levels != null) {
                    Spacer(Modifier.height(28.dp))
                    LevelStrip(state = levels, accent = accent)
                }

                Spacer(Modifier.height(24.dp))
            }

            // CTA "Comenzar": el único bucle de atención de la pantalla (§9.4). Latido +
            // halo que respira sobre el degradado verde de acción principal.
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedGameButton(
                    onClick = onStart,
                    gradient = LogicGradients.play,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pulse()
                        .softGlow(LogicColors.NeonGreen),
                    contentPadding = PaddingValues(vertical = 18.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NeonIcon(
                            icon = KortexIcons.Play,
                            tint = LogicColors.BackgroundDark,
                            size = 22.dp,
                            glow = false,
                        )
                        Text(
                            startLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = LogicColors.BackgroundDark,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * "Héroe" del juego: recuadro redondeado con halo neón de acento donde vive la identidad
 * visual del juego. Prioridad de contenido: [heroImage] (arte propio del juego) > [icon]
 * (glifo neón) > **placeholder vacío** (el recuadro con su halo, para juegos aún sin
 * diseñar). Cuando hay imagen, esta rellena el recuadro (`Crop`) recortada a su forma.
 */
@Composable
private fun GameIconHero(icon: ImageVector?, heroImage: DrawableResource?, accent: Color) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .size(132.dp)
            // Halo neón que "respira" alrededor del marco, mismo lenguaje que los
            // elementos destacados de la Home (§9.4). Va antes del clip para que el
            // resplandor se derrame fuera del recuadro.
            .softGlow(color = accent, shape = shape, maxElevation = 24.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.16f), LogicColors.SurfaceDark),
                ),
            )
            .border(BorderStroke(1.5.dp, accent.copy(alpha = 0.55f)), shape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            heroImage != null -> Image(
                painter = painterResource(heroImage),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            icon != null -> NeonIcon(icon = icon, tint = accent, size = 64.dp)
        }
    }
}

/**
 * Carril **horizontal** de niveles (petición: "que los niveles se vean así"). Cada nivel
 * es un disco cuyo lenguaje visual depende de su estado:
 *  - **superado** (`≤ maxUnlocked`): disco de acento tenue con check; rejugable.
 *  - **elegido** (`== selected`): disco brillante (blanco) con ▶; es el que lanza "Comenzar".
 *  - **frontera** (`== maxUnlocked+1`, si no está elegido): borde de acento (el próximo nuevo).
 *  - **bloqueado** (`> maxUnlocked+1`): atenuado, con candado y sin interacción.
 *
 * El carril se auto-desplaza para dejar el nivel elegido a la vista al abrir la pantalla.
 */
@Composable
private fun LevelStrip(state: LevelStripState, accent: Color) {
    val frontier = state.maxUnlocked + 1
    val total = frontier + state.lockedPreview
    val levels = (1..total).toList()
    val listState = rememberLazyListState()

    // Al entrar (o al cambiar el elegido), acerca el nivel elegido con algo de contexto
    // a su izquierda para que se lea el progreso ya conseguido.
    LaunchedEffect(state.selected) {
        listState.animateScrollToItem((state.selected - 3).coerceAtLeast(0))
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "NIVEL",
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 24.dp, bottom = 12.dp),
        )
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(levels) { level ->
                LevelDot(
                    level = level,
                    maxUnlocked = state.maxUnlocked,
                    selected = level == state.selected,
                    accent = accent,
                    onClick = { state.onSelect(level) },
                )
            }
        }
    }
}

/** Una casilla circular del carril de niveles. Ver [LevelStrip] para el lenguaje visual. */
@Composable
private fun LevelDot(
    level: Int,
    maxUnlocked: Int,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val frontier = maxUnlocked + 1
    val completed = level <= maxUnlocked
    val playable = level <= frontier
    val locked = level > frontier

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    when {
                        selected -> Brush.verticalGradient(listOf(LogicColors.OnDark, Color(0xFFD5DCF2)))
                        completed -> Brush.verticalGradient(
                            listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.12f)),
                        )
                        else -> Brush.verticalGradient(
                            listOf(LogicColors.SurfaceVariantDark, LogicColors.SurfaceDark),
                        )
                    },
                )
                .border(
                    BorderStroke(
                        width = if (selected || level == frontier) 2.dp else 1.dp,
                        color = when {
                            selected -> LogicColors.OnDark
                            level == frontier -> accent
                            completed -> accent.copy(alpha = 0.6f)
                            else -> LogicColors.OnDarkMuted.copy(alpha = 0.18f)
                        },
                    ),
                    CircleShape,
                )
                .bounceClick(enabled = playable, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // El elegido manda: ▶ oscuro sobre el disco blanco (contraste máximo).
                selected -> NeonIcon(
                    icon = KortexIcons.Play,
                    tint = LogicColors.BackgroundDark,
                    size = 26.dp,
                    glow = false,
                )
                completed -> NeonIcon(icon = KortexIcons.Check, tint = accent, size = 26.dp, glow = false)
                locked -> NeonIcon(icon = KortexIcons.Lock, tint = LogicColors.OnDarkMuted, size = 22.dp, glow = false)
                // Frontera no elegida: solo su número, en acento.
                else -> Text(
                    "$level",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                    fontWeight = FontWeight.Black,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "$level",
            style = MaterialTheme.typography.labelLarge,
            color = when {
                selected -> LogicColors.OnDark
                completed -> accent
                else -> LogicColors.OnDarkMuted
            },
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Botón circular de la cabecera (volver/ayuda): disco de superficie con el icono neón
 * dentro y la interacción táctil por defecto ([bounceClick]).
 */
@Composable
private fun CircleIconButton(
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(LogicColors.SurfaceDark.copy(alpha = 0.85f))
            .border(BorderStroke(1.dp, LogicColors.SurfaceVariantDark), CircleShape)
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NeonIcon(icon = icon, tint = tint, size = 24.dp, glow = false, modifier = iconModifier)
    }
}
