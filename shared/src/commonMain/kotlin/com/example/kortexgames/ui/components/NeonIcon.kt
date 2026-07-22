package com.example.kortexgames.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Iconos de la app: **vectoriales Material reales**, nunca emojis (requisito de
 * diseño). Se centralizan con nombres semánticos para desacoplar la UI del set
 * concreto y poder cambiarlos en un solo sitio. Se usa la variante `Rounded`,
 * coherente con el lenguaje "amigable y redondeado" de la app.
 */
object KortexIcons {
    val Home: ImageVector = Icons.Rounded.Home
    val Games: ImageVector = Icons.Rounded.SportsEsports
    val Profile: ImageVector = Icons.Rounded.Person
    val Streak: ImageVector = Icons.Rounded.LocalFireDepartment
    val Play: ImageVector = Icons.Rounded.PlayArrow
    val Settings: ImageVector = Icons.Rounded.Settings
    val ChevronRight: ImageVector = Icons.Rounded.ChevronRight
    val Dice: ImageVector = Icons.Rounded.Casino

    /** Trofeo: fin de partida / recompensa / logro. */
    val Trophy: ImageVector = Icons.Rounded.EmojiEvents

    /** Check en círculo: tarea/objetivo cumplido (p. ej. entrenamiento diario). */
    val Check: ImageVector = Icons.Rounded.CheckCircle

    /** Estrella: destacado / mejor rendimiento (p. ej. "tu juego estrella"). */
    val Star: ImageVector = Icons.Rounded.Star

    /** Deshacer el último movimiento (acción de juego). */
    val Undo: ImageVector = Icons.AutoMirrored.Rounded.Undo

    /** Reiniciar el nivel actual (acción de juego). */
    val Refresh: ImageVector = Icons.Rounded.Refresh

    /** Corazón lleno: una vida disponible (marcador de vidas). */
    val Heart: ImageVector = Icons.Rounded.Favorite

    /** Corazón vacío: una vida ya perdida. */
    val HeartOutline: ImageVector = Icons.Rounded.FavoriteBorder

    /** Candado: nivel aún bloqueado (selector de niveles). */
    val Lock: ImageVector = Icons.Rounded.Lock

    /** Cronómetro: récord de tiempo (mejor tiempo por nivel en el selector). */
    val Timer: ImageVector = Icons.Rounded.Timer

    /** Interrogación: botón de ayuda / cómo se juega (antesala de cada juego). */
    val Help: ImageVector = Icons.AutoMirrored.Rounded.HelpOutline

    /** Borrar la última letra escrita (tecla de borrado del teclado de juego). */
    val Backspace: ImageVector = Icons.AutoMirrored.Rounded.Backspace

    /** Papelera: borrar toda la palabra en curso de una vez (limpia el buffer). */
    val Trash: ImageVector = Icons.Rounded.DeleteOutline

    /** Pausa: detiene la partida y abre el menú de pausa (común a todos los juegos). */
    val Pause: ImageVector = Icons.Rounded.Pause

    /** Salir de la partida: abandona el juego y vuelve a la lista (menú de pausa). */
    val Exit: ImageVector = Icons.AutoMirrored.Rounded.ExitToApp

    /** Cerrar: descarta el menú de pausa y reanuda (botón "X" de la tarjeta de pausa). */
    val Close: ImageVector = Icons.Rounded.Close

    /** Efectos de sonido activados (toggle de audio del menú de pausa/ajustes). */
    val SfxOn: ImageVector = Icons.AutoMirrored.Rounded.VolumeUp

    /** Efectos de sonido silenciados. */
    val SfxOff: ImageVector = Icons.AutoMirrored.Rounded.VolumeOff

    /** Música de fondo activada. */
    val MusicOn: ImageVector = Icons.Rounded.MusicNote

    /** Música de fondo silenciada. */
    val MusicOff: ImageVector = Icons.Rounded.MusicOff

    /** Háptica (vibración) activada. */
    val Haptics: ImageVector = Icons.Rounded.Vibration

    /**
     * Vídeo recompensado: ver un anuncio a cambio de una ventaja opcional (p. ej.
     * un tubo extra en "Ordena las Pociones"). Señala visualmente que la acción
     * está sujeta a un anuncio, no que sea gratuita.
     */
    val RewardedAd: ImageVector = Icons.Rounded.OndemandVideo
}

/**
 * Icono con **halo neón**: dibuja un resplandor radial del color de acento detrás
 * del icono, tal como el mockup (iconos "encendidos" sobre fondo oscuro). El halo
 * es puramente estático aquí; las animaciones (latido, brillo) las aplica quien lo
 * usa vía [Modifier.pulse]/[Modifier.softGlow] si procede.
 *
 * @param tint color del icono y del resplandor.
 * @param size tamaño del glifo (el halo ocupa ~1.9x).
 * @param glow si false, dibuja el icono sin resplandor (p. ej. estado inactivo).
 * @param contentDescription etiqueta de accesibilidad; null para iconos decorativos.
 */
@Composable
fun NeonIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    glow: Boolean = true,
    contentDescription: String? = null,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (glow) {
            Box(
                modifier = Modifier
                    .size(size * 1.9f)
                    .background(
                        Brush.radialGradient(
                            listOf(tint.copy(alpha = 0.38f), Color.Transparent),
                        ),
                    ),
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}
