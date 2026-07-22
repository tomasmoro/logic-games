package com.example.kortexgames.data.remote.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests de las utilidades **puras** del flujo OAuth de Google ([GoogleOAuth]). El
 * `actual` de iOS no se puede testear en el host (depende de `ASWebAuthenticationSession`),
 * pero SÍ su lógica sensible —SHA-256, base64url, reto PKCE, construcción de URL y
 * parseo del callback—, que es justo donde un error rompe el login de forma sutil.
 *
 * Los vectores de SHA-256/PKCE son los **oficiales**: el ejemplo del apéndice B de
 * la RFC 7636, con `code_verifier` conocido y su `code_challenge` esperado.
 */
class GoogleOAuthTest {

    @Test
    fun sha256CoincideConVectoresConocidos() {
        // Vector clásico: SHA-256("abc").
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            GoogleOAuth.sha256("abc".encodeToByteArray()).toHex(),
        )
        // Cadena vacía.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            GoogleOAuth.sha256(ByteArray(0)).toHex(),
        )
        // Mensaje que cruza el límite de bloque (56 bytes → dos bloques).
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            GoogleOAuth.sha256(
                "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray(),
            ).toHex(),
        )
    }

    @Test
    fun retoPkceCoincideConElVectorDeLaRfc7636() {
        // Apéndice B de la RFC 7636.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GoogleOAuth.pkceChallenge(verifier))
    }

    @Test
    fun base64UrlNoLlevaPaddingNiCaracteresNoSeguros() {
        // 0xFF 0xFF 0xFF fuerza los símbolos 62/63 (que en base64url son '-' y '_').
        val encoded = GoogleOAuth.base64UrlNoPadding(byteArrayOf(-1, -1, -1))
        assertEquals("____", encoded)
        assertTrue(!encoded.contains('=') && !encoded.contains('+') && !encoded.contains('/'))
    }

    @Test
    fun esquemaDeRedireccionEsElClientIdInvertido() {
        assertEquals(
            "com.googleusercontent.apps.123456-abcdef",
            GoogleOAuth.reversedClientScheme("123456-abcdef.apps.googleusercontent.com"),
        )
    }

    @Test
    fun urlDeAutorizacionLlevaLosParametrosPkceObligatorios() {
        val url = GoogleOAuth.authorizationUrl(
            clientId = "cid.apps.googleusercontent.com",
            redirectUri = "com.googleusercontent.apps.cid:/oauth2redirect",
            codeChallenge = "CHALLENGE",
        )
        assertTrue(url.startsWith(GoogleOAuth.AUTH_ENDPOINT + "?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("code_challenge=CHALLENGE"))
        assertTrue(url.contains("code_challenge_method=S256"))
        // El scope se codifica (los espacios pasan a %20).
        assertTrue(url.contains("scope=openid%20email%20profile"))
        // Los ':' y '/' del redirect_uri se percent-encodean.
        assertTrue(url.contains("redirect_uri=com.googleusercontent.apps.cid%3A%2Foauth2redirect"))
    }

    @Test
    fun queryParamLeeYDecodificaElCallback() {
        val callback = "com.googleusercontent.apps.cid:/oauth2redirect?code=4%2F0Ab%20cd&scope=openid"
        assertEquals("4/0Ab cd", GoogleOAuth.queryParam(callback, "code"))
        assertEquals("openid", GoogleOAuth.queryParam(callback, "scope"))
        assertNull(GoogleOAuth.queryParam(callback, "error"))
        assertNull(GoogleOAuth.queryParam("sin-query", "code"))
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
