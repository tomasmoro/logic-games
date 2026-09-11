package com.kortexgames.app.core.review

/**
 * Ficha de la app en la tienda de la plataforma en curso, o `null` si **todavía no
 * hay tienda donde opinar**.
 *
 * Es el interruptor que decide si la invitación a valorar existe siquiera en esta
 * plataforma, y por eso es un `String?` y no un booleano suelto más una URL: si no
 * hay enlace, no hay adónde mandar al jugador, así que no se le pregunta nada. Hoy
 * eso es justo el caso de iOS —la app aún no está publicada en la App Store—, y el
 * día que lo esté basta con rellenar el `actual` de iOS: ni la política
 * ([ReviewPromptPolicy]), ni el gestor ([ReviewPromptManager]), ni el diálogo se
 * enteran del cambio.
 *
 * Se apunta a la URL `https` de la ficha (y no al esquema propio de cada tienda,
 * `market://` o `itms-apps://`) porque es la única que abre bien en todos los
 * escenarios: la app de la tienda la intercepta cuando está instalada, y cuando no
 * lo está —o el dispositivo no la tiene, caso de los Android sin servicios de
 * Google— queda la web en vez de un enlace que no resuelve a nada.
 */
expect val storeReviewLink: String?
