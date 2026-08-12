package com.kortexgames.app.core.notifications

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reglas de **cuándo se ofrece** el permiso de notificaciones.
 *
 * Lo que protegen estos tests no es cosmético: cada camino equivocado aquí gasta un
 * recurso irrecuperable (el diálogo del sistema, que solo se muestra una vez) o
 * quema la paciencia del usuario a base de insistir.
 */
class NotificationPrimingPolicyTest {

    private val policy = NotificationPrimingPolicy()

    @Test
    fun `no se ofrece antes de la primera partida`() {
        // El argumento de venta es la racha, la misión y los récords: sin una sola
        // partida jugada, nada de eso significa todavía nada para el usuario.
        assertFalse(policy.shouldPrime(gamesPlayed = 0, timesDeclined = 0, permissionAlreadyAsked = false))
    }

    @Test
    fun `se ofrece tras la primera partida`() {
        assertTrue(policy.shouldPrime(gamesPlayed = 1, timesDeclined = 0, permissionAlreadyAsked = false))
    }

    @Test
    fun `tras un ahora no espera a que haya progreso real`() {
        // Segunda oferta a las 5 partidas: con racha y marcas de por medio hay algo
        // que perder. Ofrecerla dos pantallas después sería insistir, no ayudar.
        assertFalse(policy.shouldPrime(gamesPlayed = 2, timesDeclined = 1, permissionAlreadyAsked = false))
        assertTrue(policy.shouldPrime(gamesPlayed = 5, timesDeclined = 1, permissionAlreadyAsked = false))
    }

    @Test
    fun `no se ofrece una tercera vez`() {
        // Dos "ahora no" son un no. A partir de ahí queda el interruptor de Ajustes.
        assertFalse(policy.shouldPrime(gamesPlayed = 100, timesDeclined = 2, permissionAlreadyAsked = false))
    }

    @Test
    fun `no se ofrece si el dialogo del sistema ya se mostro`() {
        // Da igual si lo aceptó o lo denegó: el sistema no lo volverá a mostrar, así
        // que una antesala aquí solo llevaría a un botón que no hace nada visible.
        assertFalse(policy.shouldPrime(gamesPlayed = 50, timesDeclined = 0, permissionAlreadyAsked = true))
        assertFalse(policy.shouldPrime(gamesPlayed = 50, timesDeclined = 1, permissionAlreadyAsked = true))
    }
}
