package com.example.kortexgames.game.memory

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.core.theme.LogicColors
import com.example.kortexgames.di.AppGraph
import com.example.kortexgames.game.GameStatus
import com.example.kortexgames.ui.components.ArcadeBrickBackground
import com.example.kortexgames.ui.components.GameIntroScreen
import com.example.kortexgames.ui.components.GameOverOverlay
import com.example.kortexgames.ui.components.GamePauseControls
import com.example.kortexgames.ui.components.clickableNoRipple
import com.example.kortexgames.ui.components.drawNeonTile
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.memory_intro

/**
 * Color neón de cada uno de los 9 botones (índice = tile). Se eligen tonos bien
 * separados en el círculo cromático (todos de [LogicColors]) para que cada botón
 * sea reconocible por su color además de por su nota: refuerza la memoria y da la
 * estética de "wireframe neón" del icono de la app. El orden acompaña el ascenso de
 * la escala musical ([MemoryNotes]) como un pequeño arcoíris de agudos crecientes.
 */
private val TileColors: List<Color> = listOf(
    LogicColors.NeonCyan,   // Do
    LogicColors.Blue,       // Re
    LogicColors.Violet,     // Mi
    LogicColors.Magenta,    // Fa
    LogicColors.Coral,      // Sol
    LogicColors.Amber,      // La
    LogicColors.Lime,       // Si
    LogicColors.NeonGreen,  // Do
    Color(0xFF7C6CFF),      // Re (índigo)
)

/**
 * Pantalla de Memoria de Secuencias. Observa el estado del ViewModel y pinta la
 * rejilla 3x3 de **tiles de tubo neón huecos** (la estética del icono de la app):
 * cada tile es un contorno redondeado que brilla sobre el lienzo oscuro. Durante
 * SHOWING los tiles se encienden y suenan solos (input ignorado) y en INPUT el
 * jugador los pulsa. Al terminar, superpone [GameOverOverlay].
 */
@Composable
fun SequenceMemoryScreen(graph: AppGraph, onExit: () -> Unit) {
    val vm: SequenceMemoryViewModel = viewModel {
        SequenceMemoryViewModel(graph.progressRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val game = state.game

    // Antesala del juego: mientras no ha arrancado (IDLE) se muestra la intro. Evita,
    // además, que la secuencia se reproduzca (con sonido) antes de que el jugador empiece.
    if (state.status == GameStatus.IDLE) {
        GameIntroScreen(
            title = "Memoria de Secuencias",
            description = "Observa la secuencia de notas y repítela en orden. Cada acierto la hace más larga.",
            accent = CategoryPalette.Memory,
            heroImage = Res.drawable.memory_intro,
            onStart = { vm.onIntent(SequenceMemoryIntent.Start) },
            onExit = onExit,
            background = {
                ArcadeBrickBackground(modifier = Modifier.fillMaxSize(), accent = CategoryPalette.Memory)
            },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(LogicColors.BackgroundDark)) {
        // Textura ambiental de muro arcade "neo-retro" (morado Memoria), muy sutil.
        // Los tiles son huecos, así que se ve a través de ellos: es el "lienzo" del icono.
        ArcadeBrickBackground(
            modifier = Modifier.fillMaxSize(),
            accent = CategoryPalette.Memory,
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Memoria de Secuencias", style = MaterialTheme.typography.headlineMedium, color = LogicColors.OnDark)
            Spacer(Modifier.height(8.dp))
            Text(
                "Nivel ${game.level}   ·   ${game.score} pts",
                style = MaterialTheme.typography.titleLarge,
                color = LogicColors.Electric,
            )
            Text(
                when (game.phase) {
                    MemoryPhase.SHOWING -> "Observa la secuencia…"
                    MemoryPhase.INPUT -> "¡Tu turno! Repítela"
                    MemoryPhase.ROUND_OK -> "¡Bien! Siguiente ronda"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyLarge,
                color = LogicColors.OnDarkMuted,
            )

            // El área de juego ocupa el espacio restante bajo la cabecera y centra el
            // tablero verticalmente (queda en el centro visual de la pantalla).
            val inputEnabled = game.phase == MemoryPhase.INPUT
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                // Tablero: recuadro traslúcido que agrupa los 9 tiles como una "consola".
                // El fondo con opacidad los cohesiona y los separa del muro del fondo sin
                // taparlo del todo; el interior de cada tile sigue siendo hueco (wireframe).
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(LogicColors.SurfaceDark.copy(alpha = 0.55f))
                        .border(
                            width = 1.dp,
                            color = LogicColors.OnDark.copy(alpha = 0.06f),
                            shape = RoundedCornerShape(28.dp),
                        )
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    for (rowIdx in 0 until 3) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            for (colIdx in 0 until 3) {
                                val tileIndex = rowIdx * 3 + colIdx
                                MemoryButton(
                                    color = TileColors[tileIndex],
                                    lit = game.litTile == tileIndex,
                                    enabled = inputEnabled,
                                    onClick = { vm.onIntent(SequenceMemoryIntent.TapTile(tileIndex)) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.status == GameStatus.FINISHED && state.gameOver != null) {
            GameOverOverlay(
                info = state.gameOver!!,
                audio = graph.audio,
                onPlayAgain = { vm.onIntent(SequenceMemoryIntent.PlayAgain) },
                onExit = onExit,
            )
        }

        // Botón de pausa + menú (Reanudar / audio / ayuda / Salir), común a todos los juegos.
        GamePauseControls(
            status = state.status,
            settings = graph.settingsRepository,
            audio = graph.audio,
            onPause = { vm.onIntent(SequenceMemoryIntent.Pause) },
            onResume = { vm.onIntent(SequenceMemoryIntent.Resume) },
            onExit = onExit,
            gameTitle = "Memoria de Secuencias",
            helpText = "Observa la secuencia de notas y repítela en orden. Cada acierto la hace más larga.",
            accent = CategoryPalette.Memory,
        )
    }
}

/**
 * Tile de la rejilla dibujado como un **tubo de neón hueco** (la estética del icono):
 * un contorno redondeado que en reposo brilla atenuado y, al encenderse ([lit] durante
 * la reproducción o pulsado por el jugador), sube a color pleno, prende su núcleo
 * blanco, llena el interior con un tinte que "enciende el cristal", suelta unas chispas
 * y rebota de escala. Todo animado con `spring` para dar peso táctil (CLAUDE.md §9.4).
 *
 * A diferencia de un botón físico sólido, aquí NO hay relleno opaco: el interior es
 * transparente y deja ver el lienzo, igual que los cuadros del icono. El feedback
 * sonoro (la nota del tile) lo dispara el motor, no la UI.
 *
 * @param color color neón propio del tile (identidad visual + halo).
 * @param lit true cuando el juego lo ilumina durante SHOWING.
 * @param enabled true solo en fase INPUT (habilita la pulsación).
 */
@Composable
private fun MemoryButton(
    color: Color,
    lit: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = lit || pressed

    // Mezcla de "encendido" (0 = apagado en reposo, 1 = neón pleno) para interpolar de
    // forma continua el brillo del borde, el halo, el relleno interior y las chispas.
    val activeAmt by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "active",
    )
    // Rebote de escala al encender: le da "peso" al toque sin desplazar el layout.
    val scale by animateFloatAsState(
        targetValue = if (active) 1.06f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "scale",
    )

    val shape = RoundedCornerShape(22.dp)

    Box(
        modifier
            .aspectRatio(1f)
            .scale(scale)
            .drawBehind { drawNeonTile(color, activeAmt, cornerRadius = 22.dp, sparks = true) }
            .clip(shape)
            .clickableNoRipple(interaction, enabled = enabled, onClick = onClick),
    )
}
