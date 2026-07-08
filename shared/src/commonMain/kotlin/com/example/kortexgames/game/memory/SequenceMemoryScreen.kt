package com.example.kortexgames.game.memory

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
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
import com.example.kortexgames.ui.components.NeonFrame
import com.example.kortexgames.ui.components.clickableNoRipple
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.memory_intro

/**
 * Color neón de cada uno de los 9 botones (índice = tile). Se eligen tonos bien
 * separados en el círculo cromático (todos de [LogicColors]) para que cada botón
 * sea reconocible por su color además de por su nota: refuerza la memoria y da la
 * estética de "consola arcade" pedida. El orden acompaña el ascenso de la escala
 * musical ([MemoryNotes]) como un pequeño arcoíris de agudos crecientes.
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
 * rejilla 3x3 de **botones arcade neón**; durante SHOWING los botones se iluminan
 * y suenan solos (input ignorado) y en INPUT el jugador los pulsa. Al terminar,
 * superpone [GameOverOverlay].
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
            Spacer(Modifier.height(24.dp))

            // Rejilla 3x3 de botones arcade dentro de un "bezel" de consola: panel
            // sólido (tapa el muro del fondo en los huecos entre botones) con borde
            // neón animado alrededor. El padding lateral externo achica los botones
            // y deja aire a los costados.
            val inputEnabled = game.phase == MemoryPhase.INPUT
            NeonFrame(
                modifier = Modifier.padding(horizontal = 16.dp),
                contentPadding = 14.dp,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (rowIdx in 0 until 3) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
    }
}

/**
 * Botón arcade neón de la rejilla. Simula un botón físico con relieve: una "base"
 * oscura fija y una "cara" de color que descansa elevada sobre ella y **se hunde**
 * (baja hasta quedar a ras) cuando se ilumina o se presiona — el mismo lenguaje
 * táctil de la app (CLAUDE.md §9.4), aquí en clave de botón real.
 *
 * El estado activo ([lit] durante la reproducción o pulsado por el jugador) sube el
 * brillo del color, enciende un halo neón y hunde la cara; todo animado con `spring`
 * para dar peso. El feedback sonoro (la nota del botón) lo dispara el motor, no la UI.
 *
 * @param color color neón propio del botón (identidad visual + halo).
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

    // Grosor del "canto" del botón: cuánto sobresale la cara sobre la base.
    val sideThickness: Dp = 10.dp

    // Cuánto se ha hundido la cara (0 = elevada en reposo, sideThickness = a ras).
    val press by animateDpAsState(
        targetValue = if (active) sideThickness else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "press",
    )
    // Mezcla de "encendido" para interpolar color y halo de forma continua.
    val activeAmt by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "active",
    )
    val glow by animateDpAsState(
        targetValue = if (active) 22.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "glow",
    )

    val shape = RoundedCornerShape(20.dp)
    // En reposo la cara es un tono apagado del color; al activarse vira al neón pleno.
    val restColor = lerp(color, LogicColors.SurfaceVariantDark, 0.62f)
    val faceColor = lerp(restColor, color, activeAmt)
    val faceBrush = Brush.verticalGradient(
        listOf(
            lerp(faceColor, Color.White, 0.10f + 0.10f * activeAmt), // brillo superior
            faceColor,
            lerp(faceColor, Color.Black, 0.30f),                     // sombra inferior
        )
    )

    Box(modifier.aspectRatio(1f).clip(shape)) {
        // Base/canto: color muy oscurecido; da la sensación de profundidad del botón.
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(lerp(color, Color.Black, 0.6f)),
        )
        // Cara del botón: se desplaza hacia abajo (top) y se acorta (bottom) al hundirse.
        Box(
            Modifier
                .matchParentSize()
                // El spring rebota fuera de [0, sideThickness]; se acota para que el
                // padding nunca sea negativo (Compose lo prohíbe y crashea si no).
                .padding(
                    top = press.coerceAtLeast(0.dp),
                    bottom = (sideThickness - press).coerceAtLeast(0.dp),
                )
                .shadow(glow, shape, clip = false, ambientColor = color, spotColor = color)
                .clip(shape)
                .background(faceBrush)
                .clickableNoRipple(interaction, enabled = enabled, onClick = onClick),
        )
    }
}
