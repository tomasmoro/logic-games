package com.kortexgames.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.kortexgames.app.core.legal.LegalLinks
import com.kortexgames.app.core.theme.LogicColors

/**
 * Piezas de UI de los **documentos legales** (condiciones y privacidad),
 * compartidas por el alta de cuenta y por Ajustes.
 *
 * Están juntas a propósito: la frase que el usuario acepta al registrarse y los
 * enlaces que luego encuentra en Ajustes tienen que apuntar exactamente a las
 * mismas URLs y llamarse igual. Si cada pantalla escribiera su propio texto,
 * acabarían divergiendo y el consentimiento registrado dejaría de corresponderse
 * con lo que el usuario puede volver a leer.
 *
 * Las URLs viven en [LegalLinks]; abrir el navegador se delega en
 * `LocalUriHandler`, que Compose Multiplatform implementa en Android e iOS, así
 * que no hace falta ningún seam `expect`/`actual` propio.
 */

/** Estilo de los enlaces embebidos en texto corrido: cian de acento y negrita. */
private val legalLinkStyles = TextLinkStyles(
    style = SpanStyle(
        color = LogicColors.NeonCyan,
        fontWeight = FontWeight.Bold,
    ),
)

/**
 * Frase de aceptación con los dos documentos como enlaces reales.
 *
 * Se construye con [LinkAnnotation.Url] en vez de con un `clickable` sobre todo
 * el texto para que solo sean pulsables las palabras del enlace y para que los
 * lectores de pantalla los anuncien como tales.
 */
private fun legalAcceptanceText(): AnnotatedString = buildAnnotatedString {
    append("He leído y acepto las ")
    withLink(LinkAnnotation.Url(LegalLinks.TERMS_OF_SERVICE, legalLinkStyles)) {
        append("Condiciones del Servicio")
    }
    append(" y la ")
    withLink(LinkAnnotation.Url(LegalLinks.PRIVACY_POLICY, legalLinkStyles)) {
        append("Política de Privacidad")
    }
    append(".")
}

/**
 * Casilla de aceptación del alta de cuenta: cuadro neón + la frase con enlaces.
 *
 * Pulsar el cuadro (o el hueco alrededor) marca y desmarca; pulsar los nombres de
 * los documentos abre la página en el navegador sin alterar la casilla. Esa
 * separación es deliberada: el usuario debe poder leer antes de aceptar sin que
 * el gesto de leer cuente como aceptación.
 *
 * @param checked si el usuario ya marcó la casilla.
 * @param onCheckedChange se llama al alternar (recibe el nuevo valor).
 * @param enabled false mientras hay un envío en curso.
 */
@Composable
fun LegalAcceptanceCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        NeonCheckbox(
            checked = checked,
            enabled = enabled,
            onClick = { onCheckedChange(!checked) },
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = legalAcceptanceText(),
            style = MaterialTheme.typography.bodyMedium,
            color = LogicColors.OnDarkMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * Cuadro de check con estética neón: al marcar se rellena de verde y el tick
 * entra con un `spring` (feedback táctil orgánico, CLAUDE.md §9.4). Es propio y
 * no un `Checkbox` de Material porque el de Material impone su propia paleta y
 * su ripple, ajenos al sistema de diseño.
 */
@Composable
private fun NeonCheckbox(
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val fill by animateColorAsState(
        targetValue = if (checked) LogicColors.NeonGreen else LogicColors.SurfaceVariantDark,
        label = "legalCheckFill",
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) LogicColors.NeonGreen else LogicColors.OnDarkMuted.copy(alpha = 0.5f),
        label = "legalCheckBorder",
    )
    // El tick "aterriza" con rebote al marcar y desaparece sin drama al desmarcar.
    val tickScale by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "legalCheckTick",
    )

    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(shape)
            .background(fill)
            .border(1.5.dp, borderColor, shape)
            .bounceClick(enabled = enabled, onClick = onClick)
            .semantics {
                stateDescription = if (checked) "Aceptado" else "Sin aceptar"
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = LogicColors.BackgroundDark,
            modifier = Modifier.size(18.dp).scale(tickScale),
        )
    }
}

/**
 * Aviso pasivo para el modo **iniciar sesión**, donde no hay casilla.
 *
 * Cierra un hueco real: entrar con Google desde "Inicia sesión" crea una cuenta
 * nueva si el email no existía, así que ese usuario nunca vería la casilla de
 * alta. Con este aviso, continuar es un acto de aceptación informada (el patrón
 * de *clickwrap* que usa la industria) y los documentos quedan a un toque.
 */
@Composable
fun LegalPassiveNotice(modifier: Modifier = Modifier) {
    Text(
        text = buildAnnotatedString {
            append("Al continuar aceptas las ")
            withLink(LinkAnnotation.Url(LegalLinks.TERMS_OF_SERVICE, legalLinkStyles)) {
                append("Condiciones del Servicio")
            }
            append(" y la ")
            withLink(LinkAnnotation.Url(LegalLinks.PRIVACY_POLICY, legalLinkStyles)) {
                append("Política de Privacidad")
            }
            append(".")
        },
        style = MaterialTheme.typography.bodyMedium,
        color = LogicColors.OnDarkMuted,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Bloque "Legal" de Ajustes: una fila por documento que abre la página pública.
 *
 * Se muestra **con y sin sesión**: los documentos aplican igual a un invitado, y
 * las tiendas esperan que sean alcanzables desde dentro de la App sin tener que
 * registrarse.
 */
@Composable
fun LegalLinksSection(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(LogicColors.SurfaceDark)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        LegalLinkRow(
            icon = Icons.Rounded.Description,
            tint = LogicColors.Blue,
            title = "Condiciones del Servicio",
            subtitle = "Qué puedes esperar de la App y qué esperamos de ti",
            onClick = { uriHandler.openUri(LegalLinks.TERMS_OF_SERVICE) },
        )
        LegalLinkRow(
            icon = Icons.Rounded.Shield,
            tint = LogicColors.NeonGreen,
            title = "Política de Privacidad",
            subtitle = "Qué datos tratamos y qué derechos tienes",
            onClick = { uriHandler.openUri(LegalLinks.PRIVACY_POLICY) },
        )
    }
}

/**
 * Fila de documento legal. Lleva icono de "abrir fuera" a la derecha porque el
 * destino es el navegador, no otra pantalla de la App: conviene que el usuario lo
 * sepa antes de pulsar.
 */
@Composable
private fun LegalLinkRow(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeonIcon(icon = icon, tint = tint, size = 22.dp, glow = false)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = LogicColors.OnDark)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = LogicColors.OnDarkMuted,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = "Se abre en el navegador",
            tint = LogicColors.OnDarkMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}
