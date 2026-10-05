package com.kortexgames.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.game.GameCatalog
import com.kortexgames.app.game.access.FREE_DAILY_PLAYS
import com.kortexgames.app.game.access.PlayAccess
import com.kortexgames.app.game.access.PlayQuotaManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.premium_badge
import kortexgames.shared.generated.resources.premium_badge_content_description
import kortexgames.shared.generated.resources.premium_unlock_body
import kortexgames.shared.generated.resources.premium_unlock_cancel
import kortexgames.shared.generated.resources.premium_unlock_title
import kortexgames.shared.generated.resources.premium_unlock_watch
import org.jetbrains.compose.resources.stringResource

/*
 * Puerta de acceso de los juegos premium en la UI.
 *
 * Por qué un CompositionLocal (mismo patrón que `LocalFirstRunFlow`): las partidas
 * empiezan desde tres componentes compartidos —la antesala ([GameIntroScreen]), el
 * cartel de fin ([GameOverOverlay]) y el menú de pausa ([GamePauseControls])— que
 * monta cada juego por su cuenta. Publicando la puerta desde la navegación, esos tres
 * componentes la aplican solos y ningún juego tiene que saber que es premium: marcar
 * otro juego como premium en el catálogo basta.
 */

/**
 * Estado y acciones de la puerta de UN juego premium. La crea [PlayGateHost]; los
 * componentes que empiezan partidas la leen de [LocalPlayGate].
 *
 * @property gameId juego al que pertenece la puerta.
 */
class PlayGate internal constructor(
    val gameId: String,
    private val manager: PlayQuotaManager,
    private val scope: CoroutineScope,
) {
    /** Acceso actual del juego, para pintar el contador y el rótulo del CTA. */
    val access: Flow<PlayAccess> = manager.access(gameId)

    /**
     * Partida esperando a que el jugador acepte el anuncio. Mientras no es `null`,
     * [PlayGateHost] muestra el cartel que ofrece el trato.
     */
    internal var pendingAction by mutableStateOf<(() -> Unit)?>(null)
        private set

    /** Hay un anuncio cargándose o en pantalla; [PlayGateHost] bloquea la UI mientras tanto. */
    internal var loadingAd by mutableStateOf(false)
        private set

    /**
     * Una petición en curso. Descarta los toques repetidos mientras se lee el cupo o
     * se muestra un anuncio (el [PlayQuotaManager] ya serializa, pero sin esto cada
     * toque extra acabaría encolando otra partida).
     */
    private var busy = false

    /**
     * Pide empezar una partida y ejecuta [action] si se concede.
     *
     * Si el cupo está agotado y el anuncio **no** se ha anunciado todavía, no lo
     * muestra directamente: abre un cartel que explica el trato. La política de AdMob
     * exige que el recompensado sea opcional y que el jugador sepa qué recibe antes de
     * verlo; "Jugar de nuevo" o "Reiniciar" no lo dicen.
     *
     * @param adAnnounced el botón pulsado ya decía que la partida cuesta un anuncio
     *        (el CTA "Ver anuncio y jugar" de la antesala): se salta el cartel.
     */
    fun request(adAnnounced: Boolean = false, action: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val needsAd = access.first() is PlayAccess.NeedsAd
                if (needsAd && !adAnnounced) {
                    pendingAction = action
                } else {
                    play(needsAd, action)
                }
            } finally {
                busy = false
            }
        }
    }

    /** El jugador acepta ver el anuncio del cartel. */
    internal fun confirmAd() {
        val action = pendingAction ?: return
        pendingAction = null
        busy = true
        scope.launch {
            try {
                play(needsAd = true, action = action)
            } finally {
                busy = false
            }
        }
    }

    /** El jugador rechaza el trato: no se empieza nada y se queda donde estaba. */
    internal fun dismissAd() {
        pendingAction = null
    }

    private suspend fun play(needsAd: Boolean, action: () -> Unit) {
        loadingAd = needsAd
        try {
            if (manager.requestPlay(gameId)) action()
        } finally {
            loadingAd = false
        }
    }
}

/**
 * Puerta del juego en pantalla, o `null` si no es premium (o el componente se usa
 * fuera de una ruta de juego). `static` porque cambia solo al cambiar de ruta.
 */
val LocalPlayGate = staticCompositionLocalOf<PlayGate?> { null }

/**
 * Envuelve la pantalla de un juego: si es premium ([GameCatalog.isPremium]) publica su
 * [PlayGate] en [LocalPlayGate] y pinta por encima el cartel del anuncio y la espera de
 * carga. Para el resto de juegos solo pinta [content].
 *
 * @param gameId juego de la ruta; `null` si la ruta no corresponde a ningún juego.
 * @param manager el [PlayQuotaManager] de la app.
 */
@Composable
fun PlayGateHost(
    gameId: String?,
    manager: PlayQuotaManager,
    content: @Composable () -> Unit,
) {
    if (gameId == null || !GameCatalog.isPremium(gameId)) {
        content()
        return
    }
    // El scope de la ruta, no el del botón: el cartel de fin o el menú de pausa pueden
    // desmontarse mientras el anuncio está en pantalla, y la partida concedida debe
    // arrancar igual al cerrarlo.
    val scope = rememberCoroutineScope()
    val gate = remember(gameId) { PlayGate(gameId, manager, scope) }

    CompositionLocalProvider(LocalPlayGate provides gate) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            AdLoadingOverlay(visible = gate.loadingAd, accent = LogicColors.Amber)
        }
        if (gate.pendingAction != null) {
            PlayUnlockDialog(onWatch = gate::confirmAd, onDismiss = gate::dismissAd)
        }
    }
}

/**
 * Envuelve una acción que **empieza una partida** para que pase por la puerta del juego
 * en pantalla. Sin puerta ([LocalPlayGate] nulo: juego no premium) devuelve [action]
 * tal cual, así los componentes compartidos pueden usarla siempre.
 */
@Composable
fun gatedPlay(action: () -> Unit): () -> Unit {
    val gate = LocalPlayGate.current ?: return action
    return { gate.request(action = action) }
}

/**
 * Cartel que ofrece el trato "un anuncio = una partida" al agotar el cupo. Mismo
 * lenguaje que el resto de diálogos (ver `ConfirmExitDialog`), con acento ámbar de
 * recompensa (§9.2).
 */
@Composable
private fun PlayUnlockDialog(onWatch: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(LogicColors.SurfaceDark)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NeonIcon(icon = KortexIcons.RewardedAd, tint = LogicColors.Amber, size = 40.dp)
            Text(
                stringResource(Res.string.premium_unlock_title),
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
            )
            Text(
                stringResource(Res.string.premium_unlock_body, FREE_DAILY_PLAYS.toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )
            AnimatedGameButton(
                onClick = onWatch,
                gradient = LogicGradients.reward,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NeonIcon(
                        icon = KortexIcons.RewardedAd,
                        tint = LogicColors.BackgroundDark,
                        size = 20.dp,
                        glow = false,
                    )
                    Text(
                        stringResource(Res.string.premium_unlock_watch),
                        style = MaterialTheme.typography.titleMedium,
                        color = LogicColors.BackgroundDark,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
            Text(
                stringResource(Res.string.premium_unlock_cancel),
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDarkMuted,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(onClick = onDismiss)
                    .padding(vertical = 10.dp),
            )
        }
    }
}

/**
 * Insignia de juego premium: corona ámbar con halo en una píldora oscura. Estática a
 * propósito (sin destello ni bucle): convive con [NewBadge] en la misma tarjeta y no
 * debe competir con ella ni con el CTA (§9.4).
 *
 * @param compact variante pequeña para tarjetas estrechas (solo el icono).
 */
@Composable
fun PremiumBadge(modifier: Modifier = Modifier, compact: Boolean = false) {
    val description = stringResource(Res.string.premium_badge_content_description)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(LogicColors.SurfaceVariantDark)
            .semantics { contentDescription = description }
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 3.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NeonIcon(icon = KortexIcons.Premium, tint = LogicColors.Amber, size = if (compact) 14.dp else 16.dp)
        if (!compact) {
            Text(
                stringResource(Res.string.premium_badge),
                style = MaterialTheme.typography.labelMedium,
                color = LogicColors.Amber,
                fontWeight = FontWeight.Black,
            )
        }
    }
}
