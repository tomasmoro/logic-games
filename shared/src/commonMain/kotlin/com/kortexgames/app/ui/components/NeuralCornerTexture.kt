package com.kortexgames.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/**
 * Textura decorativa de **red neuronal** (nodos + sinapsis) para la esquina
 * superior derecha de una tarjeta. Evoca "cerebro/mente" con líneas neón y se
 * **desvanece hacia el centro** mediante un degradado radial anclado en la
 * esquina: intenso arriba-derecha, transparente hacia el centro.
 *
 * Es puramente ornamental (no interactivo): dale un [Modifier] que lo haga ocupar
 * toda la tarjeta (p. ej. `Modifier.matchParentSize()`) y colócalo DETRÁS del
 * contenido. El recorte a las esquinas redondeadas lo aporta la tarjeta padre.
 *
 * @param accent color neón de la textura (normalmente el de la categoría).
 * @param intensity opacidad máxima en la esquina (0f..1f).
 */
@Composable
fun NeuralCornerTexture(
    accent: Color,
    modifier: Modifier = Modifier,
    intensity: Float = 0.55f,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Degradado radial anclado en la esquina superior derecha: todas las
        // líneas y nodos se pintan con este brush, así el fundido "hacia el
        // centro" sale gratis y coherente para toda la malla.
        val brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = intensity), accent.copy(alpha = 0f)),
            center = Offset(w, 0f),
            radius = w * 1.15f,
        )

        // Nodos (neuronas) en coordenadas normalizadas del cuadrante superior
        // derecho. Deterministas para que la textura sea estable entre frames.
        val nodes = listOf(
            0.80f to 0.03f, 1.02f to 0.07f, 0.66f to 0.16f, 0.90f to 0.21f,
            0.58f to 0.31f, 0.82f to 0.34f, 1.06f to 0.31f, 0.73f to 0.49f,
            0.98f to 0.47f,
        ).map { Offset(it.first * w, it.second * h) }

        // Sinapsis (aristas) que conectan neuronas cercanas.
        val edges = listOf(
            0 to 1, 0 to 2, 0 to 3, 1 to 3, 2 to 3, 3 to 5, 3 to 6,
            2 to 4, 4 to 5, 5 to 6, 5 to 7, 4 to 7, 6 to 8, 7 to 8, 5 to 8,
        )

        val stroke = 1.3.dp.toPx()
        edges.forEach { (a, b) ->
            drawLine(
                brush = brush,
                start = nodes[a],
                end = nodes[b],
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }

        // Neuronas: núcleo pequeño; algunos "hubs" (índices pares) un poco mayores.
        nodes.forEachIndexed { i, p ->
            drawCircle(
                brush = brush,
                radius = (if (i % 2 == 0) 3.4.dp else 2.2.dp).toPx(),
                center = p,
            )
        }
    }
}
