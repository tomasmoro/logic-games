package com.kortexgames.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.domain.model.DisplayNameRejection
import com.kortexgames.app.ui.components.AnimatedGameButton
import com.kortexgames.app.ui.components.playerNameErrorText
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.onboarding_player_name_hint
import kortexgames.shared.generated.resources.onboarding_player_name_placeholder
import kortexgames.shared.generated.resources.onboarding_player_name_save
import kortexgames.shared.generated.resources.onboarding_player_name_saving
import kortexgames.shared.generated.resources.onboarding_player_name_subtitle
import kortexgames.shared.generated.resources.onboarding_player_name_title
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.stringResource

/**
 * Pantalla de **elegir nombre de jugador**. Aparece una sola vez, justo tras un alta
 * con Google: el perfil nace sin `display_name` (migración 0048) y este nombre es lo
 * que verán los demás en el ranking. El alta por email no pasa por aquí — ya lo pide
 * en su formulario.
 *
 * Sin "volver" ni "saltar" a propósito: es el último paso antes de entrar y el
 * ranking necesita un nombre. Se puede cambiar luego en Ajustes.
 *
 * @param onDone el nombre quedó guardado; el host continúa a Home con la pila limpia.
 */
@Composable
fun PlayerNameScreen(graph: AppGraph, onDone: () -> Unit) {
    val vm: PlayerNameViewModel = viewModel {
        PlayerNameViewModel(graph.authRepository, graph.audio)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(vm) {
        vm.effect.collectLatest { effect ->
            when (effect) {
                PlayerNameEffect.Saved -> onDone()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LogicColors.BackgroundDark)
            .padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(Res.string.onboarding_player_name_title),
            style = MaterialTheme.typography.headlineLarge,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            stringResource(Res.string.onboarding_player_name_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = LogicColors.OnDarkMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
        )

        OutlinedTextField(
            value = state.name,
            onValueChange = { vm.onIntent(PlayerNameIntent.NameChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !state.isSaving,
            isError = state.error != null,
            placeholder = { Text(stringResource(Res.string.onboarding_player_name_placeholder)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.onIntent(PlayerNameIntent.Save) }),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LogicColors.NeonCyan,
                unfocusedBorderColor = LogicColors.SurfaceVariantDark,
                cursorColor = LogicColors.NeonCyan,
                focusedTextColor = LogicColors.OnDark,
                unfocusedTextColor = LogicColors.OnDark,
                focusedContainerColor = LogicColors.SurfaceVariantDark,
                unfocusedContainerColor = LogicColors.SurfaceVariantDark,
            ),
        )

        // Hueco de estado siempre presente (error o pista) para que el botón no salte.
        Text(
            state.error?.let { playerNameErrorText(it) } ?: stringResource(Res.string.onboarding_player_name_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.error != null) LogicColors.Error else LogicColors.OnDarkMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        AnimatedGameButton(
            text = if (state.isSaving) {
                stringResource(Res.string.onboarding_player_name_saving)
            } else {
                stringResource(Res.string.onboarding_player_name_save)
            },
            onClick = { vm.onIntent(PlayerNameIntent.Save) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canSave,
        )
    }
}
