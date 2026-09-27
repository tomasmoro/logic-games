package com.kortexgames.app.core.notifications

import kotlin.time.Instant

/**
 * # Modelo del módulo de notificaciones
 *
 * Piezas del dominio de notificaciones, sin dependencias de plataforma ni de UI.
 * El módulo completo se divide en cuatro responsabilidades separadas a propósito:
 *
 *  1. **Qué se puede notificar** — este archivo ([NotificationKind], [NotificationContent]).
 *  2. **Cuándo** — [NotificationPlanner]: función pura, testeable, sin relojes ocultos.
 *  3. **Con qué palabras** — [NotificationCopyProvider]: resuelve los textos desde
 *     `strings.xml` eligiendo una variante al azar.
 *  4. **Cómo se entrega** — [NotificationScheduler]: `expect/actual` por plataforma
 *     (AlarmManager + NotificationManager en Android, `UNUserNotificationCenter` en iOS).
 *
 * Esa separación es la que permite probar la lógica de negocio (¿toca avisar hoy?)
 * en `commonTest` sin simulador ni emulador, que es la regla de verificación del
 * proyecto (CLAUDE.md §6).
 */

/**
 * Tipos de notificación que la app sabe emitir. El `enum` —y no strings sueltos—
 * es lo que permite cancelar y reprogramar por tipo sin arrastrar identificadores
 * mágicos por el código.
 *
 * @property systemId identificador **estable** del aviso en el sistema operativo.
 *   Estable = reprogramar un tipo REEMPLAZA su aviso pendiente en vez de acumular
 *   duplicados (en Android es el `requestCode`/id de notificación, en iOS el
 *   `identifier` de la `UNNotificationRequest`). Los valores son fijos y no deben
 *   reordenarse: un cambio dejaría huérfano un aviso ya programado en dispositivos
 *   que no han abierto la app desde entonces.
 * @property channel canal/agrupación bajo la que se muestra (ver [NotificationChannel]).
 * @property isManaged true si este aviso lo gobierna el planificador local, y por
 *   tanto [NotificationScheduler.cancelAll] debe retirarlo al replanificar. Los que
 *   NO se planifican en local (los que llegarán por push, y el de prueba de
 *   depuración) se marcan false: cancelarlos en cada replanificación los haría
 *   desaparecer por sorpresa sin que nada del estado local los explique.
 */
enum class NotificationKind(
    val systemId: Int,
    val channel: NotificationChannel,
    val isManaged: Boolean = true,
) {

    /** Récord personal batido: se recuerda al día siguiente para invitar a defenderlo. */
    RECORD_BEATEN(1001, NotificationChannel.RECORDS),

    /**
     * La marca del jugador la superó **otro jugador**. No se programa nunca desde el
     * dispositivo: en local no hay forma de saber lo que hacen los demás. Existe ya
     * porque el resto del módulo (copy, canal, id) debe estar listo el día que se
     * enchufe el push remoto — ver el KDoc de [NotificationScheduler] sobre el seam.
     */
    RECORD_BROKEN(1002, NotificationChannel.RECORDS, isManaged = false),

    /** La racha de días consecutivos se pierde esta noche si no se juega. */
    STREAK_AT_RISK(1003, NotificationChannel.REMINDERS),

    /** La misión diaria (3 juegos) sigue incompleta al caer la tarde. */
    DAILY_MISSION(1004, NotificationChannel.REMINDERS),

    /** Primer escalón de reenganche: 3 días sin abrir la app. */
    INACTIVITY_3D(1005, NotificationChannel.REMINDERS),

    /** Segundo escalón: una semana sin jugar. */
    INACTIVITY_7D(1006, NotificationChannel.REMINDERS),

    /** Último escalón: dos semanas. Después de este no se insiste más. */
    INACTIVITY_14D(1007, NotificationChannel.REMINDERS),

    /** Un torneo al que el jugador puede entrar acaba de abrirse. */
    EVENT_STARTING(1008, NotificationChannel.EVENTS),

    /** Quedan pocas horas de torneo y el jugador aún no ha participado. */
    EVENT_ENDING_SOON(1009, NotificationChannel.EVENTS),

    /** El torneo cerró: ya hay clasificación definitiva que mirar. */
    EVENT_RESULTS(1010, NotificationChannel.EVENTS),

    /**
     * Aviso de prueba a 15 segundos, para comprobar en un dispositivo real que la
     * entrega funciona de punta a punta (permiso → programación → sistema → barra de
     * notificaciones) sin esperar a que se cumpla una regla de retención.
     *
     * Solo lo dispara la herramienta de depuración de Ajustes, que **únicamente
     * existe en builds de debug** (ver `isDebugBuild`). El planificador no lo emite
     * jamás, así que en una build de tienda este valor está aquí pero es inalcanzable.
     */
    DEBUG_TEST(1099, NotificationChannel.REMINDERS, isManaged = false),
}

/**
 * Agrupación de avisos de cara al sistema operativo. Android exige canales desde
 * API 26 y el usuario puede silenciar cada uno por separado; separar "récords" de
 * "recordatorios" evita que quien se harta de los recordatorios diarios pierda
 * también los avisos de sus marcas (y viceversa).
 *
 * En iOS no existe el concepto de canal, así que allí este valor solo se usa para
 * decidir el `threadIdentifier` con el que se agrupan las notificaciones.
 *
 * @property id identificador técnico del canal en Android. Estable: cambiarlo crea
 *   un canal nuevo y el usuario perdería sus ajustes de sonido/importancia.
 */
enum class NotificationChannel(val id: String) {
    /** Racha, objetivo diario, reenganche por inactividad. */
    REMINDERS("kortex_reminders"),

    /** Mejores marcas propias y ajenas. */
    RECORDS("kortex_records"),

    /**
     * Torneos: apertura, últimas horas y resultados.
     *
     * Canal propio por la misma razón que separa récords de recordatorios: quien se
     * harta de los avisos diarios no debería perder los de un torneo al que se
     * apuntó, ni al revés. Además es el canal con más probabilidad de que alguien
     * quiera SOLO este (compite de vez en cuando, no entrena a diario).
     */
    EVENTS("kortex_events"),
}

/**
 * Contenido de una notificación concreta: el tipo MÁS los datos variables que su
 * texto necesita (nombre del juego, días de racha...).
 *
 * Es una `sealed interface` y no un `data class` con campos opcionales para que sea
 * imposible programar, por ejemplo, un aviso de racha sin saber cuántos días son:
 * el compilador obliga a aportar exactamente los datos que la copy va a formatear.
 */
sealed interface NotificationContent {

    /** Tipo al que pertenece este contenido (id de sistema + canal). */
    val kind: NotificationKind

    /**
     * Récord personal batido en [gameTitle].
     *
     * @property gameTitle nombre visible del juego (del `GameCatalog`), no su UUID.
     */
    data class RecordBeaten(val gameTitle: String) : NotificationContent {
        override val kind get() = NotificationKind.RECORD_BEATEN
    }

    /**
     * Otro jugador superó la marca del usuario en [gameTitle]. Reservada al push
     * remoto (ver [NotificationKind.RECORD_BROKEN]).
     */
    data class RecordBroken(val gameTitle: String) : NotificationContent {
        override val kind get() = NotificationKind.RECORD_BROKEN
    }

    /**
     * La racha se pierde a medianoche.
     *
     * @property streakDays días consecutivos en juego (siempre >= 1).
     */
    data class StreakAtRisk(val streakDays: Int) : NotificationContent {
        override val kind get() = NotificationKind.STREAK_AT_RISK
    }

    /**
     * Misión diaria a medias.
     *
     * @property remaining juegos que faltan para completarla (siempre >= 1).
     */
    data class DailyMissionPending(val remaining: Int) : NotificationContent {
        override val kind get() = NotificationKind.DAILY_MISSION
    }

    /**
     * Reenganche por inactividad.
     *
     * @property step escalón alcanzado (3, 7 o 14 días).
     */
    data class Inactivity(val step: InactivityStep) : NotificationContent {
        override val kind get() = step.kind
    }

    /**
     * Un torneo acaba de abrirse.
     *
     * @property eventTitle título del torneo (`events.title`), que es contenido del
     *   backend y no vive en `strings.xml` — igual que los nombres de los juegos.
     */
    data class EventStarting(val eventTitle: String) : NotificationContent {
        override val kind get() = NotificationKind.EVENT_STARTING
    }

    /** Últimas horas de un torneo en el que el jugador aún no ha competido. */
    data class EventEndingSoon(val eventTitle: String) : NotificationContent {
        override val kind get() = NotificationKind.EVENT_ENDING_SOON
    }

    /** Torneo cerrado: hay clasificación definitiva. */
    data class EventResults(val eventTitle: String) : NotificationContent {
        override val kind get() = NotificationKind.EVENT_RESULTS
    }

    /** Aviso de prueba de la herramienta de depuración (ver [NotificationKind.DEBUG_TEST]). */
    data object DebugTest : NotificationContent {
        override val kind get() = NotificationKind.DEBUG_TEST
    }
}

/**
 * Escalones de la cadena de reenganche. Se modelan como `enum` (y no como un número
 * de días suelto) porque cada escalón tiene copy y tono propios: recordatorio
 * ligero a los 3 días, gancho por progreso a los 7 y "tu progreso sigue aquí" a los
 * 14. Pasados los 14 días no se insiste más: seguir notificando a quien claramente
 * dejó la app es la vía rápida a que la desinstale o silencie los avisos para
 * siempre.
 *
 * @property days días de inactividad tras los que se dispara.
 * @property kind tipo (id de sistema) con el que se programa.
 */
enum class InactivityStep(val days: Int, val kind: NotificationKind) {
    DAY_3(3, NotificationKind.INACTIVITY_3D),
    DAY_7(7, NotificationKind.INACTIVITY_7D),
    DAY_14(14, NotificationKind.INACTIVITY_14D),
}

/**
 * Una notificación **planificada**: qué contenido y en qué instante debe aparecer.
 * Es la salida de [NotificationPlanner] y la entrada de [NotificationsManager].
 *
 * @property content qué se va a decir (y con qué datos).
 * @property at instante de entrega. Siempre futuro respecto del momento en que se
 *   planificó; el scheduler descarta lo que ya venció.
 */
data class PlannedNotification(
    val content: NotificationContent,
    val at: Instant,
) {
    /** Atajo cómodo para cancelar/reprogramar por tipo. */
    val kind: NotificationKind get() = content.kind
}

/**
 * Notificación ya **resuelta a texto**, lista para entregar al sistema operativo.
 * Separarla de [PlannedNotification] mantiene la planificación libre de `suspend`
 * (resolver textos desde recursos lo es) y, sobre todo, sin idioma: el planner se
 * puede testear sin cargar recursos.
 *
 * @property kind tipo (aporta id de sistema y canal).
 * @property title título corto; en Android es la primera línea en negrita.
 * @property body cuerpo del mensaje.
 */
data class ReadyNotification(
    val kind: NotificationKind,
    val title: String,
    val body: String,
)
