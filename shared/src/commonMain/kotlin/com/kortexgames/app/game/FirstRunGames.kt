package com.kortexgames.app.game

/**
 * Los juegos de **bienvenida**: lo primero que hace un jugador recién instalado es
 * jugarlos, en este orden, ANTES de que se le ofrezca iniciar sesión.
 *
 * El porqué del orden y de la selección (decisión de producto):
 *  1. **Ordena las Pociones** — reglas evidentes sin leer nada, se entiende de un
 *     vistazo; es el mejor primer contacto para alguien que aún no sabe qué es la app.
 *  2. **Pulso Neon** — cambia de registro (reflejos, partida corta e intensa) para
 *     que la bienvenida no parezca "más de lo mismo".
 *  3. **Línea Neón** — vuelve a lo pausado y deja al jugador en modo "una más",
 *     que es justo el estado en el que conviene pedirle la cuenta.
 *
 * Vive en `game/` y no en la capa de UI porque lo consumen dos sitios que no se
 * conocen entre sí: la navegación (que traduce cada id a su ruta) y
 * [com.kortexgames.app.data.settings.OnboardingGate], que necesita saber **cuántos**
 * son para decidir si la bienvenida ya terminó.
 *
 * La lista es de [GameIds] y no de rutas a propósito: `game/` no debe conocer la
 * navegación; el mapeo id→ruta sigue siendo responsabilidad de `Routes.gameRoute`.
 */
object FirstRunGames {

    /** Ids de los juegos de bienvenida, en el orden en que se presentan. */
    val sequence: List<String> = listOf(
        GameIds.WATER_SORT,
        GameIds.NEON_PULSE,
        GameIds.NEON_LINE,
    )

    /** Cuántos juegos componen la bienvenida (el "de 3" del indicador de progreso). */
    val size: Int get() = sequence.size
}
