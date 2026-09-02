package com.kortexgames.app.ui.components

import androidx.compose.runtime.Composable
import com.kortexgames.app.domain.model.DisplayNameRejection
import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.player_name_error_blocked
import kortexgames.shared.generated.resources.player_name_error_invalid_chars
import kortexgames.shared.generated.resources.player_name_error_save
import kortexgames.shared.generated.resources.player_name_error_session
import kortexgames.shared.generated.resources.player_name_error_too_long
import kortexgames.shared.generated.resources.player_name_error_too_short
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Clave de `strings.xml` para un [DisplayNameRejection]. Es el mapa de verdad; la
 * variante [playerNameErrorText] solo lo resuelve a texto.
 *
 * Vive en `ui/components` y no en una pantalla porque el nombre de jugador se
 * edita en TRES sitios —alta por email, onboarding tras entrar con Google y
 * Ajustes— y los tres deben decir exactamente lo mismo ante el mismo motivo. Con
 * un `when` copiado en cada pantalla, cambiar un texto obligaba a acordarse de
 * los otros dos.
 */
internal fun playerNameErrorKey(error: DisplayNameRejection): StringResource = when (error) {
    DisplayNameRejection.TOO_SHORT -> Res.string.player_name_error_too_short
    DisplayNameRejection.TOO_LONG -> Res.string.player_name_error_too_long
    DisplayNameRejection.INVALID_CHARS -> Res.string.player_name_error_invalid_chars
    DisplayNameRejection.BLOCKED -> Res.string.player_name_error_blocked
    DisplayNameRejection.NO_SESSION -> Res.string.player_name_error_session
    // Incluye el fallo de red: para el usuario la acción a tomar es la misma.
    DisplayNameRejection.UNKNOWN -> Res.string.player_name_error_save
}

/**
 * Mensaje visible para un [DisplayNameRejection], desde `strings.xml`.
 *
 * Azúcar sobre [playerNameErrorKey] para el caso normal. Se usa [playerNameErrorKey]
 * directamente cuando lo que hace falta es el `StringResource` y no el texto ya
 * resuelto (para pasarlo a un componente que lo resuelve él, o para resolverlo en
 * un punto distinto del árbol de composición).
 */
@Composable
internal fun playerNameErrorText(error: DisplayNameRejection): String =
    stringResource(playerNameErrorKey(error))
