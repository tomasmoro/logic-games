package com.kortexgames.app.core.notifications

import kortexgames.shared.generated.resources.Res
import kortexgames.shared.generated.resources.notif_channel_records_description
import kortexgames.shared.generated.resources.notif_channel_records_name
import kortexgames.shared.generated.resources.notif_channel_reminders_description
import kortexgames.shared.generated.resources.notif_channel_reminders_name
import kortexgames.shared.generated.resources.notif_daily_mission_body_1
import kortexgames.shared.generated.resources.notif_daily_mission_body_2
import kortexgames.shared.generated.resources.notif_daily_mission_body_3
import kortexgames.shared.generated.resources.notif_daily_mission_title_1
import kortexgames.shared.generated.resources.notif_daily_mission_title_2
import kortexgames.shared.generated.resources.notif_daily_mission_title_3
import kortexgames.shared.generated.resources.notif_debug_test_body
import kortexgames.shared.generated.resources.notif_debug_test_title
import kortexgames.shared.generated.resources.notif_inactivity_14d_body_1
import kortexgames.shared.generated.resources.notif_inactivity_14d_body_2
import kortexgames.shared.generated.resources.notif_inactivity_14d_body_3
import kortexgames.shared.generated.resources.notif_inactivity_14d_title_1
import kortexgames.shared.generated.resources.notif_inactivity_14d_title_2
import kortexgames.shared.generated.resources.notif_inactivity_14d_title_3
import kortexgames.shared.generated.resources.notif_inactivity_3d_body_1
import kortexgames.shared.generated.resources.notif_inactivity_3d_body_2
import kortexgames.shared.generated.resources.notif_inactivity_3d_body_3
import kortexgames.shared.generated.resources.notif_inactivity_3d_title_1
import kortexgames.shared.generated.resources.notif_inactivity_3d_title_2
import kortexgames.shared.generated.resources.notif_inactivity_3d_title_3
import kortexgames.shared.generated.resources.notif_inactivity_7d_body_1
import kortexgames.shared.generated.resources.notif_inactivity_7d_body_2
import kortexgames.shared.generated.resources.notif_inactivity_7d_body_3
import kortexgames.shared.generated.resources.notif_inactivity_7d_title_1
import kortexgames.shared.generated.resources.notif_inactivity_7d_title_2
import kortexgames.shared.generated.resources.notif_inactivity_7d_title_3
import kortexgames.shared.generated.resources.notif_record_beaten_body_1
import kortexgames.shared.generated.resources.notif_record_beaten_body_2
import kortexgames.shared.generated.resources.notif_record_beaten_body_3
import kortexgames.shared.generated.resources.notif_record_beaten_title_1
import kortexgames.shared.generated.resources.notif_record_beaten_title_2
import kortexgames.shared.generated.resources.notif_record_beaten_title_3
import kortexgames.shared.generated.resources.notif_record_broken_body_1
import kortexgames.shared.generated.resources.notif_record_broken_body_2
import kortexgames.shared.generated.resources.notif_record_broken_title_1
import kortexgames.shared.generated.resources.notif_record_broken_title_2
import kortexgames.shared.generated.resources.notif_streak_risk_body_1
import kortexgames.shared.generated.resources.notif_streak_risk_body_2
import kortexgames.shared.generated.resources.notif_streak_risk_body_3
import kortexgames.shared.generated.resources.notif_streak_risk_title_1
import kortexgames.shared.generated.resources.notif_streak_risk_title_2
import kortexgames.shared.generated.resources.notif_streak_risk_title_3
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.random.Random

/**
 * # Con qué palabras
 *
 * Traduce un [NotificationContent] al texto que verá el usuario, leyéndolo de
 * `composeResources/values/strings.xml`.
 *
 * **Por qué varias variantes por mensaje:** un recordatorio que llega cada semana
 * con exactamente la misma frase se convierte en ruido de fondo y acaba silenciado.
 * Cada tipo de aviso declara aquí 2–3 redacciones equivalentes y se elige una al
 * azar en el momento de programarlo. La variante se resuelve **al programar**, no
 * al entregar, porque en ambas plataformas el texto viaja ya escrito dentro del
 * aviso pendiente del sistema.
 *
 * **Por qué pasa por recursos y no por constantes de Kotlin:** es lo que hace que
 * añadir un idioma sea crear `values-<código>/strings.xml` sin tocar este archivo.
 *
 * @param random fuente de aleatoriedad; inyectable para fijarla en los tests.
 */
class NotificationCopyProvider(private val random: Random = Random.Default) {

    /**
     * Resuelve el texto de [content]. Es `suspend` porque leer un recurso de string
     * fuera de Compose lo es (`getString`): en iOS implica tocar el bundle.
     *
     * Título y cuerpo se toman **del mismo índice** de variante a propósito: las
     * redacciones se escriben por parejas y mezclarlas produciría combinaciones que
     * nadie ha revisado (p. ej. un título que ya dice lo que repite el cuerpo).
     */
    suspend fun resolve(content: NotificationContent): ReadyNotification {
        val variants = variantsFor(content)
        val index = random.nextInt(variants.size)
        val (title, body) = variants[index]
        val args = formatArgsFor(content)
        return ReadyNotification(
            kind = content.kind,
            title = getString(title, *args),
            body = getString(body, *args),
        )
    }

    /** Nombre del canal tal y como lo verá el usuario en los ajustes del sistema. */
    suspend fun channelName(channel: NotificationChannel): String = when (channel) {
        NotificationChannel.REMINDERS -> getString(Res.string.notif_channel_reminders_name)
        NotificationChannel.RECORDS -> getString(Res.string.notif_channel_records_name)
    }

    /** Descripción del canal (Android la muestra bajo su nombre). */
    suspend fun channelDescription(channel: NotificationChannel): String = when (channel) {
        NotificationChannel.REMINDERS -> getString(Res.string.notif_channel_reminders_description)
        NotificationChannel.RECORDS -> getString(Res.string.notif_channel_records_description)
    }

    /**
     * Argumentos de formato del mensaje. Todos van como `String` (y las claves usan
     * `%1$s`, nunca `%1$d`) porque el formateo de Compose Resources es una
     * sustitución textual multiplataforma, no el `String.format` de la JVM: pasar un
     * número ya convertido evita depender de cómo cada plataforma interpreta `%d`.
     */
    private fun formatArgsFor(content: NotificationContent): Array<String> = when (content) {
        is NotificationContent.RecordBeaten -> arrayOf(content.gameTitle)
        is NotificationContent.RecordBroken -> arrayOf(content.gameTitle)
        is NotificationContent.StreakAtRisk -> arrayOf(content.streakDays.toString())
        is NotificationContent.DailyMissionPending -> arrayOf(content.remaining.toString())
        // Los mensajes de inactividad son fijos: el escalón ya elige el texto.
        is NotificationContent.Inactivity -> emptyArray()
        NotificationContent.DebugTest -> emptyArray()
    }

    /** Parejas (título, cuerpo) disponibles para cada contenido. Nunca vacías. */
    private fun variantsFor(content: NotificationContent): List<Pair<StringResource, StringResource>> =
        when (content) {
            is NotificationContent.RecordBeaten -> RECORD_BEATEN
            is NotificationContent.RecordBroken -> RECORD_BROKEN
            is NotificationContent.StreakAtRisk -> STREAK_AT_RISK
            is NotificationContent.DailyMissionPending -> DAILY_MISSION
            is NotificationContent.Inactivity -> when (content.step) {
                InactivityStep.DAY_3 -> INACTIVITY_3D
                InactivityStep.DAY_7 -> INACTIVITY_7D
                InactivityStep.DAY_14 -> INACTIVITY_14D
            }
            // Una sola redacción: es diagnóstico, no un mensaje que el usuario vaya a
            // ver repetido.
            NotificationContent.DebugTest -> DEBUG_TEST
        }

    private companion object {
        val RECORD_BEATEN = listOf(
            Res.string.notif_record_beaten_title_1 to Res.string.notif_record_beaten_body_1,
            Res.string.notif_record_beaten_title_2 to Res.string.notif_record_beaten_body_2,
            Res.string.notif_record_beaten_title_3 to Res.string.notif_record_beaten_body_3,
        )
        val RECORD_BROKEN = listOf(
            Res.string.notif_record_broken_title_1 to Res.string.notif_record_broken_body_1,
            Res.string.notif_record_broken_title_2 to Res.string.notif_record_broken_body_2,
        )
        val STREAK_AT_RISK = listOf(
            Res.string.notif_streak_risk_title_1 to Res.string.notif_streak_risk_body_1,
            Res.string.notif_streak_risk_title_2 to Res.string.notif_streak_risk_body_2,
            Res.string.notif_streak_risk_title_3 to Res.string.notif_streak_risk_body_3,
        )
        val DAILY_MISSION = listOf(
            Res.string.notif_daily_mission_title_1 to Res.string.notif_daily_mission_body_1,
            Res.string.notif_daily_mission_title_2 to Res.string.notif_daily_mission_body_2,
            Res.string.notif_daily_mission_title_3 to Res.string.notif_daily_mission_body_3,
        )
        val INACTIVITY_3D = listOf(
            Res.string.notif_inactivity_3d_title_1 to Res.string.notif_inactivity_3d_body_1,
            Res.string.notif_inactivity_3d_title_2 to Res.string.notif_inactivity_3d_body_2,
            Res.string.notif_inactivity_3d_title_3 to Res.string.notif_inactivity_3d_body_3,
        )
        val INACTIVITY_7D = listOf(
            Res.string.notif_inactivity_7d_title_1 to Res.string.notif_inactivity_7d_body_1,
            Res.string.notif_inactivity_7d_title_2 to Res.string.notif_inactivity_7d_body_2,
            Res.string.notif_inactivity_7d_title_3 to Res.string.notif_inactivity_7d_body_3,
        )
        val DEBUG_TEST = listOf(
            Res.string.notif_debug_test_title to Res.string.notif_debug_test_body,
        )
        val INACTIVITY_14D = listOf(
            Res.string.notif_inactivity_14d_title_1 to Res.string.notif_inactivity_14d_body_1,
            Res.string.notif_inactivity_14d_title_2 to Res.string.notif_inactivity_14d_body_2,
            Res.string.notif_inactivity_14d_title_3 to Res.string.notif_inactivity_14d_body_3,
        )
    }
}
