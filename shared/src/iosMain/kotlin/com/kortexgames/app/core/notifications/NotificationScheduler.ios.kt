package com.kortexgames.app.core.notifications

import com.kortexgames.app.core.audio.PlatformContext
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.darwin.NSObject
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * `actual` de iOS: **UNUserNotificationCenter**.
 *
 * Diferencias de fondo con Android que explican el código de este archivo:
 *
 *  - iOS no tiene canales. La agrupación se hace con `threadIdentifier`, que es lo
 *    que usa el sistema para apilar avisos relacionados en el centro de
 *    notificaciones. Se reutiliza el id del canal común para no inventar otro eje.
 *  - No hay "alarma": se registra una petición con un disparador **relativo**
 *    (`UNTimeIntervalNotificationTrigger`), que el sistema conserva aunque la app se
 *    cierre o se mate. Por eso el instante absoluto se convierte aquí a segundos
 *    desde ahora.
 *  - El identificador de la petición **es** la clave de reemplazo: registrar otra
 *    con el mismo identificador sustituye a la anterior, que es justo el
 *    comportamiento que [NotificationScheduler] promete.
 *  - El permiso se pide con un único diálogo del sistema y **no se puede volver a
 *    mostrar** si el usuario lo deniega (a partir de ahí solo se cambia desde
 *    Ajustes). De ahí la regla de pedirlo solo desde un gesto explícito.
 */
internal class IosNotificationScheduler(
    private val clock: Clock = Clock.System,
) : NotificationScheduler {

    private val center: UNUserNotificationCenter
        get() = UNUserNotificationCenter.currentNotificationCenter()

    init {
        // Sin delegado, iOS **descarta silenciosamente** cualquier notificación local
        // que venza con la app en primer plano (Android sí la publica). Esa diferencia
        // hace que un aviso legítimo —el recordatorio de las 19:00 mientras el jugador
        // acaba una partida— se pierda solo en iOS, y convierte cualquier prueba
        // manual con la app abierta en un falso negativo. El delegado la muestra como
        // banner, igualando el comportamiento de las dos plataformas.
        center.setDelegate(foregroundPresenter)
    }

    override suspend fun areNotificationsAllowed(): Boolean =
        suspendCancellableCoroutine { continuation ->
            center.getNotificationSettingsWithCompletionHandler { settings ->
                val status = settings?.authorizationStatus
                // Provisional/ephemeral también permiten entregar (silenciosamente, o
                // en App Clips): si el sistema deja publicar, para el módulo es un sí.
                val allowed = status == UNAuthorizationStatusAuthorized ||
                    status == UNAuthorizationStatusProvisional ||
                    status == UNAuthorizationStatusEphemeral
                if (continuation.isActive) continuation.resume(allowed)
            }
        }

    override suspend fun requestPermission(): Boolean =
        suspendCancellableCoroutine { continuation ->
            val options = UNAuthorizationOptionAlert or
                UNAuthorizationOptionSound or
                UNAuthorizationOptionBadge
            center.requestAuthorizationWithOptions(options) { granted, _ ->
                // El error (raro: solo si el bundle no admite notificaciones) se ignora
                // a propósito; para el módulo es indistinguible de un "no".
                if (continuation.isActive) continuation.resume(granted)
            }
        }

    override suspend fun schedule(notification: ReadyNotification, at: Instant) {
        val seconds = (at - clock.now()).inWholeSeconds.toDouble()
        // iOS rechaza disparadores <= 0 s; además, un aviso ya vencido no debe entregarse.
        if (seconds <= 0.0) return

        val content = UNMutableNotificationContent().apply {
            setTitle(notification.title)
            setBody(notification.body)
            setSound(UNNotificationSound.defaultSound)
            setThreadIdentifier(notification.kind.channel.id)
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = notification.kind.identifier,
            content = content,
            trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds, repeats = false),
        )
        center.addNotificationRequest(request, withCompletionHandler = null)
    }

    override fun cancel(kind: NotificationKind) {
        val ids = listOf(kind.identifier)
        center.removePendingNotificationRequestsWithIdentifiers(ids)
        // Igual que en Android: un aviso ya entregado y aún visible en el centro de
        // notificaciones es información obsoleta si el plan ha cambiado.
        center.removeDeliveredNotificationsWithIdentifiers(ids)
    }

    override fun cancelAll() {
        val ids = NotificationKind.entries.filter { it.isManaged }.map { it.identifier }
        center.removePendingNotificationRequestsWithIdentifiers(ids)
        center.removeDeliveredNotificationsWithIdentifiers(ids)
    }
}

/**
 * Delegado que permite mostrar los avisos con la app en primer plano.
 *
 * Vive como singleton de proceso porque `UNUserNotificationCenter.setDelegate` guarda
 * una referencia **débil**: un delegado creado y olvidado en el `init` del scheduler
 * sería recolectado y iOS volvería a descartar las notificaciones en primer plano,
 * con el agravante de que el fallo aparecería de forma intermitente.
 */
private val foregroundPresenter = ForegroundNotificationPresenter()

/** Implementación del protocolo `UNUserNotificationCenterDelegate` (ver [foregroundPresenter]). */
private class ForegroundNotificationPresenter :
    NSObject(),
    UNUserNotificationCenterDelegateProtocol {

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        // Banner + sonido, sin insignia: el mismo peso visual que tendría el aviso con
        // la app cerrada.
        withCompletionHandler(
            UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionSound,
        )
    }
}

/**
 * Identificador de la petición en iOS. Se deriva del [NotificationKind.systemId] —el
 * mismo número que Android usa como id— para que las dos plataformas compartan la
 * noción de "un aviso por tipo" sin mantener dos tablas de identificadores.
 */
private val NotificationKind.identifier: String get() = "kortex.notification.$systemId"

/**
 * Construye el scheduler de iOS.
 *
 * Ignora los dos parámetros a propósito: en iOS el `PlatformContext` es un marcador
 * vacío, y [copy] solo lo necesita Android para nombrar sus canales (aquí no
 * existen). Siguen en la firma porque es la del `expect` común.
 */
actual fun createNotificationScheduler(
    context: PlatformContext,
    copy: NotificationCopyProvider,
): NotificationScheduler = IosNotificationScheduler()
