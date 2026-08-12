package com.kortexgames.app.core.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kortexgames.app.shared.R

/**
 * Publica la notificación cuando vence su alarma.
 *
 * Se ejecuta **sin app viva**: el proceso puede haber muerto hace días y el sistema
 * lo revive solo para entregar este broadcast. Por eso aquí no se consulta ningún
 * repositorio ni se resuelven recursos de string —el texto ya viaja resuelto en los
 * extras (ver [AndroidNotificationScheduler])—: `onReceive` corre en el hilo
 * principal con unos pocos segundos de presupuesto y cualquier E/S lo pondría en
 * riesgo de ANR.
 *
 * Se declara en el manifest de `androidApp` (los componentes de Android necesitan
 * estar declarados en el manifest final de la aplicación).
 */
class NotificationAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val kindName = intent.getStringExtra(NotificationExtras.KIND) ?: return
        val title = intent.getStringExtra(NotificationExtras.TITLE) ?: return
        val body = intent.getStringExtra(NotificationExtras.BODY) ?: return
        // Un tipo desconocido solo puede venir de una alarma programada por una versión
        // anterior de la app cuyo enum ya no existe: se ignora en silencio.
        val kind = NotificationKind.entries.firstOrNull { it.name == kindName } ?: return

        val notification = Notification.Builder(context, kind.channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            // BigTextStyle: los cuerpos son frases completas y sin esto Android las
            // corta con puntos suspensivos en la primera línea.
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent(context, kind))
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(kind.systemId, notification)
    }

    /**
     * Al tocar la notificación se abre la app por su launcher. No se navega a ninguna
     * pantalla concreta a propósito: los deep links aún no existen en la app (un solo
     * `NavHost` sin rutas externas), y abrir en Home es el comportamiento correcto
     * mientras tanto. Cuando existan, este es el único punto a tocar.
     */
    private fun openAppIntent(context: Context, kind: NotificationKind): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context,
            kind.systemId,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
