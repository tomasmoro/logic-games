package com.kortexgames.app.core.review

/**
 * `null` **a propósito**: la app todavía no está publicada en la App Store, así que
 * en iOS no se le pide opinión a nadie (no habría ficha que abrir).
 *
 * Al publicarla, sustituir por la URL de la ficha con el sufijo de reseña, del tipo
 * `https://apps.apple.com/app/id<APP_ID>?action=write-review`, que abre la App Store
 * directamente en el formulario de valoración.
 */
actual val storeReviewLink: String? = null
