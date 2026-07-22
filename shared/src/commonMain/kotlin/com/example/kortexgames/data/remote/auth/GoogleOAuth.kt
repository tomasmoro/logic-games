package com.example.kortexgames.data.remote.auth

/**
 * Utilidades **puras** del flujo OAuth 2.0 (Authorization Code + PKCE) que usa el
 * login con Google en iOS ([GoogleAuthClient.ios]).
 *
 * Viven en `commonMain` (y no en `iosMain`) por dos motivos:
 *  1. Son lógica sin dependencias de plataforma (aritmética de bits, cadenas), así
 *     que se pueden **testear en el host JVM** (`commonTest`) con vectores conocidos
 *     —algo que el `actual` de iOS, atado a `ASWebAuthenticationSession`, no permite—.
 *  2. Documentan en un solo sitio el contrato del flujo (formato del reto PKCE,
 *     esquema de redirección, construcción de la URL de autorización).
 *
 * El `actual` de iOS solo aporta lo que sí es nativo: aleatoriedad segura, el
 * navegador embebido (`ASWebAuthenticationSession`) y el POST al token endpoint.
 *
 * PKCE (RFC 7636) sustituye al *client secret* en apps instaladas: el cliente
 * manda `code_challenge = BASE64URL(SHA-256(code_verifier))` al pedir el código y
 * luego el `code_verifier` en crudo al canjearlo, de modo que un código
 * interceptado es inútil sin el verifier.
 */
internal object GoogleOAuth {

    /** Endpoint de autorización de Google (donde se presenta el consentimiento). */
    const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"

    /** Endpoint de canje del código por tokens (id_token/access_token). */
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

    /**
     * Ruta que se añade al esquema de redirección para formar la `redirect_uri`.
     * `ASWebAuthenticationSession` intercepta el esquema completo, así que la ruta
     * es arbitraria pero debe ser estable entre la petición y el canje.
     */
    const val REDIRECT_PATH = ":/oauth2redirect"

    /**
     * Construye la `redirect_uri` a partir del **iOS OAuth Client ID** de Google.
     *
     * Google exige que las apps iOS usen como esquema el *reversed client ID*: si el
     * client id es `123-abc.apps.googleusercontent.com`, el esquema es
     * `com.googleusercontent.apps.123-abc`. Es el mismo valor que pide el SDK oficial
     * de GoogleSignIn, por eso lo derivamos y no lo pedimos por separado.
     *
     * @param iosClientId client id de tipo *iOS* (`xxxx.apps.googleusercontent.com`).
     * @return esquema de redirección (sin `://`), p. ej. `com.googleusercontent.apps.123-abc`.
     */
    fun reversedClientScheme(iosClientId: String): String {
        val suffix = ".apps.googleusercontent.com"
        val prefix = iosClientId.removeSuffix(suffix)
        return "com.googleusercontent.apps.$prefix"
    }

    /**
     * Monta la URL de autorización con los parámetros del flujo con PKCE.
     *
     * @param clientId iOS OAuth Client ID.
     * @param redirectUri URI de redirección (`<esquema>:/oauth2redirect`).
     * @param codeChallenge reto PKCE (ver [pkceChallenge]).
     * @return URL completa a abrir en el navegador embebido.
     */
    fun authorizationUrl(
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
    ): String {
        val params = listOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            // openid → id_token; email/profile → claims que Supabase mapea al perfil.
            "scope" to "openid email profile",
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
        )
        val query = params.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
        return "$AUTH_ENDPOINT?$query"
    }

    /**
     * Reto PKCE `S256`: `BASE64URL-SIN-PADDING(SHA-256(verifier))`.
     *
     * @param codeVerifier verifier de alta entropía generado por la plataforma.
     */
    fun pkceChallenge(codeVerifier: String): String =
        base64UrlNoPadding(sha256(codeVerifier.encodeToByteArray()))

    /**
     * Extrae el valor de un parámetro del *query string* de una URL de callback.
     * Implementación mínima (sin depender de un parser de URL de plataforma) para
     * leer el `code` (o un `error`) que devuelve Google en la redirección.
     *
     * @return el valor decodificado, o `null` si el parámetro no aparece.
     */
    fun queryParam(url: String, name: String): String? {
        val queryStart = url.indexOf('?').let { if (it == -1) return null else it + 1 }
        val query = url.substring(queryStart).substringBefore('#')
        return query.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.isNotEmpty() && it[0] == name }
            ?.let { if (it.size == 2) urlDecode(it[1]) else "" }
    }

    /**
     * Codificación **base64url sin padding** (RFC 4648 §5), la que exige PKCE: se
     * sustituyen `+`→`-`, `/`→`_` y se eliminan los `=` de relleno.
     */
    fun base64UrlNoPadding(bytes: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val chunk = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(table[(chunk shr 18) and 0x3F])
            sb.append(table[(chunk shr 12) and 0x3F])
            if (i + 1 < bytes.size) sb.append(table[(chunk shr 6) and 0x3F])
            if (i + 2 < bytes.size) sb.append(table[chunk and 0x3F])
            i += 3
        }
        return sb.toString()
    }

    /**
     * SHA-256 puro (FIPS 180-4). Necesario porque `commonMain` no tiene un digest
     * multiplataforma y el reto PKCE se calcula aquí para poder testearlo; en
     * Android el login usa `java.security.MessageDigest`, no esta ruta.
     */
    fun sha256(message: ByteArray): ByteArray {
        // Constantes de ronda (raíces cúbicas de los primeros 64 primos).
        val k = intArrayOf(
            0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
            -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
            -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
            -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
        )
        var h0 = 0x6a09e667; var h1 = -0x4498517b; var h2 = 0x3c6ef372; var h3 = -0x5ab00ac6
        var h4 = 0x510e527f; var h5 = -0x64fa9774; var h6 = 0x1f83d9ab; var h7 = 0x5be0cd19

        // Padding: 0x80, ceros, y la longitud en bits como big-endian de 64 bits.
        val bitLen = message.size.toLong() * 8
        val padded = message.copyOf(((message.size + 8) / 64 + 1) * 64)
        padded[message.size] = 0x80.toByte()
        for (j in 0 until 8) {
            padded[padded.size - 1 - j] = (bitLen ushr (8 * j)).toByte()
        }

        val w = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (t in 0 until 16) {
                w[t] = ((padded[offset + t * 4].toInt() and 0xFF) shl 24) or
                    ((padded[offset + t * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((padded[offset + t * 4 + 2].toInt() and 0xFF) shl 8) or
                    (padded[offset + t * 4 + 3].toInt() and 0xFF)
            }
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotr(7) xor w[t - 15].rotr(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotr(17) xor w[t - 2].rotr(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h0; var b = h1; var c = h2; var d = h3
            var e = h4; var f = h5; var g = h6; var h = h7
            for (t in 0 until 64) {
                val s1 = e.rotr(6) xor e.rotr(11) xor e.rotr(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + k[t] + w[t]
                val s0 = a.rotr(2) xor a.rotr(13) xor a.rotr(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                h = g; g = f; f = e; e = d + temp1
                d = c; c = b; b = a; a = temp1 + temp2
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e; h5 += f; h6 += g; h7 += h
            offset += 64
        }
        return intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).toBigEndianBytes()
    }

    /** Rotación circular a la derecha de 32 bits (operación base de SHA-256). */
    private fun Int.rotr(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))

    private fun IntArray.toBigEndianBytes(): ByteArray {
        val out = ByteArray(size * 4)
        for (i in indices) {
            out[i * 4] = (this[i] ushr 24).toByte()
            out[i * 4 + 1] = (this[i] ushr 16).toByte()
            out[i * 4 + 2] = (this[i] ushr 8).toByte()
            out[i * 4 + 3] = this[i].toByte()
        }
        return out
    }

    /** Percent-encoding de los caracteres no seguros en un valor de query. */
    private fun urlEncode(value: String): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
        val sb = StringBuilder(value.length)
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt().toChar()
            if (c in unreserved) {
                sb.append(c)
            } else {
                sb.append('%')
                sb.append(((byte.toInt() and 0xFF) shr 4).toString(16).uppercase())
                sb.append((byte.toInt() and 0x0F).toString(16).uppercase())
            }
        }
        return sb.toString()
    }

    /** Decodifica un valor percent-encoded (`%XX` y `+` como espacio). */
    private fun urlDecode(value: String): String {
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            when (val c = value[i]) {
                '%' -> {
                    val hex = value.substring(i + 1, i + 3)
                    bytes.add(hex.toInt(16).toByte())
                    i += 3
                }
                '+' -> { bytes.add(' '.code.toByte()); i++ }
                else -> { bytes.add(c.code.toByte()); i++ }
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}
