package com.kortexgames.app.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.domain.model.EventPhase
import com.kortexgames.app.domain.model.GameEvent
import com.kortexgames.app.domain.model.LeaderboardEntry
import com.kortexgames.app.game.GameCatalog
import com.kortexgames.app.game.GameRankingScopes
import com.kortexgames.app.ui.components.AnimatedGameButton
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.bounceClick
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.event_attempts_last
import kortexgames.shared.generated.resources.event_attempts_left
import kortexgames.shared.generated.resources.event_back
import kortexgames.shared.generated.resources.event_cta_play
import kortexgames.shared.generated.resources.event_cta_sign_in
import kortexgames.shared.generated.resources.event_cta_watch_ad
import kortexgames.shared.generated.resources.event_ad_failed
import kortexgames.shared.generated.resources.event_error_closed
import kortexgames.shared.generated.resources.event_error_generic
import kortexgames.shared.generated.resources.event_error_no_attempts
import kortexgames.shared.generated.resources.event_leaderboard_empty
import kortexgames.shared.generated.resources.event_leaderboard_guest
import kortexgames.shared.generated.resources.event_leaderboard_me
import kortexgames.shared.generated.resources.event_leaderboard_not_played
import kortexgames.shared.generated.resources.event_leaderboard_players
import kortexgames.shared.generated.resources.event_leaderboard_title
import kortexgames.shared.generated.resources.event_leaderboard_unknown_player
import kortexgames.shared.generated.resources.event_rule_attempts
import kortexgames.shared.generated.resources.event_rule_attempts_unlimited
import kortexgames.shared.generated.resources.event_rule_own_board
import kortexgames.shared.generated.resources.event_rule_rank_score
import kortexgames.shared.generated.resources.event_rule_rank_time
import kortexgames.shared.generated.resources.event_rule_reward
import kortexgames.shared.generated.resources.event_rule_same_board
import kortexgames.shared.generated.resources.event_rules_title
import kortexgames.shared.generated.resources.event_result_awarded
import kortexgames.shared.generated.resources.event_result_champion
import kortexgames.shared.generated.resources.event_result_no_mark
import kortexgames.shared.generated.resources.event_result_not_awarded
import kortexgames.shared.generated.resources.event_result_not_played
import kortexgames.shared.generated.resources.event_result_rank
import kortexgames.shared.generated.resources.event_result_title
import kortexgames.shared.generated.resources.event_state_finished
import org.jetbrains.compose.resources.stringResource

/**
 * Pantalla de un torneo: qué es, cuánto queda, cómo se puntúa y quién va ganando.
 *
 * Es la **antesala obligatoria** de una partida de torneo: el jugador no entra
 * desde la tarjeta de Home directamente porque en un torneo con tope de intentos
 * un toque accidental cuesta un intento real. Aquí lee las reglas y decide.
 *
 * @param onPlay lanza la partida en modo torneo. Recibe el evento entero —y no su
 *   id— porque quien navega necesita el juego, la dificultad fija y el reto
 *   (`puzzleId`/`seed`) para montar la partida, más los intentos ya gastados, que
 *   el juego usa para avisar de lo que cuesta abandonar.
 */
@Composable
fun EventScreen(
    graph: AppGraph,
    eventId: String,
    onBack: () -> Unit,
    onPlay: (GameEvent, Int, Int?) -> Unit,
    onSignIn: () -> Unit,
) {
    val vm: EventViewModel = viewModel(key = eventId) {
        EventViewModel(
            eventId = eventId,
            events = graph.eventsRepository,
            authRepository = graph.authRepository,
            audio = graph.audio,
            ads = graph.adManager,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Recarga al (re)entrar en la pantalla. Es lo que hace que, al volver de una
    // partida del torneo, el puesto y los intentos estén al día: el ViewModel
    // sobrevive a la navegación (está atado a la entrada del backstack), así que su
    // `init` no se vuelve a ejecutar y sin esto el jugador vería el estado de antes
    // de jugar. En el primer montaje no gasta una llamada de más: el torneo aún no
    // ha llegado de la caché local y `RefreshLeaderboard` se retira solo.
    LaunchedEffect(Unit) { vm.onIntent(EventIntent.RefreshLeaderboard) }

    LaunchedEffect(vm) {
        vm.effect.collect { effect ->
            when (effect) {
                is EventEffect.LaunchGame ->
                    onPlay(effect.event, effect.attemptsUsed, effect.attemptsAllowed)
                EventEffect.RequireSignIn -> onSignIn()
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(LogicColors.SurfaceDark)
                        .bounceClick(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    NeonIcon(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        tint = LogicColors.OnDark,
                        size = 22.dp,
                        glow = false,
                        contentDescription = stringResource(Res.string.event_back),
                    )
                }
            }

            val event = state.event
            if (event == null) {
                // Caché sin el torneo (enlace viejo, o calendario aún sin refrescar).
                Text(
                    text = stringResource(Res.string.event_error_generic),
                    style = MaterialTheme.typography.bodyLarge,
                    color = LogicColors.OnDarkMuted,
                )
                return@Column
            }

            val now by rememberEventNow(startsAt = event.startsAt, endsAt = event.endsAt)
            val phase = event.phaseAt(now)
            val accent = GameCatalog.byId(event.gameId)?.category?.accent ?: LogicColors.Amber

            EventHeader(event = event, accent = accent, phase = phase, countdown = event.countdownLabel(now))

            // Torneo cerrado: lo primero es cómo quedó, no las reglas de algo que ya
            // no se puede jugar.
            if (phase == EventPhase.FINISHED) {
                EventResultPanel(event = event, state = state, accent = accent)
            }

            EventRulesPanel(event = event, accent = accent)

            LeaderboardPanel(
                event = event,
                state = state,
                accent = accent,
            )

            state.error?.let { error ->
                Text(
                    text = when (error) {
                        EventError.CLOSED -> stringResource(Res.string.event_error_closed)
                        EventError.NO_ATTEMPTS -> stringResource(Res.string.event_error_no_attempts)
                        EventError.AD_NOT_COMPLETED -> stringResource(Res.string.event_ad_failed)
                        EventError.GENERIC -> stringResource(Res.string.event_error_generic)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.Error,
                )
            }

            // El CTA solo existe mientras el torneo admite partidas: un botón
            // "jugar" en un torneo cerrado solo sirve para generar un error.
            //
            // Tres caras, en este orden de prioridad:
            //   1. invitado → al login (es la conversión, manda sobre todo lo demás);
            //   2. sin intentos pero con extras comprables → el anuncio;
            //   3. lo normal → entrar a jugar.
            // Si se quedó sin intentos y ya no hay extras, no se pinta botón: el
            // panel de arriba ya dice "has agotado tus intentos", y un botón que
            // solo puede fallar es peor que ninguno.
            if (phase == EventPhase.LIVE) {
                when {
                    state.isGuest -> AnimatedGameButton(
                        text = stringResource(Res.string.event_cta_sign_in),
                        onClick = { vm.onIntent(EventIntent.Play) },
                        modifier = Modifier.fillMaxWidth(),
                        gradient = LogicGradients.play,
                    )

                    state.canBuyAttempt -> AnimatedGameButton(
                        text = stringResource(Res.string.event_cta_watch_ad),
                        onClick = { vm.onIntent(EventIntent.WatchAdForAttempt) },
                        modifier = Modifier.fillMaxWidth(),
                        // Degradado de recompensa (ámbar), no el verde de jugar: lo
                        // que se pulsa aquí es un trato —ver un anuncio—, no empezar
                        // una partida, y el color lo dice antes que el texto.
                        gradient = LogicGradients.reward,
                        enabled = !state.isWatchingAd,
                    )

                    // `let` sobre una copia local: `attemptsLeft` es una propiedad
                    // derivada (getter propio) y el compilador no puede hacer smart
                    // cast sobre ella.
                    state.attemptsLeft.let { it == null || it > 0 } -> AnimatedGameButton(
                        text = stringResource(Res.string.event_cta_play),
                        onClick = { vm.onIntent(EventIntent.Play) },
                        modifier = Modifier.fillMaxWidth(),
                        gradient = LogicGradients.play,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Cabecera: identidad del juego, título del torneo y cuenta atrás. */
@Composable
private fun EventHeader(
    event: GameEvent,
    accent: Color,
    phase: EventPhase,
    countdown: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GameCatalog.byId(event.gameId)?.let { game ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NeonIcon(icon = game.category.icon, tint = accent, size = 20.dp, contentDescription = null)
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                )
            }
        }
        Text(
            text = event.title,
            style = MaterialTheme.typography.headlineLarge,
            color = LogicColors.OnDark,
        )
        event.subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = LogicColors.OnDarkMuted,
            )
        }
        Text(
            text = countdown ?: stringResource(Res.string.event_state_finished),
            style = MaterialTheme.typography.titleMedium,
            color = if (phase == EventPhase.FINISHED) LogicColors.OnDarkMuted else accent,
        )
    }
}

/**
 * Cómo quedó el torneo para ESTE jugador, una vez cerrado.
 *
 * Es lo primero que se pinta en un torneo terminado porque es la única pregunta que
 * le queda: la clasificación completa está debajo, pero "¿y yo?" va antes. Cubre los
 * tres desenlaces —premiado, participó sin premio y no participó— porque callar en
 * los dos últimos dejaría la pantalla idéntica a la de un torneo en curso salvo por
 * una etiqueta.
 */
@Composable
private fun EventResultPanel(event: GameEvent, state: EventUiState, accent: Color) {
    val me = state.leaderboard?.me
    val awarded = me != null && event.rewardTopN > 0 && me.rank <= event.rewardTopN
    val isChampion = me?.rank == 1L
    // El oro es solo del campeón; el resto del podio lleva el color del juego.
    val resultAccent = if (isChampion) LogicColors.Amber else accent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(LogicColors.SurfaceDark)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (awarded) {
                NeonIcon(
                    icon = if (isChampion) KortexIcons.Trophy else KortexIcons.Medal,
                    tint = resultAccent,
                    size = 26.dp,
                    contentDescription = null,
                )
            }
            Text(
                text = stringResource(Res.string.event_result_title),
                style = MaterialTheme.typography.titleMedium,
                color = LogicColors.OnDark,
            )
        }

        when {
            me != null -> {
                Text(
                    text = stringResource(
                        Res.string.event_result_rank,
                        me.rank.toString(),
                        state.leaderboard.totalPlayers.toString(),
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    color = resultAccent,
                )
                Text(
                    text = when {
                        isChampion -> stringResource(Res.string.event_result_champion)
                        awarded -> stringResource(Res.string.event_result_awarded)
                        else -> stringResource(Res.string.event_result_not_awarded)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (awarded) resultAccent else LogicColors.OnDarkMuted,
                )
            }

            // Gastó intentos pero ninguna partida dejó marca (abandonos, o derrotas
            // en un torneo por tiempo): no está en la tabla, y decírselo es mejor que
            // dejarlo buscándose en una lista donde no aparece.
            (state.leaderboard?.myAttempts ?: 0) > 0 -> Text(
                text = stringResource(Res.string.event_result_no_mark),
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )

            else -> Text(
                text = stringResource(Res.string.event_result_not_played),
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )
        }
    }
}

/** La tabla: top, fila propia y, para el invitado, la invitación a competir. */
@Composable
private fun LeaderboardPanel(
    event: GameEvent,
    state: EventUiState,
    accent: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(LogicColors.SurfaceDark)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.event_leaderboard_title),
                style = MaterialTheme.typography.titleMedium,
                color = LogicColors.OnDark,
            )
            state.leaderboard?.let {
                Text(
                    text = stringResource(Res.string.event_leaderboard_players, it.totalPlayers.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.OnDarkMuted,
                )
            }
        }

        when {
            state.isGuest -> Text(
                text = stringResource(Res.string.event_leaderboard_guest),
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )

            // Primera carga en vuelo. No se pinta un spinner a propósito: el panel ya
            // tiene su título, y una rueda de medio segundo en mitad de la pantalla
            // llama más la atención que el contenido que va a sustituirla.
            state.isLoadingLeaderboard -> Unit

            // Sin tabla y sin carga en curso: falló. El mensaje lo pinta el bloque de
            // error de la pantalla, no se duplica aquí.
            state.leaderboard == null -> Unit

            state.leaderboard.top.isEmpty() -> Text(
                text = stringResource(Res.string.event_leaderboard_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )

            else -> state.leaderboard.top.forEach { entry ->
                LeaderboardRow(entry = entry, rankByTime = event.rankByTime, accent = accent)
            }
        }

        val me = state.leaderboard?.me
        if (!state.isGuest && state.leaderboard != null) {
            Spacer(Modifier.height(2.dp))
            if (me == null) {
                Text(
                    text = stringResource(Res.string.event_leaderboard_not_played),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.OnDarkMuted,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(Res.string.event_leaderboard_me),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LogicColors.OnDarkMuted,
                    )
                    LeaderboardRow(
                        entry = LeaderboardEntry(
                            rank = me.rank,
                            displayName = null,
                            score = if (event.rankByTime) me.bestTimeMs ?: 0 else me.bestScore,
                            isCurrentUser = true,
                        ),
                        rankByTime = event.rankByTime,
                        accent = accent,
                    )
                    // Solo se anuncian los intentos que QUEDAN. Al llegar a cero no se
                    // pinta nada: el botón de abajo ya ofrece el anuncio para seguir
                    // jugando, y rematar con un "has agotado tus intentos" en rojo
                    // justo encima contradice esa oferta con una mala noticia.
                    state.attemptsLeft?.takeIf { it > 0 }?.let { left ->
                        Text(
                            text = if (left == 1) {
                                stringResource(Res.string.event_attempts_last)
                            } else {
                                stringResource(Res.string.event_attempts_left, left.toString())
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = LogicColors.OnDarkMuted,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Una fila de la tabla. La del propio jugador se resalta con el acento para que la
 * encuentre de un vistazo sin tener que leer los nombres.
 */
@Composable
private fun LeaderboardRow(entry: LeaderboardEntry, rankByTime: Boolean, accent: Color) {
    val highlight = entry.isCurrentUser
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlight) accent.copy(alpha = 0.14f) else Color.Transparent)
            .then(
                if (highlight) {
                    Modifier.border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = entry.rank.toString(),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
            color = if (highlight) accent else LogicColors.OnDarkMuted,
        )
        Text(
            text = entry.displayName ?: stringResource(Res.string.event_leaderboard_unknown_player),
            style = MaterialTheme.typography.bodyLarge,
            color = LogicColors.OnDark,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatEventMetric(entry.score, rankByTime),
            style = MaterialTheme.typography.titleMedium,
            color = if (highlight) accent else LogicColors.OnDark,
        )
    }
}
