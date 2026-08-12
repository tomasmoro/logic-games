package com.kortexgames.app.core.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.kortexgames.app.core.audio.PlatformContext
import kotlin.time.Clock
import kotlin.time.Instant
import android.app.NotificationChannel as AndroidNotificationChannel

/**
 * `actual` de Android: **AlarmManager + NotificationManager**.
 *
 * Por qué esta pareja y no WorkManager: los avisos son de reloj (a las 20:30 de
 * hoy, dentro de tres días), no trabajo diferible con restricciones de red o
 * batería. `AlarmManager` es exactamente eso, no añade una dependencia nueva al
 * proyecto y sobrevive a que el usuario cierre la app.
 *
 * Se usa [AlarmManager.setAndAllowWhileIdle] y NO `setExactAndAllowWhileIdle`: la
 * versión exacta exige el permiso `SCHEDULE_EXACT_ALARM`, que Google Play solo
 * concede a alarmas/calendarios y rechazaría en una app de juegos. Un recordatorio
 * de retención tolera perfectamente que el sistema lo agrupe con unos minutos de
 * margen; lo que no toleraría es que la app fuese rechazada en la revisión.
 *
 * Al despertar la alarma no hay app viva a la que preguntar nada, así que el texto
 * ya resuelto viaja dentro del `Intent` hasta [NotificationAlarmReceiver].
 */
internal class AndroidNotificationScheduler(
    private val context: Context,
    private val copy: NotificationCopyProvider,
    private val clock: Clock = Clock.System,
) : NotificationScheduler {

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Los canales se crean una sola vez, la primera vez que hace falta programar. */
    private var channelsReady = false

    override suspend fun areNotificationsAllowed(): Boolean {
        // Desde Android 13 el permiso es de ejecución; antes basta con que el usuario
        // no haya silenciado la app desde los ajustes del sistema.
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return granted && notificationManager.areNotificationsEnabled()
    }

    override suspend fun requestPermission(): Boolean {
        if (areNotificationsAllowed()) return true
        // Antes de Android 13 no hay diálogo que mostrar: si los avisos están
        // desactivados, solo el usuario puede reactivarlos desde los ajustes.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return NotificationPermissionRequester.request()
    }

    override suspend fun schedule(notification: ReadyNotification, at: Instant) {
        if (at <= clock.now()) return
        ensureChannels()

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            at.toEpochMilliseconds(),
            pendingIntent(notification),
        )
    }

    override fun cancel(kind: NotificationKind) {
        // FLAG_NO_CREATE: si no existe un PendingIntent equivalente, no hay alarma que
        // cancelar y devuelve null (evita crear uno solo para tirarlo).
        val existing = PendingIntent.getBroadcast(
            context,
            kind.systemId,
            alarmIntent(context, kind),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        existing?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
        // Además retira la notificación si ya estaba publicada en la barra: al
        // replanificar, un aviso vencido y aún visible cuenta como ruido obsoleto.
        notificationManager.cancel(kind.systemId)
    }

    override fun cancelAll() {
        NotificationKind.entries.filter { it.isManaged }.forEach(::cancel)
    }

    /**
     * `PendingIntent` de la alarma. La clave es el `requestCode` = `kind.systemId`:
     * dos programaciones del mismo tipo comparten identidad, así que
     * `FLAG_UPDATE_CURRENT` **reemplaza** la anterior (extras nuevos incluidos) en
     * vez de acumular alarmas duplicadas.
     */
    private fun pendingIntent(notification: ReadyNotification): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            notification.kind.systemId,
            alarmIntent(context, notification),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Crea los canales (obligatorios desde API 26, y `minSdk` del proyecto es 26).
     * Es idempotente en el sistema, pero se cachea en memoria para no resolver los
     * textos de recursos en cada programación.
     */
    private suspend fun ensureChannels() {
        if (channelsReady) return
        for (channel in NotificationChannel.entries) {
            val channelDescription = copy.channelDescription(channel)
            notificationManager.createNotificationChannel(
                AndroidNotificationChannel(
                    channel.id,
                    copy.channelName(channel),
                    // DEFAULT y no HIGH: son recordatorios, no urgencias. HIGH
                    // dispararía "heads-up" tapando lo que el usuario esté haciendo,
                    // que es justo la clase de aviso que la gente acaba silenciando.
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = channelDescription },
            )
        }
        channelsReady = true
    }
}

/**
 * Construye el scheduler de Android. Usa el `applicationContext`: el aviso puede
 * dispararse mucho después de que muera la Activity que lo programó.
 */
actual fun createNotificationScheduler(
    context: PlatformContext,
    copy: NotificationCopyProvider,
): NotificationScheduler =
    AndroidNotificationScheduler(context.context.applicationContext, copy)

/** Extras con los que viaja el aviso hasta el receiver (ver [NotificationAlarmReceiver]). */
internal object NotificationExtras {
    const val KIND = "kortex.notification.kind"
    const val TITLE = "kortex.notification.title"
    const val BODY = "kortex.notification.body"
}

/** `Intent` de la alarma con el texto ya resuelto dentro. */
private fun alarmIntent(context: Context, notification: ReadyNotification): Intent =
    Intent(context, NotificationAlarmReceiver::class.java).apply {
        putExtra(NotificationExtras.KIND, notification.kind.name)
        putExtra(NotificationExtras.TITLE, notification.title)
        putExtra(NotificationExtras.BODY, notification.body)
    }

/**
 * Variante sin textos, solo para **identificar** la alarma al cancelarla: dos
 * `Intent` son equivalentes de cara a `PendingIntent` si coinciden acción, datos y
 * componente — los extras no cuentan para esa comparación.
 */
private fun alarmIntent(context: Context, kind: NotificationKind): Intent =
    Intent(context, NotificationAlarmReceiver::class.java).apply {
        putExtra(NotificationExtras.KIND, kind.name)
    }
