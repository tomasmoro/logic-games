package com.kortexgames.app.ui.components

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.domain.model.formatDurationShort
import com.kortexgames.app.game.GameMotif

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
 * @property bestTimes mejor tiempo por nivel (nivel → ms, menor = mejor); vacío en
 *   juegos que no lo miden. Se muestra bajo cada nivel superado como incentivo de
 *   rejugar para mejorar la marca.
 */
data class LevelStripState(
    val maxUnlocked: Int,
    val selected: Int,
    val onSelect: (Int) -> Unit,
    val lockedPreview: Int = 6,
    val bestTimes: Map<Int, Long> = emptyMap(),
)

/**
 * **Partida pendiente** detectada al abrir la antesala: el jugador salió a mitad de
 * juego y su progreso se guardó (ver
 * [com.kortexgames.app.domain.repository.SavedGameStateRepository]).
 *
 * Cuando un juego pasa esto a [GameIntroScreen], la antesala invierte la jerarquía
 * de sus acciones: **continuar** pasa a ser el CTA principal (es lo que el jugador
 * casi siempre quiere al volver) y empezar de cero baja a acción secundaria. Los
 * juegos que no guardan partida lo dejan en `null` y la antesala no cambia.
 *
 * @property detail resumen corto de lo que se retoma ("Nivel 5", "1240 pts"); se
 *   muestra bajo el CTA para que el jugador sepa QUÉ va a continuar antes de pulsar.
 *   `null` = sin resumen.
 * @property onResume reanuda la partida guardada.
 */
data class ResumeState(
    val onResume: () -> Unit,
    val detail: String? = null,
)

/**
 * **Pantalla de intro** común a todos los juegos: la antesala que se muestra antes de
 * empezar a jugar (petición del usuario, inspirada en apps tipo Impulse). Presenta la
 * identidad del juego con estética neón (§9): icono, título, descripción y —si el juego
 * tiene niveles— un **carril horizontal de niveles** con el nivel actual resaltado; un
 * botón de **ayuda** que abre la pantalla de ayuda genérica ([GameHelpSheet]) con el
 * [GameHelp] inyectado, y el CTA **Comenzar**.
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
 * @param motif motivo del juego ([GameMotif]) que se dibuja **centrado** dentro del recuadro
 *        "héroe" como arte del juego; tiene prioridad sobre [icon]. **null** = se usa [icon]
 *        (o placeholder vacío si ambos son null). Es la misma fuente que las tarjetas del
 *        catálogo, así intro/Home/lista comparten idéntica identidad visual.
 * @param levels datos del carril de niveles; **null** en juegos sin niveles (ENDLESS).
 * @param help diseño de la ayuda "¿Cómo se juega?" ([GameHelp], almacenados en
 *        [com.kortexgames.app.game.GameHelpContent]). Si no es null, el botón de ayuda
 *        de la cabecera abre la pantalla de ayuda genérica ([GameHelpSheet]); si es null,
 *        el botón cae en [onHelp].
 * @param onHelp acción del botón de ayuda cuando no se inyecta [help]; por defecto un no-op.
 * @param startLabel texto del CTA (por defecto "Comenzar").
 * @param resume partida pendiente de continuar (ver [ResumeState]); **null** en los
 *        juegos que no guardan progreso al salir, que mantienen la antesala de siempre.
 * @param configContent configuración previa a la partida (selector de dificultad, de
 *        tamaño de tablero…) para juegos ENDLESS con variantes de arranque; **null** en
 *        el resto. Se pinta dentro del propio bloque de acciones, justo ENCIMA del CTA
 *        principal, en vez de superponerse por fuera con un padding fijo calculado a
 *        ojo: así crece y se encoge con el resto del bloque (p. ej. cuando [resume] no
 *        es null añade debajo su resumen + "Empezar de nuevo") sin que nunca llegue a
 *        solaparse con el botón — un padding fijo sí se descuadra en ese caso, porque el
 *        CTA se desplaza hacia arriba al crecer el contenido que hay debajo suyo.
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
    motif: GameMotif? = null,
    levels: LevelStripState? = null,
    help: GameHelp? = null,
    onHelp: () -> Unit = {},
    startLabel: String = "Comenzar",
    resume: ResumeState? = null,
    configContent: (@Composable () -> Unit)? = null,
    background: (@Composable () -> Unit)? = null,
) {
    // Estado local de la hoja de ayuda: cuando el juego inyecta [help], el botón de la
    // cabecera abre la pantalla de ayuda genérica sin que la pantalla llamante tenga que
    // orquestar nada (por eso [onHelp] solo se usa como respaldo si no hay [help]).
    var showHelp by remember { mutableStateOf(false) }

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
                    onClick = { if (help != null) showHelp = true else onHelp() },
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
                Spacer(Modifier.height(16.dp))
                GameIconHero(icon = icon, motif = motif, accent = accent)

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

            // Acciones. El CTA principal es el único bucle de atención de la pantalla
            // (§9.4): latido + halo que respira sobre el degradado verde. Cuando hay
            // partida pendiente ese papel lo toma "Continuar" —lo que el jugador
            // quiere al volver— y empezar de cero baja a acción secundaria, para que
            // un toque distraído no borre el progreso guardado.
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (configContent != null) {
                    configContent()
                    Spacer(Modifier.height(16.dp))
                }

                AnimatedGameButton(
                    onClick = resume?.onResume ?: onStart,
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
                            if (resume != null) "Continuar" else startLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = LogicColors.BackgroundDark,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }

                if (resume != null) {
                    // Qué se retoma exactamente ("Nivel 5", "1240 pts"): sin esto el
                    // jugador no sabe si continúa lo que cree recordar.
                    if (resume.detail != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            resume.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LogicColors.OnDarkMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // Secundario y sin peso visual: descarta la partida guardada, así
                    // que no debe competir con "Continuar" (§9.1: el acento es escaso).
                    Text(
                        "Empezar de nuevo",
                        style = MaterialTheme.typography.labelLarge,
                        color = LogicColors.OnDarkMuted,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .bounceClick(onClick = onStart)
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }

        // Hoja de ayuda genérica, encima de todo: se abre desde el botón de la cabecera
        // cuando el juego inyecta [help]. Es un overlay [BoxScope], por eso va como última
        // capa del Box raíz.
        if (help != null) {
            GameHelpSheet(
                help = help,
                visible = showHelp,
                onDismiss = { showHelp = false },
            )
        }
    }
}

/**
 * "Héroe" del juego: recuadro redondeado con halo neón de acento donde vive la identidad
 * visual del juego. Prioridad de contenido: [motif] (arte propio del juego, dibujado
 * centrado con [GameMotifIcon]) > [icon] (glifo neón) > **placeholder vacío** (el recuadro
 * con su halo, para juegos aún sin diseñar). El motivo es el mismo que pinta el fondo de la
 * tarjeta del juego en el catálogo, así intro/lista/Home comparten identidad.
 */
@Composable
private fun GameIconHero(icon: ImageVector?, motif: GameMotif?, accent: Color) {
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
            motif != null -> GameMotifIcon(
                motif = motif,
                accent = accent,
                modifier = Modifier.fillMaxSize(),
            )
            icon != null -> NeonIcon(icon = icon, tint = accent, size = 64.dp)
        }
    }
}

/**
 * Carril **horizontal** de niveles (petición: "que los niveles se vean así"). Cada nivel
 * es un **tile de neón** ([drawNeonTile], §9.7) cuyo grado de encendido comunica su estado:
 *  - **superado** (`≤ maxUnlocked`): tile encendido a media luz (0.3) con check; rejugable.
 *  - **elegido / nivel actual** (`== selected`): tile a pleno brillo (0.8) con ▶; lanza "Comenzar".
 *  - **sin completar** (`> maxUnlocked`): tile apenas insinuado (0.1) —frontera con su número
 *    en acento, bloqueados con candado y sin interacción—.
 *
 * Reutilizar `drawNeonTile` en vez de un borde propio mantiene los niveles dentro del mismo
 * lenguaje de "tubo de neón" que las teclas/celdas de los juegos (fuente única, §9.7).
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
                    bestTimeMs = state.bestTimes[level],
                    onClick = { state.onSelect(level) },
                )
            }
        }
    }
}

/**
 * Una casilla circular del carril de niveles. Ver [LevelStrip] para el lenguaje visual.
 *
 * @param bestTimeMs mejor tiempo del jugador en este nivel (ms), o null si no aplica /
 *   aún no lo jugó. Cuando existe, se muestra bajo el número como récord de tiempo.
 */
@Composable
private fun LevelDot(
    level: Int,
    maxUnlocked: Int,
    selected: Boolean,
    accent: Color,
    bestTimeMs: Long?,
    onClick: () -> Unit,
) {
    val frontier = maxUnlocked + 1
    val completed = level <= maxUnlocked
    val playable = level <= frontier
    val locked = level > frontier

    // Grado de encendido del tile según su estado (petición del usuario): el nivel actual
    // a pleno brillo, los superados a media luz y los que faltan apenas insinuados. Así el
    // ojo salta directo al que se va a jugar sin depender de un bucle de atención (§9.4).
    val glow = when {
        selected -> 0.8f
        completed -> 0.3f
        else -> 0.1f
    }
    // Los bloqueados pierden el acento y viran a gris: aún no forman parte del "juego".
    val tileColor = if (locked) LogicColors.OnDarkMuted else accent

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                // Tile de neón compartido (§9.7): mismo lenguaje que las celdas del juego.
                // Sin chispas: el carril es estático, las chispas son remate de celebración.
                .drawBehind {
                    drawNeonTile(
                        baseColor = tileColor,
                        activeAmt = glow,
                        cornerRadius = 20.dp,
                        sparks = false,
                    )
                }
                .bounceClick(enabled = playable, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // El elegido manda: ▶ blanco sobre el tile a pleno brillo (contraste máximo).
                selected -> NeonIcon(
                    icon = KortexIcons.Play,
                    tint = LogicColors.OnDark,
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
        // Récord de tiempo del nivel (si el juego lo mide y ya se completó): pequeño y
        // atenuado para no competir con el número, con un cronómetro como pista visual.
        if (bestTimeMs != null) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeonIcon(
                    icon = KortexIcons.Timer,
                    tint = LogicColors.OnDarkMuted,
                    size = 11.dp,
                    glow = false,
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    formatDurationShort(bestTimeMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = LogicColors.OnDarkMuted,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
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
