package com.kortexgames.app.ui.profile

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.domain.model.EventAward
import com.kortexgames.app.game.GameCatalog
import com.kortexgames.app.game.GameInfo
import com.kortexgames.app.ui.components.GameMotifIcon
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.NeonIcon
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.profile_awards_champion
import kortexgames.shared.generated.resources.profile_awards_champion_count
import kortexgames.shared.generated.resources.profile_awards_champion_count_one
import kortexgames.shared.generated.resources.profile_awards_empty
import kortexgames.shared.generated.resources.profile_awards_position
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Vitrina de insignias de torneo del perfil: un carrusel de trofeos.
 *
 * ## Por qué vive aparte de los logros
 * Son dos sistemas distintos y deben verse distintos (ver la migración 0059): un
 * logro es progreso hacia un umbral sobre tus propias estadísticas; una insignia es
 * un puesto conseguido contra otras personas en una fecha concreta. Mezclarlas en
 * una sola lista rebajaría la segunda a "una más" justo por lo que la hace valer.
 *
 * ## Decisiones (§9)
 * - **Carrusel horizontal de piezas cuadradas.** Una vitrina crece sin techo: en
 *   vertical empujaría hacia abajo todo lo demás del perfil cada vez que ganas algo,
 *   mientras que en horizontal ocupa siempre la misma altura y premiar más no
 *   penaliza la pantalla.
 * - **El dibujo del juego, de fondo.** El mismo motivo del catálogo
 *   ([GameMotifIcon]): la insignia se reconoce por el juego antes que por el texto,
 *   y no hay que inventar arte nuevo para algo que el jugador ya asocia.
 * - **El trofeo lleva el color del juego**, no un dorado universal. Así dos trofeos
 *   distintos no se confunden de un vistazo. La única excepción es el puesto, que
 *   sí usa el metal (oro/plata/bronce) en su etiqueta: el color dice "de qué juego"
 *   y el metal dice "qué puesto".
 * - **Brillitos que aparecen y desaparecen.** Es el único sitio de la app donde algo
 *   brilla en reposo, y se lo ha ganado: son trofeos. Un solo reloj compartido para
 *   todas las chispas (ver [SparkleOverlay]) y amplitud baja, para que sea ambiente
 *   y no ruido (§9.4, regla 4).
 */
@Composable
fun EventAwardsSection(awards: List<EventAward>, modifier: Modifier = Modifier) {
    if (awards.isEmpty()) {
        Text(
            text = stringResource(Res.string.profile_awards_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDarkMuted,
            modifier = modifier,
        )
        return
    }

    val champions = remember(awards) { awards.count { it.position == 1L } }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // El contador solo aparece si hay algún primer puesto: "0 veces campeón" es
        // una forma rebuscada de decir que no has ganado, y quien está mirando su
        // vitrina no necesita que se lo recuerden.
        if (champions > 0) {
            ChampionCounter(count = champions)
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            // El carrusel respira por los lados dentro de la tarjeta de sección.
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
            items(awards, key = { it.eventId }) { award -> AwardTile(award) }
        }
    }
}

/** Cuántas veces campeón: el número que el jugador querría poder enseñar. */
@Composable
private fun ChampionCounter(count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(LogicColors.Amber.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        NeonIcon(icon = KortexIcons.Trophy, tint = LogicColors.Amber, size = 18.dp, contentDescription = null)
        Text(
            text = if (count == 1) {
                stringResource(Res.string.profile_awards_champion_count_one)
            } else {
                stringResource(Res.string.profile_awards_champion_count, count.toString())
            },
            style = MaterialTheme.typography.labelLarge,
            color = LogicColors.Amber,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Una pieza de la vitrina: cuadrada, con el dibujo del juego de fondo, el trofeo en
 * el color del juego y el puesto en el metal que le toca.
 */
@Composable
private fun AwardTile(award: EventAward) {
    val game: GameInfo? = GameCatalog.byId(award.gameId)
    val accent = game?.category?.accent ?: LogicColors.Amber
    val metal = MedalMetal.of(award.position)

    Box(
        modifier = Modifier
            .size(TILE_SIZE)
            .clip(TILE_SHAPE)
            .background(
                Brush.verticalGradient(
                    listOf(LogicColors.SurfaceVariantDark, accent.copy(alpha = 0.16f)),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.40f), TILE_SHAPE),
    ) {
        // Fondo: el arte del juego, tenue. Por debajo de todo, incluidas las chispas.
        Box(modifier = Modifier.fillMaxSize().alpha(MOTIF_ALPHA), contentAlignment = Alignment.Center) {
            val motif = game?.motif
            if (motif != null) {
                GameMotifIcon(motif = motif, accent = accent, modifier = Modifier.fillMaxSize())
            } else if (game != null) {
                NeonIcon(icon = game.category.icon, tint = accent, size = TILE_SIZE * 0.5f, glow = false, contentDescription = null)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                NeonIcon(
                    icon = if (metal == MedalMetal.GOLD) KortexIcons.Trophy else KortexIcons.Medal,
                    tint = accent,
                    size = 40.dp,
                    contentDescription = null,
                )
            }

            Text(
                text = award.eventTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDark,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )

            Text(
                text = if (award.position == 1L) {
                    stringResource(Res.string.profile_awards_champion)
                } else {
                    stringResource(
                        Res.string.profile_awards_position,
                        award.position.toString(),
                        award.totalPlayers.toString(),
                    )
                },
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                // El metal va SOLO aquí: el color del juego dice de qué es el trofeo,
                // el metal dice qué puesto fue.
                color = metal.color,
                fontWeight = FontWeight.Bold,
            )
        }

        // Chispas por encima de todo: es lo que hace que la pieza parezca un trofeo
        // y no una tarjeta más.
        SparkleOverlay(
            color = metal.color,
            count = if (metal == MedalMetal.GOLD) GOLD_SPARKLES else PODIUM_SPARKLES,
            seed = award.eventId.hashCode(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Chispas que aparecen y desaparecen sobre la pieza.
 *
 * ## Cómo se anima sin gastar el frame
 * Un solo [rememberInfiniteTransition] mueve un reloj 0→1 y **cada chispa deriva su
 * brillo de ese mismo reloj** con un desfase propio. Es lo que permite tener varias
 * parpadeando a destiempo con una única animación: N animaciones independientes por
 * pieza, multiplicadas por las piezas de la vitrina, sí se notarían.
 *
 * El brillo sigue media onda senoidal en vez de un ir y venir lineal: una chispa que
 * sube y baja a velocidad constante parece un parpadeo de fallo; con la senoidal
 * nace y muere despacio y pasa deprisa por el pico, que es como brilla un destello.
 *
 * Las posiciones se sortean con una semilla ESTABLE (el id del torneo) y se
 * recuerdan: así cada trofeo tiene su propio patrón, siempre el mismo, en vez de
 * saltar a otro sitio en cada recomposición.
 */
@Composable
private fun SparkleOverlay(color: Color, count: Int, seed: Int, modifier: Modifier = Modifier) {
    val sparkles = remember(seed, count) {
        val random = Random(seed)
        List(count) {
            Sparkle(
                x = random.nextFloat(),
                y = random.nextFloat(),
                phase = random.nextFloat(),
                scale = SPARKLE_MIN_SCALE + random.nextFloat() * (1f - SPARKLE_MIN_SCALE),
            )
        }
    }

    val transition = rememberInfiniteTransition(label = "sparkles")
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SPARKLE_CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sparkleClock",
    )

    // Un único Path reutilizado: se reconstruye en sitio para cada chispa y cada
    // frame (su tamaño cambia con el brillo), pero no se asigna memoria nueva en el
    // bucle de dibujo, que es lo que sí se notaría con varias piezas en pantalla.
    val starPath = remember { Path() }

    Canvas(modifier = modifier) {
        val radius = size.minDimension * SPARKLE_RADIUS_RATIO
        sparkles.forEach { sparkle ->
            // Media onda: 0 → 1 → 0 en cada vuelta del reloj, desfasada por chispa.
            val t = (clock + sparkle.phase) % 1f
            val glow = sin(t * PI).toFloat()
            if (glow <= 0.01f) return@forEach

            val center = Offset(x = sparkle.x * size.width, y = sparkle.y * size.height)
            val r = radius * sparkle.scale * glow
            val alpha = glow * SPARKLE_MAX_ALPHA

            drawSparkle(path = starPath, center = center, radius = r, color = color.copy(alpha = alpha))
        }
    }
}

/**
 * Dibuja un destello de cuatro puntas: grueso en el centro y afilado en las puntas.
 *
 * Es un `Path` y no dos trazos cruzados porque una cruz de líneas de grosor
 * constante se lee como un **signo más**, no como un brillo. Lo que convierte lo
 * uno en lo otro es la cintura cóncava: cada punta sale del centro y se estrecha
 * hasta desaparecer. Se consigue con cúbicas cuyos DOS puntos de control están en el
 * centro del destello, que es lo que tira de la curva hacia dentro.
 *
 * El eje vertical es algo más largo que el horizontal ([SPARKLE_ASPECT]): un
 * destello perfectamente simétrico vuelve a parecer un signo, y la asimetría leve es
 * lo que lo hace parecer luz.
 */
private fun DrawScope.drawSparkle(path: Path, center: Offset, radius: Float, color: Color) {
    val ry = radius
    val rx = radius * SPARKLE_ASPECT
    path.reset()
    path.moveTo(center.x, center.y - ry)
    path.cubicTo(center.x, center.y, center.x, center.y, center.x + rx, center.y)
    path.cubicTo(center.x, center.y, center.x, center.y, center.x, center.y + ry)
    path.cubicTo(center.x, center.y, center.x, center.y, center.x - rx, center.y)
    path.cubicTo(center.x, center.y, center.x, center.y, center.x, center.y - ry)
    path.close()
    drawPath(path, color)

    // Núcleo: el punto de luz del que salen las puntas. Sin él, el centro del
    // destello queda igual de tenue que sus extremos y pierde el brillo.
    drawCircle(color = color, radius = radius * SPARKLE_CORE_RATIO, center = center)
}

/**
 * Una chispa: dónde está (0..1 relativo a la pieza), cuándo le toca brillar y cuánto
 * de grande es. Todo relativo para que la pieza pueda cambiar de tamaño sin tocar
 * nada.
 */
private data class Sparkle(
    val x: Float,
    val y: Float,
    val phase: Float,
    val scale: Float,
)

/**
 * Metal del medallón según el puesto. Se modela como `enum` y no como un `when` de
 * colores sueltos para que el criterio ("¿qué es un bronce?") viva en un solo sitio
 * y no se conteste distinto en cada pantalla que enseñe una insignia.
 *
 * Los tonos son los de la paleta, no metales realistas: el oro es el `Amber` de
 * recompensa que ya usa la app, y plata y bronce se derivan de la escala de grises
 * y del coral para no introducir colores nuevos fuera del sistema (§9.2).
 */
private enum class MedalMetal(val color: Color) {
    GOLD(LogicColors.Amber),
    SILVER(Color(0xFFC7D0E8)),
    BRONZE(LogicColors.Coral);

    companion object {
        fun of(position: Long): MedalMetal = when (position) {
            1L -> GOLD
            2L -> SILVER
            else -> BRONZE
        }
    }
}

/** Pieza cuadrada; el ancho entra ~2,5 veces en una pantalla estrecha, así que el
 *  carrusel se ve "cortado" por la derecha y se nota que hay más. */
private val TILE_SIZE = 136.dp
private val TILE_SHAPE = RoundedCornerShape(20.dp)

/** Al campeón se le ponen más chispas; al resto del podio, algo más contenido. */
private const val GOLD_SPARKLES = 6
private const val PODIUM_SPARKLES = 4

/** Vuelta completa del reloj de chispas: lento, de ambiente (§9.4). */
private const val SPARKLE_CYCLE_MS = 2600

private const val SPARKLE_RADIUS_RATIO = 0.075f

/** Ancho del destello respecto a su alto: ligeramente ovalado, no simétrico. */
private const val SPARKLE_ASPECT = 0.62f

/** Núcleo luminoso del centro, respecto al radio del destello. */
private const val SPARKLE_CORE_RATIO = 0.16f
private const val SPARKLE_MIN_SCALE = 0.55f
private const val SPARKLE_MAX_ALPHA = 0.85f
private const val MOTIF_ALPHA = 0.28f
