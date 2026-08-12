package com.kortexgames.app.core.notifications

import com.kortexgames.app.core.audio.PlatformContext
import kotlin.time.Instant

/**
 * # Cómo se entrega
 *
 * Frontera con el sistema de notificaciones nativo. El contrato es deliberadamente
 * pequeño —programar, cancelar, permisos— para que la lógica de retención viva
 * entera en `commonMain` y cada plataforma solo aporte su fontanería:
 *
 *  - **Android**: `AlarmManager` despierta un `BroadcastReceiver` que publica la
 *    notificación en su canal. Los avisos sobreviven a que se cierre la app.
 *  - **iOS**: `UNUserNotificationCenter` con `UNTimeIntervalNotificationTrigger`;
 *    el sistema guarda la petición y la entrega aunque la app esté terminada.
 *
 * ## Seam para el push remoto
 *
 * Todo lo que hay aquí es **local**: el dispositivo se programa sus propios avisos.
 * El día que se añada "otro jugador superó tu récord"
 * ([NotificationKind.RECORD_BROKEN]) hará falta un emisor remoto (FCM en Android,
 * APNs en iOS, disparado desde Supabase). Ese emisor **no sustituye** a esta
 * interfaz: entra como una segunda fuente que produce el mismo [ReadyNotification]
 * —con su copy ya escrita en `strings.xml` y su canal ya definido— y lo publica en
 * el momento en que llega el mensaje, en vez de programarlo a futuro. Por eso el
 * modelo (tipo, canal, textos) está separado de la programación: añadir push será
 * implementar un `NotificationPresenter` por plataforma, sin tocar el planner, la
 * copy ni el manager.
 */
interface NotificationScheduler {

    /**
     * ¿Puede la app publicar notificaciones ahora mismo? Es una pregunta al sistema
     * operativo (permiso concedido y avisos no silenciados), independiente de la
     * preferencia propia de la app.
     */
    suspend fun areNotificationsAllowed(): Boolean

    /**
     * Pide el permiso del sistema si aún no se ha concedido.
     *
     * Debe llamarse desde un gesto explícito del usuario (activar el interruptor de
     * Ajustes), nunca al arrancar: ambas tiendas penalizan pedir el permiso a bocajarro
     * y, si el usuario lo deniega, no hay segunda oportunidad sin mandarlo a los
     * ajustes del sistema.
     *
     * @return true si tras la llamada la app puede notificar.
     */
    suspend fun requestPermission(): Boolean

    /**
     * Programa (o **reemplaza**, si ya había uno de ese mismo tipo) la entrega de
     * [notification] en el instante [at]. Reemplazar en vez de acumular es lo que
     * permite reprogramar el plan entero sin llevar contabilidad de lo anterior.
     *
     * Un [at] ya pasado se ignora: nunca se entrega un aviso con retraso.
     */
    suspend fun schedule(notification: ReadyNotification, at: Instant)

    /** Cancela el aviso pendiente de ese tipo, si lo hubiera. Idempotente. */
    fun cancel(kind: NotificationKind)

    /** Cancela todos los avisos pendientes de la app (apagar los recordatorios). */
    fun cancelAll()
}

/**
 * Construye el scheduler nativo de la plataforma.
 *
 * @param context contexto de plataforma (en Android envuelve el `Context`; en iOS
 *   es un marcador vacío).
 * @param copy proveedor de textos: en Android hace falta ya en la creación de los
 *   canales, cuyo nombre lee el usuario en los ajustes del sistema.
 */
expect fun createNotificationScheduler(
    context: PlatformContext,
    copy: NotificationCopyProvider,
): NotificationScheduler
