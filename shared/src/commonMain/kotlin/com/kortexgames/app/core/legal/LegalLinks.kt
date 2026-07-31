package com.kortexgames.app.core.legal

/**
 * URLs públicas de los documentos legales de la App y versión vigente del
 * consentimiento.
 *
 * Apuntan al sitio estático de `site/`, que se despliega solo a GitHub Pages con
 * `.github/workflows/deploy-site.yml`. Se centralizan aquí —y no sueltas en cada
 * pantalla— porque las consumen al menos tres sitios (alta de cuenta, Ajustes y,
 * a futuro, el aviso de cambio de términos) y un cambio de dominio no debe
 * obligar a buscar strings por el árbol.
 *
 * Se enlazan las versiones en **español** porque la UI de la App está en español;
 * cada página ofrece el cambio de idioma en su cabecera.
 */
object LegalLinks {

    /** Raíz del sitio publicado. La landing también hace de homepage en las tiendas. */
    private const val BASE = "https://tomasmoro.github.io/logic-games"

    /** Política de Privacidad (ES). */
    const val PRIVACY_POLICY = "$BASE/privacidad/"

    /** Condiciones del Servicio (ES). */
    const val TERMS_OF_SERVICE = "$BASE/condiciones/"

    /**
     * Versión de los documentos que el usuario acepta al registrarse, en formato
     * `AAAA-MM-DD` (coincide con la fecha de "última actualización" de las páginas).
     *
     * Es una **fecha y no un booleano** a propósito: si algún día cambian los
     * términos de forma sustancial, basta con subir esta constante para que
     * [com.kortexgames.app.data.settings.LegalConsentStore] detecte que el
     * consentimiento guardado quedó obsoleto y la App pueda volver a pedirlo, que
     * es justo lo que promete la §10 de las condiciones.
     */
    const val CURRENT_VERSION = "2026-07-29"
}
