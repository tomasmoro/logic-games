package com.kortexgames.app.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kortexgames.app.core.theme.LogicColors
import com.kortexgames.app.core.theme.LogicGradients
import com.kortexgames.app.di.AppGraph
import com.kortexgames.app.ui.components.LegalAcceptanceCheckbox
import com.kortexgames.app.ui.components.LegalPassiveNotice
import com.kortexgames.app.ui.components.NeonIcon
import com.kortexgames.app.ui.components.bounceClick
import com.kortexgames.app.ui.components.pulse
import com.kortexgames.app.ui.components.softGlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Pantalla de **login / bienvenida**, compartida por dos usos:
 *  - **Onboarding** (`isOnboarding = true`): primera apertura de la app. Ofrece
 *    "continuar sin cuenta" (con diálogo de riesgos) y, tras decidir, no se vuelve
 *    a mostrar (la marca [com.kortexgames.app.data.settings.OnboardingGate]).
 *  - **A demanda** (`isOnboarding = false`): un invitado pulsa "Iniciar sesión"
 *    en Home/Perfil. Aquí se muestra la flecha de volver y NO la opción de invitado.
 *
 * Diseño (CLAUDE.md §9): fondo azul-noche con halos neón, entrada animada
 * (fade + slide), marca con halo, y un único elemento en bucle (el CTA con [pulse]
 * + [softGlow]) para no competir por la atención.
 *
 * @param isOnboarding true si es la puerta de primera apertura.
 * @param onFinished se llama cuando el usuario resuelve la puerta (login o invitado).
 * @param onBack se llama al pulsar la flecha de volver (solo en uso a demanda).
 */
@Composable
fun AuthScreen(
    graph: AppGraph,
    isOnboarding: Boolean,
    onFinished: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: AuthViewModel = viewModel {
        AuthViewModel(
            graph.authRepository,
            graph.onboardingGate,
            graph.audio,
            graph.legalConsentStore,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Salida one-shot: al resolver la puerta, delega la navegación en el host.
    LaunchedEffect(vm) {
        vm.effect.collectLatest { effect ->
            when (effect) {
                AuthEffect.Finished -> onFinished()
            }
        }
    }

    // Disparo de la entrada animada: se activa una vez al componer.
    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(authBackgroundBrush()),
    ) {
        // Flecha de volver: solo cuando NO es la puerta de primera apertura (en
        // onboarding no hay "atrás", la decisión es obligatoria).
        if (!isOnboarding) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.padding(8.dp).size(48.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Volver",
                    tint = LogicColors.OnDark,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Marca: aparece primero, deslizando desde arriba.
            AnimatedVisibility(
                visible = appear,
                enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { -it / 3 },
            ) {
                BrandMark()
            }

            Spacer(Modifier.height(28.dp))

            // Tarjeta del formulario: entra un pelín después para escalonar.
            AnimatedVisibility(
                visible = appear,
                enter = fadeIn(tween(500, delayMillis = 120)) +
                    slideInVertically(tween(500, delayMillis = 120)) { it / 4 },
            ) {
                AuthForm(
                    state = state,
                    isOnboarding = isOnboarding,
                    onIntent = vm::onIntent,
                )
            }
        }
    }

    // Diálogo de riesgos de jugar sin cuenta (solo camino de invitado).
    if (state.showGuestRiskDialog) {
        GuestRiskDialog(
            onConfirm = { vm.onIntent(AuthIntent.ConfirmContinueAsGuest) },
            onDismiss = { vm.onIntent(AuthIntent.DismissGuestDialog) },
        )
    }
}

/** Fondo azul-noche con dos halos neón radiales tenues (energía sin ruido). */
@Composable
private fun authBackgroundBrush(): Brush = Brush.verticalGradient(
    listOf(
        LogicColors.BackgroundDark,
        LogicColors.SurfaceDark,
        LogicColors.BackgroundDark,
    ),
)

/** Logotipo: icono de "mente" con halo neón dentro de un anillo de degradado. */
@Composable
private fun BrandMark() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .softGlow(color = LogicColors.NeonCyan, shape = CircleShape, maxElevation = 30.dp)
                .border(2.dp, Brush.sweepGradient(LogicGradients.energy), CircleShape)
                .padding(6.dp)
                .clip(CircleShape)
                .background(LogicColors.SurfaceVariantDark),
            contentAlignment = Alignment.Center,
        ) {
            NeonIcon(icon = Icons.Rounded.Psychology, tint = LogicColors.NeonCyan, size = 44.dp)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "KortexGames",
            style = MaterialTheme.typography.headlineLarge,
            color = LogicColors.OnDark,
            fontWeight = FontWeight.Black,
        )
        Text(
            "Entrena tu mente cada día",
            style = MaterialTheme.typography.bodyLarge,
            color = LogicColors.OnDarkMuted,
        )
    }
}

/** Tarjeta con el formulario de email, Google y (en onboarding) modo invitado. */
@Composable
private fun AuthForm(
    state: AuthUiState,
    isOnboarding: Boolean,
    onIntent: (AuthIntent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(LogicColors.SurfaceDark)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Título que cambia según el modo, con una transición suave de opacidad.
        AnimatedContent(
            targetState = state.mode,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "authTitle",
        ) { mode ->
            Text(
                if (mode == AuthMode.SignIn) "Inicia sesión" else "Crea tu cuenta",
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
            )
        }

        // Nombre de jugador: solo al crear cuenta. Va PRIMERO porque es la
        // pregunta de identidad ("¿cómo te llamamos?"), no una credencial, y
        // porque así el orden del teclado (Siguiente) recorre el formulario de
        // arriba abajo sin saltos.
        AnimatedVisibility(visible = state.requiresUsername) {
            AuthTextField(
                value = state.username,
                onValueChange = { onIntent(AuthIntent.UsernameChanged(it)) },
                label = "Nombre de jugador",
                leadingIcon = Icons.Rounded.Person,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
                enabled = !state.isSubmitting,
                errorText = state.usernameError,
            )
        }

        AuthTextField(
            value = state.email,
            onValueChange = { onIntent(AuthIntent.EmailChanged(it)) },
            label = "Email",
            leadingIcon = Icons.Rounded.Email,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
            enabled = !state.isSubmitting,
            errorText = state.emailError,
        )

        PasswordField(
            value = state.password,
            onValueChange = { onIntent(AuthIntent.PasswordChanged(it)) },
            enabled = !state.isSubmitting,
            onDone = { onIntent(AuthIntent.SubmitEmail) },
            errorText = state.passwordError,
        )

        // Banner de error: aparece/desaparece con fade (no salta el layout brusco).
        AnimatedVisibility(visible = state.error != null) {
            ErrorBanner(message = state.error.orEmpty())
        }

        // Aceptación de condiciones: solo al crear cuenta. Entra y sale con la
        // misma transición suave que el resto de bloques condicionales para que
        // cambiar de modo no dé un salto de layout.
        AnimatedVisibility(visible = state.requiresLegalAcceptance) {
            LegalAcceptanceCheckbox(
                checked = state.acceptedLegal,
                onCheckedChange = { onIntent(AuthIntent.ToggleLegalAcceptance) },
                enabled = !state.isSubmitting,
            )
        }

        PrimaryButton(
            text = if (state.mode == AuthMode.SignIn) "Entrar" else "Crear cuenta",
            enabled = state.canSubmit,
            loading = state.isSubmitting,
            onClick = { onIntent(AuthIntent.SubmitEmail) },
        )

        // Cambiar entre iniciar sesión / registrarse.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.mode == AuthMode.SignIn) "¿No tienes cuenta?" else "¿Ya tienes cuenta?",
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (state.mode == AuthMode.SignIn) "Regístrate" else "Inicia sesión",
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.NeonCyan,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.bounceClick(enabled = !state.isSubmitting) {
                    onIntent(AuthIntent.ToggleMode)
                },
            )
        }

        DividerWithText("o continúa con")

        GoogleButton(
            enabled = state.canUseGoogle,
            onClick = { onIntent(AuthIntent.SignInWithGoogle) },
        )

        // En "Inicia sesión" no hay casilla, pero Google puede acabar creando una
        // cuenta si el email no existía: el aviso deja los documentos a un toque y
        // hace que continuar sea una aceptación informada.
        AnimatedVisibility(visible = !state.requiresLegalAcceptance) {
            LegalPassiveNotice()
        }

        // Solo en la puerta de primera apertura: seguir sin cuenta (con aviso).
        if (isOnboarding) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Continuar sin cuenta",
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDarkMuted,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .bounceClick(enabled = !state.isSubmitting) {
                        onIntent(AuthIntent.RequestContinueAsGuest)
                    }
                    .padding(8.dp),
            )
        }
    }
}

/** Campo de texto con estilo del tema oscuro y icono neón a la izquierda. */
@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    enabled: Boolean,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    errorText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = errorText != null,
        // El error se muestra bajo el campo, no en un banner: así el usuario ve
        // qué campo corregir sin tener que relacionarlo con un mensaje lejano.
        supportingText = errorText?.let { { Text(it) } },
        leadingIcon = { Icon(leadingIcon, contentDescription = null) },
        trailingIcon = trailing,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LogicColors.NeonCyan,
            unfocusedBorderColor = LogicColors.SurfaceVariantDark,
            focusedLabelColor = LogicColors.NeonCyan,
            unfocusedLabelColor = LogicColors.OnDarkMuted,
            cursorColor = LogicColors.NeonCyan,
            focusedTextColor = LogicColors.OnDark,
            unfocusedTextColor = LogicColors.OnDark,
            focusedLeadingIconColor = LogicColors.NeonCyan,
            unfocusedLeadingIconColor = LogicColors.OnDarkMuted,
            focusedContainerColor = LogicColors.SurfaceVariantDark,
            unfocusedContainerColor = LogicColors.SurfaceVariantDark,
            // El rojo de error también sale del tema: Material traería el suyo,
            // ajeno a la paleta (CLAUDE.md §4).
            errorBorderColor = LogicColors.Error,
            errorLabelColor = LogicColors.Error,
            errorSupportingTextColor = LogicColors.Error,
            errorCursorColor = LogicColors.Error,
            errorLeadingIconColor = LogicColors.Error,
            errorTextColor = LogicColors.OnDark,
            errorContainerColor = LogicColors.SurfaceVariantDark,
        ),
    )
}

/** Campo de contraseña con toggle de visibilidad (ojo). */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onDone: () -> Unit,
    errorText: String? = null,
) {
    var visible by remember { mutableStateOf(false) }
    AuthTextField(
        value = value,
        onValueChange = onValueChange,
        label = "Contraseña",
        leadingIcon = Icons.Rounded.Lock,
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        onDone = onDone,
        errorText = errorText,
        trailing = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (visible) "Ocultar contraseña" else "Mostrar contraseña",
                    tint = LogicColors.OnDarkMuted,
                )
            }
        },
    )
}

/** Banner de error con icono de aviso y fondo tenue rojo. */
@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LogicColors.Error.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = LogicColors.Error, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = LogicColors.OnDark)
    }
}

/**
 * CTA principal con degradado verde neón. Es el ÚNICO elemento en bucle de la
 * pantalla ([pulse] + [softGlow]); al enviar muestra un spinner y se desactiva.
 *
 * Sigue respondiendo al toque aunque el formulario sea inválido ([enabled] en
 * `false`, salvo mientras [loading]): así el intent llega al ViewModel, que
 * marca `submitAttempted` y hace aparecer el error bajo cada campo en vez de
 * dejar el botón mudo, que el usuario podría confundir con "está roto".
 */
@Composable
private fun PrimaryButton(
    text: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val active = enabled && !loading
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(if (active) Modifier.pulse().softGlow(LogicColors.NeonGreen, shape, maxElevation = 20.dp) else Modifier)
            .clip(shape)
            .background(
                if (active) Brush.horizontalGradient(LogicGradients.play)
                else Brush.horizontalGradient(listOf(LogicColors.SurfaceVariantDark, LogicColors.SurfaceVariantDark)),
            )
            .bounceClick(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = LogicColors.BackgroundDark,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (active) LogicColors.BackgroundDark else LogicColors.OnDarkMuted,
            )
        }
    }
}

/**
 * Botón secundario de Google: contorno neutro, icono + etiqueta.
 *
 * Cuando está deshabilitado (envío en curso, o falta aceptar condiciones al
 * registrarse) se atenúa: si solo dejara de responder al toque, el usuario
 * pensaría que el botón está roto en vez de entender que le falta un paso.
 */
@Composable
private fun GoogleButton(enabled: Boolean, onClick: () -> Unit) {
    val contentColor = if (enabled) LogicColors.OnDark else LogicColors.OnDarkMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.5.dp, LogicColors.SurfaceVariantDark, RoundedCornerShape(20.dp))
            .background(LogicColors.SurfaceVariantDark.copy(alpha = if (enabled) 0.4f else 0.18f))
            .bounceClick(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.Login,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Continuar con Google",
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Separador "── texto ──" con líneas tenues. */
@Composable
private fun DividerWithText(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(LogicColors.SurfaceVariantDark))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = LogicColors.OnDarkMuted,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(LogicColors.SurfaceVariantDark))
    }
}

/**
 * Diálogo que **expone los riesgos** de jugar sin cuenta antes de dejar continuar
 * (requisito de producto). Enumera qué se pierde y ofrece dos salidas claras:
 * iniciar sesión (volver) o asumir el riesgo y seguir como invitado.
 */
@Composable
private fun GuestRiskDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(LogicColors.SurfaceDark)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NeonIcon(
                icon = Icons.Rounded.WarningAmber,
                tint = LogicColors.Amber,
                size = 40.dp,
                modifier = Modifier.pulse(maxScale = 1.08f),
            )
            Text(
                "¿Seguir sin cuenta?",
                style = MaterialTheme.typography.headlineMedium,
                color = LogicColors.OnDark,
            )
            Text(
                "Puedes jugar sin iniciar sesión, pero ten en cuenta que:",
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )

            RiskBullet("Tu progreso se guarda solo en este dispositivo.")
            RiskBullet("No se sincroniza: si cambias de móvil, empiezas de cero.")
            RiskBullet("Si borras la app o los datos, perderás tu historial y tu racha.")
            RiskBullet("No podrás competir en rankings ni comparar tu percentil.")

            Spacer(Modifier.height(4.dp))

            // Acción recomendada primero: iniciar sesión. Debajo, la salida asumida.
            PrimaryButton(
                text = "Mejor inicio sesión",
                enabled = true,
                loading = false,
                onClick = onDismiss,
            )
            Text(
                "Continuar sin cuenta",
                style = MaterialTheme.typography.labelLarge,
                color = LogicColors.OnDarkMuted,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(onClick = onConfirm)
                    .padding(vertical = 10.dp),
            )
        }
    }
}

/** Punto de la lista de riesgos: viñeta ámbar + texto. */
@Composable
private fun RiskBullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(LogicColors.Amber),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDark,
            modifier = Modifier.weight(1f),
        )
    }
}
