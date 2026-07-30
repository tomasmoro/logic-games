package com.kortexgames.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.game.GameMotif

/**
 * Un **paso** de la ayuda "cómo se juega": icono + título breve + explicación. Los pasos
 * troceana el resumen en acciones concretas y ordenadas, más fáciles de digerir que un
 * párrafo corrido; la hoja de ayuda los numera automáticamente (ver [GameHelpSheet]).
 *
 * @property icon glifo neón que representa la acción del paso (arrastrar, tocar, fusionar…).
 * @property title enunciado corto del paso (1–3 palabras, en negrita).
 * @property text explicación de una o dos frases.
 */
data class HelpStep(
    val icon: ImageVector,
    val title: String,
    val text: String,
)

/**
 * **Diseño de ayuda** de un juego: la descripción declarativa de "cómo se juega" que la
 * pantalla de ayuda **genérica** ([GameHelpSheet]) sabe pintar. Es el parámetro que cada
 * juego **inyecta** para personalizar una misma pantalla reutilizable, en vez de que cada
 * juego dibuje su propia ayuda a mano (petición del usuario: ayuda genérica + diseño
 * inyectable y flexible).
 *
 * Los diseños concretos de todos los juegos viven centralizados en
 * [com.kortexgames.app.game.GameHelpContent], para tenerlos en un solo sitio.
 *
 * @property title titular de la ayuda (normalmente el nombre del juego).
 * @property summary frase que resume el objetivo del juego; es el "qué persigo". Sustituye
 *   al antiguo `helpText` (una sola cadena) como mínimo indispensable de la ayuda.
 * @property steps pasos numerados de "cómo se juega"; vacío = solo se muestra el resumen.
 * @property tips consejos opcionales (atajos, estrategia, controles secundarios); vacío =
 *   no se pinta el bloque de consejos.
 * @property accent color de acento de la ayuda (halo del icono, numeración de pasos, CTA);
 *   normalmente el de la categoría del juego, para que la ayuda comparta su identidad.
 * @property art arte opcional que encabeza la hoja. Es **totalmente inyectable** para dar
 *   máxima flexibilidad: un juego pasa aquí su motivo ([com.kortexgames.app.game.GameMotif]),
 *   su icono o un dibujo propio (p. ej. una miniatura de tablero resuelto). Recibe el
 *   [accent] ya resuelto para teñirse coherente. `null` = ayuda sin arte (solo texto).
 *   Los ayudantes [motifHelpArt] / [iconHelpArt] cubren los dos casos habituales.
 * @property diagram diagrama **gráfico** opcional a todo lo ancho, bajo los pasos: para
 *   juegos cuyas reglas se entienden mejor viendo fragmentos de tablero y ejemplos de "qué
 *   está bien / qué está mal" (Sudoku, Buscaminas…) que leyéndolas. Es otro `@Composable`
 *   libre inyectable, con el [accent] ya resuelto. `null` = sin diagrama.
 */
data class GameHelp(
    val title: String,
    val summary: String,
    val steps: List<HelpStep> = emptyList(),
    val tips: List<String> = emptyList(),
    val accent: Color = LogicColors.NeonCyan,
    val art: (@Composable (accent: Color) -> Unit)? = null,
    val diagram: (@Composable (accent: Color) -> Unit)? = null,
)

/**
 * Arte de ayuda a partir del **motivo** del juego ([GameMotif]): reutiliza el mismo dibujo
 * que el recuadro héroe de la antesala y las miniaturas de la Home, así intro/Home/ayuda
 * comparten idéntica identidad visual. Es el caso habitual para los juegos con motivo.
 */
fun motifHelpArt(motif: GameMotif): @Composable (accent: Color) -> Unit =
    { accent -> GameMotifIcon(motif = motif, accent = accent, modifier = Modifier.fillMaxSize()) }

/**
 * Arte de ayuda a partir de un **icono** ([ImageVector]) teñido con el acento. Para juegos
 * que aún no tienen motivo propio pero sí un glifo representativo (p. ej. un cohete).
 */
fun iconHelpArt(icon: ImageVector): @Composable (accent: Color) -> Unit =
    { accent -> NeonIcon(icon = icon, tint = accent, size = 56.dp) }

/**
 * # Pantalla de ayuda genérica ("¿Cómo se juega?")
 *
 * Overlay modal **reutilizable** que pinta el [GameHelp] que le inyecta cualquier juego.
 * Es "tonta": no conoce ningún juego concreto, solo sabe representar la estructura común
 * (arte → resumen → pasos numerados → consejos). Así todos los juegos comparten idéntica
 * estética de ayuda y un ajuste de "look" se hace en un único sitio.
 *
 * Se autogestiona la animación de entrada/salida con la misma "física de juego" que el
 * menú de pausa y el fin de partida (§9.4): scrim en fundido + tarjeta que entra con
 * resorte y rebote. Las pantallas solo cambian [visible]; el scrim intercepta los toques
 * del fondo y **tocar fuera de la tarjeta** cierra la hoja.
 *
 * @param help diseño de la ayuda a mostrar (contenido + arte + acento).
 * @param visible si la hoja está abierta; conducido por el estado de la pantalla llamante.
 * @param onDismiss cierra la hoja (tocar el scrim, la "X" o el CTA "Entendido").
 */
@Composable
fun BoxScope.GameHelpSheet(
    help: GameHelp,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 0.86f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "helpScrim",
    )
    val cardScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "helpCardScale",
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "helpCardAlpha",
    )

    // Mientras el scrim es transparente no montamos nada: ni tapa el juego ni roba toques.
    if (scrimAlpha <= 0f) return

    // Scrim a pantalla completa: oscurece y bloquea el fondo; tocarlo cierra la ayuda.
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = scrimAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val cardShape = RoundedCornerShape(28.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .scale(cardScale)
                .alpha(cardAlpha)
                .clip(cardShape)
                .background(LogicColors.SurfaceDark)
                .border(
                    BorderStroke(1.5.dp, Brush.linearGradient(LogicGradients.ring)),
                    cardShape,
                )
                // Absorbe los toques para que pulsar DENTRO de la tarjeta no cierre la hoja
                // (solo el scrim de fuera cierra).
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 22.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Cerrar rápido (X): fija arriba, siempre visible (fuera del scroll).
            CloseButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            )

            // Contenido desplazable: solo el cuerpo (arte, resumen, pasos, consejos) hace
            // scroll. El CTA queda FUERA, anclado al fondo, para que el jugador no tenga
            // que desplazarse hasta el final para pulsarlo. `weight(fill = false)` deja que
            // la tarjeta se encoja al contenido cuando cabe (sin hueco bajo el CTA) y que
            // sea este bloque —no el botón— el que scrollee cuando el contenido es largo.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // --- Arte inyectado por el juego (motivo, icono o dibujo propio) -------
                if (help.art != null) {
                    HelpArtHero(accent = help.accent, art = help.art)
                    Spacer(Modifier.height(18.dp))
                } else {
                    NeonIcon(icon = KortexIcons.Help, tint = help.accent, size = 40.dp)
                    Spacer(Modifier.height(12.dp))
                }

                Text(
                    help.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = LogicColors.OnDark,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    help.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LogicColors.OnDarkMuted,
                )

                // --- Pasos numerados "cómo se juega" ----------------------------------
                if (help.steps.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    help.steps.forEachIndexed { index, step ->
                        if (index > 0) Spacer(Modifier.height(12.dp))
                        HelpStepRow(number = index + 1, step = step, accent = help.accent)
                    }
                }

                // --- Diagrama gráfico (opcional): ejemplos de "bien / mal" ------------
                if (help.diagram != null) {
                    Spacer(Modifier.height(20.dp))
                    help.diagram.invoke(help.accent)
                }

                // --- Consejos (opcional) ----------------------------------------------
                if (help.tips.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    HelpTips(tips = help.tips, accent = help.accent)
                }
            }

            // --- CTA de cierre, anclado al fondo (fuera del scroll) -------------------
            Spacer(Modifier.height(18.dp))
            AnimatedGameButton(
                onClick = onDismiss,
                gradient = LogicGradients.play,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                Text(
                    "¡Entendido!",
                    style = MaterialTheme.typography.titleMedium,
                    color = LogicColors.BackgroundDark,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}

/**
 * Recuadro "héroe" que envuelve el [art] inyectado con el mismo halo neón de acento que
 * la antesala del juego, para que la ayuda comparta identidad visual con la intro.
 */
@Composable
private fun HelpArtHero(accent: Color, art: @Composable (accent: Color) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .size(120.dp)
            .softGlow(color = accent, shape = shape, maxElevation = 20.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.16f), LogicColors.SurfaceDark),
                ),
            )
            .border(BorderStroke(1.5.dp, accent.copy(alpha = 0.55f)), shape),
        contentAlignment = Alignment.Center,
    ) {
        art(accent)
    }
}

/**
 * Fila de un paso: disco numerado de acento + icono + (título en negrita / explicación).
 * El número guía el orden de lectura; el icono resume la acción de un vistazo.
 */
@Composable
private fun HelpStepRow(number: Int, step: HelpStep, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LogicColors.SurfaceVariantDark.copy(alpha = 0.6f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f))
                .border(BorderStroke(1.dp, accent.copy(alpha = 0.55f)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                fontWeight = FontWeight.Black,
            )
        }
        NeonIcon(icon = step.icon, tint = accent, size = 24.dp, glow = false)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                step.title,
                style = MaterialTheme.typography.titleMedium,
                color = LogicColors.OnDark,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                step.text,
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )
        }
    }
}

/**
 * Marco de **ejemplo gráfico** "bien / mal": cabecera con icono ✓/✗ en verde/rojo semántico
 * (§9.2) + una leyenda corta, y debajo el fragmento de tablero que ilustra la regla. Es la
 * base compartida por los diagramas de ayuda de cada juego (Sudoku, Buscaminas…) para que
 * todos muestren los ejemplos con idéntico lenguaje, en vez de que cada uno invente el suyo.
 *
 * @param correct `true` = ejemplo correcto (verde, ✓); `false` = incorrecto (rojo, ✗).
 * @param caption explicación breve bajo el fragmento (qué está pasando en el ejemplo).
 * @param content el fragmento de tablero (celdas, minas…) que se dibuja como muestra.
 */
@Composable
fun HelpExampleCard(
    correct: Boolean,
    caption: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val color = if (correct) LogicColors.Success else LogicColors.Error
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LogicColors.SurfaceVariantDark.copy(alpha = 0.5f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.5f)), shape)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NeonIcon(
                icon = if (correct) KortexIcons.Check else KortexIcons.Close,
                tint = color,
                size = 20.dp,
                glow = false,
            )
            Text(
                if (correct) "BIEN" else "MAL",
                style = MaterialTheme.typography.labelLarge,
                color = color,
                fontWeight = FontWeight.Black,
            )
        }
        Spacer(Modifier.height(12.dp))
        content()
        Spacer(Modifier.height(10.dp))
        Text(
            caption,
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDarkMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Bloque de consejos: cabecera "CONSEJOS" en acento y una lista de viñetas neón. Va después
 * de los pasos porque es material de refuerzo (estrategia/atajos), no el "cómo" básico.
 */
@Composable
private fun HelpTips(tips: List<String>, accent: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "CONSEJOS",
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        tips.forEach { tip ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NeonIcon(
                    icon = KortexIcons.Star,
                    tint = accent,
                    size = 16.dp,
                    glow = false,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    tip,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.OnDarkMuted,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
