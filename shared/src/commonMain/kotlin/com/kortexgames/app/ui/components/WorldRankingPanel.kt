package com.kortexgames.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.domain.model.GameRanking
import com.kortexgames.app.domain.model.formatDurationShort
import com.kortexgames.app.domain.model.LeaderboardEntry
import kotlin.math.roundToInt

/**
 * Tramo de la comparativa mundial al terminar una partida.
 *
 * El reparto NO es simétrico a propósito. Por debajo de la media el porcentaje se
 * **oculta**: "eres mejor que el 12%" es un dato correcto y desmotivador, y este
 * juego se abandona por frustración, no por falta de precisión estadística. En su
 * lugar se enseña el puesto —que es accionable, "me faltan 3 para subir"— con un
 * mensaje que empuja a repetir. A partir de la media el porcentaje sí se muestra,
 * porque ahí el número es el premio, y el tono sube por escalones para que cada
 * tramo se sienta distinto del anterior.
 */
enum class WorldRankTier {
    /** Nadie más ha jugado todavía: no hay comparativa posible, solo mérito de llegar primero. */
    PIONEER,

    /** Por debajo de la media (<50%). Sin porcentaje: puesto + empujón. */
    LEARNING,

    /** Ya supera a la mitad del mundo (50–80%). */
    RISING,

    /** Entre los mejores (80–95%). */
    STRONG,

    /** Top 5% mundial (≥95%), pero no el primero. */
    ELITE,

    /** Nº1 del mundo. Único tramo que dispara la celebración a pantalla completa. */
    CHAMPION;

    companion object {
        /**
         * Clasifica un [ranking]. El orden de los `when` importa: el puesto manda
         * sobre el porcentaje (ser nº1 con pocos jugadores puede dar un % bajo).
         */
        fun of(ranking: GameRanking): WorldRankTier = when {
            // Sin rivales el "nº1 del mundo" sería un título vacío; se dice la verdad.
            ranking.totalPlayers <= 1L -> PIONEER
            ranking.rank == 1L -> CHAMPION
            ranking.betterThanPct >= 95.0 -> ELITE
            ranking.betterThanPct >= 80.0 -> STRONG
            ranking.betterThanPct >= 50.0 -> RISING
            else -> LEARNING
        }
    }
}

/**
 * Color de acento del tramo. Escala deliberada de "frío y neutro" (cian, sin juicio)
 * a "recompensa" (ámbar), para que el jugador note el ascenso incluso antes de leer.
 */
private val WorldRankTier.accent: Color
    get() = when (this) {
        WorldRankTier.PIONEER, WorldRankTier.LEARNING -> LogicColors.NeonCyan
        WorldRankTier.RISING -> LogicColors.NeonGreen
        WorldRankTier.STRONG -> LogicColors.Violet
        WorldRankTier.ELITE -> LogicColors.Magenta
        WorldRankTier.CHAMPION -> LogicColors.Amber
    }

/** Icono del tramo (vectorial, §9.5): posición → progreso → medalla → trofeo. */
private val WorldRankTier.icon: ImageVector
    get() = when (this) {
        WorldRankTier.PIONEER -> KortexIcons.Star
        WorldRankTier.LEARNING -> KortexIcons.Leaderboard
        WorldRankTier.RISING -> KortexIcons.TrendingUp
        WorldRankTier.STRONG, WorldRankTier.ELITE -> KortexIcons.Medal
        WorldRankTier.CHAMPION -> KortexIcons.Trophy
    }

/**
 * Porcentaje ya redondeado y listo para pintar.
 *
 * Se topa en 99 mientras el jugador no sea el nº1: un 99,6 % redondearía a "100 %
 * de los jugadores" teniendo a alguien por delante, y esa contradicción se lee al
 * instante en la lista que hay justo debajo.
 */
private fun GameRanking.displayPct(): Int {
    val rounded = betterThanPct.roundToInt()
    return if (rank == 1L) rounded else rounded.coerceAtMost(99)
}

/**
 * Mejor marca del propio jugador según el ranking, o null si su fila no vino en la
 * ventana (no debería: la ventana siempre se centra en él).
 *
 * Ojo con el matiz: la partida recién jugada YA está contada, así que este valor es
 * "mi récord incluyendo lo de ahora". De ahí que comparar con el puntaje de esta
 * partida baste para saber si la ha superado ([recordStillHeld]).
 */
private fun GameRanking.myBestScore(): Int? =
    entries.firstOrNull { it.isCurrentUser }?.score

/**
 * ¿El jugador **conserva** un récord que esta partida no ha superado? Es decir, su
 * mejor marca es estrictamente mayor que lo que acaba de puntuar.
 *
 * Un empate cuenta como "no superado" pero tampoco como récord conservado: repetir
 * clavado tu propia marca no merece ni celebración ni consuelo.
 */
private fun GameRanking.recordStillHeld(currentScore: Int): Boolean {
    val best = myBestScore() ?: return false
    // "Mejor" es más ALTO por puntos pero más BAJO por tiempo: sin este giro, un juego
    // rankeado por tiempo felicitaría al jugador justo cuando ha ido más lento.
    return if (rankedByTime) best < currentScore else best > currentScore
}

/**
 * Formatea una marca del ranking en la unidad que le corresponde: tiempo cuando la tabla se ordena
 * por rapidez y puntos en el resto. Es el único sitio donde se decide, para que la lista, la nota
 * del récord y el mensaje del campeón no puedan contarlo de dos formas distintas.
 */
private fun GameRanking.formatMetric(value: Int): String =
    if (rankedByTime) formatDurationShort(value.toLong()) else "$value puntos"

/**
 * Textos del bloque. Se resuelven de una pieza —y no en tres funciones sueltas—
 * porque el titular, el mensaje y la nota tienen que contar la MISMA historia: en
 * cuanto el titular cambia de "¡Eres el nº1!" a "Sigues siendo el nº1", el mensaje
 * de abajo tiene que dejar de felicitar por algo que no ha pasado.
 *
 * @property note línea secundaria, o null. Recuerda el récord que sigue en pie cuando
 *   la partida no lo ha batido: sin ella, terminar por debajo de tu marca se lee como
 *   si hubieras perdido puesto, cuando el ranking guarda tu MEJOR marca y no la última.
 */
private data class RankingCopy(
    val title: String,
    val message: String,
    val note: String?,
)

/**
 * Resuelve los textos del tramo. El eje que manda, además del tramo, es si la partida
 * **ha superado algo** ([GameRanking.recordStillHeld]): celebrar una hazaña que ocurrió
 * hace tres partidas cansa y, peor, hace que la celebración de verdad no signifique nada.
 */
private fun WorldRankTier.copyFor(ranking: GameRanking, currentScore: Int): RankingCopy {
    val best = ranking.myBestScore()
    val held = ranking.recordStillHeld(currentScore)
    // Nota común a todos los tramos salvo CHAMPION, que la integra en su mensaje.
    val note = if (held && best != null) "Tu récord sigue en ${ranking.formatMetric(best)}." else null

    return when (this) {
        WorldRankTier.PIONEER -> RankingCopy(
            title = "¡Eres el primero!",
            message = "Todavía no hay con quién compararte: tu marca abre el ranking mundial.",
            note = note,
        )
        WorldRankTier.LEARNING -> RankingCopy(
            title = "Puesto #${ranking.rank} de ${ranking.totalPlayers}",
            message = "Cada partida te sube puestos. ¡Vuelve a intentarlo y alcanza al de arriba!",
            note = note,
        )
        WorldRankTier.RISING -> RankingCopy(
            title = "Mejor que el ${ranking.displayPct()}% de los jugadores",
            message = "¡Buen ritmo! Ya dejas atrás a más de la mitad del mundo.",
            note = note,
        )
        WorldRankTier.STRONG -> RankingCopy(
            title = "Mejor que el ${ranking.displayPct()}% de los jugadores",
            message = "¡Estás entre los mejores! El top mundial está a un paso.",
            note = note,
        )
        WorldRankTier.ELITE -> RankingCopy(
            title = "Mejor que el ${ranking.displayPct()}% de los jugadores",
            message = "¡Élite mundial! Estás en el 5% mejor del planeta.",
            note = note,
        )
        // Es el único tramo que se celebra a lo grande, así que es también donde más
        // importa distinguir "acabas de conseguirlo" de "lo conseguiste y lo mantienes".
        WorldRankTier.CHAMPION -> if (held && best != null) {
            RankingCopy(
                title = "Sigues siendo el nº1 del mundo",
                message = "Tu récord de ${ranking.formatMetric(best)} aguanta: esta partida no lo ha superado.",
                note = null,
            )
        } else {
            RankingCopy(
                title = "¡Eres el nº1 del mundo!",
                message = "Nadie ha superado tu marca entre ${ranking.totalPlayers} jugadores. " +
                    "Ahora toca defenderla.",
                note = null,
            )
        }
    }
}

/**
 * Bloque "comparativa con el mundo" de la tarjeta de fin de partida: mensaje del
 * tramo alcanzado + el trocito de ranking donde vive el jugador (1 por encima y 3
 * por debajo, con nombre y mejor marca de cada uno).
 *
 * Enseñar a los vecinos —y no solo un porcentaje— es lo que convierte el dato en
 * gancho: el objetivo deja de ser abstracto ("subir un percentil") y pasa a tener
 * cara y puntuación concreta ("me faltan 40 puntos para pasar a Nadia").
 *
 * La celebración a pantalla completa NO se lanza desde aquí: los fuegos artificiales
 * tienen que dibujarse por encima de TODA la tarjeta, así que los monta el llamador
 * (y solo cuando la partida ha batido algo de verdad, ver `GameOverOverlay`).
 *
 * @param ranking comparativa ya resuelta; el llamador no pinta este bloque si es null.
 * @param currentScore marca de ESTA partida, **en la unidad con la que se ordena la
 *   tabla** (puntos, o milisegundos si [GameRanking.rankedByTime]). Hace falta para
 *   distinguirla de la mejor marca del jugador —que es lo que muestra el ranking— y así
 *   poder decirle que su récord sigue en pie cuando esta partida no lo ha superado.
 */
@Composable
fun WorldRankingPanel(
    ranking: GameRanking,
    currentScore: Int,
    modifier: Modifier = Modifier,
) {
    val tier = remember(ranking) { WorldRankTier.of(ranking) }
    val copy = remember(ranking, currentScore) { tier.copyFor(ranking, currentScore) }
    val accent = tier.accent
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                // Tinte del color del tramo, muy translúcido: destaca el bloque sin
                // inundar la superficie oscura (§9.1, el neón vale porque es escaso).
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.16f), accent.copy(alpha = 0.05f)),
                ),
            )
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.45f)), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Récord mundial: corona el bloque por encima del propio mensaje del tramo.
        if (ranking.isGlobalRecord) {
            GlobalRecordBadge(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeonIcon(icon = tier.icon, tint = accent, size = 24.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    copy.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                )
                Text(
                    copy.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.OnDarkMuted,
                )
                // "Tu récord sigue en N puntos": la lista de abajo muestra la MEJOR
                // marca de cada jugador, no la última, así que sin esta línea una
                // partida floja parece haber tirado el puesto por tierra.
                copy.note?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = accent.copy(alpha = 0.85f),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        if (ranking.entries.isNotEmpty()) {
            // Rótulo de la tabla en los juegos con dificultad elegible: sin él, ver un
            // top distinto al cambiar de dificultad parecería un fallo en vez de lo
            // que es —tablas separadas, como el récord de Experto del Buscaminas—.
            ranking.difficultyLabel?.let { label ->
                Text(
                    "RANKING · ${label.uppercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = accent.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ranking.entries.forEachIndexed { index, entry ->
                    LeaderboardRow(
                        entry = entry,
                        accent = accent,
                        index = index,
                        metricLabel = ranking::formatMetric,
                    )
                }
            }
        }
    }
}

/**
 * Fila del ranking: puesto, nombre y mejor marca. La del propio jugador se resalta
 * (superficie elevada + borde de acento) para que la encuentre sin leer nombres.
 *
 * Entra con un fundido + deslizamiento escalonado por [index]: la lista se "escribe"
 * de arriba abajo en ~400 ms, lo justo para que el ojo siga el orden del ranking sin
 * que nadie tenga que esperar a que termine (§9.4: la animación no bloquea).
 */
@Composable
private fun LeaderboardRow(
    entry: LeaderboardEntry,
    accent: Color,
    index: Int,
    metricLabel: (Int) -> String,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val reveal by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(
            durationMillis = 260,
            delayMillis = index * 70,
            easing = FastOutSlowInEasing,
        ),
        label = "leaderboardRowReveal",
    )

    val shape = RoundedCornerShape(10.dp)
    val rowModifier = if (entry.isCurrentUser) {
        Modifier
            .clip(shape)
            .background(LogicColors.SurfaceVariantDark)
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.7f)), shape)
    } else {
        Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(reveal)
            // Deslizamiento corto (8dp) que acompaña al fundido; con `reveal` a 1
            // queda exactamente en su sitio.
            .padding(top = ((1f - reveal) * 8f).dp)
            .then(rowModifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "#${entry.rank}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.isCurrentUser) accent else LogicColors.OnDarkMuted,
            fontWeight = FontWeight.Bold,
            // Ancho fijo para que nombres y puntos queden alineados en columna
            // aunque los puestos tengan 1, 2 o 4 cifras.
            modifier = Modifier.width(42.dp),
        )
        Text(
            // Sin nombre elegido, un genérico: el backend manda `display_name` a null
            // antes que inventar nada, y aquí nunca hay id con el que identificar a nadie.
            entry.displayName?.takeIf { it.isNotBlank() } ?: "Jugador anónimo",
            style = MaterialTheme.typography.bodyLarge,
            color = if (entry.isCurrentUser) LogicColors.OnDark else LogicColors.OnDarkMuted,
            fontWeight = if (entry.isCurrentUser) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            metricLabel(entry.score),
            style = MaterialTheme.typography.titleMedium,
            color = if (entry.isCurrentUser) accent else LogicColors.OnDark,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Nº de pasadas del destello del récord mundial: celebra y se calma (no es un bucle). */
private const val RECORD_SHIMMER_PASSES = 3

/**
 * Píldora "¡NUEVO RÉCORD MUNDIAL!": el hito más alto de la app, así que se distingue
 * del récord personal ([NewRecordBadge] en `GameOverOverlay`) por movimiento, no solo
 * por texto — entra con un "pop" de resorte y la recorre un **destello** que barre la
 * píldora de lado a lado, como el reflejo sobre una placa metálica.
 *
 * El destello es finito ([RECORD_SHIMMER_PASSES] pasadas) para no dejar un bucle
 * permanente compitiendo con el CTA de la tarjeta (§9.4).
 */
@Composable
private fun GlobalRecordBadge(modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "globalRecordScale",
    )

    // 0→1 = el destello cruza la píldora una vez; `repeatable` lo repite y se detiene.
    val shimmer = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        shimmer.animateTo(
            targetValue = 1f,
            animationSpec = repeatable(
                iterations = RECORD_SHIMMER_PASSES,
                animation = tween(durationMillis = 900, easing = LinearEasing),
            ),
        )
    }

    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .scale(scale)
            .softGlow(LogicColors.Amber, shape = shape)
            .clip(shape)
            .background(Brush.horizontalGradient(LogicGradients.reward))
            .drawWithContent {
                drawContent()
                // La banda va de -0.5 a 1.5 del ancho para entrar y salir fuera de
                // cuadro; el `clip` de arriba recorta lo que sobra.
                val x = (shimmer.value * 2f - 0.5f) * size.width
                val half = size.width * 0.22f
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.55f),
                            Color.Transparent,
                        ),
                        start = Offset(x - half, 0f),
                        end = Offset(x + half, size.height),
                    ),
                )
            }
            .border(BorderStroke(1.5.dp, LogicColors.Amber), shape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeonIcon(
            icon = KortexIcons.Trophy,
            tint = LogicColors.BackgroundDark,
            size = 18.dp,
            glow = false,
        )
        Text(
            "¡NUEVO RÉCORD MUNDIAL!",
            style = MaterialTheme.typography.labelLarge,
            color = LogicColors.BackgroundDark,
            fontWeight = FontWeight.Black,
        )
    }
}

/**
 * Aviso de que la partida quedó guardada en local pero aún no se puede comparar
 * (modo invitado, sin red o fallo puntual del backend). Se mantiene neutro a
 * propósito: no es un error del jugador, y la partida NO se ha perdido.
 */
@Composable
fun WorldRankingUnavailable(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LogicColors.SurfaceVariantDark)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Guardado localmente · inicia sesión para comparar con el mundo",
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDarkMuted,
        )
    }
}
