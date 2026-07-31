package com.kortexgames.app.ui.settings

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.domain.model.AuthState
import com.kortexgames.app.ui.components.KortexIcons
import com.kortexgames.app.ui.components.LegalLinksSection
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.bounceClick
import kotlinx.coroutines.flow.collectLatest

/**
 * Pantalla de **Ajustes**: gestión de la cuenta (nombre de usuario, borrado),
 * acceso a los documentos legales y, más adelante, el resto de preferencias de la
 * app. Se abre desde el icono de engranaje en Perfil.
 *
 * Solo tiene sentido gestionar "cuenta" con sesión iniciada: en modo invitado
 * muestra un aviso con CTA a iniciar sesión, igual que hace Perfil.
 *
 * @param onBack vuelve a la pantalla anterior (Perfil).
 * @param onOpenAuth abre el login (invitados).
 * @param onAccountDeleted la cuenta se borró y la sesión ya cerró: el host debe
 *   sacar a la pantalla de este backstack (vuelve a Home como invitado).
 */
@Composable
fun SettingsScreen(
    graph: AppGraph,
    onBack: () -> Unit,
    onOpenAuth: () -> Unit,
    onAccountDeleted: () -> Unit,
) {
    val accountVm: AccountViewModel = viewModel {
        AccountViewModel(graph.authRepository, graph.audio, deleteAccount = graph::deleteAccount)
    }
    val account by accountVm.state.collectAsStateWithLifecycle()
    val session by graph.authRepository.sessionState.collectAsStateWithLifecycle()

    // Salida one-shot: la cuenta se borró y la sesión ya cerró, el host decide a
    // dónde navegar (vuelve a Home como invitado).
    LaunchedEffect(accountVm) {
        accountVm.effect.collectLatest { effect ->
            when (effect) {
                AccountEffect.AccountDeleted -> onAccountDeleted()
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsHeader(onBack = onBack)

            Text("Cuenta", style = MaterialTheme.typography.titleLarge, color = LogicColors.OnDark)

            when (session) {
                is AuthState.Authenticated -> {
                    DisplayNameCard(state = account, onIntent = accountVm::onIntent)
                    DangerZoneCard(onDeleteClick = { accountVm.onIntent(AccountIntent.RequestDeleteAccount) })
                }
                AuthState.Guest -> GuestAccountPrompt(onSignIn = onOpenAuth)
            }

            // Legal: fuera del `when` a propósito. Las condiciones y la privacidad
            // aplican igual a un invitado, y las tiendas esperan que se puedan leer
            // desde dentro de la App sin tener que registrarse antes.
            Text("Legal", style = MaterialTheme.typography.titleLarge, color = LogicColors.OnDark)

            LegalLinksSection()

            Spacer(Modifier.height(4.dp))
        }
    }

    if (account.showDeleteDialog) {
        DeleteAccountDialog(
            isDeleting = account.isDeleting,
            error = account.deleteError,
            onConfirm = { accountVm.onIntent(AccountIntent.ConfirmDeleteAccount) },
            onDismiss = { accountVm.onIntent(AccountIntent.DismissDeleteDialog) },
        )
    }
}

/** Cabecera: volver + título. Mismo lenguaje visual que el resto de pantallas secundarias. */
@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircleIconButton(icon = Icons.AutoMirrored.Rounded.ArrowBack, tint = LogicColors.OnDark, onClick = onBack)
        Spacer(Modifier.width(12.dp))
        Text("Ajustes", style = MaterialTheme.typography.headlineLarge, color = LogicColors.OnDark)
    }
}

/**
 * Tarjeta de nombre de usuario: modo lectura (nombre + lápiz) y modo edición
 * (campo de texto + guardar/cancelar), con spinner y error inline al guardar.
 */
@Composable
private fun DisplayNameCard(state: AccountUiState, onIntent: (AccountIntent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(LogicColors.SurfaceDark)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeonIcon(icon = Icons.Rounded.Person, tint = LogicColors.Violet, size = 24.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Nombre de usuario", style = MaterialTheme.typography.bodyLarge, color = LogicColors.OnDark)
                if (!state.isEditingName) {
                    Text(
                        state.displayName.ifBlank { "Sin definir" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = LogicColors.OnDarkMuted,
                    )
                }
            }
            if (!state.isEditingName) {
                CircleIconButton(
                    icon = KortexIcons.Pencil,
                    tint = LogicColors.NeonCyan,
                    onClick = { onIntent(AccountIntent.StartEditingName) },
                    size = 36.dp,
                )
            }
        }

        if (state.isEditingName) {
            OutlinedTextField(
                value = state.displayName,
                onValueChange = { onIntent(AccountIntent.NameChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !state.isSavingName,
                isError = state.nameError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onIntent(AccountIntent.SaveName) }),
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

            if (state.nameError != null) {
                Text(state.nameError, style = MaterialTheme.typography.bodyMedium, color = LogicColors.Error)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextActionButton(
                    text = "Cancelar",
                    color = LogicColors.OnDarkMuted,
                    enabled = !state.isSavingName,
                    onClick = { onIntent(AccountIntent.CancelEditingName) },
                    modifier = Modifier.weight(1f),
                )
                TextActionButton(
                    text = if (state.isSavingName) "Guardando…" else "Guardar",
                    color = LogicColors.NeonGreen,
                    enabled = state.canSaveName,
                    onClick = { onIntent(AccountIntent.SaveName) },
                    modifier = Modifier.weight(1f),
                    filled = true,
                )
            }
        }
    }
}

/**
 * "Zona de peligro": tarjeta con borde rojo tenue para el borrado de cuenta.
 * Separada visualmente del resto (icono/color de error) para que no se confunda
 * con una acción reversible.
 */
@Composable
private fun DangerZoneCard(onDeleteClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(LogicColors.SurfaceDark)
            .border(1.dp, LogicColors.Error.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Zona de peligro", style = MaterialTheme.typography.titleMedium, color = LogicColors.Error)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(LogicColors.Error.copy(alpha = 0.1f))
                .bounceClick(onClick = onDeleteClick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeonIcon(icon = KortexIcons.Trash, tint = LogicColors.Error, size = 22.dp, glow = false)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Borrar cuenta", style = MaterialTheme.typography.bodyLarge, color = LogicColors.OnDark)
                Text(
                    "Borra tu progreso, logros y racha para siempre",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.OnDarkMuted,
                )
            }
        }
    }
}

/** Aviso para invitados: no hay cuenta que gestionar hasta iniciar sesión. */
@Composable
private fun GuestAccountPrompt(onSignIn: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(LogicColors.SurfaceDark)
            .bounceClick(onClick = onSignIn)
            .padding(18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            NeonIcon(icon = Icons.Rounded.CloudOff, tint = LogicColors.OnDarkMuted, size = 24.dp, glow = false)
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Sin cuenta iniciada", style = MaterialTheme.typography.bodyLarge, color = LogicColors.OnDark)
                Text(
                    "Inicia sesión para gestionar tu nombre y tu cuenta",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LogicColors.OnDarkMuted,
                )
            }
        }
        Text(
            "Entrar",
            style = MaterialTheme.typography.labelLarge,
            color = LogicColors.NeonCyan,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Diálogo de confirmación de borrado: expone lo que se pierde y exige un
 * segundo toque explícito antes de disparar la acción irreversible, con
 * feedback de progreso/error inline (el usuario no debe salir del diálogo
 * pensando que ya terminó si aún está en curso).
 */
@Composable
private fun DeleteAccountDialog(
    isDeleting: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = { if (!isDeleting) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(LogicColors.SurfaceDark)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NeonIcon(icon = Icons.Rounded.WarningAmber, tint = LogicColors.Error, size = 40.dp)
            Text("¿Borrar tu cuenta?", style = MaterialTheme.typography.headlineMedium, color = LogicColors.OnDark)
            Text(
                "Esta acción es permanente. Se borrará tu progreso, logros, racha y percentiles guardados en la nube. No se puede deshacer.",
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )

            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = LogicColors.Error)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isDeleting) LogicColors.SurfaceVariantDark else LogicColors.Error)
                    .bounceClick(enabled = !isDeleting, onClick = onConfirm),
                contentAlignment = Alignment.Center,
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        color = LogicColors.OnDark,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Text(
                        "Borrar mi cuenta",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LogicColors.OnDark,
                    )
                }
            }

            Text(
                "Cancelar",
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDarkMuted,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(enabled = !isDeleting, onClick = onDismiss)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

/** Botón circular reutilizado en la cabecera (volver, editar nombre). */
@Composable
private fun CircleIconButton(
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    size: Dp = 44.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(LogicColors.SurfaceDark)
            .border(BorderStroke(1.dp, LogicColors.SurfaceVariantDark), CircleShape)
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NeonIcon(icon = icon, tint = tint, size = size * 0.5f, glow = false)
    }
}

/** Botón de texto compacto (guardar/cancelar), opcionalmente relleno. */
@Composable
private fun TextActionButton(
    text: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (filled) color.copy(alpha = if (enabled) 0.18f else 0.08f) else Color.Transparent)
            .bounceClick(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) color else LogicColors.OnDarkMuted,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
