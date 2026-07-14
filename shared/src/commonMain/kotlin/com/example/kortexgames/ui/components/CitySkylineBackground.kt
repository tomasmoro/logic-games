package com.example.kortexgames.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.kortexgames.core.theme.CategoryPalette
import com.example.kortexgames.core.theme.LogicColors

/**
 * Fondo decorativo de **skyline de ciudad "neón nocturno"** para pantallas de juego.
 * Dibuja varias **capas de siluetas de edificios** con efecto de profundidad
 * (parallax estático): las capas **traseras** son **más altas** (tejados más arriba) y
 * de color cada vez más **apagado** (mezclado hacia el fondo azul-noche); las
 * **delanteras** son **más bajas** (tejados más abajo) y de color más vivo hacia el
 * acento. Se dibujan de atrás hacia delante, así las capas de tejados más bajos quedan
 * **por delante** de las de tejados más altos y se ven varias líneas de tejados
 * claramente **escalonadas en vertical** hacia el horizonte.
 *
 * Decisiones de diseño (CLAUDE.md §9.1 "superficie mayormente oscura, acento
 * escaso"), en la misma línea que [ArcadeBrickBackground]:
 *  - Los edificios son **opacos**: en vez de bajar la opacidad (translucidez), cada
 *    capa **interpola su color** entre el fondo ([LogicColors.BackgroundDark]) y el
 *    acento. Así las capas lejanas quedan más apagadas y las cercanas más vivas, pero
 *    todas ocultan de forma limpia lo que tienen detrás (sin transparencias sumadas).
 *  - Las capas se **separan verticalmente** por altura: las traseras son más altas y
 *    su línea de tejados asoma por encima de la capa de delante (todas ancladas al
 *    borde inferior, así el frente tapa limpiamente el fondo).
 *  - La "junta" oscura entre edificios de una misma capa es el propio fondo asomando
 *    (misma técnica que las juntas del muro de ladrillos).
 *  - Un **filo de tejado más claro** por edificio simula el borde iluminado de la
 *    ciudad (relieve barato, coherente con el bisel del fondo de ladrillos).
 *  - Unas pocas **ventanas encendidas** (ámbar, muy escasas) en la capa más cercana
 *    dan el guiño "juego" sin saturar.
 *  - Un **resplandor de horizonte** bajo el skyline y una **viñeta radial** enmarcan
 *    la escena para que el contenido central siga mandando la atención.
 *
 * Es puramente estático y determinista (las alturas/anchos salen de un hash estable),
 * sin estado. Colócalo como primer hijo de un `Box` a pantalla completa, debajo del
 * contenido.
 *
 * @param accent color de la ciudad (por defecto índigo Visión Espacial). Se mezcla con
 *   el fondo internamente; pasa el color pleno de la categoría.
 * @param intensity 0f..1f atenúa cuánto se acerca cada capa al acento (1 = valor de
 *   diseño; baja para dejar la ciudad más apagada/mezclada con el fondo).
 */
@Composable
fun CitySkylineBackground(
    modifier: Modifier = Modifier,
    accent: Color = CategoryPalette.SpatialVision,
    intensity: Float = 1f,
) {
    Canvas(modifier = modifier) {
        // Resplandor del horizonte: sube desde la base del skyline (efecto de neón de
        // ciudad iluminando el cielo) antes de dibujar las siluetas encima.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, accent.copy(alpha = 0.06f * intensity)),
                startY = size.height * 0.45f,
                endY = size.height,
            ),
        )

        // Mezcla de color por capa: fracción de acento sobre el fondo azul-noche, de la
        // más lejana (más apagada, casi fondo) a la más cercana (más viva). Sin alfa:
        // los edificios son opacos y cada capa tapa limpiamente la de detrás.
        // El índice de capa crece hacia DELANTE: 0 = fondo (apagado, tejados altos),
        // último = frente (vivo, tejados bajos). Se dibujan en este orden, así las capas
        // de tejados más bajos quedan por delante de las de tejados más altos.
        val layerTints = floatArrayOf(0.10f, 0.20f, 0.34f, 0.52f)
        val layerCount = layerTints.size

        for (layer in 0 until layerCount) {
            // `depth` = distancia al frente (0 = capa delantera). Las traseras son más
            // altas para que su línea de tejados asome por encima de las delanteras.
            val depth = layerCount - 1 - layer
            val tint = (layerTints[layer] * intensity).coerceIn(0f, 1f)
            val bodyColor = lerp(LogicColors.BackgroundDark, accent, tint)
            // Filo de tejado: acercado más al acento para insinuar el borde iluminado.
            val roofColor = lerp(LogicColors.BackgroundDark, accent, (tint + 0.18f).coerceAtMost(0.7f))

            // Alturas escalonadas: las traseras (depth alto) son más altas → sus tejados
            // quedan más arriba; las delanteras más bajas. La separación entre bandas de
            // altura da el escalonado vertical visible entre líneas de tejado.
            val minHeightFrac = 0.18f + depth * 0.12f
            val maxHeightFrac = minHeightFrac + 0.16f
            // Las capas delanteras son algo más anchas (más cerca = piezas más grandes).
            val baseWidthDp = 38f + layer * 8f
            val seam = 2.dp.toPx() // junta oscura entre edificios (el fondo asoma)

            // Arranque desplazado por capa para que las siluetas no se alineen.
            var x = -hash(layer * 991 + 5) * 70.dp.toPx()
            var col = 0
            while (x < size.width) {
                val widthSeed = hash(layer * 1000 + col * 7 + 3)
                val heightSeed = hash(layer * 1000 + col * 7 + 11)

                val w = (baseWidthDp + widthSeed * 28f).dp.toPx()
                val h = (minHeightFrac + heightSeed * (maxHeightFrac - minHeightFrac)) * size.height
                // Todas las capas se anclan al borde inferior; el escalonado de tejados
                // sale de las alturas, no de mover la base (así el frente tapa el fondo).
                val top = size.height - h
                val bodyH = h
                val bodyW = (w - seam).coerceAtLeast(1f)

                drawRect(bodyColor, topLeft = Offset(x, top), size = Size(bodyW, bodyH))
                drawRect(roofColor, topLeft = Offset(x, top), size = Size(bodyW, 2.dp.toPx()))

                // Antena ocasional en algunos edificios de las capas traseras (torres altas).
                if (depth <= 1 && heightSeed > 0.72f) {
                    val antX = x + bodyW * 0.5f
                    drawRect(
                        color = roofColor,
                        topLeft = Offset(antX - 0.75.dp.toPx(), top - 10.dp.toPx()),
                        size = Size(1.5.dp.toPx(), 10.dp.toPx()),
                    )
                }

                // Ventanas encendidas solo en la capa más cercana y muy escasas.
                if (layer == layerCount - 1) {
                    drawWindows(x, top, bodyW, bodyH, col, intensity)
                }

                x += w
                col++
            }
        }

        // Viñeta: oscurece bordes hacia el fondo para no competir con el contenido.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    LogicColors.BackgroundDark.copy(alpha = 0.9f),
                ),
                center = Offset(size.width / 2f, size.height * 0.42f),
                radius = size.maxDimension * 0.62f,
            ),
        )
    }
}

/**
 * Pinta una retícula de **ventanas** dentro del edificio, encendiendo solo una
 * fracción (hash estable) con un ámbar tenue para dar vida sin saturar. Las ventanas
 * apagadas se omiten (el propio cuerpo oscuro del edificio hace de hueco).
 */
private fun DrawScope.drawWindows(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    building: Int,
    intensity: Float,
) {
    val cell = 9.dp.toPx()
    val win = 3.dp.toPx()
    val pad = 5.dp.toPx()
    val litColor = LogicColors.Amber.copy(alpha = 0.16f * intensity)

    var wy = top + pad
    var rowIdx = 0
    while (wy + win < top + height) {
        var wx = left + pad
        var colIdx = 0
        while (wx + win < left + width - pad) {
            // ~1 de cada 4 ventanas encendida (patrón estable por edificio/celda).
            if (hash(building * 613 + rowIdx * 37 + colIdx * 17) > 0.75f) {
                drawRect(litColor, topLeft = Offset(wx, wy), size = Size(win, win))
            }
            wx += cell
            colIdx++
        }
        wy += cell
        rowIdx++
    }
}

/**
 * Hash entero→[0f,1f) determinista y barato (mezcla estilo xorshift). Da variación
 * estable de alturas, anchos y ventanas sin necesidad de `Random` ni estado, para
 * que el fondo se pinte idéntico en cada recomposición.
 */
private fun hash(input: Int): Float {
    var x = input * 374761393 + 668265263
    x = (x xor (x shr 13)) * 1274126177
    x = x xor (x shr 16)
    return (x and 0x7FFFFFFF) / Int.MAX_VALUE.toFloat()
}

@Preview
@Composable
private fun CitySkylineBackgroundPreview() {
    CitySkylineBackground(modifier = Modifier, accent = CategoryPalette.SpatialVision, intensity = 1f)
}
