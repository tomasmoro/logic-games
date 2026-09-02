package com.kortexgames.app.domain.model

/**
 * Reglas del nombre de jugador (`public.users.display_name`) y el motivo por el
 * que un nombre puede rechazarse.
 *
 * Existe porque ese nombre se valida en TRES sitios —el alta por email, la
 * pantalla de onboarding y la edición desde Ajustes— y antes cada uno aplicaba
 * un criterio distinto: la pantalla de onboarding pedía 3..20 caracteres y
 * Ajustes se conformaba con "no vacío". El resultado era que un nombre aceptado
 * en una pantalla podía rechazarse en otra.
 *
 * **La autoridad es el servidor**, no este archivo: la validación de verdad vive
 * en `public.display_name_rejection` (migración 0049), que además consulta una
 * blocklist que el cliente no puede ni debe conocer. Lo de aquí es el espejo de
 * sus reglas *comprobables sin red*, para dar feedback inmediato y no gastar un
 * viaje en un nombre que obviamente no vale. Si las dos discrepan, manda el
 * servidor.
 */
object DisplayNameRules {

    /** Mínimo: evita nombres de una letra en el ranking. */
    const val MIN_LENGTH = 3

    /** Máximo: cabe en una tarjeta de ranking sin truncarse. */
    const val MAX_LENGTH = 20

    /**
     * Letras latinas, dígitos, `_`, `-` y espacios interiores simples.
     *
     * Espacios SÍ (el nombre es también el saludo de la app: "Ana María" tiene que
     * poder existir), pero ni dobles ni en los bordes: solo sirven para colarse
     * arriba en una lista o para clonar visualmente el nombre de otro.
     *
     * La lista de caracteres es deliberadamente latina. Aceptar cualquier letra
     * Unicode reabriría la suplantación por homoglifos —una "К" cirílica se dibuja
     * igual que la "K"—, que es justo lo que el servidor pliega al normalizar.
     * Coste asumido: al internacionalizar habrá que ampliarla por alfabeto.
     *
     * Debe seguir siendo idéntica a la del CHECK `users_display_name_shape`.
     */
    private val SHAPE = Regex(
        "^[A-Za-z0-9ÁÉÍÓÚÜÑáéíóúüñ_-]+( [A-Za-z0-9ÁÉÍÓÚÜÑáéíóúüñ_-]+)*$"
    )

    /**
     * Valida lo que se puede validar sin red.
     *
     * @return `null` si el nombre es aceptable **hasta donde el cliente puede
     *   saber**, o el motivo del rechazo. Nunca devuelve
     *   [DisplayNameRejection.BLOCKED]: la blocklist solo la conoce el servidor.
     */
    fun validate(raw: String): DisplayNameRejection? {
        val clean = raw.trim()
        return when {
            clean.length < MIN_LENGTH -> DisplayNameRejection.TOO_SHORT
            clean.length > MAX_LENGTH -> DisplayNameRejection.TOO_LONG
            !SHAPE.matches(clean) -> DisplayNameRejection.INVALID_CHARS
            else -> null
        }
    }
}

/**
 * Motivo por el que un nombre de jugador no se pudo fijar. Enum y no texto: el
 * mensaje lo elige la capa Compose desde `strings.xml` (CLAUDE.md §10).
 *
 * Los cinco primeros valores son espejo de los códigos que devuelve la RPC
 * `set_display_name`; [UNKNOWN] cubre el fallo de transporte (sin red, código
 * nuevo que este cliente todavía no conoce).
 */
enum class DisplayNameRejection {
    /** Menos de [DisplayNameRules.MIN_LENGTH] caracteres tras recortar. */
    TOO_SHORT,

    /** Más de [DisplayNameRules.MAX_LENGTH] caracteres. */
    TOO_LONG,

    /** Caracteres no permitidos, espacio doble o espacios en los bordes. */
    INVALID_CHARS,

    /**
     * Ofensivo o reservado (suplantación de la marca o del soporte). Solo lo puede
     * determinar el servidor: la lista de patrones es ilegible para el cliente a
     * propósito, porque publicarla es publicar el mapa de cómo esquivarla.
     */
    BLOCKED,

    /** No hay sesión, o el perfil espejo todavía no existe. */
    NO_SESSION,

    /** Fallo de red o motivo que este cliente no reconoce. */
    UNKNOWN;

    companion object {
        /**
         * Traduce el código que devuelve `set_display_name`. Un código desconocido
         * cae en [UNKNOWN] en vez de reventar: así el servidor puede añadir motivos
         * sin romper a los clientes ya publicados.
         */
        fun fromCode(code: String?): DisplayNameRejection = when (code) {
            "too_short" -> TOO_SHORT
            "too_long" -> TOO_LONG
            "invalid_chars" -> INVALID_CHARS
            "blocked" -> BLOCKED
            "no_session" -> NO_SESSION
            // Desde la migración 0050 la RPC CREA el perfil si falta, así que
            // `no_profile` ya solo significa que no existe ni la cuenta en
            // `auth.users`: un token de un usuario borrado. Se trata como fallo
            // genérico y NO como sesión caducada porque es indistinguible de un
            // error de servidor desde aquí, y el mensaje de sesión mandaría al
            // usuario a reintentar un login que tampoco va a funcionar.
            "no_profile" -> UNKNOWN
            else -> UNKNOWN
        }
    }
}

/**
 * Excepción con la que [com.kortexgames.app.domain.repository.AuthRepository.updateDisplayName]
 * falla cuando el servidor rechaza el nombre.
 *
 * Se mantiene el idioma `Result<Unit>` del resto del repositorio (ver su KDoc) en
 * vez de introducir un tipo de retorno propio, pero con el motivo tipado dentro
 * para que la UI no tenga que interpretar mensajes de texto.
 */
class DisplayNameRejectedException(
    val reason: DisplayNameRejection,
    cause: Throwable? = null,
) : Exception("display_name rechazado: $reason", cause)
