package com.kortexgames.app.game

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.ui.components.HelpExampleCard
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.NeonIcon

/**
 * # Diagramas gráficos de ayuda
 *
 * Ilustraciones específicas de cada juego para el slot [com.kortexgames.app.ui.components.GameHelp.diagram]
 * de la pantalla de ayuda. Se separan de [GameHelpContent] (que es el catálogo de textos)
 * porque son piezas gráficas con bastante dibujo: mezclarlas allí ensuciaría el catálogo.
 *
 * La idea (petición del usuario: "no todo el mundo sabe jugar") es enseñar las reglas de
 * los juegos menos evidentes con **fragmentos de tablero** y ejemplos de "qué está bien /
 * qué está mal", en lugar de solo describirlas con palabras. Todos usan el marco común
 * [HelpExampleCard] para hablar el mismo idioma visual (verde ✓ = bien, rojo ✗ = mal).
 */

/** Tamaño de celda de los fragmentos de tablero de los diagramas (Buscaminas). */
private val CellSize = 36.dp

/** Celda de Sudoku: más pequeña porque una fila/columna muestra las 9 a lo ancho. */
private val SudokuCellSize = 22.dp

/** Separación entre celdas de Sudoku. */
private val SudokuGap = 3.dp

/**
 * Diagrama de **Sudoku**: enseña la regla de "no repetir" en las **tres** unidades del
 * tablero a la vez —una fila de 9, una columna de 9 y un cuadro 3×3—, con un ejemplo válido
 * (todos distintos) y otro inválido (un número repetido, marcado en rojo) para cada una. El
 * [accent] tiñe las celdas correctas para heredar la identidad de la categoría.
 */
@Composable
fun SudokuHelpDiagram(accent: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DiagramIntro("En cada fila, columna y cuadro 3×3 deben estar los dígitos 1–9 sin repetirse.")
        Spacer(Modifier.height(12.dp))
        HelpExampleCard(correct = true, caption = "Ningún número se repite en la fila, la columna ni el cuadro.") {
            SudokuUnits(
                accent = accent,
                row = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9),
                rowErrors = emptySet(),
                column = listOf(4, 8, 1, 6, 2, 9, 5, 7, 3),
                columnErrors = emptySet(),
                block = listOf(5, 3, 7, 6, 1, 9, 2, 8, 4),
                blockErrors = emptySet(),
            )
        }
        Spacer(Modifier.height(10.dp))
        HelpExampleCard(correct = false, caption = "Los números en rojo están repetidos dentro de su unidad.") {
            SudokuUnits(
                accent = accent,
                // Fila con dos "3"; columna con dos "8"; cuadro con dos "5": el conflicto
                // se marca en rojo en las dos celdas implicadas de cada unidad.
                row = listOf(1, 2, 3, 4, 5, 6, 7, 3, 9),
                rowErrors = setOf(2, 7),
                column = listOf(4, 8, 1, 6, 2, 9, 5, 8, 3),
                columnErrors = setOf(1, 7),
                block = listOf(5, 3, 7, 6, 1, 9, 2, 5, 4),
                blockErrors = setOf(0, 7),
            )
        }
    }
}

/**
 * Las tres unidades del Sudoku juntas: la **fila** de 9 arriba, y debajo la **columna** de 9
 * y el **cuadro 3×3** en paralelo. Cada lista son los 9 dígitos de esa unidad; `*Errors`
 * son los índices (0-based) a pintar en rojo (las celdas que provocan la repetición).
 */
@Composable
private fun SudokuUnits(
    accent: Color,
    row: List<Int>,
    rowErrors: Set<Int>,
    column: List<Int>,
    columnErrors: Set<Int>,
    block: List<Int>,
    blockErrors: Set<Int>,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SudokuUnitLabel("Fila")
        Spacer(Modifier.height(6.dp))
        SudokuLine(numbers = row, errors = rowErrors, accent = accent, horizontal = true)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SudokuUnitLabel("Columna")
                Spacer(Modifier.height(6.dp))
                SudokuLine(numbers = column, errors = columnErrors, accent = accent, horizontal = false)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SudokuUnitLabel("Cuadro 3×3")
                Spacer(Modifier.height(6.dp))
                SudokuBlock(numbers = block, errors = blockErrors, accent = accent)
            }
        }
    }
}

/** Etiqueta pequeña de una unidad de Sudoku ("Fila", "Columna", "Cuadro 3×3"). */
@Composable
private fun SudokuUnitLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = LogicColors.OnDarkMuted,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Fila (u columna, si `horizontal = false`) de celdas de Sudoku a partir de sus dígitos. */
@Composable
private fun SudokuLine(numbers: List<Int>, errors: Set<Int>, accent: Color, horizontal: Boolean) {
    if (horizontal) {
        Row(horizontalArrangement = Arrangement.spacedBy(SudokuGap)) {
            numbers.forEachIndexed { i, n ->
                SudokuCell(number = n, error = i in errors, accent = accent)
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(SudokuGap)) {
            numbers.forEachIndexed { i, n ->
                SudokuCell(number = n, error = i in errors, accent = accent)
            }
        }
    }
}

/** Cuadro 3×3 de Sudoku a partir de 9 dígitos en orden fila a fila. */
@Composable
private fun SudokuBlock(numbers: List<Int>, errors: Set<Int>, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(SudokuGap)) {
        for (r in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(SudokuGap)) {
                for (c in 0 until 3) {
                    val idx = r * 3 + c
                    SudokuCell(number = numbers[idx], error = idx in errors, accent = accent)
                }
            }
        }
    }
}

/**
 * Diagrama de **Buscaminas** (Neon Defuser): un único ejemplo **conciso** de deducción. A
 * partir de dos "1" ya revelados se razona dónde está la mina (escudo) y qué casilla queda
 * garantizada como segura, para enseñar el porqué —no solo la regla— a quien no sabe jugar.
 *
 * Disposición del fragmento (3×3):
 * ```
 *   1   1   ·
 *  [🛡]  1   ·
 *  [✓]  1   ·
 * ```
 * - El **1 de la esquina** (arriba-izq.) solo tiene una casilla sin abrir a su lado —la del
 *   escudo—, así que su mina tiene que estar ahí.
 * - Los **1 del centro y de abajo** ya tocan esa mina; por tanto el resto de sus vecinas,
 *   incluida la de **debajo del escudo** (✓), están libres.
 */
@Composable
fun DefuserHelpDiagram(accent: Color) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DiagramIntro("El número de una casilla dice cuántas de sus 8 vecinas ocultan una mina.")
        Spacer(Modifier.height(16.dp))
        MineGrid(
            accent = accent,
            cells = listOf(
                MineKind.Number(1), MineKind.Number(1), MineKind.Hidden,
                MineKind.Shield, MineKind.Number(1), MineKind.Hidden,
                MineKind.Safe, MineKind.Number(1), MineKind.Hidden,
            ),
        )
        Spacer(Modifier.height(16.dp))
        MineReason(
            icon = KortexIcons.Shield,
            tint = accent,
            text = "Hay mina donde el escudo: al 1 de la esquina solo le queda esa casilla sin abrir, así que su única mina está ahí.",
        )
        Spacer(Modifier.height(10.dp))
        MineReason(
            icon = KortexIcons.Check,
            tint = LogicColors.Success,
            text = "Debajo del escudo no hay mina: los 1 del centro y de abajo ya tocan esa mina, así que sus demás casillas quedan libres.",
        )
    }
}

/** Fila de explicación del diagrama: icono de color + texto de razonamiento. */
@Composable
private fun MineReason(icon: ImageVector, tint: Color, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NeonIcon(
            icon = icon,
            tint = tint,
            size = 20.dp,
            glow = false,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDarkMuted,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Frase de contexto sobre los ejemplos, centrada y atenuada. */
@Composable
private fun DiagramIntro(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = LogicColors.OnDarkMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Celda de Sudoku del diagrama: cuadrado con un dígito; rojo si participa en un conflicto. */
@Composable
private fun SudokuCell(number: Int, error: Boolean, accent: Color) {
    val outline = if (error) LogicColors.Error else accent
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(SudokuCellSize)
            .clip(shape)
            .background(
                if (error) LogicColors.Error.copy(alpha = 0.14f) else LogicColors.SurfaceDark,
            )
            .border(BorderStroke(1.dp, outline.copy(alpha = 0.65f)), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$number",
            style = MaterialTheme.typography.labelMedium,
            color = if (error) LogicColors.Error else LogicColors.OnDark,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Estados posibles de una casilla del fragmento de Buscaminas. */
private sealed interface MineKind {
    /** Casilla sin descubrir (relieve neutro). */
    data object Hidden : MineKind

    /** Casilla revelada con su cuenta de minas vecinas. */
    data class Number(val count: Int) : MineKind

    /** Casilla marcada con un escudo: mina deducida (resaltada con el acento). */
    data object Shield : MineKind

    /** Casilla deducida **sin** mina: segura, se puede abrir (check verde). */
    data object Safe : MineKind
}

/** Fragmento 3×3 de tablero de Buscaminas a partir de 9 [MineKind] en orden fila a fila. */
@Composable
private fun MineGrid(accent: Color, cells: List<MineKind>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0 until 3) {
                    MineCell(kind = cells[row * 3 + col], accent = accent)
                }
            }
        }
    }
}

/** Una casilla del fragmento de Buscaminas, pintada según su [MineKind]. */
@Composable
private fun MineCell(kind: MineKind, accent: Color) {
    val shape = RoundedCornerShape(8.dp)
    val (bg, outline) = when (kind) {
        // Oculta: mismo borde de acento que las numeradas pero SIN dígito, para que la
        // rejilla se lea como un tablero coherente (no casillas "apagadas" sueltas).
        MineKind.Hidden -> LogicColors.SurfaceDark to accent.copy(alpha = 0.4f)
        is MineKind.Number -> LogicColors.SurfaceDark to accent.copy(alpha = 0.4f)
        MineKind.Shield -> accent.copy(alpha = 0.20f) to accent.copy(alpha = 0.85f)
        MineKind.Safe -> LogicColors.Success.copy(alpha = 0.14f) to LogicColors.Success.copy(alpha = 0.55f)
    }
    Box(
        modifier = Modifier
            .size(CellSize)
            .clip(shape)
            .background(bg)
            .border(BorderStroke(if (kind == MineKind.Shield) 1.5.dp else 1.dp, outline), shape),
        contentAlignment = Alignment.Center,
    ) {
        when (kind) {
            MineKind.Hidden -> Unit
            is MineKind.Number -> Text(
                "${kind.count}",
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.Black,
            )
            // El escudo se resalta con halo (glow) como la casilla recién marcada del juego.
            MineKind.Shield -> NeonIcon(
                icon = KortexIcons.Shield,
                tint = accent,
                size = 20.dp,
                glow = true,
            )
            MineKind.Safe -> NeonIcon(
                icon = KortexIcons.Check,
                tint = LogicColors.Success,
                size = 20.dp,
                glow = false,
            )
        }
    }
}
